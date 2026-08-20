package com.lxseek.chat.sandbox

import java.io.File

internal class AlpinePackageMetadataStore(
    private val rootfsDir: File,
) {
    private val metadataDir: File = File(rootfsDir, "etc/lxchat")
    private val baseWorldFile: File = File(metadataDir, "base-world")
    private val explicitPackagesFile: File = File(metadataDir, "explicit-packages")
    private val defaultBaseWorld = linkedSetOf("alpine-baselayout", "alpine-keys", "apk-tools", "busybox", "libc-utils")
    private val packageNameRegex = Regex("^[A-Za-z0-9][A-Za-z0-9+_.:-]*$")

    fun sanitizePackageName(packageName: String): String {
        val trimmed = packageName.trim()
        require(packageNameRegex.matches(trimmed)) { "Invalid package name: $packageName" }
        return trimmed
    }

    private fun installedDbFile(): File = File(rootfsDir, "lib/apk/db/installed")

    fun readInstalledVersions(): LinkedHashMap<String, String> {
        val installed = linkedMapOf<String, String>()
        val db = installedDbFile()
        if (!db.exists()) return installed
        var name = ""
        var version = ""
        db.readLines(Charsets.UTF_8).forEach { line ->
            when {
                line.startsWith("P:") -> name = line.substring(2).trim()
                line.startsWith("V:") -> version = line.substring(2).trim()
                line.isBlank() -> {
                    if (name.isNotEmpty()) installed[name] = version
                    name = ""
                    version = ""
                }
            }
        }
        if (name.isNotEmpty()) installed[name] = version
        return installed
    }

    private fun worldFile(): File = File(rootfsDir, "etc/apk/world")

    private fun readWorldLines(): LinkedHashSet<String> {
        val world = worldFile()
        if (!world.exists()) return linkedSetOf()
        return world.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toCollection(linkedSetOf())
    }

    private fun writeWorldLines(lines: Collection<String>) {
        val world = worldFile()
        world.parentFile?.mkdirs()
        world.writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"), Charsets.UTF_8)
    }

    private fun worldPackageName(line: String): String {
        val cleaned = line.trim().removePrefix("!")
        val end = cleaned.indexOfFirst { it == '@' || it == '<' || it == '>' || it == '=' || it == '~' }
        return if (end >= 0) cleaned.substring(0, end) else cleaned
    }

    fun captureBaseWorld(force: Boolean = false) {
        metadataDir.mkdirs()
        if (!force && baseWorldFile.exists()) return
        val current = readWorldLines()
        val installed = readInstalledVersions().keys
        val inferredBase = current.filter { worldPackageName(it) in defaultBaseWorld && worldPackageName(it) in installed }
        val fallbackBase = defaultBaseWorld.filter { it in installed }
        val base = when {
            force && current.isNotEmpty() -> current
            force -> fallbackBase.ifEmpty { defaultBaseWorld }
            inferredBase.isNotEmpty() -> inferredBase
            else -> current
        }
        baseWorldFile.writeText(base.joinToString("\n", postfix = if (base.isEmpty()) "" else "\n"), Charsets.UTF_8)
    }

    fun readBaseWorld(): LinkedHashSet<String> {
        captureBaseWorld()
        return baseWorldFile.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toCollection(linkedSetOf())
    }

    fun isBasePackage(packageName: String): Boolean =
        readBaseWorld().any { worldPackageName(it) == packageName }

    fun readExplicitPackages(): LinkedHashSet<String> {
        if (!explicitPackagesFile.exists()) return linkedSetOf()
        return explicitPackagesFile.readLines(Charsets.UTF_8)
            .mapNotNull { runCatching { sanitizePackageName(it) }.getOrNull() }
            .toCollection(linkedSetOf())
    }

    fun writeExplicitPackages(packages: Collection<String>) {
        metadataDir.mkdirs()
        val clean = packages.mapNotNull { runCatching { sanitizePackageName(it) }.getOrNull() }.toCollection(linkedSetOf())
        explicitPackagesFile.writeText(clean.joinToString("\n", postfix = if (clean.isEmpty()) "" else "\n"), Charsets.UTF_8)
    }

    fun ensurePackageMetadata() {
        if (!rootfsDir.isDirectory) return
        metadataDir.mkdirs()
        captureBaseWorld()
        if (!explicitPackagesFile.exists()) {
            val baseNames = readBaseWorld().map { worldPackageName(it) }.toSet()
            val migratedExplicit = readWorldLines()
                .map { worldPackageName(it) }
                .filter { it !in baseNames }
                .mapNotNull { runCatching { sanitizePackageName(it) }.getOrNull() }
                .toCollection(linkedSetOf())
            writeExplicitPackages(migratedExplicit)
        }
        normalizeWorld()
    }

    fun normalizeWorld(explicitPackages: Set<String> = readExplicitPackages()) {
        metadataDir.mkdirs()
        val base = readBaseWorld()
        val baseNames = base.map { worldPackageName(it) }.toSet()
        val normalized = linkedSetOf<String>()
        normalized.addAll(base)
        explicitPackages
            .mapNotNull { runCatching { sanitizePackageName(it) }.getOrNull() }
            .filter { it !in baseNames }
            .forEach { normalized.add(it) }
        writeWorldLines(normalized)
    }

    fun addExplicitPackage(packageName: String) {
        val name = sanitizePackageName(packageName)
        ensurePackageMetadata()
        val next = readExplicitPackages().apply { add(name) }
        writeExplicitPackages(next)
        normalizeWorld(next)
    }
}
