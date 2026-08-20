package com.lxseek.chat.api.openai

import com.lxseek.chat.api.*

import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.api.util.StreamingThinkTagParser
import com.lxseek.chat.api.util.convertToOpenAiMessages
import com.lxseek.chat.api.util.prepareMessages
import com.lxseek.chat.api.util.RequestFormatException
import com.lxseek.chat.api.util.requireValidSerializedRequest
import com.lxseek.chat.api.util.StreamTermination
import com.lxseek.chat.api.util.asRetryableTransportError
import com.lxseek.chat.api.util.carriesModelOutput
import com.lxseek.chat.api.util.ProviderRetryPolicy
import com.lxseek.chat.api.util.safeWireToolCallId
import com.lxseek.chat.api.util.safeWireToolName
import com.lxseek.chat.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

abstract class BaseOpenAiProvider : LlmProvider {

    protected val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    // -- Override points --

    /**
     * Modify the outgoing request before serialization (e.g. add reasoning_effort, plugins).
     * The default implementation returns the request unchanged.
     */
    protected open fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest = request

    /**
     * Extra HTTP headers to include in the POST to /chat/completions.
     */
    protected open fun getExtraHeaders(config: ProviderConfig): Map<String, String> = emptyMap()

    /**
     * Transform the system prompt before it is sent. Default: pass-through.
     */
    protected open fun transformSystemPrompt(prompt: String?): String? = prompt

    /**
     * Parse the delta from one SSE event and emit TextChunk / ThoughtChunk events.
     * The base class handles tool_calls accumulation, finish_reason emission, and usage
     * emission automatically.
     *
     * The default implementation covers the common OpenAI-compatible shape: a separate
     * `reasoning_content` field (gated on [ProviderConfig.thinkingEnabled]) plus `content`.
     * Providers with a different reasoning representation (e.g. OpenRouter's
     * `reasoning_details`) override this.
     */
    protected open suspend fun parseDeltaContent(
        delta: OpenAiDelta,
        config: ProviderConfig,
        thinkParser: StreamingThinkTagParser,
        emit: suspend (StreamEvent) -> Unit
    ) {
        // reasoning_content is the vLLM/DeepSeek-compatible field; `reasoning` is the bare-string
        // form many relays emit instead. Take whichever the endpoint actually populated.
        (delta.reasoningContent ?: delta.reasoning)?.let { reasoning ->
            if (reasoning.isNotEmpty() && config.thinkingEnabled) {
                emit(StreamEvent.ThoughtChunk(reasoning))
            }
        }
        delta.content?.let { content ->
            if (content.isNotEmpty()) {
                if (parseInlineThinkTags) {
                    thinkParser.feed(
                        content = content,
                        thinkingEnabled = config.thinkingEnabled,
                        onText = { emit(StreamEvent.TextChunk(it)) },
                        onThought = { emit(StreamEvent.ThoughtChunk(it)) }
                    )
                } else {
                    emit(StreamEvent.TextChunk(content))
                }
            }
        }
    }

    /**
     * When true, inline `<think>…</think>` in the content stream is parsed into thought chunks.
     * Enabled only for self-hosted OpenAI-compatible servers (llama.cpp/vLLM render reasoning
     * inline via the chat template); official cloud endpoints keep content literal so an answer
     * that MENTIONS a think tag is never misclassified.
     */
    protected open val parseInlineThinkTags: Boolean = false

    protected open val retryableStatusCodes: Set<Int> = setOf(429, 502, 503, 504)

    protected open val retryMissingV1BaseUrl: Boolean = false

    /**
     * OpenAI normally follows `finish_reason` with an optional usage-only event and `[DONE]`.
     * Compatible gateways sometimes omit `[DONE]` or keep the HTTP connection alive. Once the
     * semantic terminal event arrives, accept that tail only for this bounded window.
     */
    protected open val terminalSseGraceMillis: Long = 1_000L

