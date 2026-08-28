package com.lxseek.chat.runtime

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.tool.RiskLevel
import com.lxseek.chat.tool.ToolDescriptor
import com.lxseek.chat.tool.ToolExecutionResult
import com.lxseek.chat.tool.ToolProvider
import com.lxseek.chat.tool.ToolTier
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        const val ALL = "all_runtimes_status"
    }

    // ── ToolProvider ─────────────────────────────────────────

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> = descriptors

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        descriptors.map { it.definition }

    override fun handles(name: String): Boolean = name in NAMES

    override fun riskLevel(name: String): RiskLevel = when (name) {
        Names.STATUS, Names.RUNTIME_STATUS, Names.ALL -> RiskLevel.ReadOnly
        Names.EXEC, Names.NOVEL_INKOS -> RiskLevel.Moderate
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
        return try {
            val envMap = manager.ensureStarted(engineId, null, null)
            val root = manager.packageManager.versionRoot(engineId, manager.installationOf(engineId)?.version.orEmpty())
            val binary = File(root, binaryName(engineId)).absolutePath
            val timeoutMs = (args["timeout_ms"]?.toLongOrNull() ?: 60_000L).coerceIn(1_000, 300_000)
            val result = runProcessOnce(listOf(binary) + argv, envMap, root, timeoutMs)
            buildJsonObject {
                put("ok", result.isSuccess)
                put("engine_id", engineId)
                put("exit_code", result.exitCode)
                put("timed_out", result.timedOut)
                if (result.output.isNotBlank()) put("output", result.output)
                if (!result.isSuccess) put("message", "退出码 ${result.exitCode}${if (result.timedOut) "，超时" else ""}")
            }.toString()
        } catch (e: Exception) {
            error(engineId, "exec_failed", e.message ?: "执行失败")
        }
    }

    /**
     * inkos 网文创作：引擎未运行时自动拉起（等价 runtime_start 后再执行）。Extended。
     * 使用用户已有模型 Key 的 baseUrl/apiKey/model 注入进程，实际创作出章节。
     */
    private suspend fun novelInkos(args: Map<String, String>): String {
        val engineId = RuntimeEngineType.NODE_INKOS
        val message = args["message"]?.takeIf(String::isNotBlank)
            ?: return error(engineId, "missing_message", "缺少 message（创作指令）")
        val modelEnv = manager.buildModelEnv()
        if (modelEnv["INKOS_LLM_API_KEY"].isNullOrBlank()) {
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
            val root = manager.packageManager.versionRoot(engineId, manager.installationOf(engineId)?.version.orEmpty())
            val manifest = manager.packageManager.readManifest(engineId, manager.installationOf(engineId)?.version.orEmpty())
            val entry = manifest?.entry ?: "inkos/run.js"
            val binary = File(root, binaryName(engineId)).absolutePath
            val timeoutMs = (args["timeout_ms"]?.toLongOrNull() ?: 120_000L).coerceIn(10_000, 600_000)
            val result = runProcessOnce(
                listOf(binary, File(root, entry).absolutePath, "--prompt", message),
                envMap,
                root,
                timeoutMs,
            )
            buildJsonObject {
                put("ok", result.isSuccess)
                put("engine_id", engineId)
                put("action", "novel_inkos")
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
        "runtime-python" -> "python"
        "runtime-ffmpeg" -> "ffmpeg"
        else -> "node"
    }

    private companion object {
        val NAMES = setOf(
            Names.INSTALL, Names.UNINSTALL, Names.STATUS,
            Names.START, Names.STOP, Names.RUNTIME_STATUS,
            Names.EXEC, Names.NOVEL_INKOS, Names.ALL,
        )

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
                                "argv" to ToolProperty("string", "传给引擎可执行文件的参数数组（JSON）"),
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
                        description = "用 inkos 网文创作引擎实际创作小说章节。引擎未运行时自动拉起；使用用户已配置的默认模型服务（baseUrl/apiKey/model）进行创作。未配置模型会返回错误。",
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