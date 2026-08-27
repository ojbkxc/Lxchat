package com.lxseek.chat.viewmodel

import com.lxseek.chat.automation.ConversationExecutionCoordinator
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Immutable UI/Provider snapshot for one continuation intent. Shares the exact shape of a
 * regeneration intent; the two differ only in execution policy.
 */
internal typealias ConversationResumeRequest = ConversationRegenerationRequest

/**
 * Continues an interrupted (STOPPED) assistant generation without replaying side effects.
 *
 * This is the langgraph-style checkpoint/resume continuation: unlike regeneration — which re-runs
 * a multi-step tool loop from the user input and therefore re-executes every completed tool round —
 * a resume branches a fresh Run beneath the deepest durable anchor of the stopped run. The anchor is
 * selected by [ResumeRunPolicy] (the last persisted tool-RESULT, or the interrupted model row itself
 * when no tool round was committed), so the provider already sees the finished tool results in its
 * context and simply continues generating from there.
 */
internal class ConversationResumeService(
    private val conversations: ConversationRepository,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val terminalSettlement: GenerationTerminalSettlementController,
    private val boundRunGenerationLauncher: BoundRunGenerationLauncher,
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
    fun resume(
        request: ConversationResumeRequest,
        state: ConversationGenerationState,
    ): Boolean {
        // Fast-path gate before claiming the idle generation slot.
        val stoppedMessage = request.visiblePath.find { it.id == request.messageId }
        if (stoppedMessage?.participant != Participant.MODEL) return false
        if (stoppedMessage.status != MessageStatus.STOPPED) return false
        val interruptedRunId = stoppedMessage.runId ?: return false

        val uiToken = state.tryAcquireForReplacement() ?: return false
        val runId = idFactory()
        var runBound = false
        var stopFinalizationClaimed = false
        val generationJob = state.launchGenerationJob(uiToken) generation@{
            var setupModelMessageId: String? = null
            try {
                if (
                    !state.isCurrentToken(uiToken) ||
                    !isConversationOpen(request.conversationId)
                ) {
                    return@generation
                }
                val persistId = state.nextPersistId()
                executionCoordinator.withConversationLock(request.conversationId) lock@{
                    if (
                        !state.isCurrentToken(uiToken) ||
                        !isConversationOpen(request.conversationId)
                    ) {
                        return@lock
                    }
                    val persistedMessages = conversations
                        .getMessagesForConversationSnapshot(request.conversationId)
                    val anchor = ResumeRunPolicy.selectResumeTail(
                        messages = persistedMessages,
                        interruptedRunId = interruptedRunId,
                        stoppedModelMessageId = request.messageId,
                    ) ?: return@lock
                    conversations.getRun(interruptedRunId) ?: return@lock
                    val modelMessageId = idFactory()
                    setupModelMessageId = modelMessageId
                    val startTime = maxOf(clock(), anchor.timestamp + 1)
                    val modelEntity = MessageEntity(
                        id = modelMessageId,
                        conversationId = request.conversationId,
                        parentId = anchor.id,
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
                            parentRunId = interruptedRunId,
                            status = RunStatus.ACTIVE,
                            activeSlot = 1,
                            startedAt = startTime,
                            lastCheckpointAt = startTime,
                        ),
                        listOf(modelEntity),
                        messageSelectionUpdates = mapOf(anchor.id to modelEntity.id),
                    )
                    val binding = state.bindPersistedRun(uiToken, runId)
                    runBound = binding is ConversationGenerationState.RunBindingOutcome.Active
                    if (!runBound) {
                        if (binding is ConversationGenerationState.RunBindingOutcome.Stopping) {
                            stopFinalizationClaimed = true
                            terminalSettlement.settleLateBoundStop(state, binding)
                        } else {
                            withContext(NonCancellable) {
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
                            callerTag = "resume",
                        ),
                        state,
                    )
                }
            } catch (error: CancellationException) {
                if (!runBound && !stopFinalizationClaimed) {
                    withContext(NonCancellable) {
                        if (conversations.getRun(runId) != null) {
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
                    withContext(NonCancellable) {
                        if (conversations.getRun(runId) != null) {
                            val binding = state.bindPersistedRun(uiToken, runId)
                            runBound =
                                binding is ConversationGenerationState.RunBindingOutcome.Active
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
            }
        }
        return generationJob != null
    }
}