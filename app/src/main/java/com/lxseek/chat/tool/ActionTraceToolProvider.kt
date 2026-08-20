package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Read-only provider exposing the action trace history to the model. */
class ActionTraceToolProvider : ToolProvider {
    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(function = ToolFunction(
            name = "get_action_trace",
            description = "Retrieve the recent tool invocation history (action trace). Returns the most recent tool calls with their arguments, results, timing, and target server. Use this to review what has been done so far in this session, especially before repeating a command or diagnosing a failure pattern.",
            parameters = ToolParameters(
                properties = mapOf(
                    "limit" to ToolProperty("integer", "Maximum number of recent entries to return (optional, default 50, max 256)."),
                ),
                required = emptyList()
            )
        ))
    )

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name != "get_action_trace") return "Unknown tool: $name"
        val limit = runCatching {
            Json.parseToJsonElement(arguments.ifBlank { "{}" })
                .let { (it as? JsonObject)?.get("limit") as? JsonPrimitive }
                ?.content?.toIntOrNull() ?: 50
        }.getOrDefault(50)
        return ActionTraceBus.toJson(limit)
    }

    override fun handles(name: String): Boolean = name == "get_action_trace"

    override fun riskLevel(name: String): RiskLevel = RiskLevel.ReadOnly
}