    // -- Template method --

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val endpointUrls = endpointCandidates(baseUrl, "chat/completions")

        val validatedMessages = prepareMessages(messages, config.maxContextWindow)

        val apiMessages = convertToOpenAiMessages(
            messages = validatedMessages,
            systemPrompt = transformSystemPrompt(config.systemPrompt),
            includeImages = config.includeImages
        )

        var request = OpenAiChatRequest(
            model = config.modelId,
            messages = apiMessages,
            stream = true,
            streamOptions = OpenAiStreamOptions(includeUsage = true),
            tools = config.tools,
            serviceTier = config.openAiServiceTier,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            topP = config.topP,
            frequencyPenalty = config.frequencyPenalty,
            presencePenalty = config.presencePenalty
        )
        request = customizeRequest(request, config)

        try {
            request.requireValidWireFormat(name)
            val requestBodyJson = json.encodeToString(OpenAiChatRequest.serializer(), request)
            requireValidSerializedRequest(
                provider = name,
                body = requestBodyJson,
                requiredStringFields = setOf("model"),
                requiredArrayFields = setOf("messages"),
            )
            DebugLog.d(
                "LxChatAPI",
                "[$name] request model=${config.modelId} messages=${apiMessages.size} " +
                    "tools=${config.tools?.size ?: 0}",
            )

            val headers = mutableMapOf("Content-Type" to "application/json")
            if (config.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${config.apiKey}"
            for ((key, value) in getExtraHeaders(config)) headers[key] = value

            val maxAttempts = ProviderRetryPolicy.MAX_ATTEMPTS
            var attempt = 0
            var finished = false

            while (attempt < maxAttempts && !finished) {
                attempt++
                var endpointIndex = 0
                var retryScheduled = false

                while (endpointIndex < endpointUrls.size && !finished && !retryScheduled) {
                    val endpointUrl = endpointUrls[endpointIndex]
                    // Opening the request can fail before any response headers exist (connect
                    // timeout, TLS failure, reset). Those escaped the retry loop entirely before,
                    // so a single flaky connection became a hard failure. Nothing has streamed at
                    // this point, so replaying is always safe.
                    val handle = try {
                        HttpClient.streamPost(endpointUrl, requestBodyJson, headers)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val retryable = e.asRetryableTransportError()
                        if (retryable != null && attempt < maxAttempts) {
                            val retryDelayMs = ProviderRetryPolicy.delayMillis(attempt)
                            DebugLog.w(
                                "LxChatAPI",
                                "[$name] Transport failure opening stream on attempt " +
                                    "$attempt/$maxAttempts (${e.javaClass.simpleName}), " +
                                    "retrying in ${retryDelayMs}ms",
                            )
                            emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                            delay(retryDelayMs)
                            retryScheduled = true
                            continue
                        }
                        throw e
                    }
                    try {
                        if (handle.code == 200) {
                            // HTTP 200 only proves the request was accepted. Whether the MESSAGE
                            // completed is decided from semantic markers below, so a relay that
                            // cuts the stream at a content-block boundary can no longer pass as a
                            // finished answer.
                            val termination =
                                consumeSuccessfulStream(handle, config) { emit(it) }
                            DebugLog.d(
                                "LxChatSSE",
                                "[$name] stream_end ${termination.describe()} " +
                                    "attempt=$attempt/$maxAttempts",
                            )
                            if (termination.isRetryable && attempt < maxAttempts) {
                                // Nothing was surfaced yet, so a replay cannot duplicate output.
                                DebugLog.w(
                                    "LxChatAPI",
                                    "[$name] Incomplete stream on attempt $attempt/$maxAttempts, retrying",
                                )
                                val retryDelayMs = ProviderRetryPolicy.delayMillis(attempt)
                                emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                                delay(retryDelayMs)
                                retryScheduled = true
                            } else {
                                termination.toError(name)?.let { emit(StreamEvent.Error(it)) }
                                finished = true
                            }
                        } else {
                            val errorRaw = handle.errorBody ?: "Unknown error"
                            val hasV1Fallback = endpointIndex + 1 < endpointUrls.size
                            if (hasV1Fallback) {
                                DebugLog.w(
                                    "LxChatAPI",
                                    "[$name] HTTP ${handle.code}; trying endpoint candidate " +
                                        "${endpointIndex + 2}/${endpointUrls.size}",
                                )
                                endpointIndex++
                                continue
                            }

                            val responseBytes = errorRaw.toByteArray(Charsets.UTF_8).size
                            DebugLog.e(
                                "LxChatAPI",
                                "[$name] HTTP ${handle.code} responseBytes=$responseBytes",
                            )

                            if (
                                ProviderRetryPolicy.shouldRetryHttp(
                                    handle.code,
                                    errorRaw,
                                    retryableStatusCodes,
                                ) && attempt < maxAttempts
                            ) {
                                val retryDelayMs = ProviderRetryPolicy.delayMillis(attempt)
                                DebugLog.w("LxChatAPI", "[$name] Transient error ${handle.code} on attempt $attempt/$maxAttempts, retrying in ${retryDelayMs}ms...")
                                emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                                delay(retryDelayMs)
                                retryScheduled = true
                            } else {
                                emit(StreamEvent.Error(buildGenerationError(handle.code, errorRaw, endpointUrls)))
                                finished = true
                            }
                        }
                    } finally {
                        handle.close()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RequestFormatException) {
            DebugLog.e("LxChatAPI", "[$name] blocked invalid request: ${e.violations.joinToString()}")
            emit(StreamEvent.Error(GenerationError.RequestFormat(name, e.violations.joinToString())))
        } catch (e: SocketTimeoutException) {
            emit(StreamEvent.Error(GenerationError.Timeout))
        } catch (e: ConnectException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Connection refused")))
        } catch (e: UnknownHostException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Unknown host")))
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(StreamEvent.Error(GenerationError.Unknown(e)))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Consume one 200 SSE stream and report HOW it ended.
     *
     * The returned [StreamTermination] is the only evidence that the message actually completed:
     * socket EOF is produced identically by a clean finish and by a relay cutting the connection
     * between content blocks. Fatal errors the consumer itself raises are flagged so the caller
     * does not report a second, contradictory diagnostic.
     */
    private suspend fun consumeSuccessfulStream(
        handle: HttpClient.StreamHandle,
        config: ProviderConfig,
        emit: suspend (StreamEvent) -> Unit
    ): StreamTermination {
        // Parser state belongs to one transport attempt. An empty/incomplete response may be
        // replayed, and carrying a partial `<think` prefix into the next attempt would corrupt the
        // successful retry even though no output from the failed attempt was surfaced.
        val thinkParser = StreamingThinkTagParser()
        val pendingToolCalls = mutableMapOf<Int, PendingToolCall>()
        // Accumulate answer content so that, if the server emits tool calls as content text rather
        // than as structured delta.tool_calls (#33 path B), we can recover them at stream end.
        val contentBuf = StringBuilder()
        var producedContent = false
        var reportedError = false
        var streamError: GenerationError? = null
        var finishReason: String? = null
        var sawDone = false
        var timedOut = false
        val emitTracked: suspend (StreamEvent) -> Unit = { event ->
            if (event.carriesModelOutput()) producedContent = true
            if (event is StreamEvent.Error) reportedError = true
            emit(event)
        }
        val emitAndAccumulate: suspend (StreamEvent) -> Unit = { event ->
            if (event is StreamEvent.TextChunk) contentBuf.append(event.text)
            emitTracked(event)
        }
        var structuredToolCallsEmitted = false
        var textToolCallsEmitted = false
        val textToolParser = config.tools
            ?.takeIf { it.isNotEmpty() }
            ?.let { StreamingTextToolCallParser() }
        var terminalDeadlineNanos: Long? = null

        suspend fun emitPendingStructuredToolCalls() {
            if (pendingToolCalls.isEmpty()) return
            val pending = pendingToolCalls.values.toList()
            pendingToolCalls.clear()
            val incomplete = pending.firstOrNull { candidate ->
                val callId = candidate.id.ifBlank { candidate.streamKey }
                !callId.matches(safeWireToolCallId) ||
                    !candidate.name.matches(safeWireToolName) || runCatching {
                    json.parseToJsonElement(candidate.args.toString().ifBlank { "{}" }) is
                        kotlinx.serialization.json.JsonObject
                }.getOrDefault(false).not()
            }
            val callIds = pending.map { candidate ->
                candidate.id.ifBlank { candidate.streamKey }
            }
            if (incomplete != null || callIds.distinct().size != callIds.size) {
                emitTracked(
                    StreamEvent.Error(
                        GenerationError.SseParse(
                            rawLine = "tool_calls",
                            cause = when {
                                callIds.distinct().size != callIds.size ->
                                    "Provider returned duplicate tool call ids"
                                incomplete == null -> "Provider returned incomplete tool metadata"
                                !incomplete.name.matches(safeWireToolName) ->
                                    "Provider ended before the tool name was complete"
                                !incomplete.id.ifBlank { incomplete.streamKey }
                                    .matches(safeWireToolCallId) ->
                                    "Provider returned an invalid tool call id"
                                else ->
                                    "Provider ended before the tool arguments formed a complete JSON object"
                            },
                        )
                    )
                )
                return
            }
            val calls = pending.map {
                    StreamEvent.ToolCallRequest(
                        id = it.id.ifBlank { it.streamKey },
                        name = it.name,
                        arguments = it.args.toString().ifBlank { "{}" },
                        streamKey = it.streamKey,
                    )
                }
            structuredToolCallsEmitted = true
            if (calls.size == 1) emitTracked(calls.first())
            else emitTracked(StreamEvent.ToolCallsRequest(calls))
        }

        suspend fun emitFilteredText(text: String) {
            parseDeltaContent(
                delta = OpenAiDelta(content = text),
                config = config,
                thinkParser = thinkParser,
                emit = emitAndAccumulate,
            )
        }

        suspend fun emitTextToolUpdate(snapshot: StreamingTextToolCallParser.Snapshot) {
            emitTracked(
                StreamEvent.ToolCallUpdate(
                    streamKey = snapshot.streamKey,
                    id = null,
                    name = snapshot.name,
                    arguments = snapshot.arguments,
                )
            )
        }

        suspend fun emitCompletedTextTool(call: StreamingTextToolCallParser.CompletedCall) {
            textToolCallsEmitted = true
            emitTracked(
                StreamEvent.ToolCallRequest(
                    id = syntheticToolCallId(),
                    name = call.name,
                    arguments = call.arguments,
                    streamKey = call.streamKey,
                )
            )
        }

        suspend fun emitMalformedTextTool(cause: String) {
            emitTracked(
                StreamEvent.Error(
                    GenerationError.SseParse(
                        rawLine = "tool_call",
                        cause = cause,
                    )
                )
            )
        }

        suspend fun parseDeltaWithStreamingTextTools(delta: OpenAiDelta) {
            val content = delta.content
            if (textToolParser == null || content.isNullOrEmpty()) {
                parseDeltaContent(delta, config, thinkParser, emitAndAccumulate)
                return
            }

            // Emit provider-specific reasoning fields once, then route only content through the
            // text-tool parser so split tags never flash as answer text.
            parseDeltaContent(
                delta.copy(content = null),
                config,
                thinkParser,
                emitAndAccumulate,
            )
            textToolParser.feed(
                content = content,
                onText = { emitFilteredText(it) },
                onUpdate = { emitTextToolUpdate(it) },
                onComplete = { emitCompletedTextTool(it) },
                onMalformed = { emitMalformedTextTool(it) },
            )
        }

        // Read timeouts are tolerated for long thinking pauses (read timeout = 5 min), but a
        // silently-dead connection (NAT drop with no RST) must not hang forever: give up after
        // 3 consecutive timeouts (~15 min without a single byte).
        var consecutiveReadTimeouts = 0
        while (currentCoroutineContext().isActive) {
            terminalDeadlineNanos?.let { deadline ->
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) break
                val remainingMillis =
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                        .coerceAtLeast(1L)
                handle.setReadTimeoutMillis(remainingMillis)
            }
            val line = try {
                handle.readLine()
            } catch (e: SocketTimeoutException) {
                if (!currentCoroutineContext().isActive) break
                // A semantic terminal event already completed the model response. This timeout
                // only closes its optional usage/[DONE] grace tail and is therefore success.
                if (terminalDeadlineNanos != null) break
                if (++consecutiveReadTimeouts >= 3) {
                    timedOut = true
                    break
                }
                continue
            } ?: break
            consecutiveReadTimeouts = 0

            if (!line.startsWith("data: ")) continue
            val jsonStr = line.substring(6).trim()
            if (jsonStr == "[DONE]") {
                sawDone = true
                emitPendingStructuredToolCalls()
                break
            }

            try {
                val response = json.decodeFromString<OpenAiStreamResponse>(jsonStr)

                // A relay signals a mid-stream failure as a bare {"error":{...}} chunk on a 200
                // response. Previously ignoreUnknownKeys discarded it and the generation just
                // stopped with no diagnostic at all.
                response.error?.let { error ->
                    streamError = GenerationError.Api(
                        code = error.code,
                        type = error.type,
                        message = error.message.ifBlank {
                            "Provider reported an error in the response stream"
                        },
                    )
                }
                if (
                    streamError == null &&
                    ProviderRetryPolicy.isFailedToGenerateOutcome(response.outcome)
                ) {
                    streamError = GenerationError.Api(
                        code = null,
                        type = "failed_to_generate",
                        message = response.outcome.orEmpty(),
                    )
                }

                val choice = response.choices?.firstOrNull()

                choice?.delta?.let { delta ->
                    parseDeltaWithStreamingTextTools(delta)

                    delta.toolCalls?.forEach { tc ->
                        val existingEntry = tc.id?.let { id ->
                            pendingToolCalls.entries.firstOrNull { it.value.id == id }
                        }
                        val index = tc.index
                            ?: existingEntry?.key
                            ?: pendingToolCalls.keys.singleOrNull()
                            ?: pendingToolCalls.size
                        val pending = pendingToolCalls.getOrPut(index) { PendingToolCall() }
                        if (tc.id != null) pending.id = tc.id
                        tc.function?.name?.let { if (it.isNotEmpty()) pending.name = it }
                        tc.function?.arguments?.let {
                            // Snapshot-tolerant: a relay that resends the whole argument string in
                            // every delta must not produce `{"a":1}{"a":1}`, and an empty
                            // placeholder delta must not erase what was already accumulated.
                            pending.args.append(
                                if (it is JsonPrimitive) it.content else it.toString()
                            )
                        }
                        emitTracked(
                            StreamEvent.ToolCallUpdate(
                                streamKey = pending.streamKey,
                                id = pending.id.ifBlank { null },
                                name = pending.name,
                                arguments = pending.args.toString(),
                            )
                        )
                    }
                }

                choice?.finishReason?.takeIf(String::isNotBlank)?.let {
                    finishReason = it.lowercase()
                }

                if (!choice?.finishReason.isNullOrBlank()) {
                    // Several OpenAI-compatible gateways return "stop" (or close directly)
                    // after streaming a perfectly valid structured tool call. The accumulated
                    // call is authoritative; never discard it based on the terminal spelling.
                    emitPendingStructuredToolCalls()
                }

                response.usage?.let { usage ->
                    emitTracked(StreamEvent.UsageUpdate(usage.toTokenUsage()))
                }

                if (streamError != null) break

                if (!choice?.finishReason.isNullOrBlank() && terminalDeadlineNanos == null) {
                    terminalDeadlineNanos =
                        System.nanoTime() +
                            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                                terminalSseGraceMillis.coerceAtLeast(1L)
                            )
                }
            } catch (e: Exception) {
                DebugLog.e(
                    "LxChatAPI",
                    "[$name] malformed stream payload exception=${e.javaClass.simpleName}",
                )
                streamError = GenerationError.SseParse(
                    rawLine = jsonStr.take(512),
                    cause = e.localizedMessage ?: "Malformed SSE payload",
                )
                break
            }
        }

        // Do not promote an open structured call at transport EOF. Only [DONE] or finish_reason
        // can prove that its metadata is complete; leaving it pending makes the termination error
        // carry toolCallInFlight=true and prevents execution of a truncated invocation.

        textToolParser?.flush(
            onText = { emitFilteredText(it) },
            onUpdate = { emitTextToolUpdate(it) },
            onComplete = { emitCompletedTextTool(it) },
            onMalformed = { emitMalformedTextTool(it) },
        )

        thinkParser.flush(
            onText = { emitAndAccumulate(StreamEvent.TextChunk(it)) },
            onThought = { emitAndAccumulate(StreamEvent.ThoughtChunk(it)) },
            thinkingEnabled = config.thinkingEnabled
        )

        // Fallback (#33 path B): some OpenAI-compatible servers (llama.cpp et al.) finish with
        // finish_reason == "stop" and put the tool call in the content text instead of the
        // structured delta.tool_calls field. If we never saw structured tool calls but tools were
        // offered, parse them out of the accumulated content so the generation enters the
        // tool-call phase instead of just printing the JSON as an answer. Brings these servers to
        // parity with Ollama, which reads its structured tool_calls field directly.
        if (
            !structuredToolCallsEmitted &&
            !textToolCallsEmitted &&
            !config.tools.isNullOrEmpty()
        ) {
            val parsed = ToolCallTextParser.parse(contentBuf.toString())
            if (parsed.size == 1) {
                emitTracked(StreamEvent.ToolCallRequest(syntheticToolCallId(), parsed[0].name, parsed[0].arguments))
            } else if (parsed.size > 1) {
                emitTracked(StreamEvent.ToolCallsRequest(parsed.map {
                    StreamEvent.ToolCallRequest(syntheticToolCallId(), it.name, it.arguments)
                }))
            }
        }

        if (!currentCoroutineContext().isActive) {
            throw CancellationException("Stream cancelled")
        }

        return StreamTermination(
            // Either signal proves the message ended semantically. Many gateways omit one of them,
            // so requiring both would report false truncations.
            sawTerminalMarker = sawDone || finishReason != null,
            stopReason = finishReason,
            producedContent = producedContent,
            toolCallInFlight = pendingToolCalls.isNotEmpty(),
            streamError = streamError,
            alreadyReportedError = reportedError,
            timedOut = timedOut,
        )
    }

    private fun endpointCandidates(baseUrl: String, path: String): List<String> {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        val primary = "$normalizedBaseUrl/$cleanPath"
        if (!retryMissingV1BaseUrl || normalizedBaseUrl.isBlank() ||
            BaseUrlResolver.hasVersionSegment(normalizedBaseUrl)
        ) {
            return listOf(primary)
        }
        return listOf(primary, "$normalizedBaseUrl/v1/$cleanPath")
    }

    /** Synthetic id for tool calls recovered from content text (#33 path B), where the server
     *  provides no id. Unique per call so the result can still be paired back to the request. */
    private fun syntheticToolCallId(): String =
        "call_text_${java.util.UUID.randomUUID()}"

    private fun buildGenerationError(
        statusCode: Int,
        errorRaw: String,
        endpointUrls: List<String>
    ): GenerationError {
        val endpointHint = if (statusCode == 404 && endpointUrls.size > 1) {
            "\nTried ${endpointUrls.joinToString(" and ")}. OpenAI-compatible servers often require a /v1 Base URL."
        } else {
            ""
        }
        return try {
            val errorJson = json.decodeFromString<OpenAiErrorResponse>(errorRaw)
            GenerationError.Api(
                code = errorJson.error.code ?: statusCode.toString(),
                type = errorJson.error.type,
                message = errorJson.error.message + endpointHint
            )
        } catch (_: Exception) {
            GenerationError.Network(statusCode = statusCode, message = errorRaw + endpointHint)
        }
    }

    private fun authHeaders(apiKey: String): Map<String, String> =
        if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")

    private fun fetchModelPages(
        endpointUrl: String,
        headers: Map<String, String>,
    ): List<String> {
        val modelIds = linkedSetOf<String>()
        val seenCursors = mutableSetOf<String>()
        var pageUrl = endpointUrl

        repeat(MAX_MODEL_LIST_PAGES) { pageIndex ->
            val page = try {
                val responseText = HttpClient.fetchModelsResponse(pageUrl, headers)
                    .requireModelFetchBody()
                decodeModelFetchResponse {
                    json.decodeFromString<OpenAiModelListResponse>(responseText)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (pageIndex == 0) throw error
                DebugLog.w(
                    "LxChatAPI",
                    "Stopped paginating $name models after $pageIndex completed pages; " +
                        "returning ${modelIds.size} models",
                )
                return modelIds.sorted()
            }

            modelIds += page.data.map { it.id }
            if (!page.hasMore) return modelIds.sorted()

            val cursor = page.lastId?.takeIf(String::isNotBlank)
                ?: page.data.lastOrNull()?.id?.takeIf(String::isNotBlank)
            if (cursor == null) {
                DebugLog.w(
                    "LxChatAPI",
                    "$name model list reported has_more without a usable cursor; " +
                        "returning ${modelIds.size} models",
                )
                return modelIds.sorted()
            }
            if (!seenCursors.add(cursor)) {
                DebugLog.w(
                    "LxChatAPI",
                    "$name model list repeated a cursor; " +
                        "returning ${modelIds.size} models",
                )
                return modelIds.sorted()
            }

            pageUrl = endpointUrl.toHttpUrl()
                .newBuilder()
                .addQueryParameter("after", cursor)
                .build()
                .toString()
        }

        DebugLog.w(
            "LxChatAPI",
            "$name model list exceeded $MAX_MODEL_LIST_PAGES pages; " +
                "returning ${modelIds.size} models",
        )
        return modelIds.sorted()
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = withContext(Dispatchers.IO) {
        val effectiveBaseUrl = baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val endpointUrls = endpointCandidates(effectiveBaseUrl, "models")
        val headers = authHeaders(apiKey)
        var lastFailure: Exception? = null

        for ((index, endpointUrl) in endpointUrls.withIndex()) {
            try {
                val models = fetchModelPages(endpointUrl, headers)
                if (models.isEmpty()) throw ModelFetchEmptyResultException()
                return@withContext models
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastFailure = error
                if (index < endpointUrls.lastIndex) {
                    DebugLog.w(
                        "LxChatAPI",
                        "Failed to fetch $name models; trying endpoint candidate " +
                            "${index + 2}/${endpointUrls.size}",
                    )
                }
            }
        }

        val failure = lastFailure ?: ModelFetchEmptyResultException()
        DebugLog.e(
            "LxChatAPI",
            "Failed to fetch $name models exception=${failure.javaClass.simpleName}",
        )
        throw failure
    }

    private companion object {
        const val MAX_MODEL_LIST_PAGES = 50
    }
}
