package com.lxseek.chat.api.openai

import com.lxseek.chat.api.OpenAiChatRequest
import com.lxseek.chat.api.OpenAiContentPart
import com.lxseek.chat.api.util.requireValidRequestFormat
import com.lxseek.chat.api.util.safeWireToolCallId
import com.lxseek.chat.api.util.safeWireToolName
import com.lxseek.chat.api.util.validateToolDefinitions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal fun OpenAiChatRequest.requireValidWireFormat(provider: String) {
    val violations = mutableListOf<String>()
    if (model.isBlank()) violations += "model is blank"
    if (messages.isEmpty()) violations += "messages is empty"
    violations += validateToolDefinitions(tools)
    if (maxTokens != null && maxTokens <= 0) violations += "max_tokens must be positive"
    if (topP != null && topP !in 0f..1f) violations += "top_p is outside 0..1"

    val pendingToolIds = linkedSetOf<String>()
    val seenToolIds = mutableSetOf<String>()
    var sawNonSystem = false

    messages.forEachIndexed { index, message ->
        val location = "messages[$index]"
        if (message.role !in setOf("system", "user", "assistant", "tool")) {
            violations += "$location has invalid role ${message.role}"
        }
        validateContent(message.content, location, violations)

        when (message.role) {
            "system" -> {
                if (sawNonSystem) violations += "$location system role is not at the beginning"
                if (message.content.isNullOrEmpty() || !message.content.any(::isSubstantivePart)) {
                    violations += "$location system content is empty"
                }
                if (!message.toolCalls.isNullOrEmpty() || message.toolCallId != null) {
                    violations += "$location system message carries tool fields"
                }
            }
            "user" -> {
                sawNonSystem = true
                if (message.content.isNullOrEmpty() || !message.content.any(::isSubstantivePart)) {
                    violations += "$location user content is empty"
                }
                if (pendingToolIds.isNotEmpty()) {
                    violations += "$location interrupts pending tool results"
                }
                if (!message.toolCalls.isNullOrEmpty() || message.toolCallId != null) {
                    violations += "$location user message carries tool fields"
                }
            }
            "assistant" -> {
                sawNonSystem = true
                if (pendingToolIds.isNotEmpty()) {
                    violations += "$location starts before prior tool results are complete"
                    pendingToolIds.clear()
                }
                if (message.toolCallId != null) {
                    violations += "$location assistant message has tool_call_id"
                }
                if (
                    message.toolCalls.isNullOrEmpty() &&
                    (message.content.isNullOrEmpty() || !message.content.any(::isSubstantivePart))
                ) {
                    violations += "$location assistant content is empty"
                }
                message.toolCalls.orEmpty().forEachIndexed { callIndex, call ->
                    val callLocation = "$location.tool_calls[$callIndex]"
                    if (!call.id.matches(safeWireToolCallId)) {
                        violations += "$callLocation id is not wire-safe"
                    }
                    if (call.type != "function") violations += "$callLocation type is not function"
                    if (!call.function.name.matches(safeWireToolName)) {
                        violations += "$callLocation name is not wire-safe"
                    }
                    if (!isJsonObject(call.function.arguments)) {
                        violations += "$callLocation arguments are not a JSON object"
                    }
                    if (call.id.isNotBlank() && !seenToolIds.add(call.id)) {
                        violations += "$callLocation reuses tool call id ${call.id}"
                    }
                    if (call.id.isNotBlank() && !pendingToolIds.add(call.id)) {
                        violations += "$callLocation duplicates tool call id ${call.id}"
                    }
                }
                if (!message.toolCalls.isNullOrEmpty() && message.content != null) {
                    val hasVisibleContent = message.content.any(::isSubstantivePart)
                    if (!hasVisibleContent) {
                        violations += "$location tool-call content must be null or substantive"
                    }
                }
            }
            "tool" -> {
                sawNonSystem = true
                if (message.content.isNullOrEmpty()) {
                    violations += "$location tool content is absent"
                }
                val toolCallId = message.toolCallId
                if (toolCallId.isNullOrBlank()) {
                    violations += "$location tool_call_id is blank"
                } else if (!pendingToolIds.remove(toolCallId)) {
                    violations += "$location does not match a pending tool call"
                }
                if (!message.toolCalls.isNullOrEmpty()) {
                    violations += "$location tool message carries tool_calls"
                }
            }
        }
    }
    if (pendingToolIds.isNotEmpty()) violations += "tool calls are missing results"
    if (messages.lastOrNull()?.role !in setOf("user", "tool")) {
        violations += "history does not end in user/tool input"
    }
    requireValidRequestFormat(provider, violations)
}

private val validationJson = Json { ignoreUnknownKeys = true }

private fun isJsonObject(raw: String): Boolean =
    runCatching { validationJson.parseToJsonElement(raw) is JsonObject }.getOrDefault(false)

private fun validateContent(
    parts: List<OpenAiContentPart>?,
    location: String,
    violations: MutableList<String>,
) {
    if (parts == null) return
    if (parts.isEmpty()) {
        violations += "$location content is empty"
        return
    }
    parts.forEachIndexed { index, part ->
        val partLocation = "$location.content[$index]"
        when (part.type) {
            "text" -> if (part.text == null || part.imageUrl != null) {
                violations += "$partLocation is not a valid text part"
            }
            "image_url" -> if (part.imageUrl?.url.isNullOrBlank() || part.text != null) {
                violations += "$partLocation is not a valid image_url part"
            }
            else -> violations += "$partLocation has unsupported type ${part.type}"
        }
    }
}

private fun isSubstantivePart(part: OpenAiContentPart): Boolean = when (part.type) {
    "text" -> !part.text.isNullOrBlank()
    "image_url" -> !part.imageUrl?.url.isNullOrBlank()
    else -> false
}
