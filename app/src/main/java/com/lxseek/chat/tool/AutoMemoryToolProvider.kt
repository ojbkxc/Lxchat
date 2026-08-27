package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.viewmodel.AutoMemoryExtractor
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Exposes the mem0-style memory tool to the agent. The model calls [EXTRACT_MEMORY] with a
 * conversation excerpt; it extracts durable user-stated facts and consolidates them against the
 * saved-memory store (ADD / UPDATE / DELETE / NONE), mirroring mem0's extraction + update flow.
 */
class AutoMemoryToolProvider(
    private val extractor: AutoMemoryExtractor,
) : ToolProvider {

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> {
        if (!ctx.accessSavedMemories) return emptyList()
        return listOf(
            ToolDescriptor(
                definition = ToolDefinition(
                    function = ToolFunction(
                        name = EXTRACT_MEMORY,
                        description = "Extract durable, self-contained facts and preferences the user stated from a " +
                            "conversation excerpt and persist them into saved memory. Overlapping or contradictory past " +
                            "memories are automatically added/merged/updated/deleted so the store stays clean. Pass the " +
                            "conversation excerpt in the 'conversation' argument. Use after a meaningful exchange to build " +
                            "long-term memory about the user.",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "conversation" to ToolProperty(
                                    "string",
                                    "The conversation excerpt (recent user and assistant turns) to extract facts from."
                                )
                            ),
                            required = listOf("conversation")
                        )
                    )
                ),
                riskLevel = RiskLevel.LowRisk,
                tier = ToolTier.Extended,
            )
        )
    }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        toolDescriptors(ctx).map { it.definition }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        withContext(Dispatchers.IO) {
            if (name != EXTRACT_MEMORY) return@withContext "Error: Unknown tool: $name"
            val conversation = runCatching {
                Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject["conversation"]
                    ?.let { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
            }.getOrDefault("")
            if (conversation.isBlank()) {
                return@withContext "Error: The 'conversation' argument is required and cannot be empty."
            }
            when (val result = extractor.extractAndApply(conversation)) {
                is AutoMemoryExtractor.Result.Success -> buildJsonObject {
                    put("type", EXTRACT_MEMORY)
                    put("added", result.added)
                    put("updated", result.updated)
                    put("deleted", result.deleted)
                    put("none", result.none)
                }.toString()
                is AutoMemoryExtractor.Result.Failure ->
                    "Error: Memory extraction failed: ${result.reason}"
            }
        }

    override fun handles(name: String): Boolean = name == EXTRACT_MEMORY

    override fun riskLevel(name: String): RiskLevel = RiskLevel.LowRisk

    companion object {
        const val EXTRACT_MEMORY = "extract_memory"
    }
}