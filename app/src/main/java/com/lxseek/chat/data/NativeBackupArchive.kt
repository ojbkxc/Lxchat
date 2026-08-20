package com.lxseek.chat.data

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * On-demand, memory-bounded reader over a backup ZIP. Entries are decoded only when requested and
 * one at a time, so large image/video blobs never accumulate in memory. The SAF stream is first
 * copied to a temporary file because [ZipFile] needs random access; [close] disposes both.
 */
internal class NativeBackupArchive private constructor(
    private val zip: ZipFile,
    private val temporaryFile: File,
) : Closeable {
    fun has(name: String): Boolean = zip.getEntry(name) != null

    fun size(name: String): Long = zip.getEntry(name)?.size ?: -1L

    fun bytes(name: String): ByteArray? =
        zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }

    operator fun get(name: String): ByteArray? = bytes(name)

    fun stream(name: String): InputStream? =
        zip.getEntry(name)?.let { entry -> zip.getInputStream(entry) }

    fun names(): List<String> =
        zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()

    override fun close() {
        try {
            zip.close()
        } finally {
            temporaryFile.delete()
        }
    }

    companion object {
        fun open(context: Context, uri: Uri): NativeBackupArchive? {
            val temporaryFile = File(context.cacheDir, "lxchat_import_tmp.zip")
            return try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temporaryFile.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    temporaryFile.delete()
                    return null
                }
                NativeBackupArchive(ZipFile(temporaryFile), temporaryFile)
            } catch (_: Exception) {
                temporaryFile.delete()
                null
            }
        }
    }
}
