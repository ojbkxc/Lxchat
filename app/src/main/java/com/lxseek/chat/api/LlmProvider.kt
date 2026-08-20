package com.lxseek.chat.api

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.ContextBudget
import com.lxseek.chat.model.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

sealed class StreamEvent {
    data class TextChunk(val text: String) : StreamEvent()
    data class ThoughtChunk(val thought: String, val title: String? = null, val signature: String? = null) : StreamEvent()
    data class UsageUpdate(val usage: TokenUsage) : StreamEvent() {
        constructor(tokenCount: Int, thoughtsTokenCount: Int = 0) : this(
            TokenUsage(
                totalTokenCount = tokenCount.coerceAtLeast(0),
                reasoningTokenCount = thoughtsTokenCount
                    .takeIf { it > 0 },
            )
        )

        val tokenCount: Int
            get() = usage.totalTokenCount

        val thoughtsTokenCount: Int
            get() = usage.reasoningTokenCount ?: 0
    }
    data class Error(val error: GenerationError) : StreamEvent() {
        val message: String get() = error.userMessage()
    }
    /**
     * Full accumulated snapshot of a tool call while the model is still writing it.
     *
     * [streamKey] is stable even when a compatible provider sends the protocol [id] in a later
     * delta. UI code keys the live segment by [streamKey], then replaces its protocol id when the
     * matching [ToolCallRequest] completes.
     */
    data class ToolCallUpdate(
        val streamKey: String,
        val id: String?,
        val name: String,
        val arguments: String,
        val signature: String? = null,
    ) : StreamEvent()

    data class ToolCallRequest(
        val id: String,
        val name: String,
        val arguments: String,
        val signature: String? = null,
        val streamKey: String = id,
    ) : StreamEvent()
    data class ToolCallsRequest(val calls: List<ToolCallRequest>) : StreamEvent()
    /** Emitted before one provider retry. [attempt] is the 1-based retry number, while
     *  [maxAttempts] is the retry budget and excludes the initial request. */
    data class Retrying(val attempt: Int, val maxAttempts: Int) : StreamEvent()
}

data class ProviderConfig(
    val apiKey: String,
    val modelId: String,
    val systemPrompt: String? = null,
    /** Estimated provider-visible conversation token budget. */
    val maxContextWindow: Int = ContextBudget.DEFAULT_TOKENS,
    val codeExecutionEnabled: Boolean = false,
    val googleSearchEnabled: Boolean = false,
    val thinkingEnabled: Boolean = true,
    val thinkingLevel: String = "medium",
    val thinkingBudgetEnabled: Boolean = false,
    val thinkingBudgetTokens: Int = 4096,
    val openAiServiceTier: String? = null,
    val baseUrl: String? = null,
    val tools: List<ToolDefinition>? = null,
    val userPrepend: String? = null,
    val userPostpend: String? = null,
    val includeImages: Boolean = true,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: ToolFunction
)

@Serializable
data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: ToolParameters
)

@Serializable
data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, ToolProperty>,
    val required: List<String> = emptyList()
)

@Serializable
data class ToolProperty(
    val type: String,
    val description: String,
    val items: ToolProperty? = null
)

@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: OpenAiStreamOptions? = null,
    val tools: List<ToolDefinition>? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val reasoning: OpenAiReasoning? = null,
    val plugins: List<OpenAiPlugin>? = null,
    @SerialName("service_tier") val serviceTier: String? = null,
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Float? = null,
    @SerialName("presence_penalty") val presencePenalty: Float? = null
)

@Serializable
data class OpenAiPlugin(
    val id: String
)

@Serializable
data class OpenAiReasoning(
    val effort: String? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val enabled: Boolean? = null
)

@Serializable
data class OpenAiStreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: List<OpenAiContentPart>? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiRequestToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null
)

@Serializable
data class OpenAiRequestToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiRequestFunction
)

@Serializable
data class OpenAiRequestFunction(
    val name: String,
    val arguments: String
)

@Serializable
data class OpenAiContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: OpenAiImageUrl? = null
)

@Serializable
data class OpenAiImageUrl(
    val url: String
)



@Serializable
data class OpenAiTool(
    val type: String,
    val function: OpenAiFunction? = null
)

