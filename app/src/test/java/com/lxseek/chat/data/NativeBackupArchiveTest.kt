package com.lxseek.chat.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeBackupArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readsEntriesOnDemandAndDeletesTemporaryArchiveOnClose() {
        val archiveFile = temporaryFolder.newFile("backup.zip")
        val payload = "manifest".toByteArray()
        ZipOutputStream(archiveFile.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("folder/"))
            output.closeEntry()
            output.putNextEntry(ZipEntry("manifest.json"))
            output.write(payload)
            output.closeEntry()
        }
        val archive = constructArchive(archiveFile)

        assertTrue(archive.has("manifest.json"))
        assertEquals(payload.size.toLong(), archive.size("manifest.json"))
        assertArrayEquals(payload, archive.bytes("manifest.json"))
        assertArrayEquals(payload, archive.stream("manifest.json")!!.use { it.readBytes() })
        assertEquals(listOf("manifest.json"), archive.names())

        archive.close()
        assertFalse(archiveFile.exists())
    }

    private fun constructArchive(file: File): NativeBackupArchive {
        val constructor = NativeBackupArchive::class.java.getDeclaredConstructor(
            ZipFile::class.java,
            File::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(ZipFile(file), file)
    }
}
