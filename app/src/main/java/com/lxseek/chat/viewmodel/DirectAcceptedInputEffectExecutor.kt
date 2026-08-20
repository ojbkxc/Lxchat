package com.lxseek.chat.viewmodel

import com.lxseek.chat.automation.ConversationExecutionCoordinator
import com.lxseek.chat.data.local.ChatEntity
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns temporary files created while preparing one Send until queue or Room takes ownership.
 *
 * The lease is deliberately independent from Run state. Transferring it records only file
 * ownership; it cannot accept a command, bind a Run, or start generation.
 */
internal class PreparedMessagePayloadLease(
    val payload: MessagePayloadBuilder.MessagePayload,
    private val deletePath: (String) -> Unit = { path -> File(path).delete() },
) {
    private val transferred = AtomicBoolean(false)
    private val cleaned = AtomicBoolean(false)

    fun transferOwnership() {
        transferred.set(true)
    }

    fun reclaimAfterRejectedTransfer() {
        transferred.set(false)
    }

    fun releaseIfUnowned() {
        if (!transferred.get() && cleaned.compareAndSet(false, true)) {
            payload.preparedOwnedPaths.forEach(deletePath)
        }
    }
}

/** Complete immutable input for executing one reducer-authorized accepted-input effect. */
internal data class DirectAcceptedInputRequest(
    val inputEffect: RunEffect.PersistAcceptedInput,
    val wasNewChat: Boolean,
    val newConversation: ChatEntity?,
    val userText: String,
    val payloadLease: PreparedMessagePayloadLease,
    val modelId: String,
    val providerName: String,
    val activeKey: String,
    val alreadyHoldsLock: Boolean,
    val requestScroll: (conversationId: String, messageId: String) -> Unit,
    val onAccepted: suspend (SendAcceptance) -> Unit,
    val onModelMessageCreated: ((String) -> Unit)?,
) {
    val conversationId: String get() = inputEffect.identity.conversationId
    val runId: String get() = inputEffect.identity.runId
    val uiToken: Long get() = inputEffect.identity.ownerToken

    init {
        require(inputEffect.identity.pass == 0)
        require(modelId.isNotBlank())
        require(newConversation == null || newConversation.id == conversationId)
        require(wasNewChat == (newConversation != null))
    }
}

/**
 * Call-scoped handle returned immediately after the runtime host accepts the generation Job.
 * The executor retains neither field after [launch] returns.
 */
internal class DirectAcceptedInputExecution(
    val job: Job?,
    private val durableAcceptance: CompletableDeferred<SendAcceptance?>,
) {
    suspend fun awaitAcceptance(): SendAcceptance? = durableAcceptance.await()
}

/**
 * Executes one identified [RunEffect.PersistAcceptedInput] after Send admission selected Direct.
 *
 * This executor owns no RunState, mailbox, scope, Job field, guidance lease, overlay, or next-stage
 * policy. Room commit is the durable boundary. The matching identified result is delivered to the
 * call-scoped runtime host, and only an Active binding permits Compact/provider execution through
 * the existing downstream ports.
 */