@Serializable
data class OpenAiFunction(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null
)

@Serializable
data class OpenAiStreamResponse(
    val id: String? = null,
    val choices: List<OpenAiChoice>? = null,
    /** Non-standard relay outcome. Some gateways return `failed to generate` here on HTTP 200. */
    val outcome: String? = null,
    // Relays deliver a mid-stream failure as a bare {"error":{...}} chunk on an HTTP 200 response.
    // Without this field `ignoreUnknownKeys` discarded it, choices stayed null, and the generation
    // simply stopped with no diagnostic at all.
    val error: OpenAiError? = null,
    val usage: OpenAiUsage? = null
)

@Serializable
data class OpenAiChoice(
    val index: Int,
    val delta: OpenAiDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class OpenAiDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    // Bare `reasoning` string: OpenRouter and many Claude/DeepSeek-in-OpenAI relays emit this
    // instead of (or duplicated alongside) reasoning_content. Read as a fallback so a
    // non-standard endpoint's thinking is never silently dropped.
    val reasoning: String? = null,
    @SerialName("reasoning_details") val reasoningDetails: List<OpenAiReasoningDetail>? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null
)

@Serializable
data class OpenAiReasoningDetail(
    val type: String? = null,
    val text: String? = null
)

@Serializable
data class OpenAiToolCall(
    val index: Int? = null,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAiFunctionCall? = null
)

@Serializable
data class OpenAiFunctionCall(
    val name: String? = null,
    val arguments: JsonElement? = null
)

@Serializable
data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("prompt_tokens_details") val promptTokensDetails: OpenAiPromptTokensDetails? = null,
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int? = null,
    @SerialName("prompt_cache_miss_tokens") val promptCacheMissTokens: Int? = null,
    @SerialName("completion_tokens_details") val completionTokensDetails: OpenAiCompletionTokensDetails? = null
)

@Serializable
data class OpenAiPromptTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Int? = null
)

@Serializable
data class OpenAiCompletionTokensDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null
)

internal fun OpenAiUsage.toTokenUsage(): TokenUsage {
    val input = promptTokens?.coerceAtLeast(0)
    val cached = (
        promptCacheHitTokens
            ?: promptTokensDetails?.cachedTokens
        )?.coerceAtLeast(0)
    val uncached = (
        promptCacheMissTokens
            ?: if (input != null && cached != null) {
                (input - cached).coerceAtLeast(0)
            } else {
                null
            }
        )?.coerceAtLeast(0)
    val output = completionTokens?.coerceAtLeast(0)
    val reasoning = completionTokensDetails?.reasoningTokens?.coerceAtLeast(0)
    val derivedTotal = listOfNotNull(input, output).sum()
    return TokenUsage(
        totalTokenCount = (totalTokens ?: derivedTotal).coerceAtLeast(0),
        inputTokenCount = input ?: if (cached != null && uncached != null) {
            TokenUsage.addCounts(cached, uncached)
        } else {
            null
        },
        cachedInputTokenCount = cached,
        uncachedInputTokenCount = uncached,
        outputTokenCount = output,
        reasoningTokenCount = reasoning,
    )
}

@Serializable
data class OpenAiModelListResponse(
    val data: List<OpenAiModelInfo>,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("last_id") val lastId: String? = null
)

@Serializable
data class OpenAiModelInfo(val id: String)

@Serializable
data class OpenAiErrorResponse(val error: OpenAiError)

@Serializable
data class OpenAiError(val message: String, val type: String? = null, val code: String? = null)

class PendingToolCall(
    val streamKey: String = "call_stream_${java.util.UUID.randomUUID()}",
    var id: String = "",
    var name: String = "",
    /** Snapshot-tolerant accumulator: a relay that resends the whole value in every delta, or
     *  interleaves empty placeholder deltas, must not corrupt or erase the arguments. */
    val args: com.lxseek.chat.api.util.ToolArgumentAccumulator =
        com.lxseek.chat.api.util.ToolArgumentAccumulator()
)

interface LlmProvider {
    val name: String
    val defaultBaseUrl: String

    fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent>
    
    suspend fun fetchModels(apiKey: String, baseUrl: String? = null): List<String>
}
