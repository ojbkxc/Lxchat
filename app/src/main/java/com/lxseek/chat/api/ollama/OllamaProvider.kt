package com.lxseek.chat.api.ollama

import com.lxseek.chat.api.*

import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.api.util.StreamingThinkTagParser
import com.lxseek.chat.api.util.buildToolCallId
import com.lxseek.chat.api.util.prepareMessages
import com.lxseek.chat.api.util.RequestFormatException
import com.lxseek.chat.api.util.ProviderRetryPolicy
import com.lxseek.chat.api.util.StreamTermination
import com.lxseek.chat.api.util.asRetryableTransportError
import com.lxseek.chat.api.util.carriesModelOutput
import com.lxseek.chat.api.util.requireValidSerializedRequest
import com.lxseek.chat.api.util.safeWireToolCallId
import com.lxseek.chat.api.util.safeWireToolName
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.TokenUsage
import com.lxseek.chat.util.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

@Serializable
internal data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = true,
    val options: JsonObject? = null,
    val tools: List<ToolDefinition>? = null
)

@Serializable
internal data class OllamaMessage(
    val role: String,
    val content: String = "",
    val thinking: String? = null,
    val images: List<String>? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null
)

@Serializable
internal data class OllamaStreamResponse(
    val model: String? = null,
    val message: OllamaMessage? = null,
    val done: Boolean = false,
    @SerialName("done_reason") val doneReason: String? = null,
    val error: String? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null
)

internal fun ollamaStreamTermination(
    sawDone: Boolean,
    doneReason: String?,
    producedContent: Boolean,
    toolCallInFlight: Boolean = false,
    streamError: GenerationError? = null,
    timedOut: Boolean = false,
): StreamTermination = StreamTermination(
    sawTerminalMarker = sawDone,
    stopReason = doneReason?.trim()?.lowercase(),
    producedContent = producedContent,
    toolCallInFlight = toolCallInFlight,
    streamError = streamError,
    timedOut = timedOut,
)

internal fun OllamaStreamResponse.toTokenUsage(): TokenUsage {
    val input = promptEvalCount?.coerceAtLeast(0)
    val output = evalCount?.coerceAtLeast(0)
    val total = when {
        input != null && output != null -> TokenUsage.addCounts(input, output)
        input != null -> input
        else -> output ?: 0
    }
    return TokenUsage(
        totalTokenCount = total,
        inputTokenCount = input,
        // Ollama reports prompt evaluation, but not a cache hit/miss split.
        cachedInputTokenCount = null,
        uncachedInputTokenCount = null,
        outputTokenCount = output,
    )
}

@Serializable
internal data class OllamaTagsResponse(
    val models: List<OllamaModelInfo>
)

@Serializable
internal data class OllamaModelInfo(
    val name: String
)

class OllamaProvider : LlmProvider {
    override val name: String = Constants.PROVIDER_OLLAMA
    override val defaultBaseUrl: String = "http://localhost:11434"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/')?.ifBlank { null }
            ?: defaultBaseUrl.ifEmpty { null }
            ?: return@flow emit(StreamEvent.Error(GenerationError.Configuration("Ollama base URL not configured")))
        val modelName = config.modelId

        val validatedPath = prepareMessages(messages, config.maxContextWindow)

        val apiMessages = mutableListOf<OllamaMessage>()
        if (!config.systemPrompt.isNullOrBlank()) {
            apiMessages.add(OllamaMessage(role = "system", content = config.systemPrompt))
        }


