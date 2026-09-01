package com.lxseek.chat.tool

import android.content.Context
import com.lxseek.chat.adb.RootDetector
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.sandbox.SandboxSharedStorageAccess
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.security.MessageDigest

/**
 * 残留/重复文件维护工具集（双模式）。
 *
 * 参照 SD Maid SE 的独特能力，用系统自带 shell（find/du/stat/md5sum/rm）或 Kotlin
 * 文件遍历按需实现，无常驻服务、不移植第三方代码：
 *
 *  - corpse_*   已卸载应用残留数据检测与清理（仅 root；扫描 /data/data、
 *                /sdcard/Android/{data,media,obb} 顶层目录，目录名归一化后不在
 *                已安装包集合中即视为尸体残留）
 *  - residual_* 应用可消耗/缓存文件扫描与清理（root：任意已装应用公开/私有缓存、
 *                code_cache、缩略图；普通用户：仅 Lxchat 自身缓存可访问）
 *  - oldfile_*  按文件修改时间清理旧文件（root：find；普通用户：Kotlin 遍历）
 *  - dupe_*     按大小分组 + md5 校验检测重复文件并去重（同上双实现）
 *
 * 披露规则：root 用户获得全部 8 个工具；普通用户默认即可维护应用自身目录
 * （应用缓存 + 应用专属外部目录，无需任何权限），授予「所有文件访问权限」
 * （Android 11+ All files access）后可将 oldfile/dupe/residual 扩展到整个
 * /sdcard。corpse_* 始终仅 root 可用。
 */
class ResidualToolProvider(private val context: Context) : ToolProvider {

