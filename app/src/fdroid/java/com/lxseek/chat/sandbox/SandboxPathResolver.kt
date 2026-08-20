package com.lxseek.chat.sandbox

import java.io.File

internal data class ResolvedSandboxPath(
    val file: File,
    val physicalRoot: File,
    val virtualRoot: String
)

internal class SandboxPathResolver(
    private val rootfsDir: File,
    private val homeMountDir: File,
    private val homeMountPath: String,
    private val sharedStorageMountPath: String,
    private val sharedStorageHostDir: () -> File?,
) {
    fun ensureSandboxMountTargets() {
        homeMountDir.mkdirs()
        File(rootfsDir, homeMountPath.trimStart('/')).mkdirs()
        File(rootfsDir, sharedStorageMountPath.trimStart('/')).mkdirs()
    }

    fun resolveSandboxPath(path: String): ResolvedSandboxPath {
        val normalized = normalizeVirtualPath(path)
        if (normalized == homeMountPath || normalized.startsWith("$homeMountPath/")) {
            ensureSandboxMountTargets()
            val root = homeMountDir.canonicalFile
            val suffix = normalized.removePrefix(homeMountPath).trimStart('/')
            val resolved = File(root, suffix).canonicalFile
            require(resolved.absolutePath == root.absolutePath || resolved.absolutePath.startsWith(root.absolutePath + File.separator)) {
                "Path traversal: $path"
            }
            return ResolvedSandboxPath(resolved, root, homeMountPath)
        }
        if (
            normalized == sharedStorageMountPath ||
            normalized.startsWith("$sharedStorageMountPath/")
        ) {
            val root = sharedStorageHostDir()
                ?: throw SecurityException(
                    "Shared storage is not mounted. Enable it and grant all-files access first.",
                )
            val suffix = normalized.removePrefix(sharedStorageMountPath).trimStart('/')
            val resolved = File(root, suffix).canonicalFile
            require(
                resolved.absolutePath == root.absolutePath ||
                    resolved.absolutePath.startsWith(root.absolutePath + File.separator),
            ) {
                "Path traversal: $path"
            }
            return ResolvedSandboxPath(resolved, root, sharedStorageMountPath)
        }

        val root = rootfsDir.canonicalFile
        val resolved = File(root, normalized.trimStart('/')).canonicalFile
        require(resolved.absolutePath == root.absolutePath || resolved.absolutePath.startsWith(root.absolutePath + File.separator)) {
            "Path traversal: $path"
        }
        return ResolvedSandboxPath(resolved, root, "/")
    }

    fun resolvePath(path: String): File = resolveSandboxPath(path).file

    // remaining: levels still allowed including the current dir's files. -1 = unlimited;
    // 1 = only this dir's files (no descent); >1 = descend with one fewer level.
    fun walkVirtualFiles(
        dir: File,
        result: MutableList<String>,
        physicalRootAbsPath: String,
        virtualRoot: String,
        remaining: Int = -1
    ) {
        try { dir.listFiles()?.forEach {
            if (it.isDirectory) {
                if (remaining < 0 || remaining > 1) {
                    walkVirtualFiles(it, result, physicalRootAbsPath, virtualRoot, if (remaining < 0) -1 else remaining - 1)
                }
            } else {
                val path = try { it.canonicalPath } catch (_: Exception) { it.absolutePath }
                val rel = path.removePrefix(physicalRootAbsPath).removePrefix(File.separator).replace(File.separatorChar, '/')
                val prefix = if (virtualRoot == "/") "" else virtualRoot.trimEnd('/')
                result.add("$prefix/$rel")
            }
        } } catch (_: Throwable) {}
    }
}
