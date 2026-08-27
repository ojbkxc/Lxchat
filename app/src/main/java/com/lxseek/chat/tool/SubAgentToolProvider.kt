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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 子代理工具提供器 —— 让 AI 能把独立子任务委托给一个「子代理」在后台执行，而不污染当前对话上下文。
 *
 * 子代理拥有自己的全新会话与完整工具能力，通过底层一次性 Task 异步运行；父对话只需用 create /read 来
 * 生成与取回结果。同 [automationToolsEnabled] 门控，避免在用户关闭自动化能力时暴露。
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
                description = "把子任务委托给一个独立的子代理在后台执行。子代理拥有全新会话与完整工具能力，不会污染当前对话上下文。可用动作：create（默认，生成子代理并立即后台运行，prompt 为必填的完整指令，description 为其显示名）；read（读取某子代理的最新输出，id 为 create 返回的 id）；list（列出已派生的全部子代理及状态）；delete（删除某子代理及其会话）。至多同时运行 5 个；create 达到上限会失败。完成后的子代理可用 read 取回结果，用完后用 delete 清理。",
                parameters = ToolParameters(
                    properties = mapOf(
                        "action" to ToolProperty("string", "操作类型：create（默认）/ read / list / delete"),
                        "id" to ToolProperty("string", "子代理 id（read/delete 必填，来自 create 的返回）"),
                        "prompt" to ToolProperty("string", "给子代理的完整指令（create 必填），将作为其会话的第一条用户消息"),
                        "description" to ToolProperty("string", "子代理任务描述（create 用，作为其显示名，可选）"),
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
            return error(SUBAGENT, "已达子代理上限（同时最多 5 个），请先 read 取回结果并用 delete 清理")
        }
        val prompt = args.string("prompt")?.trim().orEmpty()
        if (prompt.isEmpty()) return error(SUBAGENT, "prompt 不能为空")
        val description = args.string("description")?.trim().orEmpty()
        val sub = subAgentManager.spawn(prompt, description, modelId = null)
            ?: return error(SUBAGENT, "子代理创建失败（已达并发上限）")

        return buildJsonObject {
            put("type", SUBAGENT)
            put("id", sub.id)
            put("description", sub.description)
            put("state", "running")
            put(
                "message",
                "子代理已创建并后台执行。用 subagent(action=\"read\", id=\"${sub.id}\") 取回输出；完成后用 subagent(action=\"delete\", id=\"${sub.id}\") 清理。",
            )
        }.toString()
    }

    private suspend fun read(args: JsonObject): String {
        val id = args.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return error(SUBAGENT, "id 不能为空（用 create 的返回值）")
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
            put("maxRunning", 5)
            putJsonArray("subagents") {
                subs.forEach { sub ->
                    add(buildJsonObject {
                        put("id", sub.id)
                        put("description", sub.description)
                        put("state", if (subAgentManager.isRunning(sub.id)) "running" else "completed")
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

    private fun error(tool: String, message: String): String = buildJsonObject {
        put("type", tool)
        put("error", message)
    }.toString()

    private companion object {
        const val SUBAGENT = "subagent"
    }
}