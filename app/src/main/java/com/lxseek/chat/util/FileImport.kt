package com.lxseek.chat.util

import android.content.Context
import android.net.Uri
import java.io.File

/** Supported magic-number formats for imports that validate file content. */
enum class FileImportFormat(private val magic: ByteArray) {
    GGUF(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())),
    TTF(byteArrayOf(0, 1, 0, 0)),
    OTF("OTTO".toByteArray(Charsets.US_ASCII)),
    SFNT_TRUE("true".toByteArray(Charsets.US_ASCII)),
    SFNT_TYPE_1("typ1".toByteArray(Charsets.US_ASCII));

    fun matches(bytes: ByteArray): Boolean {
        if (bytes.size < magic.size) return false
        return magic.indices.all { bytes[it] == magic[it] }
    }
}

object FileImport {
    /**
     * Copies [uri] into a uniquely named file under [context.filesDir], deleting the partial or
     * invalid file on failure. A null [formats] preserves the raw copy without magic validation.
     */
    fun copyToPrivate(
        context: Context,
        uri: Uri,
        prefix: String,
        extension: String,
        formats: Set<FileImportFormat>? = null,
        sizeLimitBytes: Long = Long.MAX_VALUE,
    ): File? {
        val fileName = if (extension.isBlank()) {
            "${prefix}_${java.util.UUID.randomUUID()}"
        } else {
            "${prefix}_${java.util.UUID.randomUUID()}.$extension"
        }
        val destination = File(context.filesDir, fileName)
        try {
            // 注意：copyTo 的第二参数是缓冲区大小而非复制上限，故先完整复制，
            // 再用目标文件实际长度做 sizeLimitBytes 校验，避免按 limit 分配巨型缓冲。
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
                true
            } == true
            if (!copied) {
                destination.delete()
                return null
            }
            if (destination.length() > sizeLimitBytes) {
                destination.delete()
                return null
            }
            if (formats != null && !isValid(destination, formats)) {
                destination.delete()
                return null
            }
            return destination
        } catch (error: Exception) {
            runCatching { destination.delete() }
            throw error
        }
    }

    private fun isValid(file: File, formats: Set<FileImportFormat>): Boolean {
        val magic = ByteArray(4)
        val bytesRead = file.inputStream().use { it.read(magic) }
        return bytesRead == magic.size && formats.any { it.matches(magic) }
    }
}
