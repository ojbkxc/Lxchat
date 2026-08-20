package com.lxseek.chat.viewmodel

import android.app.Application
import android.content.Context
import com.lxseek.chat.R
import com.lxseek.chat.api.local.LocalProvider
import com.lxseek.chat.automation.ConversationExecutionCoordinator
import com.lxseek.chat.data.local.ChatEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.SelectedAttachment
import com.lxseek.chat.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private sealed interface SendPlacement {
    data class Direct(
        val uiToken: Long,
        val runId: String,
        val inputEffect: RunEffect.PersistAcceptedInput,
    ) : SendPlacement
    data class Queued(val messageId: String) : SendPlacement
    data class QueuedAndDrain(
        val messageId: String,
        val claim: QueuedDrainClaim,
    ) : SendPlacement
    data object RetryAfterRelease : SendPlacement
    data object RetryAfterCompact : SendPlacement

    /**
     * The slot was busy and the caller asked for a direct-only send, so NOTHING was persisted.
     *
     * Distinct from [RetryAfterRelease], which waits for the slot to free up. A direct-only caller
     * must never wait: an automation caller already holds the conversation lock that the current
     * slot owner may be blocked on, so waiting there deadlocks the whole conversation.
     */
    data object Rejected : SendPlacement
}

/**
 * Result of delegating one automation (Loop) cycle to the foreground send path.
 *
 * [SlotBusy] means nothing was persisted and nothing generated, so the caller reports a typed busy
 * cycle outcome. It must not fall back to a second headless writer. It is never a partial success:
 * a direct-only send either owns the slot for the whole turn or does not run at all.
 */
internal sealed interface AutomationSendOutcome {
    data object SlotBusy : AutomationSendOutcome

    /** [modelMessageId] is the row this very send created, not a re-derived conversation tail. */
    data class Delivered(val modelMessageId: String) : AutomationSendOutcome
}

/** Dictates whether a send scrolls unconditionally or only while the user is at the bottom. */
internal enum class SendScrollPolicy {
    /** Always request absolute-bottom scroll (manual send, queue drain). */
    FORCE,
    /** Request absolute-bottom scroll only when the viewport is already at the bottom (loop cycle). */
    ATTACHED_ONLY,
}

/**
 * Adapts message commands (send / regenerate / edit / delete) to their typed services and the
 * conversation runtime. Durable accepted-input execution is delegated after mailbox admission.
 *
 * Generation state is held per-conversation in [ConversationGenerationState]
 * (obtained from [ConversationStateRegistry]); the StateFlows ChatViewModel
 * exposes to the UI are a mirror of whichever conversation is currently open.
 * Synchronous writes to those flows inside the generation coroutines are gated
 * on the open conversation via [ifOpenOn] so a background generation can't
 * clobber the visible conversation's UI.
 */
