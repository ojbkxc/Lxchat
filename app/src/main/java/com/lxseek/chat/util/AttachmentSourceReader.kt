package com.lxseek.chat.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI

/**
 * Opens attachment sources without assuming that every source is a ContentResolver URI.
 *
 * Composer-owned attachments are copied to app-private storage and represented as bare absolute
 * paths. Those paths must be opened directly; ContentResolver only owns URI-backed sources.
 */
object AttachmentSourceReader {

    fun open(context: Context, source: String): InputStream? =
        open(source) { uriSource ->
            context.contentResolver.openInputStream(Uri.parse(uriSource))
        }

    fun readText(context: Context, source: String, maxChars: Int): String? =
        readText(source, maxChars) { uriSource ->
            context.contentResolver.openInputStream(Uri.parse(uriSource))
        }

    internal fun open(
        source: String,
        uriOpener: (String) -> InputStream?,
    ): InputStream? = try {
        when {
            source.startsWith("file:", ignoreCase = true) ->
                FileInputStream(File(URI(source)))
            File(source).isAbsolute ->
                FileInputStream(source)
            else ->
                uriOpener(source)
        }
    } catch (_: Exception) {
        null
    }

    internal fun readText(
        source: String,
        maxChars: Int,
        uriOpener: (String) -> InputStream?,
    ): String? {
        require(maxChars >= 0)
        return try {
            open(source, uriOpener)?.bufferedReader()?.use { reader ->
                val result = StringBuilder(minOf(maxChars, 8_192))
                val buffer = CharArray(minOf(maxChars.coerceAtLeast(1), 8_192))
                while (result.length < maxChars) {
                    val read = reader.read(
                        buffer,
                        0,
                        minOf(buffer.size, maxChars - result.length),
                    )
                    if (read <= 0) break
                    result.append(buffer, 0, read)
                }
                result.toString()
            }
        } catch (_: Exception) {
            null
        }
    }
}
