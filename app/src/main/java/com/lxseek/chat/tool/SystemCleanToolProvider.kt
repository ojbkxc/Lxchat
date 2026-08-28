package com.lxseek.chat.tool

import com.lxseek.chat.adb.RootDetector
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.TimeUnit

/**
 * 系统清理/优化工具集（root-only）。
 *
 * 由 root 级清理模块提供底层能力：二进制安装在 /system/bin，配置目录为
 * /data/adb/wipe_cache（含清理规则 CleanConfigs、深度清理 FileConfigs、
 * 定时任务 TimedConfigs、设置 settings.prop）。模块 CLI 强制要求 UID 0
 * （getuid()==0），因此 Shizuku（shell uid 2000）无法执行，本工具集仅在
 * root 可用且二进制存在时披露给模型。工具命名统一使用 system_clean_* 语义，
 * 不暴露底层模块品牌名。
 */
class SystemCleanToolProvider : ToolProvider {

    // 底层模块内部路径与命令（实现细节，不直接暴露给模型）。
    private companion object {
        const val BIN_PATH = "/system/bin/ClearBox"
        const val MODULE_DIR = "/data/adb/modules/wipe_cache"
        const val WORK_DIR = "/data/adb/wipe_cache"
        const val SETTINGS_FILE = "$WORK_DIR/settings.prop"
        const val TIMED_DIR = "$WORK_DIR/TimedConfigs"
        const val MAX_OUTPUT = 6000
        const val TIMEOUT_CLEAN = 295000
        const val TIMEOUT_DEFAULT = 60000

        val TOOL_NAMES = setOf(
            "system_clean_status",
            "system_clean_ncdu",
            "system_clean_all",
            "system_clean_app_cache",
            "system_clean_system_cache",
            "system_clean_storage",
            "system_clean_app_dir",
            "system_clean_file_sort",
            "system_clean_disk_gc",
            "system_clean_dexoat",
            "system_clean_block_install",
            "system_clean_storage_lock",
            "system_clean_timed_task",
        )
    }

    private data class RootResult(val exitCode: Int, val output: String)

    private var binaryAvailable: Boolean? = null

    private fun isAvailable(): Boolean {
        if (!RootDetector.isRootAvailable()) return false
        binaryAvailable?.let { return it }
        val ok = runRoot("test -x $BIN_PATH && echo OK").output.contains("OK")
        binaryAvailable = ok
        return ok
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
        if (!isAvailable()) {
            return jsonError(
                name,
                "System cleanup module is unavailable (no root or binary not found).",
            )
        }
        return when (name) {
            "system_clean_status" -> status()
            "system_clean_ncdu" -> ncdu(arguments)
            "system_clean_all" -> runSimple(name, "$BIN_PATH --clear-all", TIMEOUT_CLEAN)
            "system_clean_app_cache" -> runSimple(name, "$BIN_PATH --clear-app-cache", TIMEOUT_CLEAN)
            "system_clean_system_cache" -> runSimple(name, "$BIN_PATH --clear-system-cache", TIMEOUT_CLEAN)
            "system_clean_storage" -> runSimple(name, "$BIN_PATH --clear-storage", TIMEOUT_CLEAN)
            "system_clean_app_dir" -> runAppDir(arguments)
            "system_clean_file_sort" -> runSimple(name, "$BIN_PATH --file-sort", TIMEOUT_CLEAN)
            "system_clean_disk_gc" -> runDiskGc(arguments)
            "system_clean_dexoat" -> runDexoat(arguments)
            "system_clean_block_install" -> runToggle(
                name, arguments,
                enableCmd = "$BIN_PATH --app-allow-install STOP",
                disableCmd = "$BIN_PATH --app-allow-install RESET",
            )
            "system_clean_storage_lock" -> runToggle(
                name, arguments,
                enableCmd = "$BIN_PATH --storage-lock STOP",
                disableCmd = "$BIN_PATH --storage-lock RESET",
            )
            "system_clean_timed_task" -> runTimedTask(arguments)
            else -> "Unknown tool: $name"
        }
    }

    // ── 底层执行 ──

