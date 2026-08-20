package com.lxseek.chat.sandbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotSandboxSupportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun alpineVersionsCompareNumericTokensPrereleasesAndRevisions() {
        assertTrue(compareAlpineVersions("1.10-r0", "1.2-r9") > 0)
        assertTrue(compareAlpineVersions("3.5.2-r1", "3.5.2-r0") > 0)
        assertTrue(compareAlpineVersions("2.0_rc1-r0", "2.0-r0") < 0)
        assertEquals(0, compareAlpineVersions("3.5.2-r1", "3.5.2-r1"))
    }

    @Test
    fun virtualPathsNormalizeAndPortableGlobMatchesRemainStable() {
        assertEquals("/", normalizeVirtualPath("  "))
        assertEquals("/home/lxchat/file.txt", normalizeVirtualPath("home//lxchat/file.txt/"))
        assertEquals(
            listOf("/home/lxchat/readme.md"),
            globMatch(
                files = listOf("/home/lxchat/readme.md", "/home/lxchat/image.png"),
                pattern = "*.md",
            ),
        )
    }

    @Test
    fun pathResolverKeepsEachVirtualMountInsideItsPhysicalRoot() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val home = temporaryFolder.newFolder("home")
        val shared = temporaryFolder.newFolder("shared")
        val resolver = SandboxPathResolver(
            rootfsDir = rootfs,
            homeMountDir = home,
            homeMountPath = "/home/lxchat",
            sharedStorageMountPath = "/mnt/shared",
            sharedStorageHostDir = { shared },
        )

        assertEquals(File(home, "note.txt").canonicalFile, resolver.resolvePath("/home/lxchat/note.txt"))
        assertEquals(File(shared, "photo.png").canonicalFile, resolver.resolvePath("/mnt/shared/photo.png"))
        assertEquals(File(rootfs, "etc/hosts").canonicalFile, resolver.resolvePath("/etc/hosts"))

        val escaped = runCatching { resolver.resolvePath("/home/lxchat/../../escape") }
        assertTrue(escaped.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun packageMetadataReadsInstalledVersionsAndRejectsInvalidNames() {
        val rootfs = temporaryFolder.newFolder("metadata-rootfs")
        val installed = File(rootfs, "lib/apk/db/installed")
        installed.parentFile!!.mkdirs()
        installed.writeText("P:busybox\nV:1.36.1-r2\n\nP:apk-tools\nV:2.14.4-r0\n")
        val world = File(rootfs, "etc/apk/world")
        world.parentFile!!.mkdirs()
        world.writeText("busybox\ncurl\n")
        val store = AlpinePackageMetadataStore(rootfs)

        assertEquals(
            linkedMapOf("busybox" to "1.36.1-r2", "apk-tools" to "2.14.4-r0"),
            store.readInstalledVersions(),
        )
        assertTrue(store.isBasePackage("busybox"))
        assertFalse(store.isBasePackage("curl"))
        assertEquals("python3", store.sanitizePackageName(" python3 "))
        assertTrue(runCatching { store.sanitizePackageName("python; rm") }.isFailure)
    }

    @Test
    fun rootfsArchiveExtractionRejectsTraversalEntries() {
        val tarBytes = ByteArrayOutputStream().use { bytes ->
            TarArchiveOutputStream(bytes).use { tar ->
                writeEntry(tar, "safe.txt", "safe")
                writeEntry(tar, "../escape.txt", "escape")
            }
            bytes.toByteArray()
        }
        val destination = temporaryFolder.newFolder("archive-root")
        TarArchiveInputStream(ByteArrayInputStream(tarBytes)).use { tar ->
            extractTarEntries(tar, destination)
        }

        assertEquals("safe", File(destination, "safe.txt").readText())
        assertFalse(File(destination.parentFile, "escape.txt").exists())
    }

    private fun writeEntry(
        tar: TarArchiveOutputStream,
        name: String,
        content: String,
    ) {
        val payload = content.toByteArray()
        tar.putArchiveEntry(TarArchiveEntry(name).apply { size = payload.size.toLong() })
        tar.write(payload)
        tar.closeArchiveEntry()
    }
}
