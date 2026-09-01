package com.lxseek.chat.api.gemini

import com.lxseek.chat.api.*

import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.ThinkingLevels
import com.lxseek.chat.api.util.prepareMessages
import com.lxseek.chat.api.util.adaptToolRoundsForProvider
import com.lxseek.chat.api.util.requireValidSerializedRequest
import com.lxseek.chat.api.util.ProviderRetryPolicy
import com.lxseek.chat.api.util.StreamTermination
import com.lxseek.chat.api.util.asRetryableTransportError
import com.lxseek.chat.api.util.carriesModelOutput
import com.lxseek.chat.api.util.emitTransportError
import com.lxseek.chat.api.util.safeWireToolName
import com.lxseek.chat.api.util.safeWireToolCallId
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

// Gemini thought summaries carry their headline as **bold** or a markdown heading.
// Hoisted to file level: extraction runs on every streamed thought chunk, and Regex
// construction (Pattern.compile) is far too expensive to repeat per chunk.
private val THOUGHT_TITLE_BOLD = Regex("\\*\\*(.*?)\\*\\*")
private val THOUGHT_TITLE_HEADING = Regex("(?m)^#+\\s*(.*)$")

private fun extractThoughtTitle(content: String): String? =
    THOUGHT_TITLE_BOLD.find(content)?.groupValues?.get(1)
        ?: THOUGHT_TITLE_HEADING.find(content)?.groupValues?.get(1)

private fun ChatMessage.isGeminiToolRoundCompatible(
    targetModel: String,
    signatureRequired: Boolean,
): Boolean {
    val calls = segments
        ?.filter { it.type == "tool" }
        .orEmpty()
        .ifEmpty {
            toolCall?.let { call ->
                listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = call.toolName,
                        toolArgs = call.arguments,
                        toolCallId = call.toolCallId,
                        signature = call.signature,
                    )
                )
            }.orEmpty()
        }
    if (calls.isEmpty()) return false
    return calls.all { call ->
        if (signatureRequired && call.signature.isNullOrBlank()) return@all false
        if (call.signature.isNullOrBlank()) return@all true
        call.signatureProvider?.let { provider ->
            return@all provider.equals(Constants.PROVIDER_GOOGLE, ignoreCase = true)
        }
        modelName == null ||
            modelName.equals(targetModel, ignoreCase = true) ||
            modelName.contains("gemini", ignoreCase = true)
    }
}

@Serializable
internal data class ApiGenerateContentRequest(
    val contents: List<ApiRequestContent>,
    @SerialName("system_instruction") val systemInstruction: ApiRequestContent? = null,
    val tools: List<ApiTool>? = null,
    @SerialName("toolConfig") val toolConfig: ApiToolConfig? = null,
    @SerialName("generationConfig") val generationConfig: ApiGenerationConfig? = null
)

@Serializable
internal data class ApiToolConfig(
    @SerialName("includeServerSideToolInvocations") val includeServerSideToolInvocations: Boolean = false
)

@Serializable
internal data class ApiGenerationConfig(
    @SerialName("thinkingConfig") val thinkingConfig: ApiThinkingConfig? = null,
    val temperature: Float? = null,
    @SerialName("maxOutputTokens") val maxOutputTokens: Int? = null,
    @SerialName("topP") val topP: Float? = null,
    @SerialName("frequencyPenalty") val frequencyPenalty: Float? = null,
    @SerialName("presencePenalty") val presencePenalty: Float? = null
)

@Serializable
internal data class ApiThinkingConfig(
    @SerialName("includeThoughts") val includeThoughts: Boolean,
    @SerialName("thinkingLevel") val thinkingLevel: String? = null,
    @SerialName("thinkingBudget") val thinkingBudget: Int? = null
)

@Serializable
internal data class ApiTool(
    @SerialName("code_execution") val codeExecution: JsonObject? = null,
    @SerialName("google_search") val googleSearch: JsonObject? = null,
    @SerialName("function_declarations") val functionDeclarations: List<GeminiFunctionDeclaration>? = null
)

@Serializable
internal data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: JsonObject? = null
)

@Serializable
internal data class ApiRequestContent(val role: String? = null, val parts: List<ApiRequestPart>)

@Serializable
internal data class ApiInlineData(val mimeType: String, val data: String)