    private fun runRoot(cmd: String, timeoutMs: Int = TIMEOUT_DEFAULT): RootResult {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val waitOk = p.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            val output = (p.inputStream.bufferedReader().use { it.readText() } +
                p.errorStream.bufferedReader().use { it.readText() }).trim()
            val exitCode = if (waitOk) p.exitValue() else -1
            if (!waitOk) p.destroy()
            RootResult(exitCode, output)
        } catch (e: Exception) {
            RootResult(-1, "error: ${e.message}")
        }
    }

    private fun result(name: String, cmd: String, res: RootResult): String {
        return buildJsonObject {
            put("type", name)
            put("command", cmd)
            put("exit_code", res.exitCode)
            put("output", res.output.take(MAX_OUTPUT))
        }.toString()
    }

    // ── 只读工具 ──

    private fun status(): String {
        val moduleProp = runRoot("cat $MODULE_DIR/module.prop 2>/dev/null").output
        val settings = runRoot("cat $SETTINGS_FILE 2>/dev/null").output
        val version = Regex("(?m)^version=(.+)$").find(moduleProp)?.groupValues?.get(1)?.trim()
        val activeSwitches = buildList {
            if (settings.contains("clearbox_stop_install=1")) add("install_blocked")
            if (settings.contains("clearbox_stop_storage=1")) add("storage_locked")
            if (settings.contains("clearbox_stop_app_cache=1")) add("cache_blocked")
        }
        return buildJsonObject {
            put("type", "system_clean_status")
            put("available", true)
            put("work_dir", WORK_DIR)
            if (version != null) put("version", version)
            putJsonArray("active_switches") { activeSwitches.forEach { add(JsonPrimitive(it)) } }
        }.toString()
    }

    private fun ncdu(arguments: String): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path").ifBlank { "/sdcard" }
        val cmd = "$BIN_PATH --ncdu \"$path\""
        return result("system_clean_ncdu", cmd, runRoot(cmd, TIMEOUT_CLEAN))
    }

    // ── 清理/优化工具 ──

    private fun runSimple(name: String, cmd: String, timeoutMs: Int): String {
        return result(name, cmd, runRoot(cmd, timeoutMs))
    }

    private fun runAppDir(arguments: String): String {
        val args = parseToolArgs(arguments)
        val pkg = arg(args, "package")
        if (pkg.isBlank()) {
            return jsonError("system_clean_app_dir", "package is required (e.g. com.example.app)")
        }
        val cmd = "$BIN_PATH --clear-app-cust \"$pkg\""
        return result("system_clean_app_dir", cmd, runRoot(cmd, TIMEOUT_CLEAN))
    }

    private fun runDiskGc(arguments: String): String {
        val args = parseToolArgs(arguments)
        val mode = arg(args, "mode").ifBlank { "fast" }.lowercase()
        val cmd = when (mode) {
            "f2fs" -> "$BIN_PATH --disk-f2fs-gc"
            else -> "$BIN_PATH --disk-gc"
        }
        return result("system_clean_disk_gc", cmd, runRoot(cmd, TIMEOUT_CLEAN))
    }

    private fun runDexoat(arguments: String): String {
        val args = parseToolArgs(arguments)
        val mode = arg(args, "mode").ifBlank {
            return jsonError("system_clean_dexoat", "mode is required (system, speed, speed-profile, everything, reset)")
        }.lowercase()
        val cmd = when (mode) {
            "system" -> "$BIN_PATH --dexoat-system"
            "reset" -> "$BIN_PATH --dexoat-reset"
            else -> "$BIN_PATH --dexoat-custom \"$mode\""
        }
        return result("system_clean_dexoat", cmd, runRoot(cmd, TIMEOUT_CLEAN))
    }

    private fun runToggle(name: String, arguments: String, enableCmd: String, disableCmd: String): String {
        val args = parseToolArgs(arguments)
        val cmd = if (boolArg(args, "enable")) enableCmd else disableCmd
        return result(name, cmd, runRoot(cmd, TIMEOUT_DEFAULT))
    }

    // ── 系统级定时任务（模块 Timed 守护进程读取 TimedConfigs/*.conf，Lxchat 关闭后仍生效）──

    private fun runTimedTask(arguments: String): String {
        val args = parseToolArgs(arguments)
        val action = arg(args, "action").ifBlank { "list" }.lowercase()
        return when (action) {
            "list" -> timedTaskList()
            "create" -> timedTaskCreate(args)
            "delete" -> timedTaskDelete(args)
            else -> jsonError("system_clean_timed_task", "action must be list, create or delete")
        }
    }

    private fun timedTaskList(): String {
        val cmd = "for f in $TIMED_DIR/*.conf; do [ -f \"\$f\" ] || continue; " +
            "echo \"== \$(basename \"\$f\") ==\"; cat \"\$f\"; done"
        return result("system_clean_timed_task", "list $TIMED_DIR", runRoot(cmd, TIMEOUT_DEFAULT))
    }

    private fun timedTaskCreate(args: Map<String, JsonElement>): String {
        val name = arg(args, "name")
        val time = arg(args, "time")
        val run = arg(args, "run")
        if (name.isBlank() || time.isBlank() || run.isBlank()) {
            return jsonError(
                "system_clean_timed_task",
                "create requires name, time (interval like 1/D, 6/H, 30/M) and run (shell command)",
            )
        }
        if (!Regex("[\\w-]+").matches(name)) {
            return jsonError("system_clean_timed_task", "name may only contain letters, digits, '-' and '_'")
        }
        val window = arg(args, "window").ifBlank { null }
        val notification = arg(args, "notification").ifBlank { null }
        val conf = buildString {
            append("time=").append(time).append('\n')
            append("date=0").append('\n')
            append("run=").append(run).append('\n')
            window?.let { append("in=").append(it).append('\n') }
            notification?.let { append("post=").append(it).append('\n') }
        }
        val path = "$TIMED_DIR/$name.conf"
        val cmd = "mkdir -p $TIMED_DIR && cat > \"$path\" <<'LXCONF_EOF'\n${conf}LXCONF_EOF"
        return result("system_clean_timed_task", "create $path", runRoot(cmd, TIMEOUT_DEFAULT))
    }

    private fun timedTaskDelete(args: Map<String, JsonElement>): String {
        val name = arg(args, "name")
        if (name.isBlank()) {
            return jsonError("system_clean_timed_task", "delete requires name")
        }
        if (!Regex("[\\w-]+").matches(name)) {
            return jsonError("system_clean_timed_task", "name may only contain letters, digits, '-' and '_'")
        }
        val path = "$TIMED_DIR/$name.conf"
        return result("system_clean_timed_task", "delete $path", runRoot("rm -f \"$path\"", TIMEOUT_DEFAULT))
    }

    // ── 工具定义与风险分级 ──

    private fun definitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = "system_clean_status",
                description = "Check whether the root system-cleanup module is installed; report module version, " +
                    "working directory and currently active switches (install blocking, storage lock, cache blocking).",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "system_clean_ncdu",
                description = "Analyze storage usage under a directory and list which subfolders consume the most " +
                    "space. Use before cleaning to locate the space hog. Read-only.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Directory to scan (default /sdcard)"),
                    ),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "system_clean_all",
                description = "Deep one-click cleanup: clear app caches, system caches, junk files and empty folders, " +
                    "apply custom rules, run a fast disk GC, then classify files. Run when the user asks to clean the " +
                    "device or reports low storage. May take a few minutes.",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "system_clean_app_cache",
                description = "Clear the caches of all third-party apps (system apps are not touched).",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "system_clean_system_cache",
                description = "Clear system app caches, system cache and MTP host data. Can fix an abnormal MTP file list.",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "system_clean_storage",
                description = "Clean junk files, empty folders and multimedia caches on internal/external storage.",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "system_clean_app_dir",
                description = "Clear the private data directory of a single app according to the module's configured " +
                    "rules for that package.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package" to ToolProperty("string", "Android package name, e.g. com.example.app"),
                    ),
                    required = listOf("package"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "system_clean_file_sort",
                description = "One-click classify and organize files on internal/external storage by type. " +
                    "High resource usage, may take a while.",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "system_clean_disk_gc",
                description = "Run disk optimization. fast = quick GC (default); f2fs = urgent F2FS GC for a nearly " +
                    "full filesystem. Emergency maintenance, not needed frequently.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "mode" to ToolProperty("string", "fast or f2fs"),
                    ),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "system_clean_dexoat",
                description = "Pre-compile apps (Dexoat) to speed up cold launch at the cost of some storage. " +
                    "mode: system (system default), speed / speed-profile / everything (custom modes), or reset to restore.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "mode" to ToolProperty("string", "system, speed, speed-profile, everything or reset"),
                    ),
                    required = listOf("mode"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "system_clean_block_install",
                description = "Block or allow all app installations at the filesystem level. " +
                    "enable=true blocks new installs, enable=false allows them again.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "enable" to ToolProperty("boolean", "true to block all installs, false to allow"),
                    ),
                    required = listOf("enable"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "system_clean_storage_lock",
                description = "Lock or unlock the internal storage root layout to stop apps randomly creating " +
                    "files/folders at the top level. enable=true locks, false unlocks.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "enable" to ToolProperty("boolean", "true to lock, false to unlock"),
                    ),
                    required = listOf("enable"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "system_clean_timed_task",
                description = "Manage system-level scheduled tasks that keep running even when the app is closed " +
                    "(read from the module's TimedConfigs directory by its daemon). action: list (show all tasks), " +
                    "create (new task: name + time interval like 1/D, 6/H, 30/M + run shell command, optional window " +
                    "and notification 'Title/Message'), delete (remove by name).",
                parameters = ToolParameters(
                    properties = mapOf(
                        "action" to ToolProperty("string", "list, create or delete"),
                        "name" to ToolProperty("string", "Task file name without .conf (required for create/delete)"),
                        "time" to ToolProperty("string", "Interval for create, e.g. 1/D, 6/H, 30/M"),
                        "run" to ToolProperty("string", "Shell command to execute for create"),
                        "window" to ToolProperty("string", "Optional active window like 0/5"),
                        "notification" to ToolProperty("string", "Optional notification 'Title/Message'"),
                    ),
                    required = listOf("action"),
                ),
            ),
        ),
    )

    private fun riskOf(name: String): RiskLevel = when (name) {
        "system_clean_status", "system_clean_ncdu" -> RiskLevel.ReadOnly
        else -> RiskLevel.HighRisk
    }

    private fun tierOf(name: String): ToolTier = when (name) {
        "system_clean_status", "system_clean_ncdu" -> ToolTier.Extended
        else -> ToolTier.Dangerous
    }

    private fun requiresApprovalOf(name: String): Boolean = name !in setOf(
        "system_clean_status",
        "system_clean_ncdu",
    )
}
