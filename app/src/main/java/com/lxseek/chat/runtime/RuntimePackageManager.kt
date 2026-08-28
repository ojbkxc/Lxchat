package com.lxseek.chat.runtime

import android.content.Context
import com.lxseek.chat.api.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

/**
 * 运行时引擎包管理：下载 .runtime 压缩包到 filesDir、解压到 `filesDir/runtimes/<engine>/<version>/`、
 * 处理可执行权限、读取 manifest.json、清理未使用版本。
 *
 * 使用 filesDir（[context.filesDir]）而非 cacheDir：可持久、系统清缓存时不会被清除，
 * 应用重启后可离线重建（无需重新下载）。
 */
class RuntimePackageManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 每个引擎的安装根目录：filesDir/runtimes/<engineId>。目录本身随引擎卸载递归删除。 */
    fun engineRoot(engineId: String): File =
        File(context.filesDir, "runtimes/${safeName(engineId)}")

    /** 指定版本的安装目录：filesDir/runtimes/<engineId>/<version>。 */
    fun versionRoot(engineId: String, version: String): File =
        File(engineRoot(engineId), safeName(version))

    /** 已安装（落盘）的全部版本。 */
    fun installedVersions(engineId: String): List<String> {
        val root = engineRoot(engineId)
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f -> f.isDirectory }
            .orEmpty()
            .mapNotNull { it.name.takeIf(String::isNotBlank) }
            .sorted()
    }

    /**
     * 下载并安装引擎版本。compressUrl 可包含 `{version}` 占位符，会被替换为实际版本号。
     * 返回该版本的安装根目录。
     */
    fun install(engineId: String, version: String, compressUrl: String): File {
        val target = versionRoot(engineId, version)
        if (target.isDirectory && target.resolve("manifest.json").isFile) {
            // 已存在则幂等返回。
            return target
        }
        val url = compressUrl.replace("{version}", version)
        val bytes = HttpClient.getBytes(url)
            ?: throw IOException("引擎下载失败：HTTP 错误或连接中断")
        if (bytes.isEmpty()) throw IOException("引擎下载内容为空")
        val tmp = File(context.filesDir, "runtimes/.downloads/${safeName(engineId)}-${safeName(version)}.zip")
        tmp.parentFile?.mkdirs()
        tmp.writeBytes(bytes)
        // 先校验包结构再落位：坏包不写入安装目录，并清理临时文件。
        target.mkdirs()
        try {
            unzip(tmp, target)
        } catch (e: Exception) {
            target.deleteRecursively()
            tmp.delete()
            throw IOException("引擎包结构无效或解压失败：${e.message}", e)
        } finally {
            tmp.delete()
        }
        if (target.resolve("manifest.json").isFile != true) {
            target.deleteRecursively()
            throw IOException("引擎包缺失 manifest.json")
        }
        makeExecutable(target)
        return target
    }

    /** 读取并解析引擎 manifest；缺失或损坏返回 null。 */
    fun readManifest(engineId: String, version: String): RuntimeManifest? {
        val root = versionRoot(engineId, version)
        val file = root.resolve("manifest.json")
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<RuntimeManifest>(file.readText())
        }.getOrNull()
    }

    /** 删除一个已安装版本；若该引擎不再有任何版本则删除引擎目录。 */
    fun removeVersion(engineId: String, version: String) {
        versionRoot(engineId, version).deleteRecursively()
        val root = engineRoot(engineId)
        if (root.exists() && (root.listFiles()?.isEmpty() != false)) {
            root.deleteRecursively()
        }
    }

    /** 递归删除引擎全部安装（含所有版本）。 */
    fun removeEngine(engineId: String) {
        stopGuard?.invoke(engineId)
        engineRoot(engineId).deleteRecursively()
        File(context.filesDir, "runtimes/.downloads").deleteRecursively()
    }

    private var stopGuard: ((String) -> Unit)? = null

    /** 注册卸载前强制停止对应进程的钩子（避免留残留进程）。 */
    fun onRemoveAction(stop: (String) -> Unit) {
        stopGuard = stop
    }

    // ── 内部工具 ───────────────────────────────────────────

    /** 解压 zip 到 [dest]，zip-slip 防护：路径逃逸目标目录的条目被跳过。 */
    private fun unzip(zipFile: File, dest: File) {
        val canonicalDest = dest.canonicalFile
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val out = File(dest, entry.name)
                val canonicalOut = try { out.canonicalFile } catch (e: Exception) { null } ?: continue
                if (!canonicalOut.path.startsWith(canonicalDest.path)) continue // zip-slip guard
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /** 给可执行文件加执行权限。 */
    private fun makeExecutable(root: File) {
        root.setExecutable(true)
        root.listFiles().orEmpty().forEach { child ->
            if (child.isDirectory) {
                child.setExecutable(true)
                makeExecutable(child)
            } else {
                child.setExecutable(true, true)
            }
        }
    }

    private fun safeName(s: String): String =
        s.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .trim('_', '-')
            .ifBlank { "engine" }
            .take(64)
}