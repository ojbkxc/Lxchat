package com.lxseek.chat.api.openai

import com.lxseek.chat.api.GenerationError
import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.api.LlmProvider
import com.lxseek.chat.api.ProviderConfig
import com.lxseek.chat.api.StreamEvent
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.api.util.ProviderRetryPolicy
import com.lxseek.chat.api.util.asRetryableTransportError
import com.lxseek.chat.api.util.buildToolCallId
import com.lxseek.chat.api.util.emitTransportError
import com.lxseek.chat.api.util.prepareMessages
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException

/**
 * OpenAI 官方 ChatGPT 提供商(Codex Responses API)。
 *
 * 与普通 OpenAI 兼容提供商不同,该提供商的密钥来源是「ChatGPT 官方账号登录」:
 * [com.lxseek.chat.openai.OpenAIXOAuthManager] 完成 OAuth 后,把 access token 通过
 * [com.lxseek.chat.data.repository.SettingsRepository.upsertApiKey] 写入为
 * [Constants.PROVIDER_CHATGPT] 的活动 API Key。
 *
 * 请求走 Codex Responses API(`https://chatgpt.com/backend-api/codex/responses`)而非
 * 标准 Chat Completions,并携带 `originator`、`User-Agent`、`ChatGPT-Account-Id` 等
 * Codex CLI 专有 header。对齐 cc-haha-main `src/services/openaiAuth/client.ts` 与
 * `fetch.ts`。
 *
 * SSE 事件格式与 Chat Completions 不同:每条事件以 `event: <type>` 行声明类型,
 * `data: <json>` 行携带 payload,空行分隔。终止信号是 `response.completed` 而非
 * `[DONE]`(见 cc-haha-main openaiResponsesStreamToAnthropic.ts 中 openAICodexOAuth
 * 分支)。
 */
