package com.lxseek.chat.runtime

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.tool.RiskLevel
import com.lxseek.chat.tool.ToolDescriptor
import com.lxseek.chat.tool.ToolProvider
import com.lxseek.chat.tool.ToolTier
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * 运行时引擎的 AI 工具提供者。所有工具名称见 [NAMES]；工具分级：
 * - [ToolTier.Core]（只读状态）：market_status / runtime_status；
 * - [ToolTier.Extended]：runtime_exec / novel_inkos（创作、脚本执行）；
 * - [ToolTier.Dangerous]（危险操作，用户审批）：market_install / market_uninstall /
 *   runtime_start / runtime_stop。
 *
 * 下载、启停均为危险操作，必须走 Dangerous 级用户确认；创作与脚本执行相对安全但属扩展能力。
 */
class RuntimeToolProvider(
    private val manager: RuntimeEngineManager,
) : ToolProvider {

    private val json = Json { ignoreUnknownKeys = true }

    object Names {
        const val INSTALL = "market_install"
        const val UNINSTALL = "market_uninstall"
        const val STATUS = "market_status"
        const val START = "runtime_start"
        const val STOP = "runtime_stop"
        const val RUNTIME_STATUS = "runtime_status"
        const val EXEC = "runtime_exec"
        const val NOVEL_INKOS = "novel_inkos"
        const val WEB_NOVEL = "webnovel"
        const val ALL = "all_runtimes_status"
    }

    // ── ToolProvider ─────────────────────────────────────────

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> = descriptors

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        descriptors.map { it.definition }

    override fun handles(name: String): Boolean = name in NAMES

    override fun riskLevel(name: String): RiskLevel = when (name) {
        Names.STATUS, Names.RUNTIME_STATUS, Names.ALL -> RiskLevel.ReadOnly
        Names.EXEC, Names.NOVEL_INKOS, Names.WEB_NOVEL -> RiskLevel.Moderate
        else -> RiskLevel.HighRisk
    }

    override fun requiresApprovalByDefault(name: String): Boolean =
        name in setOf(Names.INSTALL, Names.UNINSTALL, Names.START, Names.STOP)

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String {
        val args = parseArgs(arguments)
        return try {
            when (name) {
                Names.INSTALL -> install(args)
                Names.UNINSTALL -> uninstall(args)
                Names.STATUS -> marketStatus(args)
                Names.START -> runtimeStart(args)
                Names.STOP -> runtimeStop(args)
                Names.RUNTIME_STATUS -> runtimeStatus(args)
                Names.EXEC -> runtimeExec(args)
                Names.NOVEL_INKOS -> novelInkos(args)
                Names.WEB_NOVEL -> webNovel(args)
                Names.ALL -> allStatus()
                else -> error(name, "unknown_tool", "未知工具：$name")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error(args["engine_id"] ?: "runtime", "error", e.message ?: "执行失败")
        }
    }

    // ── 工具实现 ────────────────────────────────────────────

    /** 查市场 → 按 id 解析 meta → 下载安装（可指定 version，缺省按约束自动匹配）。Dangerous。 */
    private suspend fun install(args: Map<String, String>): String {
        val engineId = requiredEngineId(args) ?: return errorJson("missing_engine_id", "缺少 engine_id")
        val meta = manager.resolveCatalogMeta(engineId)
            ?: return error(engineId, "engine_not_found", "市场目录中不存在引擎「$engineId」，可用 all_runtimes_status 查看")
        val version = args["version"]?.takeIf(String::isNotBlank)
        return try {
            // runtime-python / runtime-node / runtime-ffmpeg are provided by the sandbox
            // (proot + Alpine rootfs). Install the corresponding apk package instead of
            // going through the market installation pipeline (glibc ld-linux direct load
            // is unreliable on Android).
            val sandboxPackage = sandboxPackageName(engineId)
            if (sandboxPackage != null) {
                val sandbox = manager.sandbox ?: throw IllegalStateException("Sandbox 不可用，请先安装 Linux 沙箱")
                if (!sandbox.isAvailable()) {
                    sandbox.install()
                    if (!sandbox.isAvailable()) throw IllegalStateException("Sandbox 安装失败")
                }
                // Check the apk install result — apkInstall returns false on failure (e.g.
                // network error, package not found in repo). Without this check the tool
                // would report success even when the package was never actually installed.
                val installed = sandbox.apkInstall(sandboxPackage)
                if (!installed) throw IllegalStateException("沙箱安装 $sandboxPackage 失败")
                return buildJsonObject {
                    put("ok", true)
                    put("engine_id", engineId)
                    put("action", "market_install")
                    put("message", "$sandboxPackage 已通过沙箱安装")
                }.toString()
            }
            val market = manager.market ?: throw IllegalStateException("市场服务不可用")
            market.installRuntimeInternal(meta, version)
            buildJsonObject {
                put("ok", true)
                put("engine_id", engineId)
                put("action", "market_install")
                put("message", "引擎「$engineId」安装完成${version?.let { "（版本 $it）" } ?: "（已自动匹配版本）"}")
            }.toString()
        } catch (e: Exception) {
            error(engineId, "install_failed", e.message ?: "安装失败")
        }
    }

    /** 若对应进程在运行先 stop 再卸载。Dangerous。 */
    private suspend fun uninstall(args: Map<String, String>): String {
        val engineId = requiredEngineId(args) ?: return errorJson("missing_engine_id", "缺少 engine_id")
        return try {
            val market = manager.market ?: throw IllegalStateException("市场服务不可用")
            market.uninstall(engineId)
            buildJsonObject {
                put("ok", true)
                put("engine_id", engineId)
                put("action", "market_uninstall")
                put("message", "引擎「$engineId」已卸载")
            }.toString()
        } catch (e: Exception) {
            error(engineId, "uninstall_failed", e.message ?: "卸载失败")
        }
    }

    /** 是否已装 / 版本 / 是否运行中 / 安装状态。Core。 */
    private fun marketStatus(args: Map<String, String>): String {
        val engineId = requiredEngineId(args) ?: return errorJson("missing_engine_id", "缺少 engine_id")
        val st = manager.status(engineId)
        return statusJson(st)
    }

    /** 未安装则报错提示先 market_install；已运行则幂等（不重复启动）。Dangerous。 */
    private suspend fun runtimeStart(args: Map<String, String>): String {
        val engineId = requiredEngineId(args) ?: return errorJson("missing_engine_id", "缺少 engine_id")
        return try {
            val env = manager.ensureStarted(engineId, args["version"]?.takeIf(String::isNotBlank), null)
            buildJsonObject {
                put("ok", true)
                put("engine_id", engineId)
                put("action", "runtime_start")
                put("running", true)
                put("env", env.keys.sorted().joinToString(","))
            }.toString()
        } catch (e: Exception) {
            error(engineId, "start_failed", e.message ?: "启动失败")
        }
    }

    /** 停止引擎。Dangerous。 */
    private fun runtimeStop(args: Map<String, String>): String {
        val engineId = requiredEngineId(args) ?: return errorJson("missing_engine_id", "缺少 engine_id")
        val stopped = manager.stop(engineId)
        return buildJsonObject {
            put("ok", stopped)
            put("engine_id", engineId)
            put("action", "runtime_stop")
            put("running", manager.isRunning(engineId))
        }.toString()
    }

    /** 单引擎状态。Core。 */
    private fun runtimeStatus(args: Map<String, String>): String {
        val engineId = requiredEngineId(args) ?: return errorJson("missing_engine_id", "缺少 engine_id")
        return statusJson(manager.status(engineId))
    }

    /** 全部已知引擎状态概览。Core。 */
    private fun allStatus(): String {
        val known = listOf(
            RuntimeEngineType.NODE_INKOS,
            RuntimeEngineType.PYTHON_WEB_NOVEL,
            "runtime-python",
            "runtime-ffmpeg",
        )
        return buildJsonObject {
            put("ok", true)
            putJsonArray("runtimes") {
                known.forEach { id ->
                    add(buildJsonObject {
                        put("engine_id", id)
                        putAllJsonObject(statusJsonObject(manager.status(id)))
                    })
                }
            }
        }.toString()
    }

    /**
     * 在指定引擎进程内执行一次性命令（argv 为参数数组），适用于 python 脚本 / ffmpeg 转码等。
     * Extended。示例：python → {"engine_id":"runtime-python","argv":["-c","print(1+2)"]}；
     * ffmpeg → {"engine_id":"runtime-ffmpeg","argv":["-i","in.mp4","out.mp3"]}。
     */
    private suspend fun runtimeExec(args: Map<String, String>): String {
        val engineId = requiredEngineId(args) ?: return errorJson("missing_engine_id", "缺少 engine_id")
        val argv = parseArgv(args["argv"])
        if (argv.isEmpty()) return error(engineId, "missing_argv", "缺少 argv（参数数组）")
        val timeoutMs = (args["timeout_ms"]?.toLongOrNull() ?: 60_000L).coerceIn(1_000, 300_000)
        return try {
            // runtime-python / runtime-node / runtime-ffmpeg / webnovel go through the
            // sandbox (proot + Alpine rootfs). webnovel still uses python3 inside the
            // sandbox; node/ffmpeg use their own binaries. Other engines (e.g. inkos via
            // NODE_INKOS) keep the resident-process path.
            val sandboxBinary = sandboxBinaryName(engineId)
            if (sandboxBinary != null) {
                execInSandbox(engineId, sandboxBinary, argv, timeoutMs)
            } else {
                // Other engines (e.g. inkos via NODE_INKOS) keep the resident-process path.
                val envMap = manager.ensureStarted(engineId, null, null)
                // ensureStarted guarantees the engine is installed, but a concurrent uninstall
                // could race us between ensureStarted and installationOf — fail with a clear
                // error instead of building a bogus path with an empty version string.
                val installation = manager.installationOf(engineId)
                    ?: return error(engineId, "not_installed", "引擎「$engineId」未安装或已被卸载")
                val root = manager.packageManager.versionRoot(engineId, installation.version)
                val binary = File(root, binaryName(engineId)).absolutePath
                val result = runProcessOnce(listOf(binary) + argv, envMap, root, timeoutMs)
                buildJsonObject {
                    put("ok", result.isSuccess)
                    put("engine_id", engineId)
                    put("exit_code", result.exitCode)
                    put("timed_out", result.timedOut)
                    if (result.output.isNotBlank()) put("output", result.output)
                    if (!result.isSuccess) put("message", "退出码 ${result.exitCode}${if (result.timedOut) "，超时" else ""}")
                }.toString()
            }
        } catch (e: Exception) {
            error(engineId, "exec_failed", e.message ?: "执行失败")
        }
    }

    /**
     * Execute a one-shot command inside the sandbox (proot + Alpine rootfs). [binary] is
     * the interpreter/program name inside the sandbox (python3 / node / ffmpeg); the
     * corresponding apk package is auto-installed before execution.
     */
    private suspend fun execInSandbox(engineId: String, binary: String, argv: List<String>, timeoutMs: Long): String {
        val sandbox = manager.sandbox ?: throw IllegalStateException("Sandbox 不可用，请先安装 Linux 沙箱")
        // Ensure sandbox is available.
        if (!sandbox.isAvailable()) {
            sandbox.install()
            if (!sandbox.isAvailable()) throw IllegalStateException("Sandbox 安装失败")
        }
        // Ensure the corresponding apk package is installed.
        sandbox.apkInstall(sandboxApkForBinary(binary))
        // Build command: binary + argv
        val cmd = buildString {
            append(binary)
            argv.forEach { arg -> append(" "); append(shellQuote(arg)) }
        }
        val result = sandbox.executeCommand(cmd, timeoutMs = timeoutMs.toInt())
        return buildJsonObject {
            put("ok", result.exitCode == 0)
            put("engine_id", engineId)
            put("exit_code", result.exitCode)
            if (result.stdout.isNotBlank()) put("output", result.stdout)
            if (result.stderr.isNotBlank()) put("stderr", result.stderr)
            if (result.exitCode != 0) put("message", "退出码 ${result.exitCode}")
        }.toString()
    }

    /** apk package name for sandbox-installed engines, or null if not sandbox-backed. */
    private fun sandboxPackageName(engineId: String): String? = when (engineId) {
        "runtime-python" -> "python3"
        "runtime-node" -> "nodejs"
        "runtime-ffmpeg" -> "ffmpeg"
        else -> null
    }

    /**
     * Binary name inside the sandbox for sandbox-executed engines, or null if the engine
     * is not executed inside the sandbox. webnovel reuses python3 inside the sandbox.
     */
    private fun sandboxBinaryName(engineId: String): String? = when (engineId) {
        "runtime-python" -> "python3"
        "runtime-node" -> "node"
        "runtime-ffmpeg" -> "ffmpeg"
        RuntimeEngineType.PYTHON_WEB_NOVEL -> "python3"
        else -> null
    }

    /** Map a sandbox binary name to its apk package name. */
    private fun sandboxApkForBinary(binary: String): String = when (binary) {
        "python3" -> "python3"
        "node" -> "nodejs"
        "ffmpeg" -> "ffmpeg"
        else -> binary
    }

    /** Shell 引号转义。 */
    private fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"

    /**
     * inkos 网文创作 SKILL（非独立 RUNTIME 引擎，依赖 Node.js 运行时）：引擎未运行时自动拉起
     * （等价 runtime_start 后再执行）。Extended。使用用户已有模型 Key 的 baseUrl/apiKey/model
     * 驱动 inkos CLI 创作出章节。
     *
     * 对齐真实 inkos CLI（@actalk/inkos，bin = cli/dist/index.js，基于 commander）：
     * 非交互批量续写用 `write next` 子命令，创意指令作为 `--context` 传入；
     * LLM 覆盖走 inkos 全局参数 `--api-key-env <env>`（从命名环境变量读 key）、
     * `--base-url <url>`、`--model <model>`。injectedEnv 已把 LCHAT_LLM_* 注入子进程 env。
     */
    private suspend fun novelInkos(args: Map<String, String>): String {
        val engineId = RuntimeEngineType.NODE_INKOS
        val message = args["message"]?.takeIf(String::isNotBlank)
            ?: return error(engineId, "missing_message", "缺少 message（创作指令）")
        val modelEnv = manager.buildModelEnv()
        if (modelEnv["INKOS_LLM_API_KEY"].isNullOrBlank() &&
            modelEnv["LCHAT_LLM_API_KEY"].isNullOrBlank()
        ) {
            return error(
                engineId,
                "model_not_configured",
                "未配置默认模型服务。请先在设置中配置模型（baseUrl/apiKey/model），之后即可用本工具实际创作。",
            )
        }
        return try {
            // 自动拉起引擎，并强制版本满足 inkos 的 node >= 22.5 约束。
            val envMap = manager.ensureStarted(
                engineId,
                null,
                RuntimeRequirement(runtime = "node", minVersion = "22.5"),
            )
            val installation = manager.installationOf(engineId) ?: throw IllegalStateException("引擎未安装")
            val root = manager.packageManager.versionRoot(engineId, installation.version)
            val manifest = manager.packageManager.readManifest(engineId, installation.version)
            // inkos CLI 真实入口：cli/dist/index.js（无 run.js、无 --prompt）。
            val entry = manifest?.entry ?: "cli/dist/index.js"
            val binary = File(root, binaryName(engineId)).absolutePath
            val cmd = mutableListOf(binary, File(root, entry).absolutePath)
            // 全局参数须位于子命令之前（enablePositionalOptions）。
            modelEnv["LCHAT_LLM_API_KEY"].orEmpty().let { key ->
                if (key.isNotBlank()) cmd += listOf("--api-key-env", "LCHAT_LLM_API_KEY")
            }
            modelEnv["LCHAT_LLM_BASE_URL"]?.takeIf(String::isNotBlank)
                ?.let { cmd += listOf("--base-url", it) }
            modelEnv["LCHAT_LLM_MODEL"]?.takeIf(String::isNotBlank)
                ?.let { cmd += listOf("--model", it) }
            // 非交互批量续写下一章；创意指令作为 --context。
            cmd += listOf("write", "next", "--context", message, "--json", "--quiet")
            val timeoutMs = (args["timeout_ms"]?.toLongOrNull() ?: 120_000L).coerceIn(10_000, 600_000)
            val result = runProcessOnce(cmd, envMap, root, timeoutMs)
            buildJsonObject {
                put("ok", result.isSuccess)
                put("engine_id", engineId)
                put("action", "novel_inkos")
                put("command", "inkos write next --context … --json --quiet")
                put("exit_code", result.exitCode)
                if (result.timedOut) put("timed_out", true)
                if (result.output.isNotBlank()) put("output", result.output)
                if (result.timedOut) {
                    put("message", "创作仍在进行，可稍后查询 runtime_status")
                } else if (!result.isSuccess) {
                    put("message", "创作失败，退出码 ${result.exitCode}")
                }
            }.toString()
        } catch (e: Exception) {
            error(engineId, "novel_failed", e.message ?: "创作失败")
        }
    }

    /**
     * webnovel-writer 网文创作 SKILL（GPL-3.0，非独立 RUNTIME 引擎，依赖 Python 运行时）：
     * 按 action 驱动适配器完成初始化设定 / 规划卷纲 / 写章 / 一致性审查 / 状态查询。
     * 引擎未运行时自动拉起（并自动确保依赖的 runtime-python 已装）。Extended。
     *
     * 参数：action = init|plan|write|review|query；params = 传给适配器的 JSON 字符串。
     * 适配器会注入用户已配置的默认模型 Key（LCHAT_LLM_*），RAG 可降级（无 embedding 时不阻塞）。
     */
    private suspend fun webNovel(args: Map<String, String>): String {
        val engineId = RuntimeEngineType.PYTHON_WEB_NOVEL
        val action = (args["action"] ?: "").trim().lowercase()
        if (action !in SUPPORTED_WEB_NOVEL_ACTIONS) {
            return error(engineId, "bad_action", "未知 action「$action」，支持：init/plan/write/review/query")
        }
        val params = args["params"] ?: ""
        val modelEnv = manager.buildModelEnv()
        if (modelEnv["LCHAT_LLM_API_KEY"].isNullOrBlank() && action in LLM_REQUIRED_ACTIONS) {
            return error(
                engineId,
                "model_not_configured",
                "该 action 需要 LLM 模型 Key。请先在设置中配置默认模型服务（baseUrl/apiKey/model），之后即可实际创作。",
            )
        }
        return try {
            // 通过 sandbox（proot + Alpine rootfs）执行 python3，不再用 glibc ld-linux 直接加载。
            val sandbox = manager.sandbox ?: throw IllegalStateException("Sandbox 不可用，请先安装 Linux 沙箱")
            if (!sandbox.isAvailable()) {
                sandbox.install()
                if (!sandbox.isAvailable()) throw IllegalStateException("Sandbox 安装失败")
            }
            sandbox.apkInstall("python3")
            // 获取 webnovel 引擎安装目录中的 adapter.py
            val installation = manager.installationOf(engineId) ?: throw IllegalStateException("引擎未安装")
            val root = manager.packageManager.versionRoot(engineId, installation.version)
            val manifest = manager.packageManager.readManifest(engineId, installation.version)
            val adapterFile = File(root, manifest?.entry ?: "scripts/adapter.py")
            if (!adapterFile.isFile) {
                throw IllegalStateException(
                    "webnovel 引擎包不完整：缺少 ${manifest?.entry ?: "scripts/adapter.py"}（安装目录 $root）。" +
                        "请先 market_uninstall 卸载后重新 market_install 安装最新引擎包。",
                )
            }
            // 把 adapter.py 复制到 sandbox home（executeCommand 默认 workdir 即 home）
            val sandboxHome = sandbox.getSandboxHomeDir() ?: throw IllegalStateException("Sandbox home 不可用")
            val targetAdapter = File(sandboxHome, "webnovel_adapter.py")
            adapterFile.copyTo(targetAdapter, overwrite = true)
            // 也复制 scripts 目录（adapter 可能依赖其他 .py 文件）
            val scriptsDir = File(root, "scripts")
            if (scriptsDir.isDirectory) {
                val targetScripts = File(sandboxHome, "scripts")
                targetScripts.mkdirs()
                scriptsDir.listFiles()?.forEach { it.copyTo(File(targetScripts, it.name), overwrite = true) }
            }
            // 构建命令：先 export 模型环境变量，再执行 python3。
            // executeCommand 通过 /bin/sh -c 执行，export 会注入 python 子进程；
            // workdir 默认为 sandbox home，故用相对路径引用 adapter。
            val envPrefix = modelEnv.entries.joinToString("; ") { "export ${it.key}=${shellQuote(it.value)}" }
            val cmd = buildString {
                if (envPrefix.isNotBlank()) append(envPrefix).append("; ")
                append("python3 -X utf8 webnovel_adapter.py")
                append(" --action "); append(shellQuote(action))
                append(" --params "); append(shellQuote(params))
            }
            val timeoutMs = (args["timeout_ms"]?.toLongOrNull() ?: 180_000L).coerceIn(10_000, 600_000)
            val result = sandbox.executeCommand(cmd, timeoutMs = timeoutMs.toInt())
            // 适配器输出为 JSON；尽量透传。失败时给出可读信息。
            buildJsonObject {
                put("ok", result.exitCode == 0)
                put("engine_id", engineId)
                put("action", "webnovel_$action")
                put("exit_code", result.exitCode)
                if (result.exitCode == 0) {
                    parseWebNovelJson(result.stdout)?.let { put("result", it) }
                        ?: put("output", result.stdout)
                } else {
                    put("message", "执行失败，退出码 ${result.exitCode}")
                    if (result.stdout.isNotBlank()) put("output", result.stdout)
                    if (result.stderr.isNotBlank()) put("stderr", result.stderr)
                }
            }.toString()
        } catch (e: Exception) {
            error(engineId, "webnovel_failed", e.message ?: "执行失败")
        }
    }

    /** 尝试把适配器 stdout 解析为 JSON 对象。 */
    private fun parseWebNovelJson(output: String): JsonObject? {
        if (output.isBlank()) return null
        return runCatching { json.parseToJsonElement(output) }
            .getOrNull() as? JsonObject
    }

    // ── 响应构造 ────────────────────────────────────────────

    private fun statusJson(st: RuntimeStatus): String = buildJsonObject {
        putAllJsonObject(statusJsonObject(st))
    }.toString()

    private fun statusJsonObject(st: RuntimeStatus): JsonObject = buildJsonObject {
        put("engine_id", st.engineId)
        put("installed", st.installed)
        st.installedVersion?.let { put("version", it) }
        putJsonArray("installed_versions") { st.installedVersions.forEach { add(JsonPrimitive(it)) } }
        put("running", st.running)
        st.downloadState?.let { put("download_state", it) }
        st.message?.let { put("message", it) }
    }

    private fun error(engineId: String, code: String, message: String): String = buildJsonObject {
        put("ok", false)
        put("engine_id", engineId)
        put("error", code)
        put("message", message)
    }.toString()

    private fun errorJson(code: String, message: String): String = buildJsonObject {
        put("ok", false)
        put("error", code)
        put("message", message)
    }.toString()

    // ── 参数解析 ────────────────────────────────────────────

    private fun parseArgs(raw: String): Map<String, String> {
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyMap()
        val obj = el as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }.toMap()
    }

    private fun requiredEngineId(args: Map<String, String>): String? =
        args["engine_id"]?.takeIf(String::isNotBlank)

    private fun parseArgv(raw: String?): List<String> {
        val el = raw?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: return emptyList()
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
    }

    private fun binaryName(engineId: String): String = when (engineId) {
        RuntimeEngineType.NODE_INKOS, "runtime-node" -> "node"
        RuntimeEngineType.PYTHON_WEB_NOVEL, "runtime-python" -> "python"
        "runtime-ffmpeg" -> "ffmpeg"
        else -> "node"
    }

    private companion object {
        val NAMES = setOf(
            Names.INSTALL, Names.UNINSTALL, Names.STATUS,
            Names.START, Names.STOP, Names.RUNTIME_STATUS,
            Names.EXEC, Names.NOVEL_INKOS, Names.WEB_NOVEL, Names.ALL,
        )

        val SUPPORTED_WEB_NOVEL_ACTIONS = setOf("init", "plan", "write", "review", "query")
        val LLM_REQUIRED_ACTIONS = setOf("init", "plan", "write", "review")

        val descriptors: List<ToolDescriptor> = listOf(
            ToolDescriptor(
                definition = ToolDefinition(
                    function = ToolFunction(
                        name = Names.INSTALL,
                        description = "从市场按 id 安装一个运行时引擎（如 runtime-node-inkos / runtime-python / runtime-ffmpeg）。可指定 version，缺省按约束自动匹配版本。下载为高危操作，需用户批准。",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "engine_id" to ToolProperty("string", "引擎 id，如 runtime-node-inkos"),
                                "version" to ToolProperty("string", "可选，指定安装版本；不传则按约束自动匹配"),
                            ),
                            required = listOf("engine_id"),
                        ),
                    ),
                ),
                riskLevel = RiskLevel.HighRisk,
                tier = ToolTier.Dangerous,
                requiresApproval = true,
                summary = "Install a runtime engine from the market.",
            ),
            ToolDescriptor(
                definition = ToolDefinition(
                    function = ToolFunction(
                        name = Names.UNINSTALL,
                        description = "卸载一个运行时引擎：若在运行先停止，再删除其文件。卸载为高危操作，需用户批准。",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "engine_id" to ToolProperty("string", "引擎 id"),
                            ),
                            required = listOf("engine_id"),
                        ),
                    ),
                ),
                riskLevel = RiskLevel.HighRisk,
                tier = ToolTier.Dangerous,
                requiresApproval = true,
                summary = "Uninstall a runtime engine.",
            ),
            ToolDescriptor(
                definition = ToolDefinition(
                    function = ToolFunction(
                        name = Names.STATUS,
                        description = "查询引擎是否已装、版本、是否运行中。只读。",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "engine_id" to ToolProperty("string", "引擎 id"),
                            ),
                            required = listOf("engine_id"),
                        ),
                    ),
                ),
                riskLevel = RiskLevel.ReadOnly,
                tier = ToolTier.Core,
                summary = "Query a runtime engine's install/status.",
            ),
            ToolDescriptor(
                definition = ToolDefinition(
                    function = ToolFunction(
                        name = Names.START,
                        description = "启动运行时引擎：未安装则报错提示先 market_install；已运行则幂等返回。启动为高危操作，需用户批准。",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "engine_id" to ToolProperty("string", "引擎 id"),
                                "version" to ToolProperty("string", "可选，指定版本"),
                            ),
                            required = listOf("engine_id"),
                        ),
                    ),
                ),
                riskLevel = RiskLevel.HighRisk,
                tier = ToolTier.Dangerous,
                requiresApproval = true,
                summary = "Start a runtime engine.",
            ),
            ToolDescriptor(
                definition = ToolDefinition(
                    function = ToolFunction(
                        name = Names.STOP,
                        description = "停止运行时引擎。停止为高危操作，需用户批准。空闲 10 分钟会自动停止。",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "engine_id" to ToolProperty("string", "引擎 id"),
                            ),
                            required = listOf("engine_id"),
                        ),
                    ),
                ),
                riskLevel = RiskLevel.HighRisk,
                tier = ToolTier.Dangerous,
                requiresApproval = true,
                summary = "Stop a runtime engine.",
            ),
            ToolDescriptor(
                definition = ToolDefinition(
                    function = ToolFunction(
                        name = Names.RUNTIME_STATUS,
                        description = "查询单引擎状态（是否已装、已装版本、是否运行）。只读。",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "engine_id" to ToolProperty("string", "引擎 id"),
                            ),
                            required = listOf("engine_id"),
                        ),
                    ),
                ),
                riskLevel = RiskLevel.ReadOnly,
                tier = ToolTier.Core,
                summary = "Query single runtime engine status.",
            ),
            ToolDescriptor(
                definition = ToolDefinition(
                    function = ToolFunction(
                        name = Names.EXEC,
                        description = "在引擎进程内执行一次性命令。python：argv 传脚本参数（如 -c '<code>'）；ffmpeg：argv 传转码参数（如 -i in.mp4 out.mp3）。",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "engine_id" to ToolProperty("string", "引擎 id"),
                                "argv" to ToolProperty("string", "传给引擎的参数(JSON数组字符串，如 [\"-c\",\"print(1)\"])"),
                                "timeout_ms" to ToolProperty("string", "可选，超时毫秒"),
                            ),
                            required = listOf("engine_id", "argv"),
                        ),
                    ),
                ),
                riskLevel = RiskLevel.Moderate,
                tier = ToolTier.Extended,
                summary = "Run a one-shot command inside a runtime engine.",
            ),
            ToolDescriptor(
                definition = ToolDefinition(
                    function = ToolFunction(
                        name = Names.NOVEL_INKOS,
                        description = "用 inkos 网文创作引擎实际创作小说章节（非交互批量续写下一章：inkos write next，传入的 message 作为创作指令 --context）。引擎未运行时自动拉起；通过 inkos 全局参数注入用户已配置的默认模型服务（--api-key-env/--base-url/--model）。未配置模型或引擎未安装会返回错误。",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "message" to ToolProperty("string", "创作指令（题材/设定/大纲/期望章节）"),
                                "timeout_ms" to ToolProperty("string", "可选，超时毫秒"),
                            ),
                            required = listOf("message"),
                        ),
                    ),
                ),
                riskLevel = RiskLevel.Moderate,
                tier = ToolTier.Extended,
                summary = "Write a novel chapter using the inkos engine.",
            ),
            ToolDescriptor(
                definition = ToolDefinition(
                    function = ToolFunction(
                        name = Names.WEB_NOVEL,
                        description = "用 webnovel-writer 网文创作引擎执行创作动作。action 支持 init（初始化设定）/plan（规划卷纲）/write（写章）/review（一致性审查）/query（查询状态）。引擎未运行时自动拉起并自动确保依赖的 runtime-python 已装。使用用户已配置的默认模型服务进行创作；未配置模型会返回错误。",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "action" to ToolProperty("string", "init | plan | write | review | query"),
                                "params" to ToolProperty("string", "传给适配器的 JSON 字符串（如设定、卷号/章号、审查范围等）"),
                                "timeout_ms" to ToolProperty("string", "可选，超时毫秒"),
                            ),
                            required = listOf("action"),
                        ),
                    ),
                ),
                riskLevel = RiskLevel.Moderate,
                tier = ToolTier.Extended,
                summary = "Drive the webnovel-writer engine (init/plan/write/review/query).",
            ),
            ToolDescriptor(
                definition = ToolDefinition(
                    function = ToolFunction(
                        name = Names.ALL,
                        description = "列出全部已知运行时引擎（node+inkos / python / ffmpeg）的安装与运行状态。只读。",
                        parameters = ToolParameters(properties = emptyMap(), required = emptyList()),
                    ),
                ),
                riskLevel = RiskLevel.ReadOnly,
                tier = ToolTier.Core,
                summary = "List all runtime engines' status.",
            ),
        )
    }
}

/** kotlinx JSON 辅助：在 JsonObject builder 内合并另一个 JsonObject。 */
private fun kotlinx.serialization.json.JsonObjectBuilder.putAllJsonObject(other: JsonObject) {
    other.forEach { (k, v) -> put(k, v) }
}
