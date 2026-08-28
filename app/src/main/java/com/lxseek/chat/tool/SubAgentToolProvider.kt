package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.automation.SubAgentManager
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 子代理工具提供器 —— 让 AI 能把独立子任务委托给一个「子代理」在后台执行，而不污染当前对话上下文。
 *
 * 子代理拥有自己的全新会话与完整工具能力，通过底层一次性 Task 异步运行；父对话只需用 create /read 来
 * 生成与取回结果。同 [automationToolsEnabled] 门控，避免在用户关闭自动化能力时暴露。
 *
 * 支持的附加能力：
 * - `context`：把父对话的关键事实/摘要传给子代理，作为其 prompt 的前缀注入。
 * - `timeout_seconds`：子代理超时秒数，到期自动取消并标记 timeout。
 * - `read` 返回进度信息（status / elapsedMs / outputLength / output），而非仅输出。
 */
class SubAgentToolProvider(
    private val subAgentManager: SubAgentManager,
    private val isCurrentlyEnabled: suspend () -> Boolean = { true },
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.automationToolsEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = SUBAGENT,
                description = "把子任务委托给一个独立的子代理在后台执行。子代理拥有全新会话与完整工具能力，不会污染当前对话上下文。可用动作：create（默认，生成子代理并立即后台运行，prompt 为必填的完整指令，description 为其显示名，可选 context 传入上下文事实作为 prompt 前缀，可选 timeout_seconds 设置超时自动取消）；read（读取某子代理的进度与最新输出，id 为 create 返回的 id）；list（列出已派生的全部子代理及状态）；delete（删除某子代理及其会话）。并发上限由配置决定（默认 5）；create 达到上限会失败。完成后的子代理可用 read 取回结果，用完后用 delete 清理。",
                parameters = ToolParameters(
                    properties = mapOf(
                        "action" to ToolProperty("string", "操作类型：create（默认）/ read / list / delete"),
                        "id" to ToolProperty("string", "子代理 id（read/delete 必填，来自 create 的返回）"),
                        "prompt" to ToolProperty("string", "给子代理的完整指令（create 必填），将作为其会话的第一条用户消息"),
                        "description" to ToolProperty("string", "子代理任务描述（create 用，作为其显示名，可选）"),
                        "context" to ToolProperty("string", "传给子代理的上下文（create 可选）。非空时作为前缀注入到 prompt 之前，让子代理从关键事实/父对话摘要开始而非空白"),
                        "timeout_seconds" to ToolProperty("integer", "子代理超时秒数（create 可选）。到期后自动取消该子代理并标记为 timeout；不传则永不超时"),
                    ),
                    required = emptyList(),
                ),
            )),
        )
    }

    override fun handles(name: String): Boolean = name == SUBAGENT

    override fun riskLevel(name: String): RiskLevel = RiskLevel.Moderate

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name != SUBAGENT) return error(name, "Unknown tool: $name")
        if (!ctx.automationToolsEnabled || !isCurrentlyEnabled()) {
            return error(name, "Subagents are disabled")
        }
        return try {
            val args = Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
            when (args.string("action") ?: "create") {
                "create" -> create(args)
                "read" -> read(args)
                "list" -> list(args)
                "delete" -> delete(args)
                else -> error(name, "action must be one of: create / read / list / delete")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error(name, e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Invalid arguments")
        }
    }

    // ── 各动作 ───────────────────────────────────────────────

    private fun create(args: JsonObject): String {
        if (subAgentManager.isFull) {
            return error(SUBAGENT, "已达子代理并发上限，请先 read 取回结果并用 delete 清理")
        }
        val prompt = args.string("prompt")?.trim().orEmpty()
        if (prompt.isEmpty()) return error(SUBAGENT, "prompt 不能为空")
        val description = args.string("description")?.trim().orEmpty()
        val context = args.string("context")?.trim()?.takeIf { it.isNotEmpty() }
        val timeoutSeconds = args.int("timeout_seconds")?.takeIf { it > 0 }
        val timeoutMs = timeoutSeconds?.let { it.toLong() * 1000L }
        val sub = subAgentManager.spawn(
            prompt = prompt,
            description = description,
            modelId = null,
            context = context,
            timeoutMs = timeoutMs,
        ) ?: return error(SUBAGENT, "子代理创建失败（已达并发上限）")

        return buildJsonObject {
            put("type", SUBAGENT)
            put("id", sub.id)
            put("description", sub.description)
            put("state", "running")
            if (timeoutSeconds != null) put("timeout_seconds", timeoutSeconds)
            put(
                "message",
                "子代理已创建并后台执行。用 subagent(action=\"read\", id=\"${sub.id}\") 取回输出；完成后用 subagent(action=\"delete\", id=\"${sub.id}\") 清理。",
            )
        }.toString()
    }

    private suspend fun read(args: JsonObject): String {
        val id = args.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return error(SUBAGENT, "id 不能为空（用 create 的返回值）")
        // 优先返回进度快照；若子代理不存在则回退到仅输出（保持向后兼容的容错）。
        val progress = subAgentManager.progress(id)
        if (progress != null) {
            return buildJsonObject {
                put("type", SUBAGENT)
                put("id", id)
                put("state", progress.status)
                put("elapsedMs", progress.elapsedMs)
                put("outputLength", progress.outputLength)
                put("output", progress.lastOutputPreview)
            }.toString()
        }
        // 回退：子代理已不在内存映射中（例如进程重启后），仍尝试直接读底层输出。
        val output = subAgentManager.latestOutput(id)
        return buildJsonObject {
            put("type", SUBAGENT)
            put("id", id)
            put("state", if (subAgentManager.isRunning(id)) "running" else "completed")
            put("output", output)
        }.toString()
    }

    private fun list(args: JsonObject): String {
        val subs = subAgentManager.list()
        return buildJsonObject {
            put("type", SUBAGENT)
            put("count", subs.size)
            put("maxRunning", subAgentManager.maxRunning)
            putJsonArray("subagents") {
                subs.forEach { sub ->
                    add(buildJsonObject {
                        put("id", sub.id)
                        put("description", sub.description)
                        put("state", when {
                            sub.timedOut -> "timeout"
                            subAgentManager.isRunning(sub.id) -> "running"
                            else -> "completed"
                        })
                        put("createdAt", sub.createdAt)
                    })
                }
            }
        }.toString()
    }

    private suspend fun delete(args: JsonObject): String {
        val id = args.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return error(SUBAGENT, "id 不能为空（用 create 的返回值）")
        subAgentManager.remove(id)
        return buildJsonObject {
            put("type", SUBAGENT)
            put("id", id)
            put("state", "deleted")
            put("message", "子代理已删除（含其执行会话）。")
        }.toString()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun error(tool: String, message: String): String = buildJsonObject {
        put("type", tool)
        put("error", message)
    }.toString()

    private companion object {
        const val SUBAGENT = "subagent"
    }
}