class OpenAIXProvider(
    /** Supplies the ChatGPT account id for the `ChatGPT-Account-Id` header. Null/blank
     *  omits the header (Codex treats it as optional, see cc-haha-main fetch.ts). */
    private val accountIdProvider: () -> String? = { null },
) : LlmProvider {
    override val name: String = Constants.PROVIDER_CHATGPT

    // Required by LlmProvider; ChatGPT OAuth has no user-configurable base URL —
    // the Codex Responses endpoint is fixed.
    override val defaultBaseUrl: String = CODEX_API_ENDPOINT

    companion object {
        const val CODEX_API_ENDPOINT = "https://chatgpt.com/backend-api/codex/responses"
        const val CODEX_ORIGINATOR = "codex_cli_rs"
        const val CODEX_CLIENT_VERSION = "0.144.0"
        const val CODEX_USER_AGENT = "codex-cli/$CODEX_CLIENT_VERSION"

        /** Preset ChatGPT model list (aligned with cc-haha-main models.ts). */
        val PRESET_MODELS = listOf(
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-5.3-codex",
            "gpt-5.4",
            "gpt-5.5",
            "gpt-5.4-mini",
        )

        private const val TAG = "OpenAIXProvider"
        private val RETRYABLE_STATUS_CODES = setOf(429, 502, 503, 504)
    }

    /**
     * Codex OAuth 不兼容 `/v1/models`;直接返回预置模型列表。
     * UI 在登录成功时已把 [PRESET_MODELS] 写入 settings,这里作为兜底。
     */
    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> =
        withContext(Dispatchers.IO) { PRESET_MODELS }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig,
    ): Flow<StreamEvent> = flow {
        val validatedMessages = prepareMessages(messages, config.maxContextWindow)

        // Build the Responses API request body.
        val requestBody = buildResponsesRequestBody(validatedMessages, config)

        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "text/event-stream",
            "originator" to CODEX_ORIGINATOR,
            "User-Agent" to CODEX_USER_AGENT,
        )
        if (config.apiKey.isNotBlank()) {
            headers["Authorization"] = "Bearer ${config.apiKey}"
        }
        accountIdProvider()?.takeIf(String::isNotBlank)?.let {
            headers["ChatGPT-Account-Id"] = it
        }

        DebugLog.d(
            TAG,
            "[$name] request model=${config.modelId} messages=${validatedMessages.size} " +
                "tools=${config.tools?.size ?: 0}",
        )

        val maxAttempts = ProviderRetryPolicy.MAX_ATTEMPTS
        var attempt = 0
        var finished = false

        try {
            while (attempt < maxAttempts && !finished) {
                attempt++

                val handle = try {
                    HttpClient.streamPost(CODEX_API_ENDPOINT, requestBody, headers)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val retryable = e.asRetryableTransportError()
                    if (retryable != null && attempt < maxAttempts) {
                        val retryDelayMs = ProviderRetryPolicy.delayMillis(attempt)
                        DebugLog.w(
                            TAG,
                            "[$name] Transport failure opening stream on attempt " +
                                "$attempt/$maxAttempts (${e.javaClass.simpleName}), " +
                                "retrying in ${retryDelayMs}ms",
                        )
                        emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                        delay(retryDelayMs)
                        continue
                    }
                    throw e
                }

                try {
                    if (handle.code == 200) {
                        val outcome = consumeResponsesStream(handle, config) { emit(it) }
                        DebugLog.d(
                            TAG,
                            "[$name] stream_end terminal=${outcome.sawTerminal} " +
                                "emitted=${outcome.emittedToConsumer} attempt=$attempt/$maxAttempts",
                        )
                        if (!outcome.sawTerminal) {
                            if (attempt < maxAttempts && !outcome.emittedToConsumer) {
                                // M2 修复：未见到语义终态事件时仅在「尚未向下游 emit
                                // 过任何内容」时才自动重放——一旦重放，已 emit 的
                                // TextChunk 会造成内容重复。此处未 emit 过，重放安全。
                                DebugLog.w(
                                    TAG,
                                    "[$name] Incomplete stream on attempt $attempt/$maxAttempts, retrying",
                                )
                                val retryDelayMs = ProviderRetryPolicy.delayMillis(attempt)
                                emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                                delay(retryDelayMs)
                            } else {
                                // 已向下游 emit 过部分内容（或重试次数用尽）：不能再
                                // 自动重放，直接以「响应不完整」错误告终。
                                emit(StreamEvent.Error(GenerationError.IncompleteStream(
                                    provider = name,
                                    stopReason = null,
                                    toolCallInFlight = false,
                                    producedContent = outcome.emittedToConsumer,
                                )))
                                finished = true
                            }
                        } else {
                            finished = true
                        }
                    } else {
                        val errorRaw = handle.errorBody ?: "Unknown error"
                        val responseBytes = errorRaw.toByteArray(Charsets.UTF_8).size
                        DebugLog.e(TAG, "[$name] HTTP ${handle.code} responseBytes=$responseBytes")
                        if (
                            ProviderRetryPolicy.shouldRetryHttp(
                                handle.code,
                                errorRaw,
                                RETRYABLE_STATUS_CODES,
                            ) && attempt < maxAttempts
                        ) {
                            val retryDelayMs = ProviderRetryPolicy.delayMillis(attempt)
                            DebugLog.w(
                                TAG,
                                "[$name] Transient error ${handle.code} on attempt " +
                                    "$attempt/$maxAttempts, retrying in ${retryDelayMs}ms...",
                            )
                            emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                            delay(retryDelayMs)
                        } else {
                            emit(StreamEvent.Error(buildApiError(handle.code, errorRaw)))
                            finished = true
                        }
                    }
                } finally {
                    handle.close()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emitTransportError(name, TAG, e)
        }
    }.flowOn(Dispatchers.IO)

    // ── Request building ──────────────────────────────────────

    /**
     * Build the Codex Responses API request body from the conversation messages.
     *
     * Mapping (mirrors cc-haha-main anthropicToOpenaiResponses.ts):
     * - system prompt → `instructions` field (lifted out of `input`)
     * - user/assistant text → `{type:"message", role, content}`
     * - assistant tool_call → `{type:"function_call", call_id, name, arguments}`
     * - tool result → `{type:"function_call_output", call_id, output}`
     */
    private fun buildResponsesRequestBody(
        messages: List<ChatMessage>,
        config: ProviderConfig,
    ): String {
        val input = JSONArray()

        for (msg in messages) {
            // tool_ messages: assistant turn carrying tool_calls
            if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    for (seg in toolSegs) {
                        val callId = seg.toolCallId
                            ?: buildToolCallId(seg.toolName ?: "", seg.toolArgs ?: "{}")
                        input.put(JSONObject().apply {
                            put("type", "function_call")
                            put("call_id", callId)
                            put("name", seg.toolName ?: "")
                            put("arguments", seg.toolArgs ?: "{}")
                        })
                    }
                } else {
                    // R4：安全化处理——判空后不再用 !! 强制解包，null 时直接跳过。
                    msg.toolCall?.let { tc ->
                        val callId = tc.toolCallId ?: buildToolCallId(tc.toolName, tc.arguments)
                        input.put(JSONObject().apply {
                            put("type", "function_call")
                            put("call_id", callId)
                            put("name", tc.toolName)
                            put("arguments", tc.arguments)
                        })
                    }
                }
                continue
            }

            // result_ messages: tool results
            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    for (seg in toolSegs) {
                        val callId = seg.toolCallId
                            ?: buildToolCallId(seg.toolName ?: "", seg.toolArgs ?: "{}")
                        input.put(JSONObject().apply {
                            put("type", "function_call_output")
                            put("call_id", callId)
                            put("output", seg.toolResult ?: "")
                        })
                    }
                } else {
                    // R4：安全化处理——判空后不再用 !! 强制解包，null 时直接跳过。
                    msg.toolCall?.let { tc ->
                        val callId = tc.toolCallId ?: buildToolCallId(tc.toolName, tc.arguments)
                        input.put(JSONObject().apply {
                            put("type", "function_call_output")
                            put("call_id", callId)
                            put("output", tc.result)
                        })
                    }
                }
                continue
            }

            // Normal text message
            val role = if (msg.participant == Participant.USER) "user" else "assistant"
            val text = msg.text
            if (text.isBlank()) continue
            input.put(JSONObject().apply {
                put("type", "message")
                put("role", role)
                put("content", text)
            })
        }

        val body = JSONObject().apply {
            put("model", config.modelId)
            put("input", input)
            put("stream", true)
            put("store", false)
            config.systemPrompt?.takeIf(String::isNotBlank)?.let { put("instructions", it) }

            // tools — Responses API shape: {type:"function", name, description, parameters}
            config.tools?.takeIf { it.isNotEmpty() }?.let { tools ->
                val toolsArray = JSONArray()
                for (tool in tools) {
                    toolsArray.put(toolDefinitionToJson(tool))
                }
                put("tools", toolsArray)
            }

            // reasoning effort — Codex CLI sends {effort:"medium"} by default
            if (config.thinkingEnabled) {
                val effort = when (config.thinkingLevel.lowercase()) {
                    "low" -> "low"
                    "high" -> "high"
                    else -> "medium"
                }
                put("reasoning", JSONObject().put("effort", effort))
            }

            // Codex OAuth requires encrypted reasoning content to be included.
            put("include", JSONArray().put("reasoning.encrypted_content"))

            // Sampling parameters (optional, only sent when explicitly set)
            config.temperature?.let { put("temperature", it) }
            config.topP?.let { put("top_p", it) }
            config.maxTokens?.let { put("max_output_tokens", it) }
        }

        return body.toString()
    }

    private fun toolDefinitionToJson(tool: ToolDefinition): JSONObject = JSONObject().apply {
        put("type", "function")
        put("name", tool.function.name)
        tool.function.description.takeIf(String::isNotBlank)?.let { put("description", it) }
        put("parameters", toolParametersToJson(tool.function.parameters))
    }

    private fun toolParametersToJson(params: ToolParameters): JSONObject = JSONObject().apply {
        put("type", params.type)
        val props = JSONObject()
        for ((key, prop) in params.properties) {
            props.put(key, toolPropertyToJson(prop))
        }
        put("properties", props)
        if (params.required.isNotEmpty()) {
            put("required", JSONArray(params.required))
        }
    }

    private fun toolPropertyToJson(prop: ToolProperty): JSONObject = JSONObject().apply {
        put("type", prop.type)
        prop.description.takeIf(String::isNotBlank)?.let { put("description", it) }
        prop.items?.let { put("items", toolPropertyToJson(it)) }
    }

    // ── SSE stream consumption ─────────────────────────────────

    /**
     * Consume one 200 SSE stream from the Codex Responses API.
     *
     * Returns true when a semantic terminal event (`response.completed`) was seen,
     * false when the stream ended without one (truncation / transport EOF).
     *
     * Event handling mirrors cc-haha-main openaiResponsesStreamToAnthropic.ts with
     * `openAICodexOAuth: true`: `[DONE]` is ignored, `response.completed` is the
     * terminal signal, and `response.failed`/`response.incomplete`/`error` surface
     * as [StreamEvent.Error].
     */
    private suspend fun consumeResponsesStream(
        handle: HttpClient.StreamHandle,
        config: ProviderConfig,
        emit: suspend (StreamEvent) -> Unit,
    ): StreamOutcome {
        var currentEvent = ""
        val dataLines = mutableListOf<String>()
        var sawTerminal = false
        var emitted = false
        // item_id -> pending tool call accumulator
        val pendingToolCalls = linkedMapOf<String, PendingCodexToolCall>()

        // M2：所有下游 emit 必须经过这里，保证 emitted 标记不漏（重放判定依据）。
        suspend fun emitTracked(event: StreamEvent) {
            emitted = true
            emit(event)
        }

        suspend fun dispatchEvent(): Boolean {
            if (dataLines.isEmpty()) {
                currentEvent = ""
                return false
            }
            val dataText = dataLines.joinToString("\n")
            val eventName = currentEvent
            currentEvent = ""
            dataLines.clear()

            // Codex OAuth: [DONE] is NOT the terminal signal; response.completed is.
            if (dataText == "[DONE]") return false

            val data = try {
                JSONObject(dataText)
            } catch (e: Exception) {
                DebugLog.e(
                    TAG,
                    "[$name] malformed SSE payload: ${dataText.take(256)}",
                )
                return false
            }

            val resolvedEvent = eventName.ifBlank { data.optString("type") }
            if (resolvedEvent.isBlank()) return false

            when (resolvedEvent) {
                "response.output_text.delta" -> {
                    val delta = data.optString("delta")
                    if (delta.isNotEmpty()) {
                        emitTracked(StreamEvent.TextChunk(delta))
                    }
                }

                "response.reasoning_text.delta",
                "response.reasoning_summary_text.delta" -> {
                    val delta = data.optString("delta")
                    if (delta.isNotEmpty() && config.thinkingEnabled) {
                        emitTracked(StreamEvent.ThoughtChunk(delta))
                    }
                }

                "response.output_item.added" -> {
                    val item = data.optJSONObject("item")
                    if (item != null && item.optString("type") == "function_call") {
                        val itemId = item.optString("id").ifBlank { item.optString("call_id") }
                        val callId = item.optString("call_id").ifBlank { itemId }
                        val toolName = item.optString("name")
                        if (itemId.isNotBlank()) {
                            val pending = pendingToolCalls.getOrPut(itemId) {
                                PendingCodexToolCall(callId, toolName)
                            }
                            if (callId.isNotBlank()) pending.callId = callId
                            if (toolName.isNotBlank()) pending.name = toolName
                        }
                    }
                }

                "response.function_call_arguments.delta" -> {
                    val itemId = data.optString("item_id")
                    val delta = data.optString("delta")
                    if (itemId.isNotBlank() && delta.isNotEmpty()) {
                        val pending = pendingToolCalls.getOrPut(itemId) {
                            PendingCodexToolCall("", "")
                        }
                        pending.args.append(delta)
                        emitTracked(StreamEvent.ToolCallUpdate(
                            streamKey = itemId,
                            id = pending.callId.ifBlank { null },
                            name = pending.name,
                            arguments = pending.args.toString(),
                        ))
                    }
                }

                "response.function_call_arguments.done" -> {
                    val itemId = data.optString("item_id")
                    if (itemId.isNotBlank()) {
                        val pending = pendingToolCalls.remove(itemId)
                        if (pending != null && pending.name.isNotBlank()) {
                            emitTracked(StreamEvent.ToolCallRequest(
                                id = pending.callId.ifBlank { itemId },
                                name = pending.name,
                                arguments = pending.args.toString().ifBlank { "{}" },
                                streamKey = itemId,
                            ))
                        }
                    }
                }

                "response.completed" -> {
                    sawTerminal = true
                    return true
                }

                "response.failed",
                "response.cancelled",
                "response.incomplete",
                "error" -> {
                    val errorMsg = readStreamError(resolvedEvent, data)
                    emitTracked(StreamEvent.Error(GenerationError.Api(
                        code = null,
                        type = resolvedEvent,
                        message = errorMsg,
                    )))
                    sawTerminal = true
                    return true
                }
            }
            return false
        }

        var consecutiveReadTimeouts = 0
        while (currentCoroutineContext().isActive) {
            val line = try {
                handle.readLine()
            } catch (e: SocketTimeoutException) {
                if (!currentCoroutineContext().isActive) break
                // A semantic terminal event already completed the response; this timeout
                // only closes the optional trailing tail and is therefore success.
                if (sawTerminal) break
                // Silently-dead connection (NAT drop with no RST): give up after 3 consecutive
                // timeouts (~15 min without a single byte).
                if (++consecutiveReadTimeouts >= 3) break
                continue
            } ?: break
            consecutiveReadTimeouts = 0

            if (line.isEmpty()) {
                dispatchEvent()
                continue
            }
            if (line.startsWith(":")) continue  // SSE comment / heartbeat

            val colon = line.indexOf(':')
            val field = if (colon == -1) line else line.substring(0, colon)
            var value = if (colon == -1) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)

            when (field) {
                "event" -> currentEvent = value
                "data" -> dataLines.add(value)
            }
        }

        // Flush any buffered event at transport EOF.
        if (dataLines.isNotEmpty()) {
            dispatchEvent()
        }

        // Defensive: emit any tool call that never received response.function_call_arguments.done
        // (should not happen when response.completed arrives, but guards against truncation).
        for ((itemId, pending) in pendingToolCalls) {
            if (pending.name.isNotBlank()) {
                emitTracked(StreamEvent.ToolCallRequest(
                    id = pending.callId.ifBlank { itemId },
                    name = pending.name,
                    arguments = pending.args.toString().ifBlank { "{}" },
                    streamKey = itemId,
                ))
            }
        }

        if (!currentCoroutineContext().isActive) {
            throw CancellationException("Stream cancelled")
        }

        return StreamOutcome(sawTerminal, emitted)
    }

    /**
     * Read the human-readable error message from a `response.failed` /
     * `response.incomplete` / `error` SSE event.
     *
     * Mirrors cc-haha-main `readStreamError`: looks at `response.error` then top-level
     * `error`, falls back to a generic message naming the event.
     */
    private fun readStreamError(event: String, data: JSONObject): String {
        val response = data.optJSONObject("response")
        val error = response?.optJSONObject("error") ?: data.optJSONObject("error") ?: data
        val message = error.optString("message").takeIf(String::isNotBlank)
        if (message != null) return message
        if (event == "response.incomplete") {
            val reason = response?.optJSONObject("incomplete_details")?.optString("reason")
                ?: "unknown"
            return "OpenAI response was incomplete: $reason"
        }
        return "OpenAI stream ended with $event"
    }

    private fun buildApiError(statusCode: Int, errorRaw: String): GenerationError = try {
        val errorJson = JSONObject(errorRaw)
        val error = errorJson.optJSONObject("error") ?: errorJson
        GenerationError.Api(
            code = error.optString("code").ifBlank { statusCode.toString() },
            type = error.optString("type").takeIf(String::isNotBlank),
            message = error.optString("message").ifBlank { errorRaw },
        )
    } catch (_: Exception) {
        GenerationError.Network(statusCode = statusCode, message = errorRaw)
    }
}

/** Accumulator for one Codex Responses API tool call while its arguments stream in. */
private class PendingCodexToolCall(
    var callId: String,
    var name: String,
) {
    val args: StringBuilder = StringBuilder()
}

/** 一次 200 流的消耗结果：是否见到语义终态事件、是否已向下游 emit 过任何事件。
 *  M2：`emittedToConsumer` 为 true 时调用方不得自动重放请求，否则已 emit 的
 *  内容会随重放重复。 */
private class StreamOutcome(
    val sawTerminal: Boolean,
    val emittedToConsumer: Boolean,
)
