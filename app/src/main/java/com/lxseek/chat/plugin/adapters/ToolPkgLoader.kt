package com.lxseek.chat.plugin.adapters

import com.lxseek.chat.plugin.Plugin
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Loader for ToolPkg packages: unzips into a cache directory, adapts via
 * [ToolPkgAdapter], and tracks the loaded [Plugin] instances for lifecycle management
 * (unload / list / get).
 *
 * Each package is cached under `cacheDir/<toolpkgId>/` so its scripts and resources
 * remain on disk for the ToolPkg JS runtime (and any UI DSL renderer) to read after
 * loading. The cache directory is created lazily on first load.
 *
 * Zip-slip safe: entry paths that escape the cache root are rejected.
 */
class ToolPkgLoader(private val cacheDir: File) {

    private val adapter = ToolPkgAdapter()
    private val loaded = LinkedHashMap<String, LoadedPackage>()

    private data class LoadedPackage(val plugin: Plugin, val cacheRoot: File)

    /**
     * Load a `.toolpkg` ZIP: adapt it to a [Plugin], unzip its contents into
     * `cacheDir/<toolpkgId>/`, and remember it for later unload/list. Returns
     * the plugin, or null when the package is malformed or already loaded.
     */
    fun load(zipFile: File): Plugin? {
        val plugin = adapter.adapt(zipFile) ?: return null
        val id = plugin.manifest.id
        if (loaded.containsKey(id)) return null

        val cacheRoot = File(cacheDir, id)
        cacheRoot.mkdirs()
        unzipInto(zipFile, cacheRoot)

        loaded[id] = LoadedPackage(plugin, cacheRoot)
        return plugin
    }

    /** Unload a package by id: drop it from the registry and delete its cache directory. */
    fun unload(pluginId: String) {
        val pkg = loaded.remove(pluginId) ?: return
        pkg.cacheRoot.deleteRecursively()
    }

    /** List the ids of currently loaded packages. */
    fun listLoaded(): List<String> = loaded.keys.toList()

    /** Look up a loaded plugin by id. */
    fun get(pluginId: String): Plugin? = loaded[pluginId]?.plugin

    /**
     * Unzip [zipFile] into [dest], preserving entry paths. Existing files are overwritten.
     * Entries whose resolved path escapes [dest] (zip-slip) are skipped.
     */
    private fun unzipInto(zipFile: File, dest: File) {
        val canonicalDest = dest.canonicalFile
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val out = File(dest, entry.name)
                val canonicalOut = out.canonicalFile
                // Zip-slip guard: require an exact match or a separator boundary so
                // "/cache/pluginId" does not falsely contain "/cache/pluginId_evil".
                if (canonicalOut != canonicalDest &&
                    !canonicalOut.path.startsWith(canonicalDest.path + File.separator)
                ) continue
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
        }
    }
}