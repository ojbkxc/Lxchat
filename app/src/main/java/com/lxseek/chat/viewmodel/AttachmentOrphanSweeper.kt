package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.AttachmentMeta
import com.lxseek.chat.model.SelectedAttachment
import kotlinx.serialization.json.Json
import java.io.File

/** Reclaims old private attachment files only after scanning every durable reference source. */
internal class AttachmentOrphanSweeper(
    private val conversations: ConversationRepository,
    private val filesDirectory: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun sweep() {
        val referenced = HashSet<String>()
        collectMessageReferences(referenced)
        collectDraftReferences(referenced)

        val cutoffNow = now()
        deleteOldUnreferencedRootAttachments(referenced, cutoffNow)
        deleteOldUnreferencedFiles(File(filesDirectory, "images"), "camera_", referenced, cutoffNow)
        listOf(
            File(filesDirectory, "run-inputs"),
            File(filesDirectory, "fork-attachments"),
        ).forEach { directory ->
            deleteOldUnreferencedFiles(directory, requiredPrefix = null, referenced, cutoffNow)
        }
    }

    private suspend fun collectMessageReferences(referenced: MutableSet<String>) {
        var afterMessageId: String? = null
        while (true) {
            val page = conversations.getMessageAttachmentReferencesPage(
                afterId = afterMessageId,
                limit = DATABASE_SCAN_PAGE_SIZE,
            )
            page.forEach { message ->
                message.images.forEach { referenced.add(it.removePrefix("file://")) }
                message.attachmentMeta?.let { json ->
                    runCatching { Json.decodeFromString<AttachmentMeta>(json) }.getOrNull()
                        ?.items?.forEach { item ->
                            item.originalUri?.takeIf { it.startsWith("file://") }
                                ?.let { referenced.add(it.removePrefix("file://")) }
                        }
                }
            }
            afterMessageId = page.lastOrNull()?.id
            if (page.size < DATABASE_SCAN_PAGE_SIZE) break
        }
    }

    private suspend fun collectDraftReferences(referenced: MutableSet<String>) {
        var afterConversationId: String? = null
        while (true) {
            val page = conversations.getConversationDraftAttachmentReferencesPage(
                afterId = afterConversationId,
                limit = DATABASE_SCAN_PAGE_SIZE,
            )
            page.forEach { conversation ->
                runCatching {
                    Json.decodeFromString<List<SelectedAttachment>>(conversation.draftAttachments)
                }.getOrNull()?.forEach { attachment ->
                    attachment.localPath?.let { referenced.add(it) }
                    attachment.processedFrames?.forEach { referenced.add(it) }
                    attachment.preRenderedPaths?.forEach { referenced.add(it) }
                }
            }
            afterConversationId = page.lastOrNull()?.id
            if (page.size < DATABASE_SCAN_PAGE_SIZE) break
        }
    }

    private fun deleteOldUnreferencedRootAttachments(
        referenced: Set<String>,
        cutoffNow: Long,
    ) {
        val prefixes = arrayOf("att_", "vid_", "img_", "pdf_")
        filesDirectory.listFiles { file ->
            file.isFile && prefixes.any { prefix -> file.name.startsWith(prefix) }
        }?.forEach { file ->
            deleteIfOldAndUnreferenced(file, referenced, cutoffNow)
        }
    }

    private fun deleteOldUnreferencedFiles(
        directory: File,
        requiredPrefix: String?,
        referenced: Set<String>,
        cutoffNow: Long,
    ) {
        directory.listFiles { file ->
            file.isFile && (requiredPrefix == null || file.name.startsWith(requiredPrefix))
        }?.forEach { file ->
            deleteIfOldAndUnreferenced(file, referenced, cutoffNow)
        }
    }

    private fun deleteIfOldAndUnreferenced(
        file: File,
        referenced: Set<String>,
        cutoffNow: Long,
    ) {
        if (
            file.absolutePath !in referenced &&
            cutoffNow - file.lastModified() > MINIMUM_FILE_AGE_MS
        ) {
            runCatching { file.delete() }
        }
    }

    private companion object {
        const val DATABASE_SCAN_PAGE_SIZE = 64
        const val MINIMUM_FILE_AGE_MS = 60 * 60 * 1000L
    }
}
