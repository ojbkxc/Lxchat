package com.lxseek.chat.data

import android.content.Context
import androidx.core.content.FileProvider
import com.lxseek.chat.model.SelectedAttachment
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

internal class NativeConversationMediaRestorer(
    private val context: Context,
    private val importJson: Json,
) {
    private fun detectImageExtension(bytes: ByteArray): String {
        if (bytes.size < 4) return "jpg"
        return when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "gif"
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() -> "webp"
            else -> "jpg"
        }
    }

    private fun detectVideoExtension(bytes: ByteArray): String {
        if (bytes.size < 4) return "mp4"
        return when {
            bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte() -> "webm"
            else -> "mp4"
        }
    }

    data class RestoredMediaFile(
        val absolutePath: String,
        val uri: String,
    )

    data class RestoredMedia(
        val archiveFiles: Map<String, RestoredMediaFile>,
        val legacyImagesByMessage: Map<String, List<String>>,
        val legacyVideosByMessage: Map<String, Map<Int, String>>,
        val createdFiles: List<File>,
    )

    fun restoreConversationMedia(archive: NativeBackupArchive): RestoredMedia {
        val archiveFiles = mutableMapOf<String, RestoredMediaFile>()
        val legacyImagesByMessage =
            mutableMapOf<String, MutableList<Pair<Int, String>>>()
        val legacyVideosByMessage =
            mutableMapOf<String, MutableMap<Int, String>>()
        val createdFiles = mutableListOf<File>()
        val names = archive.names()
        try {
            val imagesDir = File(context.filesDir, "images")
            imagesDir.mkdirs()

            fun restoreEntry(path: String, kind: String): RestoredMediaFile? {
                return archive.stream(path)?.buffered()?.use { input ->
                    input.mark(16)
                    val header = ByteArray(16)
                    val headerSize = input.read(header).coerceAtLeast(0)
                    input.reset()
                    val extension = when (kind) {
                        "image" -> detectImageExtension(header.copyOf(headerSize))
                        "video" -> detectVideoExtension(header.copyOf(headerSize))
                        else -> path.substringAfterLast('.', "bin")
                            .lowercase()
                            .takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
                            ?: "bin"
                    }
                    val targetDir = if (kind == "image") imagesDir else context.filesDir
                    val prefix = when (kind) {
                        "image" -> "img_import_"
                        "video" -> "vid_import_"
                        else -> "draft_import_"
                    }
                    val target = File(targetDir, "$prefix${UUID.randomUUID()}.$extension")
                    val copied = target.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                    if (copied <= 0L) {
                        target.delete()
                        null
                    } else {
                        createdFiles += target
                        val uri = if (kind == "image") {
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                target,
                            ).toString()
                        } else {
                            "file://${target.absolutePath}"
                        }
                        RestoredMediaFile(target.absolutePath, uri)
                    }
                }
            }

            names.asSequence()
                .filter {
                    it.startsWith(NativeBackupFormat.IMAGE_MEDIA_PREFIX) ||
                        it.startsWith(NativeBackupFormat.VIDEO_MEDIA_PREFIX) ||
                        it.startsWith(NativeBackupFormat.DRAFT_MEDIA_PREFIX)
                }
                .forEach { path ->
                    val kind = when {
                        path.startsWith(NativeBackupFormat.IMAGE_MEDIA_PREFIX) -> "image"
                        path.startsWith(NativeBackupFormat.VIDEO_MEDIA_PREFIX) -> "video"
                        else -> "draft"
                    }
                    restoreEntry(path, kind)?.let { archiveFiles[path] = it }
                }

            // v1-v3 media layout. Sort by the explicit numeric index instead of trusting ZIP
            // enumeration order.
            names.filter { it.startsWith("images/") }.forEach { path ->
                val parts = path.removePrefix("images/").split("/")
                if (parts.size != 2) return@forEach
                val index = parts[1].toIntOrNull() ?: return@forEach
                restoreEntry(path, "image")?.let { restored ->
                    legacyImagesByMessage
                        .getOrPut(parts[0]) { mutableListOf() }
                        .add(index to restored.uri)
                }
            }

            names.filter { it.startsWith("videos/") }.forEach { path ->
                val parts = path.removePrefix("videos/").split("/")
                if (parts.size != 2) return@forEach
                val index = parts[1].toIntOrNull() ?: return@forEach
                restoreEntry(path, "video")?.let { restored ->
                    legacyVideosByMessage
                        .getOrPut(parts[0]) { mutableMapOf() }[index] = restored.uri
                }
            }
        } catch (error: Exception) {
            createdFiles.forEach { runCatching { it.delete() } }
            throw error
        }

        return RestoredMedia(
            archiveFiles = archiveFiles,
            legacyImagesByMessage = legacyImagesByMessage.mapValues { (_, indexed) ->
                indexed.sortedBy { it.first }.map { it.second }
            },
            legacyVideosByMessage = legacyVideosByMessage,
            createdFiles = createdFiles,
        )
    }

    fun restoreDraftAttachments(
        raw: String?,
        restoredMedia: RestoredMedia,
    ): String? {
        if (raw.isNullOrBlank()) return null
        val attachments = runCatching {
            importJson.decodeFromString<List<SelectedAttachment>>(raw)
        }.getOrNull() ?: return null
        val restored = attachments.mapNotNull { attachment ->
            val primary = restoredMedia.archiveFiles[attachment.localPath]
                ?: restoredMedia.archiveFiles[attachment.uri]
                ?: return@mapNotNull null
            attachment.copy(
                uri = primary.uri,
                localPath = primary.absolutePath,
                processedFrames = attachment.processedFrames
                    ?.mapNotNull { restoredMedia.archiveFiles[it]?.absolutePath }
                    ?.takeIf { it.isNotEmpty() },
                preRenderedPaths = attachment.preRenderedPaths
                    ?.mapNotNull { restoredMedia.archiveFiles[it]?.absolutePath }
                    ?.takeIf { it.isNotEmpty() },
            )
        }
        return restored.takeIf { it.isNotEmpty() }?.let(importJson::encodeToString)
    }
}
