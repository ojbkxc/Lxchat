package com.lxseek.chat.viewmodel

import com.lxseek.chat.automation.ConversationExecutionCoordinator
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunStatus
import com.lxseek.chat.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Immutable UI/Provider snapshot for one regenerate intent. */
internal data class ConversationRegenerationRequest(
    val conversationId: String,
    val messageId: String,
    val modelId: String,
    val providerName: String,
    val activeKey: String,
    val visiblePath: List<ChatMessage>,
)

/**
 * Creates one regeneration branch after the existing visual transition admits the intent.
 *
 * The call-scoped runtime host remains the sole Run-state owner; this service stores no mutable
 * transition, Run, Job, scope, stream, or overlay state.
 */
internal class ConversationRegenerationService(
    private val conversations: ConversationRepository,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val transitions: RegenerationTransitionCoordinator,
    private val terminalSettlement: GenerationTerminalSettlementController,
    private val boundRunGenerationLauncher: BoundRunGenerationLauncher,
    private val guidanceDrain: QueuedGuidanceDrainExecutor,
    private val toUiMessage: (MessageEntity) -> ChatMessage,
    private val isConversationOpen: (String) -> Boolean,
    private val projectGraph: (
        conversationId: String,
        messages: List<ChatMessage>,
        selectedChildren: Map<String?, String>,
        streamingMessage: ChatMessage,
    ) -> Unit,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun regenerate(
        request: ConversationRegenerationRequest,
        state: ConversationGenerationState,
    ): Boolean {
        val messageToRegenerate = request.visiblePath
            .find { it.id == request.messageId } ?: return false
        if (messageToRegenerate.participant != Participant.MODEL) return false
        val sourceRunId = messageToRegenerate.runId ?: return false
        val targetUserMessageId = messageToRegenerate.parentId ?: return false
        val outputBoundary = request.visiblePath
            .filter {
                it.runId == sourceRunId &&
                    it.participant == Participant.MODEL &&
                    !it.id.startsWith(Constants.TOOL_MSG_PREFIX) &&
                    !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
            }
            .maxWithOrNull(
                compareBy<ChatMessage> { it.runSequence ?: Long.MAX_VALUE }
                    .thenBy { it.timestamp }
                    .thenBy { it.id },
            )
        if (outputBoundary?.id != request.messageId) return false

        val uiToken = state.tryAcquireForReplacement() ?: return false
        val transition = transitions.begin(
            conversationId = request.conversationId,
            oldMessageId = request.messageId,
            targetUserMessageId = targetUserMessageId,
        ) ?: run {
            state.scope.launch {
                guidanceDrain.releaseUnlaunchedSlotAndDrain(state, uiToken)
            }
            return false
        }
        val runId = idFactory()
        var graphCommitted = false
        val generationJob = state.launchGenerationJob(uiToken) generation@{
            var setupModelMessageId: String? = null
            var runBound = false
            var stopFinalizationClaimed = false
            try {
                if (!transitions.awaitFade(transition.id)) return@generation
                if (
                    !state.isCurrentToken(uiToken) ||
                    !transitions.isAnimating(transition.id) ||
                    !isConversationOpen(request.conversationId)
                ) {
                    return@generation
                }
                val persistId = state.nextPersistId()
                executionCoordinator.withConversationLock(request.conversationId) lock@{
                    if (
                        !state.isCurrentToken(uiToken) ||
                        !transitions.isAnimating(transition.id) ||
                        !isConversationOpen(request.conversationId)
                    ) {
                        return@lock
                    }
                    val persistedMessages = conversations
                        .getMessagesForConversationSnapshot(request.conversationId)
                    val persistedTarget = persistedMessages
                        .find { it.id == request.messageId } ?: return@lock
                    if (persistedTarget.runId != sourceRunId) return@lock
                    conversations.getRun(sourceRunId) ?: return@lock
                    val sourceInput = RunRegenerationPolicy.selectBoundaryInput(
                        persistedMessages,
                        sourceRunId,
                    ) ?: return@lock
                    val modelMessageId = idFactory()
                    setupModelMessageId = modelMessageId
                    val startTime = maxOf(clock(), persistedTarget.timestamp + 1)
                    val modelEntity = MessageEntity(
                        id = modelMessageId,
                        conversationId = request.conversationId,
                        parentId = sourceInput.id,
                        text = "",
                        thoughts = null,
                        thoughtTitle = null,
                        status = MessageStatus.SENDING,
                        participant = Participant.MODEL,
                        timestamp = startTime,
                        modelName = request.modelId,
                        runId = runId,
                        runSequence = 0,
                    )
                    val graphCommit = conversations.createRunWithMessages(
                        RunEntity(
                            id = runId,
                            conversationId = request.conversationId,
                            parentRunId = sourceInput.runId,
                            status = RunStatus.ACTIVE,
                            activeSlot = 1,
                            startedAt = startTime,
                            lastCheckpointAt = startTime,
                        ),
                        listOf(modelEntity),
                        messageSelectionUpdates = mapOf(sourceInput.id to modelEntity.id),
                    )
                    graphCommitted = true
                    transitions.markCommitted(transition.id)
                    val binding = state.bindPersistedRun(uiToken, runId)
                    runBound = binding is ConversationGenerationState.RunBindingOutcome.Active
                    if (!runBound) {
                        if (binding is ConversationGenerationState.RunBindingOutcome.Stopping) {
                            stopFinalizationClaimed = true
                            terminalSettlement.settleLateBoundStop(state, binding)
                        } else {
                            withContext(kotlinx.coroutines.NonCancellable) {
                                conversations.finishStoppedGeneration(emptyList(), runId)
                            }
                        }
                        return@lock
                    }
                    val placeholder = toUiMessage(modelEntity)
                    state.streamUpdate(uiToken, placeholder)
                    if (isConversationOpen(request.conversationId)) {
                        projectGraph(
                            request.conversationId,
                            listOf(placeholder),
                            graphCommit.messageSelections,
                            placeholder,
                        )
                    }
                    boundRunGenerationLauncher.launch(
                        BoundRunGenerationRequest(
                            conversationId = request.conversationId,
                            modelMessageId = modelMessageId,
                            startTime = startTime,
                            isRegenerate = false,
                            replaceMessageId = null,
                            providerName = request.providerName,
                            modelId = request.modelId,
                            activeKey = request.activeKey,
                            uiToken = uiToken,
                            persistId = persistId,
                            runId = runId,
                            pass = 0,
                            callerTag = "regenerate",
                        ),
                        state,
                    )
                }
            } catch (error: CancellationException) {
                if (!runBound && !stopFinalizationClaimed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (conversations.getRun(runId) != null) {
                            graphCommitted = true
                            transitions.markCommitted(transition.id)
                            val binding = state.bindPersistedRun(uiToken, runId)
                            stopFinalizationClaimed =
                                terminalSettlement.settleCancelledDurableRun(state, binding)
                            if (!stopFinalizationClaimed) {
                                conversations.finishStoppedGeneration(emptyList(), runId)
                            }
                        }
                    }
                }
                throw error
            } catch (error: Exception) {
                if (!runBound && !stopFinalizationClaimed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (conversations.getRun(runId) != null) {
                            graphCommitted = true
                            transitions.markCommitted(transition.id)
                            val binding = state.bindPersistedRun(uiToken, runId)
                            runBound = binding is
                                ConversationGenerationState.RunBindingOutcome.Active
                            if (binding is ConversationGenerationState.RunBindingOutcome.Stopping) {
                                stopFinalizationClaimed = true
                                terminalSettlement.settleLateBoundStop(state, binding)
                            }
                        }
                    }
                }
                terminalSettlement.failGenerationSetup(
                    conversationId = request.conversationId,
                    runId = runId,
                    modelMessageId = setupModelMessageId,
                    uiToken = uiToken,
                    state = state,
                    error = error,
                )
            } finally {
                if (!graphCommitted) transitions.abort(transition.id)
            }
        }
        if (generationJob == null) transitions.abort(transition.id)
        return generationJob != null
    }
}