        apiMessages.addAll(validatedPath.flatMap { msg ->
            val entries = mutableListOf<OllamaMessage>()

            // tool_ messages: assistant turn with tool_calls (and thinking from segments)
            if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                val thinkingContent = msg.segments?.lastOrNull { it.type == "thought" }?.content
                if (!toolSegs.isNullOrEmpty()) {
                    val toolCalls = toolSegs.map { seg ->
                        val tid = seg.toolCallId ?: buildToolCallId(seg.toolName ?: "", seg.toolArgs ?: "{}")
                        val argsObj = try { json.parseToJsonElement(seg.toolArgs ?: "{}") as? JsonObject } catch (_: Exception) { JsonObject(emptyMap()) }
                        OpenAiToolCall(
                            id = tid,
                            type = "function",
                            function = OpenAiFunctionCall(name = seg.toolName ?: "", arguments = argsObj ?: JsonObject(emptyMap()))
                        )
                    }
                    entries.add(OllamaMessage(
                        role = "assistant",
                        content = "",
                        thinking = thinkingContent?.ifEmpty { null },
                        toolCalls = toolCalls
                    ))
                } else msg.toolCall?.let { tc ->
                    // 局部绑定非空 toolCall，避免多处 !! 强解
                    val toolId = tc.toolCallId ?: buildToolCallId(tc.toolName, tc.arguments)
                    val argsObj = try { json.parseToJsonElement(tc.arguments) as? JsonObject } catch (_: Exception) { JsonObject(emptyMap()) }
                    entries.add(OllamaMessage(
                        role = "assistant",
                        content = "",
                        thinking = thinkingContent?.ifEmpty { null },
                        toolCalls = listOf(OpenAiToolCall(
                            id = toolId,
                            type = "function",
                            function = OpenAiFunctionCall(name = tc.toolName, arguments = argsObj ?: JsonObject(emptyMap()))
                        ))
                    ))
                }
                return@flatMap entries
            }

            // result_ messages carry the tool result(s)
            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    for (seg in toolSegs) {
                        entries.add(OllamaMessage(
                            role = "tool",
                            content = seg.toolResult ?: "",
                            toolName = seg.toolName,
                        ))
                    }
                } else msg.toolCall?.let { tc ->
                    // 局部绑定非空 toolCall，避免多处 !! 强解
                    entries.add(OllamaMessage(
                        role = "tool",
                        content = tc.result,
                        toolName = tc.toolName,
                    ))
                }
                return@flatMap entries
            }

            val images = if (config.includeImages && msg.participant == Participant.USER) msg.images.mapNotNull { imagePath ->
                try {
                    val file = File(imagePath)
                    if (file.exists()) {
                        android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                    } else null
                } catch (e: Exception) { null }
            } else null

            // Normal message: text + images only
            entries.add(OllamaMessage(
                role = if (msg.participant == Participant.USER) "user" else "assistant",
                content = msg.text,
                images = images?.takeIf { it.isNotEmpty() }
            ))
            entries
        })

        // Generation settings previously never reached Ollama (the options field stayed null,
        // silently ignoring the user's temperature/top_p/max-tokens configuration).
        val options = buildMap<String, kotlinx.serialization.json.JsonElement> {
            config.temperature?.let { put("temperature", kotlinx.serialization.json.JsonPrimitive(it)) }
            config.topP?.let { put("top_p", kotlinx.serialization.json.JsonPrimitive(it)) }
            config.maxTokens?.let { put("num_predict", kotlinx.serialization.json.JsonPrimitive(it)) }
        }.takeIf { it.isNotEmpty() }?.let { JsonObject(it) }

        val requestBody = OllamaChatRequest(
            model = config.modelId,
            messages = apiMessages,
            stream = true,
            options = options,
            tools = config.tools
        )

        try {
            requestBody.requireValidWireFormat()
            val url = "$baseUrl/api/chat"
            val headers = mutableMapOf("Content-Type" to "application/json")
            if (config.apiKey.isNotEmpty()) {
                headers["Authorization"] = "Bearer ${config.apiKey}"
            }
            val requestBodyJson = json.encodeToString(OllamaChatRequest.serializer(), requestBody)
            requireValidSerializedRequest(
                provider = "Ollama",
                body = requestBodyJson,
                requiredStringFields = setOf("model"),
                requiredArrayFields = setOf("messages"),
            )
            DebugLog.d(
                "LxChatAPI",
                "[Ollama] request model=${config.modelId} messages=${apiMessages.size} " +
                    "tools=${config.tools?.size ?: 0}",
            )
            val maxAttempts = ProviderRetryPolicy.MAX_ATTEMPTS
            val retryableCodes = setOf(401, 429, 502, 503, 504)
            var attempt = 0
            var completed = false

            while (attempt < maxAttempts && !completed) {
                attempt++
                val handle = try {
                    HttpClient.streamPost(url, requestBodyJson, headers)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val retryable = e.asRetryableTransportError()
                    if (retryable != null && attempt < maxAttempts) {
                        emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                        delay(ProviderRetryPolicy.delayMillis(attempt))
                        continue
                    }
                    throw e
                }
                try {
                    if (handle.code == 200) {
                        val thinkParser = StreamingThinkTagParser()
                        var receivedStructuredThinking = false
                        var producedContent = false
                        var sawDone = false
                        var doneReason: String? = null
                        var timedOut = false
                        var streamError: GenerationError? = null
                        var toolCallInFlight = false

                        suspend fun emitTracked(event: StreamEvent) {
                            if (event.carriesModelOutput()) producedContent = true
                            emit(event)
                        }

                        // Tolerate long thinking pauses, but not a silently-dead connection:
                        // 3 consecutive read timeouts (~15 min without a byte) → give up.
                        var consecutiveReadTimeouts = 0
                        while (currentCoroutineContext().isActive) {
                            val line = try {
                                handle.readLine()
                            } catch (e: java.net.SocketTimeoutException) {
                                if (!currentCoroutineContext().isActive) break
                                if (++consecutiveReadTimeouts >= 3) {
                                    timedOut = true
                                    break
                                }
                                continue
                            } ?: break
                            consecutiveReadTimeouts = 0
                            try {
                                val response = json.decodeFromString<OllamaStreamResponse>(line)
                                response.error?.takeIf(String::isNotBlank)?.let { message ->
                                    streamError = GenerationError.Api(
                                        code = null,
                                        type = "ollama_stream_error",
                                        message = message,
                                    )
                                }
                                response.message?.let { msg ->
                                    // 1. Handle explicit thinking field (Ollama 0.5.4+)
                                    msg.thinking?.let { thinking ->
                                        if (thinking.isNotEmpty() && config.thinkingEnabled) {
                                            emitTracked(StreamEvent.ThoughtChunk(thinking, null))
                                            receivedStructuredThinking = true
                                        }
                                    }

                                    // 2. Ollama tool calls are complete snapshots in one NDJSON
                                    // message. Reject the whole batch if any call lacks a name or a
                                    // complete JSON-object argument payload.
                                    msg.toolCalls?.takeIf { it.isNotEmpty() }?.let { toolCalls ->
                                        val parsed = toolCalls.map { tc ->
                                            val streamKey = "call_stream_${java.util.UUID.randomUUID()}"
                                            val id = tc.id
                                                ?: "${Constants.TOOL_CALL_ID_PREFIX}${java.util.UUID.randomUUID()}"
                                            val name = tc.function?.name.orEmpty()
                                            val args = tc.function?.arguments?.let {
                                                if (it is kotlinx.serialization.json.JsonPrimitive && it.isString) {
                                                    it.content
                                                } else {
                                                    it.toString()
                                                }
                                            }.orEmpty().ifBlank { "{}" }
                                            Triple(
                                                StreamEvent.ToolCallUpdate(
                                                    streamKey = streamKey,
                                                    id = id,
                                                    name = name,
                                                    arguments = args,
                                                ),
                                                StreamEvent.ToolCallRequest(
                                                    id = id,
                                                    name = name,
                                                    arguments = args,
                                                    streamKey = streamKey,
                                                ),
                                                runCatching {
                                                    name.matches(safeWireToolName) &&
                                                        id.matches(safeWireToolCallId) &&
                                                        json.parseToJsonElement(args) is JsonObject
                                                }.getOrDefault(false),
                                            )
                                        }
                                        val callIds = parsed.map { it.second.id }
                                        if (
                                            parsed.any { !it.third } ||
                                            callIds.distinct().size != callIds.size
                                        ) {
                                            toolCallInFlight = true
                                            streamError = GenerationError.SseParse(
                                                rawLine = "tool_calls",
                                                cause = "Ollama returned incomplete tool metadata",
                                            )
                                        } else {
                                            parsed.forEach { emitTracked(it.first) }
                                            val calls = parsed.map { it.second }
                                            if (calls.size == 1) emitTracked(calls.single())
                                            else emitTracked(StreamEvent.ToolCallsRequest(calls))
                                        }
                                    }

                                    // 3. Handle content: if structured thinking was received, emit
                                    // content directly. Otherwise parse inline tags for old models.
                                    if (msg.content.isNotEmpty()) {
                                        if (receivedStructuredThinking) {
                                            emitTracked(StreamEvent.TextChunk(msg.content))
                                        } else {
                                            thinkParser.feed(
                                                content = msg.content,
                                                thinkingEnabled = config.thinkingEnabled,
                                                onText = { emitTracked(StreamEvent.TextChunk(it)) },
                                                onThought = { emitTracked(StreamEvent.ThoughtChunk(it)) },
                                            )
                                        }
                                    }
                                }
                                if (response.done) {
                                    sawDone = true
                                    doneReason = response.doneReason
                                    emit(StreamEvent.UsageUpdate(response.toTokenUsage()))
                                }
                                if (streamError != null || sawDone) break
                            } catch (e: Exception) {
                                DebugLog.e(
                                    "LxChatAPI",
                                    "[Ollama] malformed stream payload exception=${e.javaClass.simpleName}",
                                )
                                streamError = GenerationError.SseParse(
                                    rawLine = line.take(512),
                                    cause = e.localizedMessage ?: "Malformed Ollama stream payload",
                                )
                                break
                            }
                        }
                        thinkParser.flush(
                            onText = { emitTracked(StreamEvent.TextChunk(it)) },
                            onThought = { emitTracked(StreamEvent.ThoughtChunk(it)) },
                            thinkingEnabled = config.thinkingEnabled,
                        )
                        if (!currentCoroutineContext().isActive) {
                            throw kotlinx.coroutines.CancellationException("Stream cancelled")
                        }
                        val termination = ollamaStreamTermination(
                            sawDone = sawDone,
                            doneReason = doneReason,
                            producedContent = producedContent,
                            toolCallInFlight = toolCallInFlight,
                            streamError = streamError,
                            timedOut = timedOut,
                        )
                        DebugLog.d("LxChatSSE", "[Ollama] ${termination.describe()}")
                        if (termination.isRetryable && attempt < maxAttempts) {
                            emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                            delay(ProviderRetryPolicy.delayMillis(attempt))
                        } else {
                            termination.toError("Ollama")?.let { emit(StreamEvent.Error(it)) }
                            completed = true
                        }
                    } else {
                        val errorRaw = handle.errorBody ?: "Unknown error"
                        val responseBytes = errorRaw.toByteArray(Charsets.UTF_8).size
                        DebugLog.e(
                            "LxChatAPI",
                            "[Ollama] HTTP ${handle.code} responseBytes=$responseBytes",
                        )

                        if (
                            ProviderRetryPolicy.shouldRetryHttp(
                                handle.code,
                                errorRaw,
                                retryableCodes,
                            ) && attempt < maxAttempts
                        ) {
                            emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                            delay(ProviderRetryPolicy.delayMillis(attempt))
                        } else {
                            val genError = try {
                                val errorJson = json.decodeFromString<OpenAiErrorResponse>(errorRaw)
                                GenerationError.Api(code = errorJson.error.code ?: handle.code.toString(), type = errorJson.error.type, message = errorJson.error.message)
                            } catch (_: Exception) {
                                GenerationError.Network(statusCode = handle.code, message = errorRaw)
                            }
                            emit(StreamEvent.Error(genError))
                            completed = true
                        }
                    }
                } finally { handle.close() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RequestFormatException) {
            DebugLog.e("LxChatAPI", "[Ollama] blocked invalid request: ${e.violations.joinToString()}")
            emit(StreamEvent.Error(GenerationError.RequestFormat("Ollama", e.violations.joinToString())))
        } catch (e: java.net.SocketTimeoutException) {
            emit(StreamEvent.Error(GenerationError.Timeout))
        } catch (e: java.net.ConnectException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Connection refused")))
        } catch (e: java.net.UnknownHostException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Unknown host")))
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(StreamEvent.Error(GenerationError.Unknown(e)))
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val effectiveBaseUrl = baseUrl?.trimEnd('/')?.ifBlank { null } ?: "http://localhost:11434"
        val responseText = HttpClient.fetchModelsResponse("$effectiveBaseUrl/api/tags")
            .requireModelFetchBody()
        val models = decodeModelFetchResponse {
            json.decodeFromString<OllamaTagsResponse>(responseText)
                .models.map { it.name }
        }
        if (models.isEmpty()) throw ModelFetchEmptyResultException()
        models
    }
}
