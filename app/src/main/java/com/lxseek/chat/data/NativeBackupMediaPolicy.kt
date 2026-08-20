package com.lxseek.chat.data

import com.lxseek.chat.model.AttachmentMeta
import com.lxseek.chat.model.MessageSegment
import kotlinx.serialization.json.Json

internal object NativeBackupMediaPolicy {
    private val json = Json { ignoreUnknownKeys = true }

    fun toolImagePaths(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<MessageSegment>>(raw)
                .flatMap { segment -> segment.toolImages.map { it.path } }
        }.getOrDefault(emptyList())
    }

    fun rewriteAttachmentMetaForExport(
        raw: String?,
        oldToNewImageIndex: Map<Int, Int>,
        archiveEntryForSource: (String) -> String?,
    ): String? {
        val meta = raw?.let {
            runCatching { json.decodeFromString<AttachmentMeta>(it) }.getOrNull()
        } ?: return null
        val items = meta.items.map { item ->
            val originalCount = item.pageCount?.coerceAtLeast(1) ?: 1
            val survivingIndices = item.imageIndex
                ?.let { start ->
                    (start until start + originalCount).mapNotNull(oldToNewImageIndex::get)
                }
                .orEmpty()
            item.copy(
                // Only copied video payloads keep a structural original reference. Other types
                // render from archived message media/text and must not expose a device URI.
                originalUri = item.originalUri
                    ?.takeIf { item.type == "video" }
                    ?.let(archiveEntryForSource),
                imageIndex = when {
                    item.imageIndex == null -> null
                    else -> survivingIndices.firstOrNull()
                },
                pageCount = when {
                    item.pageCount == null -> null
                    else -> survivingIndices.size
                },
            )
        }
        return json.encodeToString(AttachmentMeta(items))
    }

    fun rewriteToolImagePathsForExport(
        raw: String?,
        archiveEntryForSource: (String) -> String?,
    ): String? {
        if (raw.isNullOrBlank()) return raw
        val segments = runCatching {
            json.decodeFromString<List<MessageSegment>>(raw)
        }.getOrNull() ?: return raw
        return json.encodeToString(
            segments.map { segment ->
                segment.copy(
                    toolImages = segment.toolImages.mapNotNull { image ->
                        archiveEntryForSource(image.path)?.let { archivedPath ->
                            image.copy(path = archivedPath)
                        }
                    },
                )
            },
        )
    }

    fun restoreAttachmentMeta(
        raw: String?,
        archiveVersion: Int,
        legacyVideoUris: Map<Int, String>,
        restoredUriForArchiveEntry: (String) -> String?,
    ): String? {
        if (raw.isNullOrBlank()) return raw
        val meta = runCatching {
            json.decodeFromString<AttachmentMeta>(raw)
        }.getOrNull() ?: return null
        return json.encodeToString(
            AttachmentMeta(
                meta.items.map { item ->
                    item.copy(
                        originalUri = when {
                            archiveVersion >= 4 -> item.originalUri
                                ?.let(restoredUriForArchiveEntry)
                            item.type == "video" -> legacyVideoUris[item.imageIndex ?: 0]
                            else -> null
                        },
                    )
                },
            ),
        )
    }

    fun restoreToolImagePaths(
        raw: String?,
        archiveVersion: Int,
        restoredPathForArchiveEntry: (String) -> String?,
    ): String? {
        if (raw.isNullOrBlank()) return raw
        val segments = runCatching {
            json.decodeFromString<List<MessageSegment>>(raw)
        }.getOrNull() ?: return raw
        return json.encodeToString(
            segments.map { segment ->
                segment.copy(
                    toolImages = segment.toolImages.mapNotNull { image ->
                        if (archiveVersion < 4) {
                            // v1-v3 stored only a device-absolute path. Dropping an unusable
                            // thumbnail is safer than retaining a misleading foreign path.
                            null
                        } else {
                            restoredPathForArchiveEntry(image.path)?.let { restoredPath ->
                                image.copy(path = restoredPath)
                            }
                        }
                    },
                )
            },
        )
    }
}
