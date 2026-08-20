package com.lxseek.chat.data

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.model.AttachmentMeta
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Owns attachment copies created while cloning persisted messages.
 *
 * Copies are isolated by [ownerKey]: repeated references inside one message resolve to one file,
 * while two cloned messages never acquire a shared backing file that one message deletion could
 * remove from the other. Call [commit] only after the database graph commits; otherwise
 * [rollback] removes every file created by this session.
 */
internal class MessageAttachmentCloneSession(
    private val destinationDir: File,
) {
    private val copiedByOwnerAndSource = mutableMapOf<Pair<String, String>, String>()
    private val createdFiles = mutableListOf<File>()
    private var committed = false

    fun cloneMessage(
        message: MessageEntity,
        ownerKey: String = message.id,
    ): MessageEntity {
        val clonePath: (String) -> String = { path -> cloneBackingPath(ownerKey, path) }
        return message.copy(
            images = message.images.map(clonePath),
            attachmentMeta = message.attachmentMeta?.let { raw ->
                cloneAttachmentMeta(raw, clonePath)
            },
        )
    }

    fun cloneBackingPath(ownerKey: String, path: String): String {
        val cacheKey = ownerKey to path
        copiedByOwnerAndSource[cacheKey]?.let { return it }
        val source = File(path)
        if (!source.isFile) return path
        check(destinationDir.exists() || destinationDir.mkdirs()) {
            "Cannot create attachment clone directory"
        }
        val suffix = source.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        val destination = File(destinationDir, "${UUID.randomUUID()}$suffix")
        source.copyTo(destination, overwrite = false)
        createdFiles += destination
        return destination.absolutePath.also { copiedByOwnerAndSource[cacheKey] = it }
    }

    fun commit() {
        committed = true
        createdFiles.clear()
        copiedByOwnerAndSource.clear()
    }

    fun rollback() {
        if (committed) return
        createdFiles.asReversed().forEach { file -> runCatching { file.delete() } }
        createdFiles.clear()
        copiedByOwnerAndSource.clear()
    }
}

internal fun cloneAttachmentMeta(
    raw: String,
    cloneBackingPath: (String) -> String,
): String {
    val meta = Json.decodeFromString<AttachmentMeta>(raw)
    return Json.encodeToString(
        meta.copy(
            items = meta.items.map { item ->
                val uri = item.originalUri
                if (uri != null && uri.startsWith("file://")) {
                    item.copy(
                        originalUri = "file://${cloneBackingPath(uri.removePrefix("file://"))}"
                    )
                } else {
                    item
                }
            }
        )
    )
}