@Serializable
internal data class ApiRequestPart(
    val text: String? = null,
    val inlineData: ApiInlineData? = null,
    val thought: String? = null,
    @SerialName("thoughtSignature") val thoughtSignature: String? = null,
    @SerialName("functionCall") val functionCall: GeminiFunctionCall? = null,
    @SerialName("functionResponse") val functionResponse: GeminiFunctionResponse? = null
)

@Serializable
internal data class GeminiFunctionResponse(
    val id: String? = null,
    val name: String,
    val response: JsonObject
)

@Serializable
internal data class ApiResponseContent(val role: String? = null, val parts: List<ApiResponsePart>)

@Serializable
internal data class ApiResponsePart(
    val text: String? = null,
    val thought: JsonElement? = null,
    @SerialName("thoughtSignature") val thoughtSignature: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("executable_code") val executableCode: ApiExecutableCode? = null,
    @SerialName("code_execution_result") val codeExecutionResult: ApiCodeExecutionResult? = null,
    @SerialName("functionCall") val functionCall: GeminiFunctionCall? = null
)

@Serializable
internal data class GeminiFunctionCall(
    val id: String? = null,
    val name: String,
    val args: JsonObject? = null,
    @SerialName("thought_signature") val thoughtSignature: String? = null
)

@Serializable
internal data class ApiExecutableCode(val language: String, val code: String)

@Serializable
internal data class ApiCodeExecutionResult(val outcome: String, val output: String)

@Serializable
internal data class ApiStreamResponse(
    val candidates: List<ApiCandidate>? = null,
    @SerialName("usageMetadata") val usageMetadata: ApiUsageMetadata? = null,
    val error: ApiError? = null,
    val outcome: String? = null,
)

@Serializable
internal data class ApiCandidate(
    val content: ApiResponseContent? = null,
    @SerialName("finishReason") val finishReason: String? = null,
)

@Serializable
internal data class ApiUsageMetadata(
    val promptTokenCount: Int? = null,
    val cachedContentTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    val totalTokenCount: Int? = null,
    val thoughtsTokenCount: Int? = null
)

internal fun ApiUsageMetadata.toTokenUsage(): TokenUsage {
    val input = promptTokenCount?.coerceAtLeast(0)
    val cached = cachedContentTokenCount?.coerceAtLeast(0)
    val uncached = if (input != null && cached != null) {
        (input - cached).coerceAtLeast(0)
    } else {
        null
    }
    val visibleOutput = candidatesTokenCount?.coerceAtLeast(0)
    val reasoning = thoughtsTokenCount?.coerceAtLeast(0)
    val output = when {
        visibleOutput != null && reasoning != null ->
            TokenUsage.addCounts(visibleOutput, reasoning)
        visibleOutput != null -> visibleOutput
        reasoning != null -> reasoning
        else -> null
    }
    val derivedTotal = when {
        input != null && output != null -> TokenUsage.addCounts(input, output)
        input != null -> input
        else -> output ?: 0
    }
    return TokenUsage(
        totalTokenCount = (totalTokenCount ?: derivedTotal).coerceAtLeast(0),
        inputTokenCount = input,
        cachedInputTokenCount = cached,
        uncachedInputTokenCount = uncached,
        outputTokenCount = output,
        reasoningTokenCount = reasoning,
    )
}

@Serializable
internal data class ApiErrorResponse(val error: ApiError)

@Serializable
internal data class ApiError(val code: Int? = null, val message: String? = null, val status: String? = null)

@Serializable
internal data class ModelListResponse(val models: List<ModelInfo>)

@Serializable
internal data class ModelInfo(val name: String, val displayName: String, val supportedGenerationMethods: List<String>)

internal fun normalizeGeminiFinishReason(raw: String): String = when (
    raw.trim().lowercase().replace('-', '_')
) {
    "max_tokens", "max_output_tokens" -> "max_output_tokens"
    else -> raw.trim().lowercase().replace('-', '_')
}

internal fun geminiStreamTermination(
    sawDone: Boolean,
    finishReason: String?,
    producedContent: Boolean,
    streamError: GenerationError? = null,
    timedOut: Boolean = false,
    toolCallInFlight: Boolean = false,
): StreamTermination = StreamTermination(
    sawTerminalMarker = sawDone || finishReason != null,
    stopReason = finishReason,
    producedContent = producedContent,
    toolCallInFlight = toolCallInFlight,
    streamError = streamError,
    timedOut = timedOut,
)

