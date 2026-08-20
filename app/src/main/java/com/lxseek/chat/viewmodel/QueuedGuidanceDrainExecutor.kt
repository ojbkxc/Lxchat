package com.lxseek.chat.viewmodel

import com.lxseek.chat.automation.ConversationExecutionCoordinator
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunStatus
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One exact FIFO lease paired with the mailbox effect that authorizes its fresh Run. */
internal data class QueuedDrainClaim(
    val lease: GuidanceBatchLease,
    val inputEffect: RunEffect.PersistAcceptedInput,
) {
    val batch: List<QueuedSend> get() = lease.batch
    val uiToken: Long get() = inputEffect.identity.ownerToken
}

/**
 * Transfers one claimed in-memory guidance batch through the normal fresh-Run durable boundary.
 *
 * Pending and claimed guidance remain owned by the call-scoped runtime host. This executor stores
 * no lease, Run state, Job, scope, overlay, or continuation authority.
 */
internal class QueuedGuidanceDrainExecutor(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val requestBuilder: GenerationRequestBuilder,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val compactController: ConversationCompactController,
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
    private val onScrollToAbsoluteBottomAfter: (conversationId: String, messageId: String) -> Unit,
    private val onUserMessagePersisted: (messageId: String, text: String) -> Unit,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Called only while [ConversationGenerationState.queueMutationMutex] is held. */
    suspend fun claimUnderLock(
        state: ConversationGenerationState,
        lease: GuidanceBatchLease,
    ): QueuedDrainClaim? {
        val proposedRunId = idFactory()
        val transition = try {
            state.commands.requestSend(
                proposedRunId = proposedRunId,
                effectId = "guidance-$proposedRunId",
                directOnly = false,
                hasPendingGuidance = false,
            )
        } catch (error: Exception) {
            state.settleGuidanceClaim(lease.id, durable = false)
            throw error
        }
        val inputEffect = transition.effects
            .filterIsInstance<RunEffect.PersistAcceptedInput>()
            .singleOrNull()
        if (!transition.accepted || inputEffect == null) {
            state.settleGuidanceClaim(lease.id, durable = false)
            return null
        }
        return QueuedDrainClaim(lease, inputEffect)
    }

    /** One FIFO lease becomes one durable user bubble after the prior Run releases. */
    suspend fun drainAfterSettlement(state: ConversationGenerationState) {
        val claim = state.queueMutationMutex.withLock {
            if (!state.consumeQueueDrainPermission()) return@withLock null
            state.claimQueuedSends()?.let { lease -> claimUnderLock(state, lease) }
        } ?: return
        launchClaim(state, claim)
    }

    /**
     * Releases a claimed slot for which no generation Job was installed, then drains its queue.
     */
    suspend fun releaseUnlaunchedSlotAndDrain(
        state: ConversationGenerationState,
        uiToken: Long,
    ): Unit = withContext(kotlinx.coroutines.NonCancellable) {
        var drainClaim: QueuedDrainClaim? = null
        state.queueMutationMutex.withLock {
            if (state.endGeneration(uiToken) && state.consumeQueueDrainPermission()) {
                state.claimQueuedSends()?.let { lease ->
                    drainClaim = claimUnderLock(state, lease)
                }
            }
        }
        drainClaim?.let { launchClaim(state, it) }
        Unit
    }

    fun launchClaim(
        state: ConversationGenerationState,
        claim: QueuedDrainClaim,
    ) {
        val batch = claim.batch
        val conversationId = state.conversationId
        check(claim.inputEffect.identity.conversationId == conversationId)
        val uiToken = claim.uiToken
        val runId = claim.inputEffect.identity.runId
        val modelId = batch.last().modelId
        val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelId) ?: run {
            state.settleGuidanceClaim(claim.lease.id, durable = false)
            state.deferNextQueueDrain()
            state.scope.launch {
                releaseUnlaunchedSlotAndDrain(state, uiToken)
            }
            return
        }
        state.loadingChange(uiToken, true)
        val jobBodyStarted = AtomicBoolean(false)
        val generationJob = state.launchGenerationJob(uiToken) {
            jobBodyStarted.set(true)
            var setupModelMessageId: String? = null
            var graphCommitted = false
            var runBound = false
            var stopFinalizationClaimed = false
            suspend fun reconcileGuidanceOwnership(): Boolean =
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (!graphCommitted) {
                        graphCommitted = conversations.getRun(runId) != null
                    }
                    state.settleGuidanceClaim(claim.lease.id, durable = graphCommitted)
                    graphCommitted
                }
            try {
                val queued = mergeQueuedGuidance(batch)
                val persistId = state.nextPersistId()
                executionCoordinator.withConversationLock(conversationId) {
                    val snapshot = conversations.getMessagesForConversationSnapshot(conversationId)
                    val selections = conversations.restoreBranchSelections(conversationId)
                    val path = ConversationUiState.resolvePath(
                        snapshot.map(toUiMessage),
                        streamingMsg = null,
                        selectedChildren = selections,
                    )
                    val parentId = path.lastOrNull()?.id
                    val start = clock()
                    val users = listOf(
                        MessageEntity(
                            id = queued.id,
                            conversationId = conversationId,
                            parentId = parentId,
                            text = queued.text,
                            images = queued.preparedImages,
                            thoughts = null,
                            status = MessageStatus.SUCCESS,
                            participant = Participant.USER,
                            timestamp = start,
                            attachmentMeta = queued.preparedAttachmentMetaJson,
                            runId = runId,
                            runSequence = 0,
                            consumedAtPass = 0,
                        ),
                    )
                    val modelMessageId = idFactory()
                    setupModelMessageId = modelMessageId
                    val placeholderEntity = MessageEntity(
                        id = modelMessageId,
                        conversationId = conversationId,
                        parentId = users.last().id,
                        text = "",
                        thoughts = null,
                        status = MessageStatus.SENDING,
                        participant = Participant.MODEL,
                        timestamp = start + users.size,
                        modelName = modelId,
                        runId = runId,
                        runSequence = users.size.toLong(),
                    )
                    val selectionUpdates = buildMap<String?, String> {
                        users.forEach { put(it.parentId, it.id) }
                        put(users.last().id, modelMessageId)
                    }
                    val graphCommit = conversations.createRunWithMessages(
                        run = RunEntity(
                            id = runId,
                            conversationId = conversationId,
                            parentRunId = path.lastOrNull()?.runId,
                            status = RunStatus.ACTIVE,
                            activeSlot = 1,
                            startedAt = start,
                            lastCheckpointAt = placeholderEntity.timestamp,
                        ),
                        messages = users + placeholderEntity,
                        messageSelectionUpdates = selectionUpdates,
                    )
                    val committedUsers = graphCommit.messages.dropLast(1)
                    val committedPlaceholder = graphCommit.messages.last()
                    graphCommitted = true
                    check(state.settleGuidanceClaim(claim.lease.id, durable = true))
                    val binding = withContext(kotlinx.coroutines.NonCancellable) {
                        state.finishInputPersistence(claim.inputEffect.identity)
                    }
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
                        return@withConversationLock
                    }
                    committedUsers.forEach { user ->
                        if (user.text.isNotBlank()) {
                            runCatching { onUserMessagePersisted(user.id, user.text) }
                                .onFailure { error ->
                                    DebugLog.w(
                                        "MessageGenerationController",
                                        "Failed to enqueue queued-message indexing for ${user.id}",
                                        error,
                                    )
                                }
                        }
                        try {
                            settings.incrementMessagesSent()
                        } catch (error: Exception) {
                            DebugLog.w(
                                "MessageGenerationController",
                                "Failed to increment the queued sent-message counter",
                                error,
                            )
                        }
                    }
                    val placeholder = toUiMessage(committedPlaceholder)
                    state.streamUpdate(uiToken, placeholder)
                    if (isConversationOpen(conversationId)) {
                        onScrollToAbsoluteBottomAfter(conversationId, committedUsers.last().id)
                        projectGraph(
                            conversationId,
                            committedUsers.map(toUiMessage) + placeholder,
                            graphCommit.messageSelections,
                            placeholder,
                        )
                    }
                    compactController.automaticBeforeBoundary(
                        conversationId = conversationId,
                        fallbackModel = modelId,
                        contextLimit = requestBuilder
                            .buildEffectiveConversationSettings(conversationId)
                            .contextWindow ?: settings.maxContextWindow.value,
                        state = state,
                    )
                    boundRunGenerationLauncher.launch(
                        BoundRunGenerationRequest(
                            conversationId = conversationId,
                            modelMessageId = modelMessageId,
                            startTime = committedPlaceholder.timestamp,
                            isRegenerate = false,
                            replaceMessageId = null,
                            providerName = providerName,
                            modelId = modelId,
                            activeKey = activeKey,
                            uiToken = uiToken,
                            persistId = persistId,
                            runId = runId,
                            pass = 0,
                            callerTag = "guidanceBoundary",
                        ),
                        state,
                    )
                }
            } catch (error: CancellationException) {
                val durable = reconcileGuidanceOwnership()
                if (durable && !runBound && !stopFinalizationClaimed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        val binding = state.finishInputPersistence(claim.inputEffect.identity)
                        stopFinalizationClaimed =
                            terminalSettlement.settleCancelledDurableRun(state, binding)
                        if (!stopFinalizationClaimed) {
                            conversations.finishStoppedGeneration(emptyList(), runId)
                        }
                    }
                }
                throw error
            } catch (error: Exception) {
                val durable = reconcileGuidanceOwnership()
                if (durable && !runBound && !stopFinalizationClaimed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        val binding = state.finishInputPersistence(claim.inputEffect.identity)
                        runBound = binding is ConversationGenerationState.RunBindingOutcome.Active
                        if (binding is ConversationGenerationState.RunBindingOutcome.Stopping) {
                            stopFinalizationClaimed = true
                            terminalSettlement.settleLateBoundStop(state, binding)
                        }
                    }
                } else if (!durable) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        state.commands.inputPersistenceFailed(claim.inputEffect.identity)
                    }
                }
                terminalSettlement.failGenerationSetup(
                    conversationId,
                    runId,
                    setupModelMessageId,
                    uiToken,
                    state,
                    error,
                )
                if (!durable) state.deferNextQueueDrain()
            }
        }
        generationJob?.invokeOnCompletion {
            if (!jobBodyStarted.get()) {
                state.settleGuidanceClaim(claim.lease.id, durable = false)
            }
        }
        if (generationJob == null) {
            state.settleGuidanceClaim(claim.lease.id, durable = false)
            state.scope.launch {
                state.awaitSendAvailable()
                val retryClaim = state.queueMutationMutex.withLock {
                    val retryLease = state.claimQueuedSends() ?: return@withLock null
                    claimUnderLock(state, retryLease)
                }
                retryClaim?.let { launchClaim(state, it) }
            }
        }
    }
}
