package com.lxseek.chat.sandbox

import java.io.File

// ── Tar Extraction ──────────────────────────────────

internal fun extractTarEntries(tar: org.apache.commons.compress.archivers.tar.TarArchiveInputStream, destDir: File) {
    val destPrefix = destDir.canonicalPath + File.separator
    // Reject any entry whose resolved path escapes destDir (Zip-Slip / path traversal).
    fun safeChild(name: String): File? {
        val f = File(destDir, name)
        return if (f.canonicalPath == destDir.canonicalPath || f.canonicalPath.startsWith(destPrefix)) f else null
    }
    val symlinks = mutableListOf<Pair<String, String>>()
    var entry = tar.nextEntry
    while (entry != null) {
        val outFile = safeChild(entry.name)
        if (outFile == null) { entry = tar.nextEntry; continue }
        when {
            entry.isDirectory -> outFile.mkdirs()
            entry.isSymbolicLink -> { outFile.parentFile?.mkdirs(); symlinks.add(entry.name to entry.linkName) }
            entry.isFile -> { outFile.parentFile?.mkdirs(); outFile.outputStream().use { tar.copyTo(it) }; if (entry.mode and 0x40 != 0) outFile.setExecutable(true, false) }
        }
        entry = tar.nextEntry
    }
    for ((name, target) in symlinks) {
        val outFile = safeChild(name) ?: continue; if (outFile.exists()) continue
        val src = if (target.startsWith("/")) File(destDir, target.trimStart('/'))
                  else File(outFile.parentFile ?: destDir, target)
        // Containment check on the symlink source too.
        if (src.canonicalPath != destDir.canonicalPath && !src.canonicalPath.startsWith(destPrefix)) continue
        if (!src.exists()) continue
        try {
            if (src.isDirectory) src.walkTopDown().forEach { f -> val rel = f.relativeTo(src).path; val dst = File(outFile, rel); if (f.isDirectory) dst.mkdirs() else { dst.parentFile?.mkdirs(); f.copyTo(dst, true) } }
            else { outFile.parentFile?.mkdirs(); src.copyTo(outFile, true) }
        } catch (_: Throwable) {}
    }
}
