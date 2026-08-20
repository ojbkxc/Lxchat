package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.LocalModelSerializer
import com.lxseek.chat.api.ProviderConfig
import com.lxseek.chat.api.StreamEvent
import com.lxseek.chat.api.util.applyNearestContextCompact
import com.lxseek.chat.api.util.contextWindowUsage
import com.lxseek.chat.api.util.projectGenerationStatusesForApi
import com.lxseek.chat.api.util.splitLogicalContext
import com.lxseek.chat.api.util.stripEmptyTurns
import com.lxseek.chat.api.util.validateToolMessages
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import com.lxseek.chat.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class CompactRequest(
    val model: String,
    val prompt: String,
    val retainLogicalMessages: Int,
)

/**
 * A compact model is generating a new summary, so its history needs a real terminal user request.
 * The older prefix often ends with an assistant answer after the retained suffix is removed. Sending
 * that prefix directly violates OpenAI-compatible, Anthropic, and Gemini generation boundaries.
 * This instruction exists only in the compact request and is never persisted into the conversation.
 */
internal fun buildCompactSummaryInput(prefix: List<ChatMessage>): List<ChatMessage> =
    prefix + ChatMessage(
        id = "compact_summary_request_${UUID.randomUUID()}",
        text = "Summarize the conversation context above according to the system instructions. Return only the summary.",
        participant = Participant.USER,
        status = MessageStatus.SUCCESS,
    )

internal fun resolveCompactGraphSuffixRoot(
    providerSuffixRootId: String?,
    entitiesById: Map<String, MessageEntity>,
): MessageEntity? {
    var current = providerSuffixRootId?.let(entitiesById::get)
    while (
        current != null &&
        (current.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            current.id.startsWith(Constants.RESULT_MSG_PREFIX))
    ) {
        current = current.parentId?.let(entitiesById::get)
    }
    return current
}

/** Provider-equivalent split input without role coalescing away durable graph ids. */
internal fun compactSplitMessages(messages: List<ChatMessage>): List<ChatMessage> =
    stripEmptyTurns(
        validateToolMessages(
            projectGenerationStatusesForApi(messages.distinctBy(ChatMessage::id))
        )
    )

sealed interface CompactResult {
    data class Created(val messageId: String) : CompactResult
    data object NotNeeded : CompactResult
    data class Failed(val message: String) : CompactResult
}

/** Narrow operation port used by the application-level Compact effect executor. */
internal interface ContextCompactOperation {
    suspend fun automaticNeeded(conversationId: String, contextLimit: Int): Boolean

    suspend fun compactAutomatic(
        conversationId: String,
        fallbackModel: String,
        contextLimit: Int,
        compactRunId: String,
        onSummaryChunk: (String) -> Unit = {},
    ): CompactResult

    suspend fun compactManual(
        conversationId: String,
        request: CompactRequest,
        compactRunId: String,
        onSummaryChunk: (String) -> Unit = {},
    ): CompactResult
}

internal fun automaticCompactNeeded(
    entities: List<MessageEntity>,
    selectedChildren: Map<String?, String>,
    contextLimit: Int,
    retainLogicalMessages: Int,
): Boolean {
    val selectedPath = ConversationUiState.resolvePath(
        allMessages = entities.map { it.toUiChatMessage { text -> text } },
        streamingMsg = null,
        selectedChildren = selectedChildren,
    )
    val entitiesById = entities.associateBy(MessageEntity::id)
    return automaticCompactNeeded(
        path = ApiPathAssembler.assemble(
            selectedPath.mapNotNull { entitiesById[it.id] },
            entities,
        ).map { it.toUiChatMessage { text -> text } },
        contextLimit = contextLimit,
        retainLogicalMessages = retainLogicalMessages,
    )
}

internal fun automaticCompactNeeded(
    path: List<ChatMessage>,
    contextLimit: Int,
    retainLogicalMessages: Int,
): Boolean {
    if (path.isEmpty() || retainLogicalMessages < 0) return false
    val nearest = path.indexOfLast { it.id.startsWith(Constants.COMPACT_MSG_PREFIX) }
    val compactablePath = compactSplitMessages(path.drop(nearest.coerceAtLeast(-1) + 1))
    val split = splitLogicalContext(compactablePath, retainLogicalMessages)
    return split.prefix.isNotEmpty() &&
        contextWindowUsage(path, contextLimit.coerceAtLeast(1)).estimatedTokenCount >=
        contextLimit.coerceAtLeast(1)
}