internal class DirectAcceptedInputEffectExecutor(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val graphWriter: AcceptedInputGraphWriter,
    private val renderStore: ConversationRenderStore,
    private val requestBuilder: GenerationRequestBuilder,
    private val compactController: ConversationCompactController,
    private val terminalSettlement: GenerationTerminalSettlementController,
    private val boundRunGenerationLauncher: BoundRunGenerationLauncher,
    private val acceptanceNotifier: SendAcceptanceNotifier,
    private val toUiMessage: (MessageEntity) -> ChatMessage,
    private val isConversationOpen: (String) -> Boolean,
    private val applyPendingConversationSettings: suspend (String) -> Unit,
    private val publishNewConversation: (String) -> Unit,
    private val onUserMessagePersisted: (messageId: String, text: String) -> Unit,
    private val onGenerateTitle: (String) -> Unit,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun launch(
        request: DirectAcceptedInputRequest,
        state: ConversationGenerationState,
    ): DirectAcceptedInputExecution {
        check(state.conversationId == request.conversationId)
        val acceptance = CompletableDeferred<SendAcceptance?>()
        val job = state.launchGenerationJob(request.uiToken) generation@ {
            executeInGenerationJob(request, state, acceptance)
        }
        if (job == null) {
            request.payloadLease.releaseIfUnowned()
            acceptance.complete(null)
        } else {
            // Covers cancellation before the coroutine body reaches its own finally block.
            job.invokeOnCompletion {
                request.payloadLease.releaseIfUnowned()
                acceptance.complete(null)
            }
        }
        return DirectAcceptedInputExecution(job, acceptance)
    }

    private suspend fun executeInGenerationJob(
        request: DirectAcceptedInputRequest,
        state: ConversationGenerationState,
        durableAcceptance: CompletableDeferred<SendAcceptance?>,
    ) {
        val persistId = state.nextPersistId()
        var runBound = false
        var bindingOutcome: ConversationGenerationState.RunBindingOutcome =
            ConversationGenerationState.RunBindingOutcome.Rejected
        var inputGraphCommitted = false
        val userMessageId = idFactory()
        val modelMessageId = idFactory()
        var roomProjectionFence: RoomMessageProjectionFence? = null

        suspend fun reconcileCommittedInput(): Boolean = withContext(NonCancellable) {
            if (!inputGraphCommitted) {
                inputGraphCommitted = conversations.getRun(request.runId) != null
            }
            if (!inputGraphCommitted) return@withContext false
            request.payloadLease.transferOwnership()
            if (bindingOutcome is ConversationGenerationState.RunBindingOutcome.Rejected) {
                bindingOutcome = state.finishInputPersistence(request.inputEffect.identity)
                runBound = bindingOutcome is ConversationGenerationState.RunBindingOutcome.Active
            }
            if (!durableAcceptance.isCompleted) {
                val accepted = SendAcceptance.Direct(userMessageId, request.conversationId)
                acceptanceNotifier.notify(accepted, request.onAccepted)
                durableAcceptance.complete(accepted)
                runCatching { request.onModelMessageCreated?.invoke(modelMessageId) }
                if (request.wasNewChat) publishNewConversation(request.conversationId)
            }
            true
        }

        try {
            withOptionalLock(request.conversationId, request.alreadyHoldsLock) generationLock@ {
                applyPendingConversationSettings(request.conversationId)
                val graphCommit = graphWriter.commit(
                    request = AcceptedInputGraphWriter.Request(
                        inputEffect = request.inputEffect,
                        userMessageId = userMessageId,
                        modelMessageId = modelMessageId,
                        userText = request.userText,
                        images = request.payloadLease.payload.allImages,
                        attachmentMeta = request.payloadLease.payload.attachmentMeta
                            ?.let(Json::encodeToString),
                        modelId = request.modelId,
                        userTimestamp = clock(),
                        newConversation = request.newConversation,
                    ),
                    beforeRoomCommit = {
                        if (!request.wasNewChat && isConversationOpen(request.conversationId)) {
                            roomProjectionFence = renderStore.beginRoomMessageProjectionFence()
                        }
                    },
                )
                val userEntity = graphCommit.userMessage
                val modelEntity = graphCommit.modelMessage
                inputGraphCommitted = true
                request.payloadLease.transferOwnership()

                // Room already committed. A caller cancellation cannot undo this acknowledgement.
                withContext(NonCancellable) {
                    bindingOutcome = state.finishInputPersistence(request.inputEffect.identity)
                    runBound = bindingOutcome is ConversationGenerationState.RunBindingOutcome.Active
                    notifyPersistedUser(userMessageId, request.userText)
                    val accepted = SendAcceptance.Direct(userMessageId, request.conversationId)
                    acceptanceNotifier.notify(accepted, request.onAccepted)
                    durableAcceptance.complete(accepted)
                    runCatching { request.onModelMessageCreated?.invoke(modelMessageId) }
                        .onFailure { error ->
                            DebugLog.w(
                                "AcceptedInputExecutor",
                                "Failed to report created model row $modelMessageId",
                                error,
                            )
                        }

                    if (request.wasNewChat) {
                        request.requestScroll(request.conversationId, userMessageId)
                        publishNewConversation(request.conversationId)
                    }

                    val placeholder = toUiMessage(modelEntity)
                    if (runBound) {
                        state.loadingChange(request.uiToken, true)
                        state.streamUpdate(request.uiToken, placeholder)
                    }
                    if (isConversationOpen(request.conversationId)) {
                        if (!request.wasNewChat) {
                            request.requestScroll(request.conversationId, userMessageId)
                        }
                        renderStore.commitGraph(
                            committedMessages = listOf(
                                toUiMessage(userEntity),
                                if (runBound) placeholder
                                else placeholder.copy(status = MessageStatus.STOPPED),
                            ),
                            selectedChildren = graphCommit.messageSelections,
                            streamingMessage = if (runBound) placeholder else null,
                            roomProjectionFence = roomProjectionFence,
                        )
                        roomProjectionFence = null
                    }
                    roomProjectionFence?.let(renderStore::releaseRoomMessageProjectionFence)
                    roomProjectionFence = null
                }

                if (!runBound) {
                    val stopping = bindingOutcome as?
                        ConversationGenerationState.RunBindingOutcome.Stopping
                    if (stopping != null) {
                        terminalSettlement.settleLateBoundStop(state, stopping)
                    } else {
                        withContext(NonCancellable) {
                            conversations.finishStoppedGeneration(emptyList(), request.runId)
                        }
                    }
                    return@generationLock
                }
                if (!request.wasNewChat) {
                    compactController.automaticBeforeBoundary(
                        conversationId = request.conversationId,
                        fallbackModel = request.modelId,
                        contextLimit = requestBuilder
                            .buildEffectiveConversationSettings(request.conversationId)
                            .contextWindow ?: settings.maxContextWindow.value,
                        state = state,
                    )
                }
                boundRunGenerationLauncher.launch(
                    BoundRunGenerationRequest(
                        conversationId = request.conversationId,
                        modelMessageId = modelMessageId,
                        startTime = modelEntity.timestamp,
                        isRegenerate = false,
                        replaceMessageId = null,
                        providerName = request.providerName,
                        modelId = request.modelId,
                        activeKey = request.activeKey,
                        uiToken = request.uiToken,
                        persistId = persistId,
                        runId = request.runId,
                        pass = 0,
                        callerTag = "sendMessage",
                    ),
                    state,
                )
                val lastMessage = conversations
                    .getMessagesForConversationSnapshot(request.conversationId)
                    .find { it.id == modelMessageId }
                if (
                    request.wasNewChat &&
                    settings.titleGenerationEnabled.value &&
                    kotlinx.coroutines.currentCoroutineContext().isActive &&
                    lastMessage?.status != MessageStatus.ERROR
                ) {
                    onGenerateTitle(request.conversationId)
                }
            }
        } catch (error: CancellationException) {
            if (
                !runBound &&
                bindingOutcome is ConversationGenerationState.RunBindingOutcome.Rejected
            ) {
                withContext(NonCancellable) {
                    if (reconcileCommittedInput()) {
                        val claimed = terminalSettlement.settleCancelledDurableRun(
                            state,
                            bindingOutcome,
                        )
                        if (!claimed) {
                            conversations.finishStoppedGeneration(emptyList(), request.runId)
                        }
                    }
                }
            }
            throw error
        } catch (error: Exception) {
            val durable = reconcileCommittedInput()
            if (!durable) {
                withContext(NonCancellable) {
                    runCatching {
                        state.commands.inputPersistenceFailed(request.inputEffect.identity)
                    }
                }
            } else {
                val stopping = bindingOutcome as?
                    ConversationGenerationState.RunBindingOutcome.Stopping
                if (stopping != null) {
                    terminalSettlement.settleLateBoundStop(state, stopping)
                }
            }
            terminalSettlement.failGenerationSetup(
                conversationId = request.conversationId,
                runId = request.runId,
                modelMessageId = modelMessageId,
                uiToken = request.uiToken,
                state = state,
                error = error,
            )
        } finally {
            roomProjectionFence?.let(renderStore::releaseRoomMessageProjectionFence)
            if (!durableAcceptance.isCompleted) durableAcceptance.complete(null)
            request.payloadLease.releaseIfUnowned()
        }
    }

    private suspend fun <T> withOptionalLock(
        conversationId: String,
        alreadyHoldsLock: Boolean,
        block: suspend () -> T,
    ): T = if (alreadyHoldsLock) block()
    else executionCoordinator.withConversationLock(conversationId, block)

    private suspend fun notifyPersistedUser(messageId: String, text: String) {
        if (text.isNotBlank()) {
            runCatching { onUserMessagePersisted(messageId, text) }
                .onFailure { error ->
                    DebugLog.w(
                        "AcceptedInputExecutor",
                        "Failed to enqueue user-message indexing for $messageId",
                        error,
                    )
                }
        }
        try {
            settings.incrementMessagesSent()
        } catch (error: Exception) {
            DebugLog.w(
                "AcceptedInputExecutor",
                "Failed to increment the sent-message counter",
                error,
            )
        }
    }
}