    private companion object {
        const val MAX_OUTPUT = 6000
        const val TIMEOUT_DEFAULT = 60000
        const val TIMEOUT_SCAN = 295000

        val TOOL_NAMES = setOf(
            "corpse_scan",
            "corpse_clean",
            "residual_scan",
            "residual_clean",
            "oldfile_scan",
            "oldfile_clean",
            "dupe_scan",
            "dupe_clean",
        )

        /** 尸体残留允许存在的根目录（防止任意路径删除）。 */
        val CORPSE_ROOTS = listOf(
            "/data/data",
            "/data/user/0",
            "/sdcard/Android/data",
            "/sdcard/Android/media",
            "/sdcard/Android/obb",
        )

        /**
         * 尸体残留扫描脚本：枚举已安装包，扫描各数据区顶层目录，目录名归一化后
         * 不在已安装集合中且形似包名（含点）的目录即视为残留。输出 label|name|normalized|sizeKB。
         */
        const val CORPSE_SCAN_SCRIPT = """
            installed=$(pm list packages 2>/dev/null | sed 's/^package://')
            LIMIT=${'$'}LIMIT
            count=0
            normalize() {
              n="${'$'}1"
              case "${'$'}n" in
                .external.*) n="${'$'}{n#.external.}" ;;
                _*|.*) n="${'$'}{n#?}" ;;
              esac
              case "${'$'}n" in
                *:remote) n="${'$'}{n%:remote}" ;;
                *.overlay) n="${'$'}{n%.overlay}" ;;
              esac
              echo "${'$'}n"
            }
            is_pkgname() { echo "${'$'}1" | grep -qE '^[A-Za-z][A-Za-z0-9_]*\.[A-Za-z0-9._-]+$'; }
            is_installed() { echo "${'$'}installed" | grep -qx "${'$'}1"; }
            scan_area() {
              area="${'$'}1"; label="${'$'}2"
              [ -d "${'$'}area" ] || return 0
              for d in "${'$'}area"/*/; do
                [ -d "${'$'}d" ] || continue
                [ "${'$'}count" -ge "${'$'}LIMIT" ] && return 0
                b=$(basename "${'$'}d")
                n=$(normalize "${'$'}b")
                is_pkgname "${'$'}n" || continue
                is_installed "${'$'}n" && continue
                sz=$(du -sk "${'$'}d" 2>/dev/null | awk '{print ${'$'}1}')
                echo "${'$'}label|${'$'}b|${'$'}n|${'$'}{sz:-0}"
                count=$((count + 1))
              done
            }
            scan_area /data/data private
            scan_area /sdcard/Android/data public
            scan_area /sdcard/Android/media media
            scan_area /sdcard/Android/obb obb
        """

        /** 残留缓存扫描脚本：逐包统计公开/私有缓存、code_cache 与缩略图可消耗目录。输出 pkg|totalKB|dirs。 */
        const val RESIDUAL_SCAN_SCRIPT = """
            PKG="${'$'}PKG"
            if [ -n "${'$'}PKG" ]; then
              pkgs="${'$'}PKG"
            else
              pkgs=$(pm list packages 2>/dev/null | sed 's/^package://')
            fi
            for p in ${'$'}pkgs; do
              total=0; out=""
              for d in \
                "/data/data/${'$'}p/cache" \
                "/data/data/${'$'}p/code_cache" \
                "/data/data/${'$'}p/files/.thumbnails" \
                "/sdcard/Android/data/${'$'}p/cache" \
                "/sdcard/Android/data/${'$'}p/files/.thumbnails"; do
                [ -d "${'$'}d" ] || continue
                sz=$(du -sk "${'$'}d" 2>/dev/null | awk '{print ${'$'}1}')
                [ -n "${'$'}sz" ] || sz=0
                [ "${'$'}sz" -gt 0 ] 2>/dev/null || continue
                total=$((total + sz))
                out="${'$'}out;${'$'}d=${'$'}szK"
              done
              [ "${'$'}total" -gt 0 ] 2>/dev/null && echo "${'$'}p|${'$'}total|${'$'}out"
            done
        """

        private fun validPackage(pkg: String): Boolean = Regex("[a-zA-Z0-9._-]+").matches(pkg)

        /** 与 shell 版 normalize 一致的目录名归一化。 */
        private fun normalizePkgName(name: String): String {
            var n = name
            if (n.startsWith(".external.")) n = n.removePrefix(".external.")
            else if (n.startsWith("_") || n.startsWith(".")) n = n.drop(1)
            if (n.endsWith(":remote")) n = n.removeSuffix(":remote")
            else if (n.endsWith(".overlay")) n = n.removeSuffix(".overlay")
            return n
        }

        /** 简单 glob（*.log / foo?.txt）转正则。 */
        private fun globToRegex(glob: String): Regex {
            val sb = StringBuilder("^")
            for (c in glob) {
                when (c) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append('.')
                    '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
            }
            sb.append('$')
            return Regex(sb.toString())
        }

        private fun md5Hex(f: File): String = try {
            val md = MessageDigest.getInstance("MD5")
            f.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    private val rootMode: Boolean get() = RootDetector.isRootAvailable()
    private val sharedMode: Boolean get() = SandboxSharedStorageAccess.isGranted(context)

    /** 普通用户总有自有目录可维护（应用缓存 + 应用专属外部目录），故本工具集对全体用户披露。 */
    private fun isAvailable(): Boolean = true

    /** 普通用户默认扫描根：有 All-files-access 时用整个 /sdcard，否则退回应用自有目录。 */
    private fun defaultScanRoot(): String {
        if (sharedMode) return "/sdcard"
        val d = context.getExternalFilesDir(null) ?: context.cacheDir
        return d?.absolutePath ?: "/sdcard"
    }

    override fun handles(name: String): Boolean = name in TOOL_NAMES

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!isAvailable()) return emptyList()
        return definitions()
    }

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> {
        if (!isAvailable()) return emptyList()
        return definitions().map { def ->
            ToolDescriptor(
                definition = def,
                riskLevel = riskOf(def.function.name),
                tier = tierOf(def.function.name),
                requiresApproval = requiresApprovalOf(def.function.name),
            )
        }
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        return when (name) {
            "corpse_scan" -> if (rootMode) corpseScan(args)
            else jsonError(name, "corpse_scan needs root (or a Storage-Access-Framework grant for public areas); " +
                "non-root users can use oldfile_*/dupe_*/residual_* instead.")
            "corpse_clean" -> if (rootMode) corpseClean(args) else jsonError(name, "corpse_clean needs root.")
            "residual_scan" -> if (rootMode) residualScan(args) else residualScanNonRoot(args)
            "residual_clean" -> if (rootMode) residualClean(args) else residualCleanNonRoot(args)
            "oldfile_scan" -> if (rootMode) oldfileScan(args) else oldfileScanNonRoot(args)
            "oldfile_clean" -> if (rootMode) oldfileClean(args) else oldfileCleanNonRoot(args)
            "dupe_scan" -> if (rootMode) dupeScan(args) else dupeScanNonRoot(args)
            "dupe_clean" -> if (rootMode) dupeClean(args) else dupeCleanNonRoot(args)
            else -> "Unknown tool: $name"
        }
    }

    // ── 底层执行（root shell / 普通文件遍历） ──

    private fun runRoot(cmd: String, timeoutMs: Int = TIMEOUT_DEFAULT): RootResult =
        com.lxseek.chat.tool.runRoot(cmd, timeoutMs)

    private fun result(name: String, cmd: String, res: RootResult): String =
        rootToolResult(name, cmd, res, MAX_OUTPUT)