/** Non-destructive context compaction. Original messages remain in the graph. */
internal class ContextCompactor(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val providers: ProviderRegistry,
    private val pauseLoop: suspend (String) -> Unit,
) : ContextCompactOperation {
    override suspend fun automaticNeeded(conversationId: String, contextLimit: Int): Boolean =
        settings.contextCompactEnabled.value && automaticCompactNeeded(
            conversations.getMessagesForConversationSnapshot(conversationId),
            conversations.restoreBranchSelections(conversationId),
            contextLimit,
            settings.contextCompactRetainCount.value,
        )

    override suspend fun compactAutomatic(
        conversationId: String,
        fallbackModel: String,
        contextLimit: Int,
        compactRunId: String,
        onSummaryChunk: (String) -> Unit,
    ): CompactResult {
        if (!settings.contextCompactEnabled.value) return CompactResult.NotNeeded
        return compact(
            conversationId = conversationId,
            request = CompactRequest(
                model = settings.contextCompactModel.value?.takeIf(String::isNotBlank) ?: fallbackModel,
                prompt = settings.contextCompactPrompt.value,
                retainLogicalMessages = settings.contextCompactRetainCount.value,
            ),
            threshold = contextLimit.coerceAtLeast(1),
            compactRunId = compactRunId,
            onSummaryChunk = onSummaryChunk,
        )
    }

    override suspend fun compactManual(
        conversationId: String,
        request: CompactRequest,
        compactRunId: String,
        onSummaryChunk: (String) -> Unit,
    ): CompactResult = compact(
        conversationId,
        request,
        threshold = null,
        compactRunId = compactRunId,
        onSummaryChunk = onSummaryChunk,
    )

    private suspend fun compact(
        conversationId: String,
        request: CompactRequest,
        threshold: Int?,
        compactRunId: String,
        onSummaryChunk: (String) -> Unit,
    ): CompactResult {
        require(compactRunId.isNotBlank())
        if (request.model.isBlank()) return CompactResult.Failed("Select a compact model")
        if (request.prompt.isBlank()) return CompactResult.Failed("Compact prompt cannot be empty")
        if (request.retainLogicalMessages < 0) return CompactResult.Failed("Retained messages cannot be negative")

        val entities = conversations.getMessagesForConversationSnapshot(conversationId)
        val selected = conversations.restoreBranchSelections(conversationId)
        val selectedPath = ConversationUiState.resolvePath(
            allMessages = entities.map { it.toUiChatMessage { text -> text } },
            streamingMsg = null,
            selectedChildren = selected,
        )
        val entitiesById = entities.associateBy(MessageEntity::id)
        val selectedEntities = selectedPath.mapNotNull { entitiesById[it.id] }
        val path = ApiPathAssembler.assemble(selectedEntities, entities)
            .map { it.toUiChatMessage { text -> text } }
        if (path.isEmpty()) return CompactResult.NotNeeded
        val nearest = path.indexOfLast { it.id.startsWith(Constants.COMPACT_MSG_PREFIX) }
        val activePath = path.drop(nearest.coerceAtLeast(-1) + 1)
        val compactablePath = compactSplitMessages(activePath)
        val split = splitLogicalContext(compactablePath, request.retainLogicalMessages)
        val activeUsage = contextWindowUsage(path, threshold ?: Int.MAX_VALUE)
        if (
            threshold != null &&
            activeUsage.estimatedTokenCount < threshold
        ) return CompactResult.NotNeeded
        if (split.prefix.isEmpty()) return CompactResult.NotNeeded

        // Provider order places a persisted tool round before its visible aggregate model row,
        // while the message graph stores that model row as the tool root's parent. Anchoring the
        // Compact before the tool row would therefore strand the aggregate above the boundary.
        // Walk protocol parents to the visible aggregate so the whole logical suffix remains a
        // descendant and ApiPathAssembler reconstructs summary + tool/results + aggregate.
        val providerSuffixRoot = split.suffix.firstOrNull()
        val compactableIds = compactablePath.mapTo(hashSetOf(), ChatMessage::id)
        // Automatic checks run after the USER row is durable but before its provider pass. The
        // selected graph therefore ends in an empty SENDING placeholder (or, at a tool boundary,
        // an in-progress visible aggregate) that provider canonicalization intentionally omits.
        // It is still a real graph suffix and must remain below the new Compact boundary.
        val graphOnlySuffixRootId = selectedPath.lastOrNull()
            ?.takeIf { it.id !in compactableIds }
            ?.id
        val graphSuffixRootId = providerSuffixRoot?.id ?: graphOnlySuffixRootId
        val graphSuffixRoot = resolveCompactGraphSuffixRoot(graphSuffixRootId, entitiesById)
        if (graphSuffixRootId != null && graphSuffixRoot == null) {
            return CompactResult.Failed("Compact suffix graph anchor disappeared")
        }
        val originalParentId = graphSuffixRoot?.parentId ?: split.prefix.last().id

        val providerName = providers.providerForModel(request.model)
        val key = settings.awaitActiveKey(providerName).orEmpty()
        if (!providers.isConfigured(providerName, key)) {
            return CompactResult.Failed("The selected compact model is not configured")
        }
        val provider = providers.getInstanceOrNull(providerName)
            ?: return CompactResult.Failed("The selected compact provider is unavailable")

        pauseLoop(conversationId)
        val input = applyNearestContextCompact(path.take(nearest + 1) + split.prefix)
            .filterNot { it.id.startsWith(Constants.COMPACT_MSG_PREFIX) }
        if (input.isEmpty()) return CompactResult.NotNeeded
        val config = ProviderConfig(
            apiKey = key,
            modelId = ModelId.parse(request.model).modelName,
            systemPrompt = request.prompt,
            maxContextWindow = Int.MAX_VALUE,
            thinkingEnabled = false,
            baseUrl = providers.getEffectiveBaseUrl(providerName),
        )
        val summary = StringBuilder()
        var error: String? = null
        suspend fun collectResponse() {
            provider.generateResponse(buildCompactSummaryInput(input), config).collect { event ->
                when (event) {
                    is StreamEvent.TextChunk -> {
                        summary.append(event.text)
                        onSummaryChunk(event.text)
                    }
                    is StreamEvent.Error -> error = event.message
                    else -> Unit
                }
            }
        }
        if (providerName == Constants.PROVIDER_LOCAL) {
            LocalModelSerializer.mutex.withLock {
                withContext(Dispatchers.IO) { collectResponse() }
            }
        } else collectResponse()
        val summaryText = summary.toString().trim()
        error?.let { return CompactResult.Failed(it) }
        if (summaryText.isBlank()) return CompactResult.Failed("Compact model returned an empty summary")

        // Re-read under the caller's conversation lock and refuse stale graph mutation.
        val current = conversations.getMessagesForConversationSnapshot(conversationId)
        val byId = current.associateBy(MessageEntity::id)
        if (graphSuffixRoot != null && byId[graphSuffixRoot.id]?.parentId != originalParentId) {
            return CompactResult.Failed("Conversation changed while compacting")
        }
        val source = byId[split.prefix.last().id]
            ?: return CompactResult.Failed("Compact boundary disappeared")
        val sourceRun = conversations.getRun(source.runId)
            ?: return CompactResult.Failed("Compact source run disappeared")
        val compactId = Constants.COMPACT_MSG_PREFIX + UUID.randomUUID()
        val runId = compactRunId
        val now = System.currentTimeMillis()
        val run = RunEntity(
            id = runId,
            conversationId = conversationId,
            parentRunId = sourceRun.id,
            status = RunStatus.COMPLETED,
            activeSlot = null,
            startedAt = now,
            lastCheckpointAt = now,
            endedAt = now,
            endReason = RunEndReason.MODEL_COMPLETED,
        )
        val message = MessageEntity(
            id = compactId,
            conversationId = conversationId,
            parentId = originalParentId,
            text = summaryText,
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
            timestamp = now,
            modelName = request.model,
            runId = runId,
            runSequence = 0,
        )
        val repaired = selected.toMutableMap().apply {
            put(originalParentId, compactId)
            if (graphSuffixRoot != null) put(compactId, graphSuffixRoot.id)
        }
        conversations.insertContextCompactBeforeSuffix(
            run,
            message,
            graphSuffixRoot?.id,
            repaired,
            now,
        )
        return CompactResult.Created(compactId)
    }
}
