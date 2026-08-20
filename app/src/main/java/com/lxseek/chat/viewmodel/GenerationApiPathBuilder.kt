package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.ProviderConfig
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.util.projectGenerationStatusesForApi
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.AttachmentMeta
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.TokenUsage
import com.lxseek.chat.model.ToolCallData
import com.lxseek.chat.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal data class GenerationApiPathRequest(
    val parentId: String?,
    val conversationId: String,
    val isRegenerate: Boolean,
    val replaceMessageId: String?,
    val config: GenerationConfig,
    val context: GenerationContext,
    val loadedMessages: List<MessageEntity>? = null,
)

internal data class GenerationApiPath(
    val messages: List<ChatMessage>,
    val providerConfig: ProviderConfig,
)

internal fun interface GenerationToolDefinitionSource {
    fun definitions(context: GenerationContext): List<ToolDefinition>
}

/**
 * Builds the immutable Provider request path from one durable Room snapshot.
 *
 * It may read Room when the caller has not supplied a snapshot, but it performs no writes and has
 * no runtime, Provider, tool-execution, continuation or finalization authority.
 */
internal class GenerationApiPathBuilder(
    private val conversations: ConversationRepository,
    private val toolDefinitions: GenerationToolDefinitionSource,
    private val planStateHolder: com.lxseek.chat.tool.PlanStateHolder? = null,
) {
    suspend fun build(request: GenerationApiPathRequest): GenerationApiPath =
        withContext(Dispatchers.Default) {
            val dbMessages = request.loadedMessages
                ?: conversations.getMessagesForConversationSnapshot(request.conversationId)
            val messagesById = dbMessages.associateBy { it.id }
            val pathEntities = mutableListOf<MessageEntity>()
            var currentId: String? = request.parentId
            while (currentId != null) {
                val message = messagesById[currentId] ?: break
                pathEntities.add(0, message)
                if (message.id.startsWith(Constants.COMPACT_MSG_PREFIX)) break
                currentId = message.parentId
            }
            // Inject each persisted tool protocol row exactly once. A queued intervention may have
            // a result_ ancestor while that same round is also reachable as a side chain of the
            // visible model message; ApiPathAssembler owns that overlap and prevents replay.
            val expanded = ApiPathAssembler.assemble(pathEntities, dbMessages)
            val toolHistoryCompactor = ToolRoundHistoryCompactor()
            val currentPath = expanded.map { entity ->
                val decodedSegments = entity.toolCallJson?.let { json ->
                    try {
                        Json.decodeFromString<List<MessageSegment>>(json)
                    } catch (_: Exception) {
                        null
                    }
                }
                val segments = if (
                    decodedSegments != null &&
                    entity.id.startsWith(Constants.TOOL_MSG_PREFIX)
                ) {
                    toolHistoryCompactor.compact(entity.runId, decodedSegments)
                } else {
                    decodedSegments
                }
                val toolCall = segments?.lastOrNull { it.type == "tool" }?.let { segment ->
                    ToolCallData(
                        toolName = segment.toolName ?: "",
                        arguments = segment.toolArgs ?: "{}",
                        result = segment.toolResult ?: "",
                        signature = segment.signature,
                        toolCallId = segment.toolCallId,
                        resultImages = segment.toolImages,
                        displayName = segment.toolDisplayName,
                        resultText = segment.toolResultText,
                        structuredResult = segment.toolStructuredResult,
                    )
                }
                val attachmentMeta = entity.attachmentMeta?.let { json ->
                    try {
                        Json.decodeFromString<AttachmentMeta>(json)
                    } catch (_: Exception) {
                        null
                    }
                }
                val attachmentText = attachmentMeta?.items?.mapNotNull { item ->
                    val includeTranscription = request.context.imageTranscriptionEnabled &&
                        !item.transcription.isNullOrBlank()
                    when {
                        item.textContent != null -> {
                            val label = item.fileName ?: "file"
                            "\n\n--- File: $label ---\n${item.textContent}"
                        }
                        includeTranscription -> {
                            val label = item.fileName ?: "image"
                            "\n\n--- Image Transcription: $label ---\n${item.transcription}"
                        }
                        else -> null
                    }
                }?.joinToString("").orEmpty()
                val combinedText = if (attachmentText.isNotBlank()) {
                    entity.text + attachmentText
                } else {
                    entity.text
                }
                val hasTranscription = request.context.imageTranscriptionEnabled &&
                    attachmentMeta?.items?.any { !it.transcription.isNullOrBlank() } == true
                ChatMessage(
                    id = entity.id,
                    parentId = entity.parentId,
                    text = combinedText,
                    images = if (hasTranscription) emptyList() else entity.images,
                    thoughts = entity.thoughts,
                    thoughtTitle = entity.thoughtTitle,
                    tokenCount = entity.tokenCount,
                    tokenUsage = TokenUsage.fromPersisted(
                        totalTokenCount = entity.tokenCount,
                        inputTokenCount = entity.inputTokenCount,
                        cachedInputTokenCount = entity.cachedInputTokenCount,
                        uncachedInputTokenCount = entity.uncachedInputTokenCount,
                        outputTokenCount = entity.outputTokenCount,
                        reasoningTokenCount = entity.reasoningTokenCount,
                    ),
                    status = entity.status,
                    participant = entity.participant,
                    timestamp = entity.timestamp,
                    thoughtTimeMs = entity.thoughtTimeMs,
                    modelName = entity.modelName,
                    segments = segments,
                    toolCall = toolCall,
                    runId = entity.runId,
                    runSequence = entity.runSequence,
                    consumedAtPass = entity.consumedAtPass,
                )
            }.let(::projectGenerationStatusesForApi)
                .let { path ->
                    if (request.isRegenerate && request.replaceMessageId != null) {
                        val oldIndex = path.indexOfFirst { it.id == request.replaceMessageId }
                        if (oldIndex >= 0) path.take(oldIndex) else path
                    } else {
                        path
                    }
                }

            val config = request.config
            GenerationApiPath(
                messages = currentPath,
                providerConfig = ProviderConfig(
                    apiKey = config.apiKey,
                    modelId = config.modelId,
                    systemPrompt = (config.effectiveSystemPrompt ?: "") +
                        (planStateHolder?.let { psh ->
                            com.lxseek.chat.tool.PlanHandler.buildPlanContext(psh, request.conversationId)
                                ?.takeIf { it.isNotBlank() }?.let { "\n\n$it" }
                        } ?: ""),
                    maxContextWindow = config.maxContextWindow,
                    codeExecutionEnabled = config.codeExecutionEnabled,
                    googleSearchEnabled = config.googleSearchEnabled,
                    thinkingEnabled = config.thinkingEnabled,
                    thinkingLevel = config.thinkingLevel,
                    thinkingBudgetEnabled = config.thinkingBudgetEnabled,
                    thinkingBudgetTokens = config.thinkingBudgetTokens,
                    openAiServiceTier = config.openAiServiceTier,
                    baseUrl = config.baseUrl,
                    tools = toolDefinitions.definitions(request.context),
                    userPrepend = config.userPrepend,
                    userPostpend = config.userPostpend,
                    temperature = config.temperature,
                    maxTokens = config.maxTokens,
                    topP = config.topP,
                    frequencyPenalty = config.frequencyPenalty,
                    presencePenalty = config.presencePenalty,
                ),
            )
        }
}