    private fun installedPackages(): Set<String> {
        return runRoot("pm list packages 2>/dev/null | sed 's/^package://'", TIMEOUT_DEFAULT)
            .output.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    // ── 尸体残留：CorpseFinder（仅 root） ──

    private fun corpseScan(args: Map<String, JsonElement>): String {
        val limit = arg(args, "limit").ifBlank { "120" }.toIntOrNull()?.coerceIn(1, 500) ?: 120
        val cmd = "LIMIT=$limit\n" + CORPSE_SCAN_SCRIPT
        val res = runRoot(cmd, TIMEOUT_SCAN)
        val items = res.output.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 4) return@mapNotNull null
            CorpseEntry(parts[0], parts[1], parts[2], parts[3].toLongOrNull() ?: 0L)
        }
            .sortedByDescending { it.sizeKb }
            .take(limit)
        return buildJsonObject {
            put("type", "corpse_scan")
            put("mode", "root")
            put("exit_code", res.exitCode)
            put("count", items.size)
            putJsonArray("corpses") {
                items.forEach { c ->
                    buildJsonObject {
                        put("area", c.area)
                        put("name", c.name)
                        put("normalized", c.normalized)
                        put("size_kb", c.sizeKb)
                        put("path", c.path())
                    }.let { add(it) }
                }
            }
            put("hint", "Directories whose names do not match any installed package. " +
                "Confirm with the model before deleting via corpse_clean.")
        }.toString()
    }

    private fun corpseClean(args: Map<String, JsonElement>): String {
        val paths = extractPaths(args)
        if (paths.isEmpty()) {
            return jsonError("corpse_clean", "paths (JSON array of full directory paths from corpse_scan) is required")
        }
        val installed = installedPackages()
        val invalid = paths.filter { p ->
            val path = p.trimEnd('/')
            val underRoot = CORPSE_ROOTS.any { root -> path == root || path.startsWith("$root/") }
            if (!underRoot) return@filter true
            val base = path.substringAfterLast('/')
            if (base.isBlank()) return@filter true
            val norm = normalizePkgName(base)
            if (installed.contains(norm)) return@filter true
            false
        }
        if (invalid.isNotEmpty()) {
            return jsonError(
                "corpse_clean",
                "Rejected paths (must be a leftover dir under " +
                    "${CORPSE_ROOTS.joinToString(", ")} and not belong to an installed package): ${invalid.joinToString(", ")}",
            )
        }
        val quoted = paths.joinToString(" ") { shellQuote(it) }
        val cmd = "before=\$(du -sk $quoted 2>/dev/null | awk '{s+=\$1} END{print s}'); " +
            "rm -rf -- $quoted; echo \"freed_kb=\${before:-0}\""
        val res = runRoot(cmd, TIMEOUT_SCAN)
        return buildJsonObject {
            put("type", "corpse_clean")
            put("mode", "root")
            put("command", cmd)
            put("exit_code", res.exitCode)
            put("output", res.output.take(MAX_OUTPUT))
            put("deleted", paths.size)
        }.toString()
    }

    // ── 可消耗/缓存残留：AppCleaner ──

    private fun residualScan(args: Map<String, JsonElement>): String {
        val pkg = arg(args, "package").ifBlank { "" }
        if (pkg.isNotEmpty() && !validPackage(pkg)) {
            return jsonError("residual_scan", "package must look like a package name (e.g. com.example.app)")
        }
        val cmd = "PKG=$pkg\n" + RESIDUAL_SCAN_SCRIPT
        val res = runRoot(cmd, TIMEOUT_SCAN)
        val items = res.output.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 3) return@mapNotNull null
            ResidualEntry(parts[0], parts[1].toLongOrNull() ?: 0L, parts[2])
        }
            .sortedByDescending { it.totalKb }
        return buildJsonObject {
            put("type", "residual_scan")
            put("mode", "root")
            put("exit_code", res.exitCode)
            put("count", items.size)
            putJsonArray("residuals") {
                items.forEach { r ->
                    buildJsonObject {
                        put("package", r.pkg)
                        put("total_kb", r.totalKb)
                        put("dirs", r.dirs)
                    }.let { add(it) }
                }
            }
            put("hint", "Expendable files (caches, code_cache, thumbnails). These are safe to delete " +
                "for installed apps via residual_clean; apps will regenerate them.")
        }.toString()
    }

    private fun residualClean(args: Map<String, JsonElement>): String {
        val pkg = arg(args, "package")
        val all = boolArg(args, "all")
        if (pkg.isBlank() && !all) {
            return jsonError("residual_clean", "package is required, or set all=true to clean every installed app")
        }
        if (pkg.isNotEmpty() && !validPackage(pkg)) {
            return jsonError("residual_clean", "package must look like a package name")
        }
        if (all) {
            val res = runRoot(
                "PKG=\n" + RESIDUAL_SCAN_SCRIPT +
                    " | while IFS='|' read -r p t d; do [ -z \"\$p\" ] && continue; " +
                    "echo \"\$d\" | tr ';' '\n' | sed 's/^;//;s/=\$//' | while IFS= read -r dir; do " +
                    "[ -n \"\$dir\" ] && rm -rf -- \"\$dir\"; done; done",
                TIMEOUT_SCAN,
            )
            return result("residual_clean", "clean all expendable files", res)
        }
        val res = runRoot(
            "PKG=$pkg\n" + RESIDUAL_SCAN_SCRIPT +
                " | while IFS='|' read -r p t d; do [ -z \"\$d\" ] && continue; " +
                "echo \"\$d\" | tr ';' '\n' | sed 's/^;//;s/=\$//' | while IFS= read -r dir; do " +
                "[ -n \"\$dir\" ] && rm -rf -- \"\$dir\"; done; done",
            TIMEOUT_SCAN,
        )
        return result("residual_clean", "clean expendable files for $pkg", res)
    }

    // ── 旧文件清理：Swiper ──

    private fun oldfileScan(args: Map<String, JsonElement>): String {
        val path = arg(args, "path").ifBlank { "/sdcard" }
        val days = arg(args, "days").toIntOrNull()
        if (days == null || days < 1) {
            return jsonError("oldfile_scan", "days (age threshold) is required and must be >= 1")
        }
        val pattern = arg(args, "pattern").ifBlank { null }
        val limit = arg(args, "limit").ifBlank { "100" }.toIntOrNull()?.coerceIn(1, 300) ?: 100
        val nameArg = pattern?.let { "-name $it" } ?: ""
        val script = """
            find $path -type f -mtime +$days $nameArg -print 2>/dev/null | while IFS= read -r f; do
              [ -f "${'$'}f" ] || continue
              s=$(stat -c %s "${'$'}f" 2>/dev/null); [ -n "${'$'}s" ] || s=0
              echo "${'$'}s|${'$'}f"
            done | sort -rn | head -n $limit
        """.trimIndent()
        val res = runRoot(script, TIMEOUT_SCAN)
        val items = res.output.lines().mapNotNull { line ->
            val i = line.indexOf('|')
            if (i <= 0) return@mapNotNull null
            OldFileEntry(line.substring(0, i).toLongOrNull() ?: 0L, line.substring(i + 1))
        }
        return buildJsonObject {
            put("type", "oldfile_scan")
            put("mode", "root")
            put("exit_code", res.exitCode)
            put("path", path)
            put("days", days)
            put("count", items.size)
            putJsonArray("files") {
                items.forEach { f ->
                    buildJsonObject {
                        put("size_bytes", f.sizeBytes)
                        put("path", f.path)
                    }.let { add(it) }
                }
            }
            put("hint", "Files in $path not modified for over $days day(s). Review before oldfile_clean.")
        }.toString()
    }

    private fun oldfileClean(args: Map<String, JsonElement>): String {
        val path = arg(args, "path").ifBlank { "/sdcard" }
        val days = arg(args, "days").toIntOrNull()
        if (days == null || days < 1) {
            return jsonError("oldfile_clean", "days (age threshold) is required and must be >= 1")
        }
        val pattern = arg(args, "pattern").ifBlank { null }
        val nameArg = pattern?.let { "-name $it" } ?: ""
        val cmd = "find $path -type f -mtime +$days $nameArg -delete 2>/dev/null; echo done"
        return result("oldfile_clean", cmd, runRoot(cmd, TIMEOUT_SCAN))
    }

    // ── 重复文件：Deduplicator ──

    private fun dupeScan(args: Map<String, JsonElement>): String {
        val path = arg(args, "path").ifBlank { "/sdcard" }
        val minSize = arg(args, "min_size").ifBlank { "1048576" }.toLongOrNull()?.coerceAtLeast(1024L) ?: 1048576L
        val limit = arg(args, "limit").ifBlank { "40" }.toIntOrNull()?.coerceIn(1, 200) ?: 40
        val script = """
            find $path -type f -size +${minSize}c -print 2>/dev/null | while IFS= read -r f; do
              [ -f "${'$'}f" ] || continue
              s=$(stat -c %s "${'$'}f" 2>/dev/null); [ -n "${'$'}s" ] || continue
              echo "${'$'}s|${'$'}f"
            done | awk -F'|' '{c[$1]++; lines[$1]=lines[$1] $0 "\n"} END {for(s in c) if(c[s]>1) printf "%s", lines[s]}' \
              | while IFS= read -r line; do
                  [ -z "${'$'}line" ] && continue
                  s="${'$'}{line%%|*}"; f="${'$'}{line#*|}"
                  h=$(md5sum "${'$'}f" 2>/dev/null | awk '{print $1}'); [ -n "${'$'}h" ] || continue
                  echo "${'$'}h|${'$'}s|${'$'}f"
                done | sort | awk -F'|' '{c[$1]++; lines[$1]=lines[$1] $0 "\n"} END {for(h in c) if(c[h]>1) printf "%s", lines[h]}' | head -n ${'$'}(( $limit * 6 ))
        """.trimIndent()
        val res = runRoot(script, TIMEOUT_SCAN)
        val groups = linkedMapOf<String, MutableList<DupeFile>>()
        res.output.lines().forEach { line ->
            val parts = line.split("|")
            if (parts.size < 3) return@forEach
            val group = parts[0]
            val file = DupeFile(parts[2], parts[1].toLongOrNull() ?: 0L)
            groups.getOrPut(group) { mutableListOf() }.add(file)
        }
        return buildJsonObject {
            put("type", "dupe_scan")
            put("mode", "root")
            put("exit_code", res.exitCode)
            put("path", path)
            put("min_size", minSize)
            put("groups", groups.size)
            putJsonArray("duplicates") {
                var shown = 0
                for ((hash, files) in groups) {
                    if (shown >= limit) break
                    shown++
                    add(
                        buildJsonObject {
                            put("md5", hash)
                            put("size_bytes", files.firstOrNull()?.sizeBytes ?: 0L)
                            put("count", files.size)
                            putJsonArray("paths") {
                                files.forEach { add(JsonPrimitive(it.path)) }
                            }
                        },
                    )
                }
            }
            put("hint", "Files sharing the same md5 within a group are exact duplicates. " +
                "Can be slow on large directories; prefer a subdirectory path. " +
                "Use dupe_clean to delete all but the newest copy per group.")
        }.toString()
    }

    private fun dupeClean(args: Map<String, JsonElement>): String {
        val path = arg(args, "path").ifBlank { "/sdcard" }
        val minSize = arg(args, "min_size").ifBlank { "1048576" }.toLongOrNull()?.coerceAtLeast(1024L) ?: 1048576L
        val script = """
            find $path -type f -size +${minSize}c -print 2>/dev/null | while IFS= read -r f; do
              [ -f "${'$'}f" ] || continue
              s=$(stat -c %s "${'$'}f" 2>/dev/null); [ -n "${'$'}s" ] || continue
              m=$(stat -c %Y "${'$'}f" 2>/dev/null); [ -n "${'$'}m" ] || m=0
              echo "${'$'}s|${'$'}m|${'$'}f"
            done | awk -F'|' '{c[$1]++; lines[$1]=lines[$1] $0 "\n"} END {for(s in c) if(c[s]>1) printf "%s", lines[s]}' \
              | while IFS= read -r line; do
                  [ -z "${'$'}line" ] && continue
                  s="${'$'}{line%%|*}"; rest="${'$'}{line#*|}"; m="${'$'}{rest%%|*}"; f="${'$'}{rest#*|}"
                  h=$(md5sum "${'$'}f" 2>/dev/null | awk '{print $1}'); [ -n "${'$'}h" ] || continue
                  echo "${'$'}h|${'$'}s|${'$'}m|${'$'}f"
                done | sort | awk -F'|' '{c[$1]++; lines[$1]=lines[$1] $0 "\n"} END {for(h in c) if(c[h]>1) printf "%s", lines[h]}' \
              | sort -k1,1 -k3,3rn | awk -F'|' '{
                  if ($1 != last) { last=$1; kept=0; first=""; }
                  if (kept == 0) { kept=1; first=$0; next; }
                  print "DELETE " $0;
                }'
        """.trimIndent()
        val res = runRoot(script, TIMEOUT_SCAN)
        val toDelete = res.output.lines().mapNotNull { line ->
            if (!line.startsWith("DELETE ")) return@mapNotNull null
            val parts = line.removePrefix("DELETE ").split("|")
            if (parts.size < 4) return@mapNotNull null
            parts[3]
        }
        if (toDelete.isEmpty()) {
            return buildJsonObject {
                put("type", "dupe_clean")
                put("mode", "root")
                put("exit_code", res.exitCode)
                put("deleted", 0)
                put("output", "No duplicates found (or all groups already unique).")
            }.toString()
        }
        val quoted = toDelete.joinToString(" ") { shellQuote(it) }
        val delCmd = "rm -f -- $quoted; echo deleted_count=${toDelete.size}"
        val delRes = runRoot(delCmd, TIMEOUT_SCAN)
        return buildJsonObject {
            put("type", "dupe_clean")
            put("mode", "root")
            put("command", delCmd)
            put("exit_code", delRes.exitCode)
            put("deleted", toDelete.size)
            put("output", delRes.output.take(MAX_OUTPUT))
            putJsonArray("paths") {
                toDelete.forEach { add(JsonPrimitive(it)) }
            }
        }.toString()
    }

    // ── 非 root 路径：All-files-access 下用 Kotlin 遍历 /sdcard ──

    private suspend fun residualScanNonRoot(args: Map<String, JsonElement>): String = withContext(Dispatchers.IO) {
        // 非 root 只能访问应用自身缓存；其他应用公开缓存需 SAF/root。
        val dirs = listOfNotNull(context.cacheDir, context.externalCacheDir).filter { it.isDirectory }
        val items = dirs.map { d ->
            val size = d.walkTopDown().onFail { _, _ -> }.fold(0L) { acc, f ->
                if (f.isFile) acc + f.length() else acc
            }
            ResidualEntry("com.lxseek.chat (self)", size / 1024, d.absolutePath)
        }
        buildJsonObject {
            put("type", "residual_scan")
            put("mode", "non_root")
            put("count", items.size)
            putJsonArray("residuals") {
                items.forEach { r ->
                    buildJsonObject {
                        put("package", r.pkg)
                        put("total_kb", r.totalKb)
                        put("dirs", r.dirs)
                    }.let { add(it) }
                }
            }
            put("hint", "Non-root mode: only Lxchat's own cache is accessible. Other apps' caches " +
                "require root or a Storage-Access-Framework grant (corpse_* stays root-only).")
        }.toString()
    }

    private suspend fun residualCleanNonRoot(args: Map<String, JsonElement>): String = withContext(Dispatchers.IO) {
        val dirs = listOfNotNull(context.cacheDir, context.externalCacheDir)
        var freed = 0L
        dirs.forEach { d ->
            if (d.isDirectory) {
                d.walkTopDown().onFail { _, _ -> }.forEach { if (it.isFile) freed += it.length() }
                d.deleteRecursively()
            }
        }
        buildJsonObject {
            put("type", "residual_clean")
            put("mode", "non_root")
            put("freed_bytes", freed)
            put("output", "Cleared Lxchat's own cache directories.")
        }.toString()
    }

    private suspend fun oldfileScanNonRoot(args: Map<String, JsonElement>): String = withContext(Dispatchers.IO) {
        val path = arg(args, "path").ifBlank { "/sdcard" }
        val days = arg(args, "days").toIntOrNull()
        if (days == null || days < 1) {
            return@withContext jsonError("oldfile_scan", "days (age threshold) is required and must be >= 1")
        }
        val pattern = arg(args, "pattern").ifBlank { null }
        val limit = arg(args, "limit").ifBlank { "100" }.toIntOrNull()?.coerceIn(1, 300) ?: 100
        val root = File(path)
        if (!root.isDirectory) {
            return@withContext jsonError("oldfile_scan", "path is not an accessible directory: $path")
        }
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val matcher = pattern?.let(::globToRegex)
        val items = mutableListOf<OldFileEntry>()
        root.walkTopDown().onFail { _, _ -> }.forEach { f ->
            if (!f.isFile) return@forEach
            if (f.lastModified() >= cutoff) return@forEach
            if (matcher != null && !matcher.matches(f.name)) return@forEach
            items.add(OldFileEntry(f.length(), f.absolutePath))
        }
        items.sortByDescending { it.sizeBytes }
        val top = items.take(limit)
        buildJsonObject {
            put("type", "oldfile_scan")
            put("mode", "non_root")
            put("path", path)
            put("days", days)
            put("count", top.size)
            putJsonArray("files") {
                top.forEach { f ->
                    buildJsonObject {
                        put("size_bytes", f.sizeBytes)
                        put("path", f.path)
                    }.let { add(it) }
                }
            }
            put("hint", "Files in $path not modified for over $days day(s). Review before oldfile_clean.")
        }.toString()
    }

    private suspend fun oldfileCleanNonRoot(args: Map<String, JsonElement>): String = withContext(Dispatchers.IO) {
        val path = arg(args, "path").ifBlank { "/sdcard" }
        val days = arg(args, "days").toIntOrNull()
        if (days == null || days < 1) {
            return@withContext jsonError("oldfile_clean", "days (age threshold) is required and must be >= 1")
        }
        val pattern = arg(args, "pattern").ifBlank { null }
        val root = File(path)
        if (!root.isDirectory) {
            return@withContext jsonError("oldfile_clean", "path is not an accessible directory: $path")
        }
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val matcher = pattern?.let(::globToRegex)
        var deleted = 0
        var freed = 0L
        root.walkTopDown().onFail { _, _ -> }.forEach { f ->
            if (!f.isFile) return@forEach
            if (f.lastModified() >= cutoff) return@forEach
            if (matcher != null && !matcher.matches(f.name)) return@forEach
            freed += f.length()
            if (f.delete()) deleted++
        }
        buildJsonObject {
            put("type", "oldfile_clean")
            put("mode", "non_root")
            put("path", path)
            put("days", days)
            put("deleted", deleted)
            put("freed_bytes", freed)
        }.toString()
    }

    private suspend fun dupeScanNonRoot(args: Map<String, JsonElement>): String = withContext(Dispatchers.IO) {
        val path = arg(args, "path").ifBlank { "/sdcard" }
        val minSize = arg(args, "min_size").ifBlank { "1048576" }.toLongOrNull()?.coerceAtLeast(1024L) ?: 1048576L
        val limit = arg(args, "limit").ifBlank { "40" }.toIntOrNull()?.coerceIn(1, 200) ?: 40
        val root = File(path)
        if (!root.isDirectory) {
            return@withContext jsonError("dupe_scan", "path is not an accessible directory: $path")
        }
        val bySize = HashMap<Long, MutableList<File>>()
        root.walkTopDown().onFail { _, _ -> }.forEach { f ->
            if (!f.isFile) return@forEach
            val len = f.length()
            if (len < minSize) return@forEach
            bySize.getOrPut(len) { mutableListOf() }.add(f)
        }
        val byMd5 = LinkedHashMap<String, MutableList<DupeFile>>()
        bySize.values.asSequence().filter { it.size > 1 }.forEach { group ->
            group.forEach { f ->
                val h = md5Hex(f)
                if (h.isNotEmpty()) byMd5.getOrPut(h) { mutableListOf() }.add(DupeFile(f.absolutePath, f.length()))
            }
        }
        buildJsonObject {
            put("type", "dupe_scan")
            put("mode", "non_root")
            put("path", path)
            put("min_size", minSize)
            put("groups", byMd5.values.count { it.size > 1 })
            putJsonArray("duplicates") {
                byMd5.entries.asSequence().filter { it.value.size > 1 }.take(limit).forEach { (hash, files) ->
                    buildJsonObject {
                        put("md5", hash)
                        put("size_bytes", files.first().sizeBytes)
                        put("count", files.size)
                        putJsonArray("paths") {
                            files.forEach { add(JsonPrimitive(it.path)) }
                        }
                    }.let { add(it) }
                }
            }
            put("hint", "Files sharing the same md5 within a group are exact duplicates. " +
                "Can be slow on large directories; prefer a subdirectory path. " +
                "Use dupe_clean to delete all but the newest copy per group.")
        }.toString()
    }

    private suspend fun dupeCleanNonRoot(args: Map<String, JsonElement>): String = withContext(Dispatchers.IO) {
        val path = arg(args, "path").ifBlank { defaultScanRoot() }
        val minSize = arg(args, "min_size").ifBlank { "1048576" }.toLongOrNull()?.coerceAtLeast(1024L) ?: 1048576L
        val root = File(path)
        if (!root.isDirectory) {
            return@withContext jsonError("dupe_clean", "path is not an accessible directory: $path")
        }
        val bySize = HashMap<Long, MutableList<File>>()
        root.walkTopDown().onFail { _, _ -> }.forEach { f ->
            if (!f.isFile) return@forEach
            val len = f.length()
            if (len < minSize) return@forEach
            bySize.getOrPut(len) { mutableListOf() }.add(f)
        }
        val byMd5 = HashMap<String, MutableList<File>>()
        bySize.values.filter { it.size > 1 }.forEach { group ->
            group.forEach { f ->
                val h = md5Hex(f)
                if (h.isNotEmpty()) byMd5.getOrPut(h) { mutableListOf() }.add(f)
            }
        }
        var deleted = 0
        var freed = 0L
        val deletedPaths = mutableListOf<String>()
        byMd5.values.filter { it.size > 1 }.forEach { group ->
            val newest = group.maxByOrNull { it.lastModified() } ?: return@forEach
            group.filter { it.absolutePath != newest.absolutePath }.forEach { f ->
                freed += f.length()
                if (f.delete()) {
                    deleted++
                    deletedPaths.add(f.absolutePath)
                }
            }
        }
        buildJsonObject {
            put("type", "dupe_clean")
            put("mode", "non_root")
            put("path", path)
            put("deleted", deleted)
            put("freed_bytes", freed)
            putJsonArray("paths") {
                deletedPaths.forEach { add(JsonPrimitive(it)) }
            }
        }.toString()
    }

    // ── 数据模型 ──

    private data class CorpseEntry(val area: String, val name: String, val normalized: String, val sizeKb: Long) {
        fun path(): String = when (area) {
            "private" -> "/data/data/$name"
            "public" -> "/sdcard/Android/data/$name"
            "media" -> "/sdcard/Android/media/$name"
            else -> "/sdcard/Android/obb/$name"
        }
    }

    private data class ResidualEntry(val pkg: String, val totalKb: Long, val dirs: String)
    private data class OldFileEntry(val sizeBytes: Long, val path: String)
    private data class DupeFile(val path: String, val sizeBytes: Long)

    private fun extractPaths(args: Map<String, JsonElement>): List<String> {
        val arr = (args["paths"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
        if (!arr.isNullOrEmpty()) return arr
        val single = arg(args, "path").trim()
        return if (single.isNotEmpty()) listOf(single) else emptyList()
    }

    // ── 工具定义与风险分级 ──

    private fun definitions(): List<ToolDefinition> {
        val out = mutableListOf<ToolDefinition>()
        if (rootMode) out += CORPSE_DEFS
        if (isAvailable()) out += SHARED_DEFS
        return out.distinctBy { it.function.name }
    }

    private val SHARED_DEFS: List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = "residual_scan",
                description = "Scan expendable files (caches, code_cache, thumbnails). With root: scans " +
                    "every installed app and optionally one package; without root: reports Lxchat's own cache. " +
                    "Read-only.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package" to ToolProperty("string", "Optional package to restrict the scan to (root only)"),
                    ),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "residual_clean",
                description = "Delete expendable files (caches, code_cache, thumbnails). With root: of one " +
                    "package, or of every installed app when all=true. Without root: only Lxchat's own cache. " +
                    "Apps regenerate caches; data (login, settings, chat history) is NOT touched.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package" to ToolProperty("string", "Package to clean, e.g. com.example.app (root only)"),
                        "all" to ToolProperty("boolean", "true to clean every installed app (root only)"),
                    ),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "oldfile_scan",
                description = "Scan for files not modified for at least days days under path " +
                    "(default: Lxchat's own dirs, or /sdcard with the 'All files access' permission), " +
                    "optionally filtered by pattern (e.g. *.log). Returns the " +
                    "largest old files first, capped by limit (default 100). Read-only.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Directory to scan, default /sdcard"),
                        "days" to ToolProperty("string", "Age threshold in days, e.g. 90"),
                        "pattern" to ToolProperty("string", "Optional filename pattern, e.g. *.log"),
                        "limit" to ToolProperty("string", "Max files to return, default 100"),
                    ),
                    required = listOf("days"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "oldfile_clean",
                description = "Delete files not modified for at least days days under path " +
                    "(default /sdcard), optionally filtered by pattern. Without root this requires " +
                    "the 'All files access' permission. Irreversible; run oldfile_scan first and confirm.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Directory to clean, default /sdcard"),
                        "days" to ToolProperty("string", "Age threshold in days, e.g. 90"),
                        "pattern" to ToolProperty("string", "Optional filename pattern, e.g. *.log"),
                    ),
                    required = listOf("days"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "dupe_scan",
                description = "Scan for exact duplicate files under path (default /sdcard): groups " +
                    "files by size, then verifies with md5. min_size is the byte threshold (default " +
                    "1048576 = 1MB). Can be slow on large directories; prefer a subdirectory. Without " +
                    "root this requires the 'All files access' permission. Read-only.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Directory to scan, default /sdcard"),
                        "min_size" to ToolProperty("string", "Min file size in bytes, default 1048576"),
                        "limit" to ToolProperty("string", "Max duplicate groups to return, default 40"),
                    ),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "dupe_clean",
                description = "Delete duplicate files under path keeping the newest copy of each " +
                    "duplicate group (same md5). min_size is the byte threshold (default 1048576). " +
                    "Default path is Lxchat's own dirs, or /sdcard with the 'All files access' permission. " +
                    "Irreversible; run dupe_scan first to preview the groups.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Directory to scan, default /sdcard"),
                        "min_size" to ToolProperty("string", "Min file size in bytes, default 1048576"),
                    ),
                ),
            ),
        ),
    )

    private val CORPSE_DEFS: List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = "corpse_scan",
                description = "Scan for leftover data of uninstalled apps (corpses) in /data/data, " +
                    "/sdcard/Android/data, /sdcard/Android/media and /sdcard/Android/obb. " +
                    "Directories whose (normalized) name matches no installed package are reported " +
                    "with size. Requires root. Read-only. limit caps how many are returned (default 120).",
                parameters = ToolParameters(
                    properties = mapOf(
                        "limit" to ToolProperty("string", "Max corpses to return, default 120"),
                    ),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "corpse_clean",
                description = "Delete leftover data of uninstalled apps. paths is a JSON array of " +
                    "directory paths from corpse_scan. Each path is validated to sit under an allowed " +
                    "data area and to not belong to an installed package before deletion. Requires root. " +
                    "Irreversible.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "paths" to ToolProperty("string", "JSON array of full directory paths to delete"),
                    ),
                    required = listOf("paths"),
                ),
            ),
        ),
    )

    private fun riskOf(name: String): RiskLevel = when (name) {
        "corpse_scan", "residual_scan", "oldfile_scan", "dupe_scan" -> RiskLevel.ReadOnly
        "residual_clean" -> RiskLevel.HighRisk
        else -> RiskLevel.Destructive
    }

    private fun tierOf(name: String): ToolTier = when (name) {
        "corpse_scan", "residual_scan", "oldfile_scan", "dupe_scan" -> ToolTier.Extended
        else -> ToolTier.Dangerous
    }

    private fun requiresApprovalOf(name: String): Boolean = name !in setOf(
        "corpse_scan",
        "residual_scan",
        "oldfile_scan",
        "dupe_scan",
    )
}
