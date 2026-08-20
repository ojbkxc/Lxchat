package com.lxseek.chat.api

/**
 * Typed error hierarchy for LLM generation failures.
 *
 * Replaces ad-hoc string-based error messages in StreamEvent.Error with
 * structured types that enable differentiated UI handling (retry actions,
 * error icons, recovery strategies) per error category.
 *
 * Phase 1b creates the type hierarchy. Phase 7 migrates all provider
 * emit sites from StreamEvent.Error(String) to StreamEvent.Error(GenerationError).
 */
sealed class GenerationError {

    /** HTTP-level error (connection refused, timeout, DNS failure, etc.). */
    data class Network(
        val statusCode: Int,
        val message: String
    ) : GenerationError()

    /** API-level error returned by the provider (invalid key, rate limit, server error). */
    data class Api(
        val code: String?,
        val type: String?,
        val message: String
    ) : GenerationError()

    /** Failed to parse a line from the SSE stream. */
    data class SseParse(
        val rawLine: String,
        val cause: String
    ) : GenerationError()

    /**
     * The stream ended without a semantic terminal marker (`message_stop` / `finish_reason` /
     * `[DONE]`), so the response is provably incomplete rather than merely finished.
     *
     * The common shape is a relay closing the connection at a content-block boundary — right
     * where a `tool_use` block was about to begin — which stays invisible if HTTP 200 response
     * headers are treated as proof of success.
     */
    data class IncompleteStream(
        val provider: String,
        val stopReason: String?,
        val toolCallInFlight: Boolean,
        val producedContent: Boolean,
    ) : GenerationError()

    /**
     * The provider stopped because the output token cap was reached (`max_tokens` / `length`).
     * On thinking models that cap covers reasoning and answer together, so a large reasoning
     * budget can exhaust it exactly where a tool call would have started.
     */
    data class OutputTruncated(
        val provider: String,
        val stopReason: String?,
    ) : GenerationError()

    /** A tool execution failed (memory, web search, shell, RAG). */
    data class ToolExecution(
        val toolName: String,
        val arguments: String,
        val message: String
    ) : GenerationError()

    /** Image/video/PDF transcription failed. */
    data class Transcription(
        val imagePath: String,
        val message: String
    ) : GenerationError()

    /** Embedding computation failed. */
    data class Embedding(
        val modelId: String,
        val message: String
    ) : GenerationError()

    /** On-device GGUF model error (file not found, failed to load, etc.). */
    data class LocalModel(
        val message: String
    ) : GenerationError()

    /** Missing or invalid configuration (no API key, no base URL, etc.). */
    data class Configuration(
        val message: String
    ) : GenerationError()

    /** The request was rejected locally before any network I/O because its shape was not valid. */
    data class RequestFormat(
        val provider: String,
        val details: String,
    ) : GenerationError()

    /** Wraps an unexpected exception. */
    data class Unknown(
        val cause: Throwable
    ) : GenerationError()

    /** Generation was cancelled by the user. */
    object Cancelled : GenerationError()

    /** Request timed out waiting for a server response. */
    object Timeout : GenerationError()

    /** Human-readable message suitable for displaying in the UI. */
    fun userMessage(): String = when (this) {
        is Network -> when (statusCode) {
            401 -> "Authentication failed. Please check your API key."
            429 -> "Rate limit exceeded. Please wait and try again."
            in 500..599 -> "Server error ($statusCode). The service may be temporarily unavailable."
            else -> "Network error ($statusCode): $message"
        }
        is Api -> buildString {
            if (code != null) append("$code")
            if (type != null) append(" [$type]")
            if (isNotEmpty()) append(": ")
            append(message)
        }
        is SseParse -> "Failed to parse server response."
        is IncompleteStream -> buildString {
            append("$provider ended the response early")
            if (toolCallInFlight) append(" while a tool call was still being written")
            append(". ")
            append(
                if (stopReason == null) {
                    "No completion signal arrived, so the reply is incomplete."
                } else {
                    "The stream closed after stop_reason=$stopReason with no completion signal."
                }
            )
        }
        is OutputTruncated ->
            "Response hit the output token limit (stop_reason=${stopReason ?: "max_tokens"}) " +
                "and was cut off. Raise Max Tokens, or lower the thinking budget, then retry."
        is ToolExecution -> "Tool '$toolName' failed: $message"
        is Transcription -> "Image transcription failed: $message"
        is Embedding -> "Embedding failed: $message"
        is LocalModel -> message
        is Configuration -> message
        is RequestFormat -> "Request validation failed before sending ($provider): $details"
        is Unknown -> cause.localizedMessage ?: "An unexpected error occurred."
        Cancelled -> "Generation cancelled."
        Timeout -> "Request timed out."
    }

    /**
     * Localized (zh-CN) human-readable message suitable for displaying in the UI.
     *
     * Unlike [userMessage] (which returns English diagnostic text), this variant produces
     * concise Chinese descriptions that map directly to differentiated recovery actions
     * in the assistant error bubble (retry / check network / raise Max Tokens).
     */
    fun userFriendlyMessage(): String = when (this) {
        is Network -> "网络连接失败：$message"
        is Api -> "API 错误：$message"
        is SseParse -> "数据解析错误"
        is IncompleteStream -> "响应不完整，请重试"
        is OutputTruncated -> "输出被截断，请增加 Max Tokens"
        is ToolExecution -> "工具执行失败：$message"
        is Transcription -> "转码失败：$message"
        is Embedding -> "嵌入计算失败"
        is LocalModel -> "本地模型错误：$message"
        is Configuration -> "配置错误：$message"
        is RequestFormat -> "请求格式错误：$details"
        is Unknown -> cause.localizedMessage ?: "未知错误"
        Cancelled -> "生成已取消"
        Timeout -> "请求超时"
    }
}
