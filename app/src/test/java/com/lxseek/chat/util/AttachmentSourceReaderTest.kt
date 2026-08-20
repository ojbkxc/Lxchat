package com.lxseek.chat.util

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentSourceReaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readText_supportsBareAbsolutePath() {
        val file = temporaryFolder.newFile("message.txt")
        file.writeText("hello from disk")

        val content = AttachmentSourceReader.readText(
            source = file.absolutePath,
            maxChars = 100,
            uriOpener = { error("absolute paths must not use the URI opener") },
        )

        assertEquals("hello from disk", content)
    }

    @Test
    fun readText_supportsFileUri() {
        val file = temporaryFolder.newFile("message.txt")
        file.writeText("file uri")

        val content = AttachmentSourceReader.readText(
            source = file.toURI().toString(),
            maxChars = 100,
            uriOpener = { error("file URIs must not use the URI opener") },
        )

        assertEquals("file uri", content)
    }

    @Test
    fun readText_delegatesContentUriAndHonorsLimit() {
        val content = AttachmentSourceReader.readText(
            source = "content://documents/message.txt",
            maxChars = 4,
            uriOpener = { ByteArrayInputStream("content".toByteArray()) },
        )

        assertEquals("cont", content)
    }

    @Test
    fun readText_distinguishesEmptyFileFromReadFailure() {
        val emptyFile = temporaryFolder.newFile("empty.txt")

        val emptyContent = AttachmentSourceReader.readText(
            source = emptyFile.absolutePath,
            maxChars = 100,
            uriOpener = { error("absolute paths must not use the URI opener") },
        )
        val missingContent = AttachmentSourceReader.readText(
            source = temporaryFolder.root.resolve("missing.txt").absolutePath,
            maxChars = 100,
            uriOpener = { error("absolute paths must not use the URI opener") },
        )

        assertEquals("", emptyContent)
        assertNull(missingContent)
    }

    @Test
    fun readText_returnsNullWhenStreamFailsDuringRead() {
        val content = AttachmentSourceReader.readText(
            source = "content://documents/broken.txt",
            maxChars = 100,
            uriOpener = {
                object : InputStream() {
                    override fun read(): Int = throw IOException("broken stream")
                }
            },
        )

        assertNull(content)
    }
}