internal class MessageGenerationController(
    private val viewModelScope: CoroutineScope,
    private val application: Application,
    private val appContext: Context,
    // -- Process-scoped collaborators --
    private val convRepo: ConversationRepository,
    private val settings: SettingsRepository,
    private val registry: ConversationStateRegistry,
    private val generationManagerProvider: () -> GenerationManager,
    private val requestBuilder: GenerationRequestBuilder,
    private val payloadBuilder: MessagePayloadBuilder,
    private val providerRegistry: ProviderRegistry,
    private val localProvider: LocalProvider,
    private val executionCoordinator: ConversationExecutionCoordinator,
    // -- Shared UI state: the SAME instances ChatViewModel exposes -never recreate --
    private val renderStore: ConversationRenderStore,
    private val currentConversationId: StateFlow<String?>,
    private val isNewChatMode: StateFlow<Boolean>,
    private val applyPendingConversationSettings: suspend (String) -> Unit,
    private val pendingSystemPromptId: StateFlow<String?>,
    private val currentActiveModel: StateFlow<String>,
    private val messages: StateFlow<List<ChatMessage>>,
    // -- Callbacks into ChatViewModel-owned side effects --
    private val onScrollToMessage: (String?) -> Unit,
    private val onScrollToAbsoluteBottomAfter: (conversationId: String, messageId: String) -> Unit,
    /** Like [onScrollToAbsoluteBottomAfter] but the scroll is suppressed when the viewport is not
     *  already at the bottom. Used by loop cycles so automated messages never steal the user's
     *  scroll position. */
    private val onScrollToAttachedBottomAfter: (conversationId: String, messageId: String) -> Unit,
    /** Fires on every send acceptance (Direct + Queued) regardless of trigger source.
     *  ChatApp wires this to haptics.confirm() so manual send, queue drain, and loop cycle
     *  all produce identical haptic feedback. */
    private val onSendAcceptedEvent: ((conversationId: String, messageId: String) -> Unit)? = null,
    private val onSnackbar: (String) -> Unit,
    private val onSnackbarSuspend: suspend (String) -> Unit,  // sequential emit inside generateTitle
    // Called when sendMessage creates a NEW conversation, so the UI can suppress the
    // conversation-open auto-scroll (the send's own physical-bottom scroll handles it) and
    // avoid a double scroll on the first message of a new chat.
    private val onConversationCreatedBySend: (String) -> Unit = {},
    /** Publishes the first durable Send's conversation into the selection state owner. */
    private val onConversationAcceptedBySend: (String) -> Unit = {},
    // Called once when a hidden task/loop execution becomes searchable. The callback
    // only enqueues background work; embedding computation must not run under the send lock.
    // Called after a USER message row is persisted (send / edit), so incremental RAG
    // indexing covers the user's side too -the model reply is indexed at generation end
    // via GenerationManager.onMessagePersisted, and without this hook user messages only
    // ever entered the cache through a manual full re-cache. Enqueues background work only.
    private val onUserMessagePersisted: (messageId: String, text: String) -> Unit = { _, _ -> },
    /** Covers destructive tree mutation until ChatApp has settled the resulting path. */
    private val onTreeMutationStart: suspend () -> Long? = { null },
    private val onTreeMutationSettling: (requestId: Long?, targetMessageId: String?) -> Unit =
        { _, _ -> },
    private val onTreeMutationFailed: (requestId: Long?) -> Unit = {},
    private val regenerationTransitions: RegenerationTransitionCoordinator,
    private val pauseConversationTasks: suspend (String) -> Unit = {},
) {
    private val titleGenerator = ConversationTitleGenerator(convRepo, settings, providerRegistry)
    private val compactController = ConversationCompactController(
        conversations = convRepo,
        executionCoordinator = executionCoordinator,
        operation = ContextCompactor(
            conversations = convRepo,
            settings = settings,
            providers = providerRegistry,
            pauseLoop = pauseConversationTasks,
        ),
        projectGraph = { conversationId, all, selected ->
            ifOpenOn(conversationId) {
                renderStore.replaceGraph(
                    allMessages = all.map { it.toUiChatMessage(appContext) },
                    selectedChildren = selected,
                )
            }
        },
    )
    private val acceptanceNotifier = SendAcceptanceNotifier(onSendAcceptedEvent)
    private val terminalSettlement = GenerationTerminalSettlementController(
        conversations = convRepo,
        stopFinalizer = GenerationFinalizer(convRepo) { _, _ -> },
        runFinalizationEffects = RunFinalizationEffectCoordinator(),
        failureText = { appContext.getString(R.string.failed_to_generate) },
        toUiMessage = { it.toUiChatMessage(appContext) },
        onSnackbar = onSnackbar,
    )
    private val boundRunGenerationLauncher = BoundRunGenerationLauncher(
        requestBuilder = requestBuilder,
        settings = settings,
        conversations = convRepo,
        generationManagerProvider = generationManagerProvider,
        compactController = compactController,
        terminalSettlement = terminalSettlement,
        toUiMessage = { it.toUiChatMessage(appContext) },
    )
    private val directAcceptedInputExecutor = DirectAcceptedInputEffectExecutor(
        conversations = convRepo,
        settings = settings,
        executionCoordinator = executionCoordinator,
        graphWriter = AcceptedInputGraphWriter(convRepo),
        renderStore = renderStore,
        requestBuilder = requestBuilder,
        compactController = compactController,
        terminalSettlement = terminalSettlement,
        boundRunGenerationLauncher = boundRunGenerationLauncher,
        acceptanceNotifier = acceptanceNotifier,
        toUiMessage = { it.toUiChatMessage(appContext) },
        isConversationOpen = { currentConversationId.value == it },
        applyPendingConversationSettings = applyPendingConversationSettings,
        publishNewConversation = { conversationId ->
            onConversationAcceptedBySend(conversationId)
            onConversationCreatedBySend(conversationId)
        },
        onUserMessagePersisted = onUserMessagePersisted,
        onGenerateTitle = ::generateTitle,
    )
    private val editService = ConversationEditService(
        conversations = convRepo,
        executionCoordinator = executionCoordinator,
        inputCloner = EditedRunInputCloner(
            java.io.File(application.filesDir, "run-inputs"),
        ),
        terminalSettlement = terminalSettlement,
        boundRunGenerationLauncher = boundRunGenerationLauncher,
        toUiMessage = { it.toUiChatMessage(appContext) },
        isConversationOpen = { currentConversationId.value == it },
        projectGraph = { _, committedMessages, selectedChildren, streamingMessage ->
            renderStore.commitGraph(
                committedMessages = committedMessages,
                selectedChildren = selectedChildren,
                streamingMessage = streamingMessage,
            )
        },
        awaitProjectedPath = { conversationId, messageId ->
            combine(messages, currentConversationId) { path, openConversationId ->
                openConversationId != conversationId || path.any { it.id == messageId }
            }.first { projectedOrClosed -> projectedOrClosed }
        },
        onUserMessagePersisted = onUserMessagePersisted,
        onScrollToMessage = { onScrollToMessage(it) },
    )
    private val queuedGuidanceDrainExecutor = QueuedGuidanceDrainExecutor(
        conversations = convRepo,
        settings = settings,
        requestBuilder = requestBuilder,
        executionCoordinator = executionCoordinator,
        compactController = compactController,
        terminalSettlement = terminalSettlement,
        boundRunGenerationLauncher = boundRunGenerationLauncher,
        toUiMessage = { it.toUiChatMessage(appContext) },
        isConversationOpen = { currentConversationId.value == it },
        projectGraph = { _, committedMessages, selectedChildren, streamingMessage ->
            renderStore.commitGraph(
                committedMessages = committedMessages,
                selectedChildren = selectedChildren,
                streamingMessage = streamingMessage,
            )
        },
        onScrollToAbsoluteBottomAfter = onScrollToAbsoluteBottomAfter,
        onUserMessagePersisted = onUserMessagePersisted,
    )
    private val regenerationService = ConversationRegenerationService(
        conversations = convRepo,
        executionCoordinator = executionCoordinator,
        transitions = regenerationTransitions,
        terminalSettlement = terminalSettlement,
        boundRunGenerationLauncher = boundRunGenerationLauncher,
        guidanceDrain = queuedGuidanceDrainExecutor,
        toUiMessage = { it.toUiChatMessage(appContext) },
        isConversationOpen = { currentConversationId.value == it },
        projectGraph = { _, committedMessages, selectedChildren, streamingMessage ->
            renderStore.commitGraph(
                committedMessages = committedMessages,
                selectedChildren = selectedChildren,
                streamingMessage = streamingMessage,
            )
        },
    )
    private val branchMutationService = ConversationBranchMutationService(
        scope = viewModelScope,
        conversations = convRepo,
        executionCoordinator = executionCoordinator,
        toUiMessage = { it.toUiChatMessage(appContext) },
        isConversationOpen = { currentConversationId.value == it },
        projectGraph = { all, selected ->
            renderStore.replaceGraph(allMessages = all, selectedChildren = selected)
        },
        onMutationStart = onTreeMutationStart,
        onMutationSettling = onTreeMutationSettling,
        onMutationFailed = onTreeMutationFailed,
    )

    private fun resolveScrollCallback(policy: SendScrollPolicy): (String, String) -> Unit =
        when (policy) {
            SendScrollPolicy.FORCE -> onScrollToAbsoluteBottomAfter
            SendScrollPolicy.ATTACHED_ONLY -> onScrollToAttachedBottomAfter
        }

    /**
     * Run [block] only if the currently-open conversation is [genId]. Guards synchronous
     * writes to the shared global flows so a background generation (operating on its own
     * private [ConversationGenerationState] flows) cannot clobber the visible conversation's UI.
     */
    private fun ifOpenOn(genId: String, block: () -> Unit) {
        if (currentConversationId.value == genId) block()
    }

    suspend fun compactManual(request: CompactRequest): CompactResult {
        val conversationId = currentConversationId.value
            ?: return CompactResult.Failed("Open a conversation first")
        return compactController.manual(
            conversationId = conversationId,
            request = request,
            state = registry.getOrCreate(conversationId),
        )
    }

    // ==================================
    // deleteMessage
    // ==================================

    /**
     * Deletes one structural message branch. A USER target removes its complete edit subtree; a
     * MODEL target removes its regeneration subtree while retaining the shared boundary USER.
     * ACTIVE and STOPPING both reject deletion; Stop is never an implicit side effect.
     */
    fun deleteMessage(messageId: String): Int {
        val currentId = currentConversationId.value ?: return 0
        val state = registry.getOrCreate(currentId)
        return branchMutationService.delete(
            conversationId = currentId,
            messageId = messageId,
            state = state,
            snapshot = renderStore.allMessages,
        )
    }

    // ==================================
    // regenerate
    // ==================================

    fun regenerate(messageId: String): Boolean {
        val genId = currentConversationId.value ?: return false
        val state = registry.getOrCreate(genId)
        val modelId = currentActiveModel.value
        val (providerName, activeKey) =
            requestBuilder.resolveProviderKey(modelId) ?: return false
        return regenerationService.regenerate(
            ConversationRegenerationRequest(
                conversationId = genId,
                messageId = messageId,
                modelId = modelId,
                providerName = providerName,
                activeKey = activeKey,
                visiblePath = messages.value.toList(),
            ),
            state,
        )
    }

    // ==================================
    // editMessage
    // ==================================

    suspend fun editMessage(messageId: String, newText: String): Boolean =
        withContext(Dispatchers.Default) {
            editMessageOffMain(messageId, newText)
        }

    private suspend fun editMessageOffMain(messageId: String, newText: String): Boolean {
        if (newText.isBlank()) return false
        val genId = currentConversationId.value ?: return false
        val state = registry.getOrCreate(genId)
        val modelId = currentActiveModel.value
        val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelId) ?: return false
        return editService.edit(
            ConversationEditRequest(
                conversationId = genId,
                messageId = messageId,
                newText = newText,
                modelId = modelId,
                providerName = providerName,
                activeKey = activeKey,
                visiblePath = messages.value.toList(),
            ),
            state,
        )
    }

    // ==================================
    // sendMessage
    // ==================================

    suspend fun sendMessage(
        text: String,
        images: List<String> = emptyList(),
        attachments: List<SelectedAttachment> = emptyList(),
        onAccepted: suspend (SendAcceptance) -> Unit = {},
    ): SendAcceptance? = withContext(Dispatchers.Default) {
        sendMessageOffMain(text, images, attachments, onAccepted)
    }

    private suspend fun sendMessageOffMain(
        text: String,
        images: List<String>,
        attachments: List<SelectedAttachment>,
        onAccepted: suspend (SendAcceptance) -> Unit,
    ): SendAcceptance? {
        val selectedModelId = currentActiveModel.value
        // Pre-flight: a blank model fails fast BEFORE creating a new-chat row or enqueueing, so the
        // Send button never swallows a message into a conversation that can't generate.
        if (selectedModelId.isBlank()) {
            onSnackbar(application.getString(R.string.no_model_selected))
            return null
        }
        // Resolve a stable id before claiming the generation slot, but do not publish a new-chat
        // transition yet. Its conversation + Run + message graph commit atomically below; only
        // after the composer acknowledges that durable success may the screen switch and render.
        val wasNewChat = isNewChatMode.value || currentConversationId.value == null
        val genId = if (wasNewChat) {
            UUID.randomUUID().toString()
        } else {
            currentConversationId.value ?: return null
        }
        val newConversation = if (wasNewChat) {
            ChatEntity(
                id = genId,
                title = appContext.getString(R.string.new_chat),
                modelId = selectedModelId,
                systemPromptId = pendingSystemPromptId.value,
            )
        } else {
            null
        }
        return sendInto(
            genId = genId,
            wasNewChat = wasNewChat,
            newConversation = newConversation,
            text = text,
            images = images,
            attachments = attachments,
            modelId = selectedModelId,
            onAccepted = onAccepted,
        )
    }

    internal suspend fun drainQueuedAfterGeneration(state: ConversationGenerationState) {
        queuedGuidanceDrainExecutor.drainAfterSettlement(state)
    }

    internal suspend fun drainQueuedAfterStop(state: ConversationGenerationState) =
        drainQueuedAfterGeneration(state)

    /**
     * Core send into a KNOWN conversation [genId] (never re-reads currentConversationId, so a
     * background send lands in its own conversation). Placement enters the conversation command
     * mailbox: a bound or preparing Run accepts memory-only guidance, Compact waits only for its
     * exact settlement, STOPPING waits for release, and IDLE emits one identified persistence
     * effect before generation launches. The installed Job's completion hook releases the slot and
     * requests queue drain; pre-launch failures release via
     * [QueuedGuidanceDrainExecutor.releaseUnlaunchedSlotAndDrain].
     */
    private suspend fun sendInto(
        genId: String,
        wasNewChat: Boolean,
        newConversation: ChatEntity?,
        text: String,
        images: List<String>,
        attachments: List<SelectedAttachment>,
        modelId: String,
        onAccepted: suspend (SendAcceptance) -> Unit,
        scrollPolicy: SendScrollPolicy = SendScrollPolicy.FORCE,
        alreadyHoldsLock: Boolean = false,
        directOnly: Boolean = false,
        /** Reports the model row this send created, so an automation caller never has to re-derive
         *  it by scanning the conversation tail (a concurrent branch would win that scan). */
        onModelMessageCreated: ((String) -> Unit)? = null,
        onGenerationJob: ((kotlinx.coroutines.Job?) -> Unit)? = null,
    ): SendAcceptance? {
        val state = registry.getOrCreate(genId)
        val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelId) ?: return null
        if (providerName == Constants.PROVIDER_LOCAL) {
            val localModelId = modelId.substringAfter("${Constants.PROVIDER_LOCAL}:")
            val config = settings.localChatModels.value.find { it.modelId == localModelId }
            if (config == null || !java.io.File(config.localFilePath).exists()) {
                onSnackbar(application.getString(R.string.local_model_not_found))
                return null
            }
        }

        // Expensive media work finishes before the atomic placement decision. The composer does
        // not clear until this function returns, and the placement below does not report success
        // until Room owns every input/file reference.
        val payloadLease = PreparedMessagePayloadLease(
            payloadBuilder.buildMessagePayload(application, images, attachments),
        )
        val payload = payloadLease.payload

        suspend fun enqueueAcceptedGuidance(runId: String): QueuedSend {
            val queued = QueuedSend(
                id = UUID.randomUUID().toString(),
                text = text,
                modelId = modelId,
                attachments = attachments,
                runId = runId,
                images = images,
                preparedImages = payload.allImages,
                preparedAttachmentMetaJson = payload.attachmentMeta?.let(Json::encodeToString),
                preparedOwnedPaths = payload.preparedOwnedPaths,
            )
            // Guidance acceptance is intentionally memory-only. The current provider pass
            // observes it through hasQueuedSends(), but Room, the selected tree, and LazyColumn
            // cannot expose a bubble before the next durable boundary.
            state.enqueueSend(queued)
            payloadLease.transferOwnership()
            try {
                acceptanceNotifier.notify(
                    acceptance = SendAcceptance.Queued(queued.id, genId),
                    onAccepted = onAccepted,
                )
            } catch (error: Exception) {
                state.removeQueuedSend(queued.id)
                payloadLease.reclaimAfterRejectedTransfer()
                throw error
            }
            return queued
        }

        var placement: SendPlacement? = null
        val proposedRunId = UUID.randomUUID().toString()
        val sendEffectId = "send-$proposedRunId"
        try {
            while (placement == null) {
                val decision = state.queueMutationMutex.withLock {
                    val pendingQueue = state.queuedSends.value
                    val transition = state.commands.requestSend(
                        proposedRunId = proposedRunId,
                        effectId = sendEffectId,
                        directOnly = directOnly,
                        hasPendingGuidance = pendingQueue.isNotEmpty(),
                    )
                    check(transition.accepted)
                    when (val effect = transition.effects.single()) {
                        is RunEffect.PersistAcceptedInput -> SendPlacement.Direct(
                            uiToken = effect.identity.ownerToken,
                            runId = effect.identity.runId,
                            inputEffect = effect,
                        )
                        is RunEffect.DrainGuidanceFirst -> {
                            // A previously accepted batch may still be waiting for its asynchronous
                            // handoff. Never let a newer Direct send leapfrog the FIFO batch.
                            check(!directOnly)
                            val queued = enqueueAcceptedGuidance(pendingQueue.last().runId)
                            val lease = checkNotNull(state.claimQueuedSends())
                            val claim = queuedGuidanceDrainExecutor.claimUnderLock(state, lease)
                            if (claim != null) {
                                SendPlacement.QueuedAndDrain(queued.id, claim)
                            } else {
                                SendPlacement.Queued(queued.id)
                            }
                        }
                        is RunEffect.AcceptGuidance -> {
                            // Reducer acceptance is the only placement authority. The guidance is
                            // memory-only, so a concurrent Room terminalization cannot attach it to
                            // the old Run. If settlement already won, immediately hand the FIFO
                            // batch back through a fresh normal Send rather than stranding it.
                            val queued = enqueueAcceptedGuidance(effect.identity.runId)
                            if (!state.generating.value) {
                                val lease = checkNotNull(state.claimQueuedSends())
                                val claim = queuedGuidanceDrainExecutor.claimUnderLock(state, lease)
                                if (claim != null) {
                                    SendPlacement.QueuedAndDrain(queued.id, claim)
                                } else {
                                    SendPlacement.Queued(queued.id)
                                }
                            } else {
                                SendPlacement.Queued(queued.id)
                            }
                        }
                        is RunEffect.AwaitRunRelease -> SendPlacement.RetryAfterRelease
                        is RunEffect.AwaitCompactSettlement -> SendPlacement.RetryAfterCompact
                        is RunEffect.RejectSendBusy -> SendPlacement.Rejected
                        else -> error(
                            "SendRequested emitted unexpected effect ${effect.javaClass.simpleName}",
                        )
                    }
                }
                if (decision == SendPlacement.RetryAfterRelease) {
                    state.awaitSendAvailable()
                } else if (decision == SendPlacement.RetryAfterCompact) {
                    state.awaitCompactSettled()
                } else {
                    placement = decision
                }
            }
        } catch (error: Exception) {
            payloadLease.releaseIfUnowned()
            throw error
        }

        if (placement is SendPlacement.Rejected) {
            payloadLease.releaseIfUnowned()
            return null
        }
        if (placement is SendPlacement.Queued) {
            return SendAcceptance.Queued(placement.messageId, genId)
        }
        if (placement is SendPlacement.QueuedAndDrain) {
            queuedGuidanceDrainExecutor.launchClaim(state, placement.claim)
            return SendAcceptance.Queued(placement.messageId, genId)
        }
        val direct = placement as SendPlacement.Direct
        val execution = directAcceptedInputExecutor.launch(
            DirectAcceptedInputRequest(
                inputEffect = direct.inputEffect,
                wasNewChat = wasNewChat,
                newConversation = newConversation,
                userText = text,
                payloadLease = payloadLease,
                modelId = modelId,
                providerName = providerName,
                activeKey = activeKey,
                alreadyHoldsLock = alreadyHoldsLock,
                requestScroll = resolveScrollCallback(scrollPolicy),
                onAccepted = onAccepted,
                onModelMessageCreated = onModelMessageCreated,
            ),
            state,
        )
        onGenerationJob?.invoke(execution.job)
        return execution.awaitAcceptance()
    }

    /**
     * Entry point for loop cycles on the foreground-open conversation.
     *
     * The coordinator lock is already held by `LoopManager.executeByConversationId`, so neither
     * the setup phase nor the generation job re-acquires it. That is only correct because this
     * function SUSPENDS until the generation job completes: the caller's lease therefore spans the
     * whole turn, exactly as it would for a headless run. Returning early would leave the
     * generation running unlocked and would also report success to the Loop before the cycle
     * actually produced anything.
     *
     * `directOnly` is mandatory here, not an optimization. Both alternatives would need the
     * conversation lock this caller already holds:
     *  - waiting on `generating` deadlocks against a manual send that is itself blocked on the
     *    lock, because that send can only release the slot after acquiring it;
     *  - a queued send is answered by a drain that also takes the lock, so this function would
     *    have no job to join and would have to guess an outcome it never observed.
     * [AutomationSendOutcome.SlotBusy] lets the Loop treat the cycle as not-run instead.
     *
     * [AutomationSendOutcome.Delivered.modelMessageId] is the row this send actually created.
     * Callers must never re-derive it by scanning the conversation tail: a concurrent branch or an
     * older run can win that scan and report a previous turn's answer as this cycle's result.
     *
     * Scrolls only when the viewport is attached at the bottom ([SendScrollPolicy.ATTACHED_ONLY])
     * so automated messages never steal the user's scroll position.
     */
    internal suspend fun sendMessageFromAutomationAwaitingCompletion(
        genId: String,
        text: String,
        modelId: String,
    ): AutomationSendOutcome {
        var generationJob: kotlinx.coroutines.Job? = null
        var createdModelMessageId: String? = null
        val acceptance = sendInto(
            genId = genId,
            wasNewChat = false,
            newConversation = null,
            text = text,
            images = emptyList(),
            attachments = emptyList(),
            modelId = modelId,
            onAccepted = {},
            scrollPolicy = SendScrollPolicy.ATTACHED_ONLY,
            alreadyHoldsLock = true,
            directOnly = true,
            onModelMessageCreated = { createdModelMessageId = it },
            onGenerationJob = { generationJob = it },
        ) ?: return AutomationSendOutcome.SlotBusy
        // directOnly makes Queued unreachable. Assert rather than reporting a cycle this call
        // never actually ran.
        check(acceptance is SendAcceptance.Direct) {
            "A direct-only automation send must never be queued"
        }
        // launchGenerationJob returns null when the slot was revoked between claim and launch (a
        // Stop landing in that window). Nothing generated, so the cycle did not run.
        val job = generationJob ?: return AutomationSendOutcome.SlotBusy
        try {
            job.join()
        } catch (e: CancellationException) {
            // The Loop/Worker lease owns this delegated turn. If its caller is cancelled, do not
            // leave a process-scoped controller job generating outside that released lease.
            job.cancel()
            throw e
        }
        val modelMessageId = createdModelMessageId ?: return AutomationSendOutcome.SlotBusy
        return AutomationSendOutcome.Delivered(modelMessageId)
    }

    fun generateTitle(conversationId: String) {
        viewModelScope.launch {
            settings.awaitInitialLoad()
            if (settings.titleGenerationNotificationsEnabled.value) {
                onSnackbarSuspend(appContext.getString(R.string.snackbar_generating_title))
            }
            when (titleGenerator.generateAndPersist(conversationId)) {
                is ConversationTitleGenerator.Result.Success -> {
                    if (settings.titleGenerationNotificationsEnabled.value) {
                        onSnackbarSuspend(appContext.getString(R.string.snackbar_title_generated))
                    }
                }
                is ConversationTitleGenerator.Result.Failure -> {
                    if (settings.titleGenerationNotificationsEnabled.value) {
                        onSnackbarSuspend(appContext.getString(R.string.snackbar_title_error))
                    }
                }
            }
        }
    }
}
