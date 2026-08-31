package com.lxseek.chat.runtime

import android.content.Context
import com.lxseek.chat.api.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections
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
     * 返回该版本的安装根目录。网络下载与解压均为阻塞操作，统一切到 [Dispatchers.IO]
     * 执行，避免在 UI 主线程同步联网触发 NetworkOnMainThreadException。
     *
     * 下载采用流式落盘（[HttpClient.downloadToFile]）而非 [HttpClient.getBytes]：引擎包可达
     * 数百 MB，全量加载到 ByteArray 会 OOM；流式 copyTo 直接写文件，内存占用与包大小无关。
     */
    suspend fun install(
        engineId: String,
        version: String,
        compressUrl: String,
        onLog: ((String) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val target = versionRoot(engineId, version)
        if (target.isDirectory && target.resolve("manifest.json").isFile) {
            // 已存在则幂等返回。
            onLog?.invoke("[$engineId] $version 已安装，跳过")
            return@withContext target
        }
        val url = compressUrl.replace("{version}", version)
        val tmp = File(context.filesDir, "runtimes/.downloads/${safeName(engineId)}-${safeName(version)}.zip")
        tmp.parentFile?.mkdirs()
        // 流式下载到临时文件：避免大包全量入内存导致 OOM。
        onLog?.invoke("[$engineId] 开始下载 $version")
        onLog?.invoke("[$engineId] 地址: $url")
        try {
            var lastReportedPercent = -1
            var lastReportedBytes = -1L
            HttpClient.downloadToFile(url, tmp, onProgress = { done, total ->
                if (total > 0) {
                    val percent = (done * 100 / total).toInt()
                    // Throttle: report every 5% and always the final 100%.
                    if (percent >= lastReportedPercent + 5 || percent == 100) {
                        lastReportedPercent = percent
                        onLog?.invoke("[$engineId] 下载中 $percent% (${formatSize(done)}/${formatSize(total)})")
                    }
                } else {
                    // Total unknown (-1): report every ~5MB of transferred bytes.
                    if (done - lastReportedBytes >= DOWNLOAD_PROGRESS_STEP_BYTES) {
                        lastReportedBytes = done
                        onLog?.invoke("[$engineId] 已下载 ${formatSize(done)}")
                    }
                }
            })
            onLog?.invoke("[$engineId] 下载完成（${formatSize(tmp.length())}）")
        } catch (e: IOException) {
            tmp.delete()
            // 仅输出干净的错误摘要，避免将来源字符串中的乱码带进 UI 日志。
            val detail = (e.message ?: "").replace(Regex("[^\\u0020-\\u007E]"), "").ifBlank { e::class.simpleName ?: "" }
            onLog?.invoke("[$engineId] 下载失败: $detail")
            throw IOException("Engine download failed: ${e.message}", e)
        }
        // 先校验包结构再落位：坏包不写入安装目录，并清理临时文件。
        onLog?.invoke("[$engineId] 解压中…")
        target.mkdirs()
        try {
            unzip(tmp, target)
        } catch (e: Exception) {
            target.deleteRecursively()
            tmp.delete()
            onLog?.invoke("[$engineId] 解压失败: ${e.message}")
            throw IOException("Invalid engine package or extraction failed: ${e.message}", e)
        } finally {
            tmp.delete()
        }
        if (target.resolve("manifest.json").isFile != true) {
            target.deleteRecursively()
            onLog?.invoke("[$engineId] 校验失败: 缺少 manifest.json")
            throw IOException("Engine package missing manifest.json")
        }
        makeExecutable(target)
        onLog?.invoke("[$engineId] 安装完成: $version")
        return@withContext target
    }

    /** Unknown total size -> report download progress every ~5MB of transferred bytes. */
    private val DOWNLOAD_PROGRESS_STEP_BYTES = 5L * 1024 * 1024

    /** 字节数格式化为可读大小（用于安装日志进度显示）。 */
    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var v = bytes.toDouble()
        var i = -1
        do {
            v /= 1024.0
            i++
        } while (v >= 1024.0 && i < units.lastIndex)
        return "%.1f %s".format(java.util.Locale.US, v, units[i])
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
        if (root.exists() && (root.listFiles()?.isEmpty() == true)) {
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

    /** 解压 zip 到 [dest]，zip-slip 防护：路径逃逸目标目录的条目被跳过。
     *  如果 zip 所有文件都在单个顶层目录下，则自动 strip 这个顶层目录，让文件直接落到 dest。
     * 这处理常见的发布包结构：github release 导出的 zip 通常包含一个顶层同名目录。
     */
    private fun unzip(zipFile: File, dest: File) {
        val canonicalDest = dest.canonicalFile
        ZipFile(zipFile).use { zip ->
            val entries = Collections.list(zip.entries())
            // 检测是否所有条目都在单个顶层目录下
            val topDirs = entries
                .filterNot { it.isDirectory }
                .mapNotNull { it.name.split('/').firstOrNull() }
                .toSet()

            val stripTopDir = topDirs.size == 1 && entries.all {
                it.name.startsWith(topDirs.first() + "/")
            }

            val stripPrefix = if (stripTopDir) topDirs.first() + "/" else ""

            entries.forEach { entry ->
                if (entry.isDirectory) return@forEach
                val entryName = entry.name.removePrefix(stripPrefix)
                if (entryName.isBlank()) return@forEach

                val out = File(dest, entryName)
                val canonicalOut = try { out.canonicalFile } catch (e: Exception) { null } ?: return@forEach
                // 路径前缀比较：追加分隔符避免 /foo/bar 匹配 /foo/barbaz（zip-slip 防护）
                if (canonicalDest != canonicalOut && !canonicalOut.path.startsWith(canonicalDest.path + File.separator)) {
                    return@forEach
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /**
     * 给可执行文件加执行权限。Android 上 File.setExecutable 不可靠（SELinux / FUSE 层
     * 可能静默忽略），改用 chmod -R 755 确保 bin/ 下的 wrapper 和 real binary 都可执行。
     */
    private fun makeExecutable(root: File) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("chmod", "-R", "755", root.absolutePath))
            proc.waitFor()
        } catch (e: Exception) {
            // fallback: File.setExecutable
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
    }

    private fun safeName(s: String): String =
        s.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .trim('_', '-')
            .ifBlank { "engine" }
            .take(64)
}
