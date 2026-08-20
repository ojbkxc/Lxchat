package com.lxseek.chat.api.util

import com.lxseek.chat.api.OpenAiChatRequest
import com.lxseek.chat.api.OpenAiFunctionCall
import com.lxseek.chat.api.OpenAiMessage
import com.lxseek.chat.api.OpenAiRequestFunction
import com.lxseek.chat.api.OpenAiRequestToolCall
import com.lxseek.chat.api.OpenAiToolCall
import com.lxseek.chat.api.anthropic.AnthropicContentPart
import com.lxseek.chat.api.anthropic.AnthropicMessage
import com.lxseek.chat.api.anthropic.AnthropicRequest
import com.lxseek.chat.api.anthropic.requireValidWireFormat
import com.lxseek.chat.api.gemini.ApiGenerateContentRequest
import com.lxseek.chat.api.gemini.ApiRequestContent
import com.lxseek.chat.api.gemini.ApiRequestPart
import com.lxseek.chat.api.gemini.GeminiFunctionCall
import com.lxseek.chat.api.gemini.GeminiFunctionResponse
import com.lxseek.chat.api.gemini.requireValidWireFormat
import com.lxseek.chat.api.ollama.OllamaChatRequest
import com.lxseek.chat.api.ollama.OllamaMessage
import com.lxseek.chat.api.ollama.requireValidWireFormat
import com.lxseek.chat.api.openai.requireValidWireFormat
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/** Regression coverage for a live tool continuation: every wire history ends in durable input. */
class ProviderContinuationRequestValidatorTest {
    private val emptyObject = JsonObject(emptyMap())

    @Test
    fun openAiAcceptsCompleteToolResultAsTerminalInput() {
        OpenAiChatRequest(
            model = "deepseek-chat",
            messages = listOf(
                OpenAiMessage("user", content = listOf(text("start"))),
                OpenAiMessage(
                    "assistant",
                    toolCalls = listOf(
                        OpenAiRequestToolCall(
                            id = "call_1",
                            function = OpenAiRequestFunction("file_read", "{}"),
                        )
                    ),
                ),
                OpenAiMessage("tool", content = listOf(text("result")), toolCallId = "call_1"),
            ),
        ).requireValidWireFormat("DeepSeek")
    }

    @Test
    fun anthropicAcceptsCompleteToolResultAsTerminalInput() {
        AnthropicRequest(
            model = "claude-sonnet-5",
            messages = listOf(
                AnthropicMessage("user", listOf(AnthropicContentPart("text", text = "start"))),
                AnthropicMessage(
                    "assistant",
                    listOf(
                        AnthropicContentPart(
                            type = "tool_use",
                            id = "call_1",
                            name = "file_read",
                            input = emptyObject,
                        )
                    ),
                ),
                AnthropicMessage(
                    "user",
                    listOf(
                        AnthropicContentPart(
                            type = "tool_result",
                            toolUseId = "call_1",
                            content = "result",
                        )
                    ),
                ),
            ),
        ).requireValidWireFormat()
    }

    @Test
    fun geminiAcceptsCompleteFunctionResponseAsTerminalInput() {
        ApiGenerateContentRequest(
            contents = listOf(
                ApiRequestContent("user", listOf(ApiRequestPart(text = "start"))),
                ApiRequestContent(
                    "model",
                    listOf(
                        ApiRequestPart(
                            functionCall = GeminiFunctionCall(
                                id = "call_1",
                                name = "file_read",
                                args = emptyObject,
                            )
                        )
                    ),
                ),
                ApiRequestContent(
                    "user",
                    listOf(
                        ApiRequestPart(
                            functionResponse = GeminiFunctionResponse(
                                id = "call_1",
                                name = "file_read",
                                response = emptyObject,
                            )
                        )
                    ),
                ),
            ),
        ).requireValidWireFormat("gemini-2.5-pro")
    }

    @Test
    fun ollamaAcceptsCompleteToolResultAsTerminalInput() {
        OllamaChatRequest(
            model = "qwen3",
            messages = listOf(
                OllamaMessage("user", content = "start"),
                OllamaMessage(
                    "assistant",
                    toolCalls = listOf(
                        OpenAiToolCall(
                            index = 0,
                            id = "call_1",
                            type = "function",
                            function = OpenAiFunctionCall("file_read", emptyObject),
                        )
                    ),
                ),
                OllamaMessage("tool", content = "result", toolName = "file_read"),
            ),
        ).requireValidWireFormat()
    }

    private fun text(value: String) = com.lxseek.chat.api.OpenAiContentPart(
        type = "text",
        text = value,
    )
}