class GeminiProvider(
    override val name: String = Constants.PROVIDER_GOOGLE,
    override val defaultBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : LlmProvider {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val cleanModelName = config.modelId.removePrefix("models/")
        
        // Context windowing
        val canonicalPath = prepareMessages(messages, config.maxContextWindow)
        val requiresFunctionCallSignature =
            cleanModelName.contains("gemini-3", ignoreCase = true) ||
                cleanModelName.contains("gemini-3.5", ignoreCase = true)
        val validatedPath = adaptToolRoundsForProvider(
            messages = canonicalPath,
            providerName = "Gemini",
        ) { toolMessage ->
            toolMessage.isGeminiToolRoundCompatible(
                targetModel = cleanModelName,
                signatureRequired = requiresFunctionCallSignature,
            )
        }

        val apiContents = coalesceGeminiContents(validatedPath.flatMap { msg ->
            val entries = mutableListOf<ApiRequestContent>()

            // tool_ messages: model turn with functionCall(s)
            // Note: Gemini 3 requires thought to be boolean in requests, so we omit thought strings
            // and only include thoughtSignature on the functionCall part
            if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    val parts = toolSegs.map { seg ->
                        val args = try {
                            json.parseToJsonElement(seg.toolArgs ?: "{}") as? JsonObject
                        } catch (_: Exception) { JsonObject(emptyMap()) }
                        ApiRequestPart(
                            functionCall = GeminiFunctionCall(
                                id = seg.toolCallId,
                                name = seg.toolName ?: "",
                                args = args ?: JsonObject(emptyMap())
                            ),
                            thoughtSignature = seg.signature
                        )
                    }
                    entries.add(ApiRequestContent(role = "model", parts = parts))
                } else msg.toolCall?.let { tc ->
                    // 局部绑定非空 toolCall，避免多处 !! 强解
                    val args = try {
                        json.parseToJsonElement(tc.arguments) as? JsonObject
                    } catch (_: Exception) { JsonObject(emptyMap()) }
                    entries.add(ApiRequestContent(
                        role = "model",
                        parts = listOf(ApiRequestPart(
                            functionCall = GeminiFunctionCall(
                                id = tc.toolCallId,
                                name = tc.toolName,
                                args = args ?: JsonObject(emptyMap())
                            ),
                            thoughtSignature = tc.signature
                        ))
                    ))
                }
                return@flatMap entries
            }

            // result_ messages carry the function response(s)
            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    val parts = toolSegs.map { seg ->
                        val response = buildGeminiFunctionResponse(seg.toolResult ?: "{}")
                        ApiRequestPart(functionResponse = GeminiFunctionResponse(
                            id = seg.toolCallId,
                            name = seg.toolName ?: "",
                            response = response
                        ))
                    }
                    entries.add(ApiRequestContent(role = "user", parts = parts))
                } else msg.toolCall?.let { tc ->
                    // 局部绑定非空 toolCall，避免多处 !! 强解
                    val response = buildGeminiFunctionResponse(tc.result)
                    entries.add(ApiRequestContent(
                        role = "user",
                        parts = listOf(ApiRequestPart(functionResponse = GeminiFunctionResponse(
                            id = tc.toolCallId,
                            name = tc.toolName,
                            response = response
                        )))
                    ))
                }
                return@flatMap entries
            }

            // Normal message: text + images only
            val parts = mutableListOf<ApiRequestPart>()
            if (msg.text.isNotEmpty()) {
                parts.add(ApiRequestPart(text = msg.text))
            }
            if (config.includeImages && msg.participant == Participant.USER) for (imagePath in msg.images) {
                try {
                    val file = File(imagePath)
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        parts.add(ApiRequestPart(inlineData = ApiInlineData(mimeType = com.lxseek.chat.api.util.imageMimeType(imagePath), data = base64)))
                    }
                } catch (e: Exception) {
                    DebugLog.e(
                        "LxChatAPI",
                        "[Gemini] failed to encode image exception=${e.javaClass.simpleName}",
                    )
                }
            }
            if (parts.isEmpty()) parts.add(ApiRequestPart(text = "[Attachment unavailable]"))
            entries.add(ApiRequestContent(
                role = if (msg.participant == Participant.USER) "user" else "model",
                parts = parts
            ))

            entries
        })

        val systemInstruction = if (!config.systemPrompt.isNullOrBlank()) {
            ApiRequestContent(parts = listOf(ApiRequestPart(text = config.systemPrompt)))
        } else null

        val tools = mutableListOf<ApiTool>()
        if (config.codeExecutionEnabled) tools.add(ApiTool(codeExecution = JsonObject(emptyMap())))
        if (config.googleSearchEnabled) tools.add(ApiTool(googleSearch = JsonObject(emptyMap())))

        // Add memory function declarations as a separate tool entry
        val functionDeclarations = config.tools?.map { td ->
            GeminiFunctionDeclaration(
                name = td.function.name,
                description = td.function.description,
                parameters = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive(td.function.parameters.type),
                        "properties" to JsonObject(
                            td.function.parameters.properties.mapValues { (_, prop) ->
                                val propMap = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                                    "type" to JsonPrimitive(prop.type),
                                    "description" to JsonPrimitive(prop.description)
                                )
                                if (prop.items != null) {
                                    propMap["items"] = JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive(prop.items.type),
                                            "description" to JsonPrimitive(prop.items.description)
                                        )
                                    )
                                }
                                JsonObject(propMap)
                            }
                        ),
                        "required" to kotlinx.serialization.json.JsonArray(
                            td.function.parameters.required.map { JsonPrimitive(it) }
                        )
                    )
                )
            )
        }
        if (!functionDeclarations.isNullOrEmpty()) {
            tools.add(ApiTool(functionDeclarations = functionDeclarations))
        }

        val thinkingConfig = if (!config.thinkingEnabled) {
            null
        } else when {
            cleanModelName.contains("gemini-3", ignoreCase = true) || cleanModelName.contains("gemini-3.5", ignoreCase = true) -> {
                ApiThinkingConfig(includeThoughts = true, thinkingLevel = ThinkingLevels.geminiLevel(config.thinkingLevel))
            }
            cleanModelName.contains("gemini-2.5", ignoreCase = true) -> {
                ApiThinkingConfig(
                    includeThoughts = true,
                    thinkingBudget = config.thinkingBudgetTokens.takeIf { config.thinkingBudgetEnabled }
                )
            }
            cleanModelName.contains("thinking-exp", ignoreCase = true) ->
                ApiThinkingConfig(includeThoughts = true)
            else -> null
        }

        val hasBuiltInTools = tools.any { it.codeExecution != null || it.googleSearch != null }
        val hasFunctionDeclarations = tools.any { it.functionDeclarations != null }
        val toolConfig = if (hasBuiltInTools && hasFunctionDeclarations) {
            ApiToolConfig(includeServerSideToolInvocations = true)
        } else null

        val hasGenParams = config.temperature != null || config.maxTokens != null || config.topP != null
                || config.frequencyPenalty != null || config.presencePenalty != null
        val genConfig = if (thinkingConfig != null || hasGenParams) ApiGenerationConfig(
            thinkingConfig = thinkingConfig,
            temperature = config.temperature,
            maxOutputTokens = config.maxTokens,
            topP = config.topP,
            frequencyPenalty = config.frequencyPenalty,
            presencePenalty = config.presencePenalty
        ) else null

        val requestBody = ApiGenerateContentRequest(
            contents = apiContents,
            systemInstruction = systemInstruction,
            tools = if (tools.isNotEmpty()) tools else null,
            toolConfig = toolConfig,
            generationConfig = genConfig
        )

        try {
            requestBody.requireValidWireFormat(cleanModelName)
            // Determine if baseUrl already includes versioning
            val finalUrlString = if (baseUrl.contains("/v1") || baseUrl.contains("/v1beta")) {
                "$baseUrl/models/$cleanModelName:streamGenerateContent?alt=sse"
            } else {
                "$baseUrl/v1beta/models/$cleanModelName:streamGenerateContent?alt=sse"
            }

            val headers = mapOf(
                "Content-Type" to "application/json",
                "x-goog-api-key" to config.apiKey
            )
            val requestJson = json.encodeToString(ApiGenerateContentRequest.serializer(), requestBody)
            requireValidSerializedRequest(
                provider = "Gemini",
                body = requestJson,
                requiredArrayFields = setOf("contents"),
            )
            DebugLog.d(
                "LxChatAPI",
                "[Gemini] request model=$cleanModelName messages=${apiContents.size} " +
                    "thinking=${config.thinkingEnabled} tools=${tools.size}",
            )
            val maxAttempts = ProviderRetryPolicy.MAX_ATTEMPTS
            val retryableCodes = setOf(429, 500, 502, 503, 504)
            var attempt = 0
            var done = false

            while (attempt < maxAttempts && !done) {
                attempt++
                val handle = try {
                    HttpClient.streamPost(finalUrlString, requestJson, headers)
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
                        var currentThoughtSignature: String? = null
                        var inThoughtBlock = false
                        var consecutiveReadTimeouts = 0
                        var producedContent = false
                        var finishReason: String? = null
                        var sawDone = false
                        var timedOut = false
                        var streamError: GenerationError? = null
                        var toolCallInFlight = false
                        val completedToolCallIds = mutableSetOf<String>()

                        suspend fun emitTracked(event: StreamEvent) {
                            if (event.carriesModelOutput()) producedContent = true
                            emit(event)
                        }

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
                            if (!line.startsWith("data: ")) continue
                            val jsonStr = line.substring(6).trim()
                            if (jsonStr == "[DONE]") {
                                sawDone = true
                                break
                            }
                            try {
                                val response = json.decodeFromString<ApiStreamResponse>(jsonStr)
                                response.error?.let { error ->
                                    streamError = GenerationError.Api(
                                        code = error.code?.toString(),
                                        type = error.status,
                                        message = error.message?.ifBlank {
                                            "Gemini reported an error in the response stream"
                                        } ?: "Gemini reported an error in the response stream",
                                    )
                                }
                                if (streamError == null && ProviderRetryPolicy.isFailedToGenerateOutcome(response.outcome)) {
                                    streamError = GenerationError.Api(null, "failed_to_generate", response.outcome.orEmpty())
                                }
                                val candidate = response.candidates?.firstOrNull()
                                candidate?.finishReason?.takeIf(String::isNotBlank)?.let {
                                    finishReason = normalizeGeminiFinishReason(it)
                                }
                                inThoughtBlock = false
                                candidate?.content?.parts?.forEach { part ->
                                    var isPartOfThought = false
                                    part.thought?.let { thoughtElement ->
                                        if (thoughtElement is JsonPrimitive) {
                                            if (thoughtElement.isString) {
                                                val content = thoughtElement.content
                                                emitTracked(StreamEvent.ThoughtChunk(content, extractThoughtTitle(content), currentThoughtSignature))
                                                isPartOfThought = true
                                                inThoughtBlock = true
                                            } else if (thoughtElement.content == "true") {
                                                isPartOfThought = true
                                                inThoughtBlock = true
                                            }
                                        }
                                    }
                                    part.reasoningContent?.let {
                                        emitTracked(StreamEvent.ThoughtChunk(it, extractThoughtTitle(it), currentThoughtSignature))
                                        isPartOfThought = true
                                        inThoughtBlock = true
                                    }
                                    part.thoughtSignature?.let { sig ->
                                        currentThoughtSignature = sig
                                        isPartOfThought = true
                                        inThoughtBlock = true
                                    }
                                    part.text?.takeIf(String::isNotEmpty)?.let {
                                        if (isPartOfThought || inThoughtBlock) {
                                            emitTracked(StreamEvent.ThoughtChunk(it, extractThoughtTitle(it), currentThoughtSignature))
                                            inThoughtBlock = false
                                        } else emitTracked(StreamEvent.TextChunk(it))
                                    }
                                    part.executableCode?.let {
                                        emitTracked(StreamEvent.TextChunk("\n```${it.language}\n${it.code}\n```\n"))
                                    }
                                    part.codeExecutionResult?.let {
                                        emitTracked(StreamEvent.TextChunk("\n> Output: ${it.output}\n"))
                                    }
                                    part.functionCall?.let { fc ->
                                        val callId = fc.id ?: "call_${UUID.randomUUID()}"
                                        if (
                                            !fc.name.matches(safeWireToolName) ||
                                            !callId.matches(safeWireToolCallId) ||
                                            !completedToolCallIds.add(callId)
                                        ) {
                                            toolCallInFlight = true
                                            streamError = GenerationError.SseParse(
                                                rawLine = "functionCall",
                                                cause = "Gemini returned invalid or duplicate tool metadata",
                                            )
                                        } else {
                                            val argsJson = fc.args?.let {
                                                Json.encodeToString(JsonObject.serializer(), it)
                                            } ?: "{}"
                                            val signature = part.thoughtSignature
                                                ?: fc.thoughtSignature
                                                ?: currentThoughtSignature
                                            val streamKey = "call_stream_${UUID.randomUUID()}"
                                            emitTracked(StreamEvent.ToolCallUpdate(streamKey, callId, fc.name, argsJson, signature))
                                            emitTracked(StreamEvent.ToolCallRequest(callId, fc.name, argsJson, signature, streamKey))
                                            currentThoughtSignature = null
                                            inThoughtBlock = false
                                        }
                                    }
                                }
                                response.usageMetadata?.let { emit(StreamEvent.UsageUpdate(it.toTokenUsage())) }
                                if (streamError != null || finishReason != null) break
                            } catch (e: Exception) {
                                DebugLog.e(
                                    "LxChatAPI",
                                    "[Gemini] malformed stream payload exception=${e.javaClass.simpleName}",
                                )
                                streamError = GenerationError.SseParse(
                                    rawLine = jsonStr.take(512),
                                    cause = e.localizedMessage ?: "Malformed SSE payload",
                                )
                                break
                            }
                        }
                        if (!currentCoroutineContext().isActive) throw kotlinx.coroutines.CancellationException("Stream cancelled")
                        val termination = geminiStreamTermination(
                            sawDone,
                            finishReason,
                            producedContent,
                            streamError,
                            timedOut,
                            toolCallInFlight,
                        )
                        DebugLog.d("LxChatSSE", "[Gemini] ${termination.describe()}")
                        if (termination.isRetryable && attempt < maxAttempts) {
                            emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                            delay(ProviderRetryPolicy.delayMillis(attempt))
                        } else {
                            termination.toError("Gemini")?.let { emit(StreamEvent.Error(it)) }
                            done = true
                        }
                    } else {
                        val errorRaw = handle.errorBody ?: "Unknown error (Code: ${handle.code})"
                        val responseBytes = errorRaw.toByteArray(Charsets.UTF_8).size
                        DebugLog.e(
                            "LxChatAPI",
                            "[Gemini] HTTP ${handle.code} responseBytes=$responseBytes",
                        )
                        val retryable = ProviderRetryPolicy.shouldRetryHttp(
                            handle.code,
                            errorRaw,
                            retryableCodes,
                        )
                        if (retryable && attempt < maxAttempts) {
                            emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                            delay(ProviderRetryPolicy.delayMillis(attempt))
                        } else {
                            val genError = try {
                                val errorJson = json.decodeFromString<ApiErrorResponse>(errorRaw)
                                GenerationError.Api(
                                    code = (errorJson.error.code ?: handle.code).toString(),
                                    type = errorJson.error.status,
                                    message = errorJson.error.message ?: "No error message provided",
                                )
                            } catch (_: Exception) {
                                GenerationError.Network(handle.code, errorRaw)
                            }
                            emit(StreamEvent.Error(genError))
                            done = true
                        }
                    }
                } finally {
                    handle.close()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emitTransportError("Gemini", "LxChatAPI", e)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val effectiveBaseUrl = baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val finalUrlString = if (effectiveBaseUrl.contains("/v1") || effectiveBaseUrl.contains("/v1beta")) {
            "$effectiveBaseUrl/models"
        } else {
            "$effectiveBaseUrl/v1beta/models"
        }

        val responseText = HttpClient.fetchModelsResponse(
            finalUrlString,
            mapOf("x-goog-api-key" to apiKey),
        ).requireModelFetchBody()
        val models = decodeModelFetchResponse {
            json.decodeFromString<ModelListResponse>(responseText)
                .models
                .filter { it.supportedGenerationMethods.contains("generateContent") }
                .map { it.name.removePrefix("models/") }
        }
        if (models.isEmpty()) throw ModelFetchEmptyResultException()
        models
    }

    private fun buildGeminiFunctionResponse(result: String): JsonObject {
        return try {
            val parsed = json.parseToJsonElement(result)
            when (parsed) {
                is JsonObject -> parsed
                else -> JsonObject(mapOf("result" to parsed))
            }
        } catch (_: Exception) {
            JsonObject(mapOf("result" to JsonPrimitive(result)))
        }
    }
}
