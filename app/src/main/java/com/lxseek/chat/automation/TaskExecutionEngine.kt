package com.lxseek.chat.automation

import android.app.Application
import android.content.Context
import com.lxseek.chat.api.local.LocalProvider
import com.lxseek.chat.data.MemoryManager
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import com.lxseek.chat.sandbox.SandboxManagerFactory
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.viewmodel.CompactResult
import com.lxseek.chat.viewmodel.AcceptedInputGraphWriter
import com.lxseek.chat.viewmodel.ContextCompactEffectCoordinator
import com.lxseek.chat.viewmodel.ContextCompactor
import com.lxseek.chat.viewmodel.ConversationTitleGenerator
import com.lxseek.chat.viewmodel.GenerationManager
import com.lxseek.chat.viewmodel.GenerationFinalizer
import com.lxseek.chat.viewmodel.ConversationStateRegistry
import com.lxseek.chat.viewmodel.ConversationGenerationState
import com.lxseek.chat.viewmodel.GenerationRequestBuilder
import com.lxseek.chat.viewmodel.ProviderRegistry
import com.lxseek.chat.viewmodel.RagManager
import com.lxseek.chat.viewmodel.RunFinalizationEffectCoordinator
import com.lxseek.chat.viewmodel.ShellConfirmationController
import com.lxseek.chat.viewmodel.fallbackConversationTitle
import com.lxseek.chat.viewmodel.toUiChatMessage
import com.lxseek.chat.tool.McpToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Headless single-shot generation engine (process-scoped).
 *
 * Drives one complete generation (including the agentic tool loop) for a conversation without
 * depending on a ViewModel or Compose state, while reusing the same [GenerationManager] pipeline
 * as foreground generation. Background Task/Loop runners call [runOnce]; when the conversation is
 * open, the engine attaches to its shared generation state so Stop and queued guidance retain the
 * same ownership semantics as the foreground path.
 *
 * Collaborators are the process-scoped singletons from `AppContainer`, so the
 * background engine shares the live provider map, the on-device llama engine, and
 * the conversation/settings repositories with the UI.
 */
class TaskExecutionEngine(
    private val application: Application,
    private val appContext: Context,
    private val convRepo: ConversationRepository,
    private val settings: SettingsRepository,
    private val memoryManager: MemoryManager,
    private val providerRegistry: ProviderRegistry,
    localProvider: LocalProvider,
    sandboxFactory: SandboxManagerFactory?,
    private val appScope: CoroutineScope,
    private val executionCoordinator: ConversationExecutionCoordinator,
    shellConfirmation: ShellConfirmationController,
    mcpToolProvider: McpToolProvider,
    private val generationRegistry: ConversationStateRegistry,
    private val automationExecutionGate: AutomationExecutionGate = AutomationExecutionGate(),
    private val pauseConversationLoop: suspend (String) -> Unit = {},
) {
    sealed interface Result {
        data class Success(val modelMessageId: String, val text: String) : Result
        data class Busy(val reason: String = "Conversation is already generating") : Result
        data class Failure(val reason: String) : Result
    }

    /**
     * Optional bridge that redirects loop cycles on the foreground-open conversation through the
     * regular MessageGenerationController send path (with attached-only scroll) instead of the
     * headless engine path. Set by ChatViewModel when it is constructed; cleared on dispose.
     *
     * Contract: the bridge SUSPENDS until the delegated turn finishes and reports the durable
     * outcome. It must not return as soon as the send is accepted, otherwise the caller's
     * conversation lease would be released while the generation is still running, and the Loop
     * would record the cycle as complete before it produced anything.
     */
    private val foregroundBridgeLock = Any()
    private var foregroundBridgeOwner: Any? = null
    private var foregroundSendBridge: (suspend (conversationId: String, userText: String, modelId: String) -> BridgeOutcome)? = null

    /** Owner-token binding prevents an older ViewModel's late onCleared from erasing a newer one. */
    fun attachForegroundSendBridge(
        owner: Any,
        bridge: suspend (conversationId: String, userText: String, modelId: String) -> BridgeOutcome,
    ) = synchronized(foregroundBridgeLock) {
        foregroundBridgeOwner = owner
        foregroundSendBridge = bridge
    }

    fun detachForegroundSendBridge(owner: Any) = synchronized(foregroundBridgeLock) {
        if (foregroundBridgeOwner !== owner) return@synchronized
        foregroundBridgeOwner = null
        foregroundSendBridge = null
    }

    private fun currentForegroundSendBridge() = synchronized(foregroundBridgeLock) {
        foregroundSendBridge
    }

    /** Outcome of a delegated foreground send. [NotDelegated] means the caller must run headlessly. */
    sealed interface BridgeOutcome {
        data object NotDelegated : BridgeOutcome
        data class Busy(val reason: String = "Conversation is already generating") : BridgeOutcome
        data class Completed(val modelMessageId: String, val text: String) : BridgeOutcome
        data class Failed(val reason: String) : BridgeOutcome
    }

    /** Embedding subsystem powering RAG/semantic-search context during generation.
     *  One per engine, mirrors `ChatViewModel.ragManager` but on the app scope. */
    private val ragManager = RagManager(
        conversations = convRepo,
        settings = settings,
        localProvider = localProvider,
        appContext = appContext,
        scope = appScope,
        emitSnackbar = {},
    )
    private val stopFinalizer = GenerationFinalizer(convRepo, ragManager::indexMessageForRag)
    private val runFinalizationEffects = RunFinalizationEffectCoordinator()
    private val titleGenerator = ConversationTitleGenerator(convRepo, settings, providerRegistry)
    private val contextCompactor = ContextCompactor(
        conversations = convRepo,
        settings = settings,
        providers = providerRegistry,
        pauseLoop = pauseConversationLoop,
    )
    private val acceptedInputGraphWriter = AcceptedInputGraphWriter(convRepo)
    private val compactEffectCoordinator = ContextCompactEffectCoordinator()

    private suspend fun settleStopEffect(
        state: ConversationGenerationState,
        effect: RunEffect.FinalizeStop,
        messages: List<ChatMessage>,
    ) = withContext(NonCancellable) {
        stopFinalizer.launchStopFinalization(
            scope = state.scope,
            identity = effect.identity,
            messages = messages,
        ) { completion ->
            val result = state.finishStopFinalization(completion)
            if (result.accepted && completion.success) state.clearStoppedOverlay()
        }.join()
    }

    /**
     * Task-only post-processing. Loop runs share this engine but never call this method, so a
     * conversation loop cannot repeatedly retitle itself after every cycle.
     */
    suspend fun updateTaskExecutionTitle(conversationId: String, response: String) {
        settings.awaitInitialLoad()
        providerRegistry.awaitInitialSync()
        if (settings.titleGenerationEnabled.value) {
            when (val result = titleGenerator.generateAndPersist(conversationId)) {
                is ConversationTitleGenerator.Result.Success -> return
                is ConversationTitleGenerator.Result.Failure ->
                    DebugLog.w(
                        "TaskExecutionEngine",
                        "Task title generation failed; using response fallback",
                    )
            }
        }
        val fallback = fallbackConversationTitle(response)
        if (fallback.isBlank()) return
        convRepo.getConversation(conversationId)?.let { conversation ->
            convRepo.updateConversationTitleIfUnchanged(
                id = conversationId,
                expectedTitle = conversation.title,
                newTitle = fallback,
            )
        }
    }

    private val generationManager = GenerationManager(
        app = application,
        conversations = convRepo,
        memoryManager = memoryManager,
        providers = providerRegistry.all,
        context = appContext,
        sandboxFactory = sandboxFactory,
        additionalToolProviders = listOf(mcpToolProvider),
    ).also {
        // Foreground Task/Loop executions share the exact same prompt and session trust state as
        // Chat. ShellConfirmationController itself fails fast when no Activity is visible.
        it.onConfirmShellCommand = shellConfirmation::confirm
        it.onToolApproval = { request ->
            val allowed = shellConfirmation.confirm(request.toolName, request.summary)
            if (allowed) com.lxseek.chat.tool.ToolApprovalResult.Approved
            else com.lxseek.chat.tool.ToolApprovalResult.Denied("user declined")
        }
    }

    /**
     * Injects [userText] as a new user turn at the leaf of [conversationId] and runs
     * one full generation, persisting the assistant reply. [modelId] is the prefixed
     * model id (e.g. "OpenAI:gpt-4o"); null/blank falls back to the app default model.
     *
     * [systemPromptOverride] bypasses the per-conversation / active-prompt resolution:
     * pass a task's own system prompt, or "" to run with no system prompt at all (the
     * default for task executions). Leave null to resolve the prompt the way the
     * foreground chat does (conversation's prompt id, falling back to the active one).
     */
    suspend fun runOnce(
        conversationId: String,
        userText: String,
        modelId: String? = null,
        systemPromptOverride: String? = null,
        foregroundServiceManagedExternally: Boolean = false,
        precondition: suspend () -> Boolean = { true },
    ): Result = automationExecutionGate.withExecution {
        executionCoordinator.withAutomationConversationLock(conversationId) {
            runOnceLocked(
                conversationId = conversationId,
                userText = userText,
                modelId = modelId,
                systemPromptOverride = systemPromptOverride,
                foregroundServiceManagedExternally = foregroundServiceManagedExternally,
                precondition = precondition,
            )
        }
    }

    /**
     * LoopManager owns both automation guards across its persistent cycle claim, generation, and
     * schedule update. Re-entering either non-reentrant guard from [runOnce] would deadlock, so
     * this entry point trusts the shared gate -> conversation-lease order already held by Loop.
     */
    internal suspend fun runOnceWithAutomationGuardsHeld(
        conversationId: String,
        userText: String,
        modelId: String? = null,
        systemPromptOverride: String? = null,
        foregroundServiceManagedExternally: Boolean = false,
        precondition: suspend () -> Boolean = { true },
    ): Result = runOnceLocked(
        conversationId = conversationId,
        userText = userText,
        modelId = modelId,
        systemPromptOverride = systemPromptOverride,
        foregroundServiceManagedExternally = foregroundServiceManagedExternally,
        precondition = precondition,
    )

    private suspend fun runOnceLocked(
        conversationId: String,
        userText: String,
        modelId: String?,
        systemPromptOverride: String?,
        foregroundServiceManagedExternally: Boolean,
        precondition: suspend () -> Boolean,
    ): Result {
        settings.awaitInitialLoad()
        providerRegistry.awaitInitialSync()
        convRepo.ensureRunRecovery()
        if (!precondition()) return Result.Failure("Execution cancelled")
        val conversation = convRepo.getConversation(conversationId)
            ?: return Result.Failure("Conversation not found: $conversationId")
        val effectiveModelId = modelId?.takeIf { it.isNotBlank() }
            ?: conversation.modelId?.takeIf { it.isNotBlank() }
            ?: settings.selectedModel.value

        // If the conversation is open in the foreground, delegate the send to the regular
        // controller path so the loop cycle gets bubble animation, scroll, and haptics.
        // The controller manages its own slot; do NOT acquire it here before the bridge check.
        // The bridge only returns once the delegated turn is durably finished, so the caller's
        // conversation lease still spans the whole generation and the Result reflects what
        // actually happened rather than merely "the send was accepted".
        val bridge = currentForegroundSendBridge()
        if (bridge != null) {
            when (val outcome = bridge(conversationId, userText, effectiveModelId)) {
                is BridgeOutcome.Completed ->
                    return Result.Success(outcome.modelMessageId, outcome.text)
                is BridgeOutcome.Busy -> return Result.Busy(outcome.reason)
                is BridgeOutcome.Failed -> return Result.Failure(outcome.reason)
                BridgeOutcome.NotDelegated -> Unit
            }
        }

        if (effectiveModelId.isBlank()) return Result.Failure("No model selected")
        val generationState = generationRegistry.getOrCreate(conversationId)
        val now = System.currentTimeMillis()
        val runId = UUID.randomUUID().toString()
        val userMessageId = UUID.randomUUID().toString()
        val modelMessageId = UUID.randomUUID().toString()
        val startTime = now + 1
        var lastStreamed: ChatMessage? = null
        var runCreated = false
        var runBound = false
        var stopEffectHandled = false
        var bindingOutcome: ConversationGenerationState.RunBindingOutcome =
            ConversationGenerationState.RunBindingOutcome.Rejected
        var inputEffect: RunEffect.PersistAcceptedInput? = null

        return try {
            val providerName = providerRegistry.providerForModel(effectiveModelId)
            val activeKey = settings.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() }
                ?: settings.resolveActiveKey(providerName) ?: ""
            if (!providerRegistry.isConfigured(providerName, activeKey)) {
                return Result.Failure("Provider not configured: $providerName")
            }

            val builder = GenerationRequestBuilder(
                settings = settings,
                convRepo = convRepo,
                memoryManager = memoryManager,
                providerRegistry = providerRegistry,
                ragManager = ragManager,
                appContext = appContext,
                pendingConversationSettings = MutableStateFlow(null),
                onSnackbar = {},
            )
            val resolved = if (systemPromptOverride != null) {
                GenerationRequestBuilder.ResolvedPrompt(
                    systemPromptOverride.ifBlank { null },
                    null,
                    null,
                )
            } else {
                builder.buildEffectiveSystemPrompt(conversationId, effectiveModelId)
            }
            val effectiveSettings = builder.buildEffectiveConversationSettings(conversationId)
            val contextLimit = effectiveSettings.contextWindow ?: settings.maxContextWindow.value

            // Headless Task/Loop uses the same direct-only Send command as the foreground bridge.
            // The queue mutex keeps pending guidance from being overtaken between inspection and
            // the mailbox decision. Busy is typed and persists no message/Run side effect.
            val admission = AutomationRuntimeAdmission.request(
                state = generationState,
                proposedRunId = runId,
                effectId = "automation-send-$runId",
            )
            val acceptedInputEffect = when (admission) {
                AutomationRuntimeAdmission.Decision.Busy -> return Result.Busy()
                is AutomationRuntimeAdmission.Decision.Accepted -> admission.inputEffect
            }
            inputEffect = acceptedInputEffect
            val uiToken = acceptedInputEffect.identity.ownerToken
            val currentJob = currentCoroutineContext()[Job]
            if (currentJob == null || !generationState.attachGenerationJob(uiToken, currentJob)) {
                withContext(NonCancellable) {
                    if (generationState.commands.abandonSendLaunch(acceptedInputEffect.identity)) {
                        generationState.onQueueDrainRequested?.invoke(generationState)
                    }
                }
                return Result.Failure("Conversation generation slot was revoked")
            }
            val persistToken = generationState.nextPersistId()

            suspend fun compactAtBoundary(): CompactResult {
                return when (
                    val execution = compactEffectCoordinator.executeAutomatic(
                        generationState,
                    ) { effect ->
                        contextCompactor.compactAutomatic(
                            conversationId = conversationId,
                            fallbackModel = effectiveModelId,
                            contextLimit = contextLimit,
                            compactRunId = effect.compactRunId,
                            onSummaryChunk = { chunk ->
                                generationState.appendCompactPreview(effect.identity, chunk)
                            },
                        )
                    }
                ) {
                    is ContextCompactEffectCoordinator.Execution.Settled -> execution.result
                    ContextCompactEffectCoordinator.Execution.Busy -> {
                        if (generationState.stopping.value) {
                            throw CancellationException("Automatic context compact was stopped")
                        }
                        error("Automatic context compact was not admitted for the active Run")
                    }
                    ContextCompactEffectCoordinator.Execution.Superseded -> {
                        if (generationState.stopping.value) {
                            throw CancellationException(
                                "Automatic context compact was superseded by Stop",
                            )
                        }
                        error("Automatic context compact result was superseded")
                    }
                }
            }
            val graphCommit = acceptedInputGraphWriter.commit(
                AcceptedInputGraphWriter.Request(
                    inputEffect = acceptedInputEffect,
                    userMessageId = userMessageId,
                    modelMessageId = modelMessageId,
                    userText = userText,
                    modelId = effectiveModelId,
                    userTimestamp = now,
                ),
            )
            runCreated = true
            bindingOutcome = withContext(NonCancellable) {
                generationState.finishInputPersistence(acceptedInputEffect.identity)
            }
            runBound = bindingOutcome is ConversationGenerationState.RunBindingOutcome.Active
            if (!runBound) {
                val stopping = bindingOutcome as?
                    ConversationGenerationState.RunBindingOutcome.Stopping
                if (stopping != null) {
                    settleStopEffect(
                        state = generationState,
                        effect = stopping.finalizationEffect,
                        messages = emptyList(),
                    )
                    stopEffectHandled = true
                } else {
                    // Runtime disposal is the only rejected durable edge. The ordinary Stop race
                    // is represented by the exact effect handled above.
                    withContext(NonCancellable) {
                        convRepo.finishStoppedGeneration(emptyList(), runId)
                    }
                }
                currentCoroutineContext().ensureActive()
                return Result.Failure("Execution cancelled")
            }
            val placeholder = graphCommit.modelMessage.toUiChatMessage(appContext)
            generationState.loadingChange(uiToken, true)
            generationState.streamUpdate(uiToken, placeholder)

            // The current user boundary must be durable before eligibility is evaluated. The
            // compactor excludes the empty SENDING placeholder from token accounting while
            // retaining it as the graph suffix below a newly inserted Compact boundary.
            when (
                val compactResult = compactAtBoundary()
            ) {
                is CompactResult.Failed -> error(
                    "Automatic context compact failed: ${compactResult.message}"
                )
                is CompactResult.Created,
                CompactResult.NotNeeded -> Unit
            }

            val (config, baseGenCtx) = builder.buildGenerationPair(
                providerName, effectiveModelId, activeKey,
                resolved.systemPrompt, resolved.userPrepend, resolved.userPostpend,
                effectiveSettings, conversationId,
            )
            val genCtx = baseGenCtx.copy(
                // Automation tools are intentionally foreground-only: a scheduled run must
                // not recursively create more tasks/loops without a user in the loop.
                automationToolsEnabled = false,
                foregroundServiceManagedExternally = foregroundServiceManagedExternally,
            )

            val baseCallbacks = generationState.callbacksFor(uiToken, persistToken)
            generationManager.generate(
                conversationId = conversationId,
                modelMessageId = modelMessageId,
                startTime = startTime,
                isRegenerate = false,
                replaceMessageId = null,
                modelName = effectiveModelId,
                runId = runId,
                pass = 0,
                ownerToken = uiToken,
                config = config,
                ctx = genCtx,
                generationJob = currentJob,
                callbacks = baseCallbacks.copy(
                    onStreamUpdate = { message ->
                        lastStreamed = message
                        baseCallbacks.onStreamUpdate(message)
                    },
                    onToolRoundPersisted = {
                        when (
                            val compactResult = compactAtBoundary()
                        ) {
                            is CompactResult.Failed -> error(
                                "Automatic context compact failed: ${compactResult.message}"
                            )
                            is CompactResult.Created,
                            CompactResult.NotNeeded -> Unit
                        }
                    },
                ),
                streamScope = generationState.streamScope,
            )
            val finalMsg = convRepo.getMessagesForConversationSnapshot(conversationId)
                .find { it.id == modelMessageId }
            if (finalMsg != null && finalMsg.status == MessageStatus.SUCCESS) {
                Result.Success(modelMessageId, finalMsg.text)
            } else {
                Result.Failure(finalMsg?.text?.takeIf { it.isNotBlank() } ?: "Generation failed")
            }
        } catch (e: CancellationException) {
            if (!runCreated && inputEffect != null) {
                withContext(NonCancellable) {
                    runCreated = convRepo.getRun(runId) != null
                    if (!runCreated) {
                        generationState.commands.inputPersistenceFailed(inputEffect.identity)
                    }
                }
            }
            withContext(NonCancellable) {
                // If Room committed just before cancellation surfaced, first echo that exact
                // persistence result. This either binds Active or emits the mailbox-owned late
                // Stop effect; it never invents a second terminal writer.
                if (
                    runCreated &&
                    !runBound &&
                    bindingOutcome is ConversationGenerationState.RunBindingOutcome.Rejected &&
                    inputEffect != null
                ) {
                    bindingOutcome = generationState.finishInputPersistence(inputEffect.identity)
                    runBound = bindingOutcome is
                        ConversationGenerationState.RunBindingOutcome.Active
                }
                when (val binding = bindingOutcome) {
                    is ConversationGenerationState.RunBindingOutcome.Stopping -> {
                        if (!stopEffectHandled) {
                            settleStopEffect(
                                state = generationState,
                                effect = binding.finalizationEffect,
                                messages = emptyList(),
                            )
                            stopEffectHandled = true
                        }
                    }
                    ConversationGenerationState.RunBindingOutcome.Active -> {
                        // An external user Stop already owns its effect. Worker/Task cancellation
                        // enters the same mailbox only when no Stop is in progress.
                        if (!generationState.stopping.value) {
                            val stopped = generationState.stop()
                            stopped.finalizationEffect?.let { effect ->
                                settleStopEffect(
                                    state = generationState,
                                    effect = effect,
                                    messages = stopped.stoppedMessage?.let(::listOf).orEmpty(),
                                )
                            }
                        }
                    }
                    ConversationGenerationState.RunBindingOutcome.Rejected -> {
                        if (runCreated) {
                            // Runtime disposal/replacement is an exceptional recovery edge.
                            val stopped = lastStreamed?.copy(status = MessageStatus.STOPPED)
                            convRepo.finishStoppedGeneration(
                                stopped?.let(::listOf).orEmpty(),
                                runId,
                            )
                        }
                    }
                }
            }
            throw e
        } catch (e: Exception) {
            DebugLog.e(
                "TaskExecutionEngine",
                "runOnce failed for conversation=$conversationId " +
                    "errorType=${e.javaClass.simpleName}",
            )
            val reason = e.localizedMessage ?: "Unexpected error"
            if (!runCreated && inputEffect != null) {
                withContext(NonCancellable) {
                    runCreated = convRepo.getRun(runId) != null
                    if (!runCreated) {
                        generationState.commands.inputPersistenceFailed(inputEffect.identity)
                    }
                }
            }
            if (
                runCreated &&
                !runBound &&
                bindingOutcome is ConversationGenerationState.RunBindingOutcome.Rejected &&
                inputEffect != null
            ) {
                withContext(NonCancellable) {
                    bindingOutcome = generationState.finishInputPersistence(inputEffect.identity)
                    runBound = bindingOutcome is
                        ConversationGenerationState.RunBindingOutcome.Active
                }
            }
            if (runCreated) {
                val failedMessage = ChatMessage(
                    id = modelMessageId,
                    parentId = userMessageId,
                    text = reason,
                    thoughts = null,
                    status = MessageStatus.ERROR,
                    participant = Participant.MODEL,
                    timestamp = startTime,
                    modelName = effectiveModelId.takeIf { it.isNotBlank() },
                    runId = runId,
                    runSequence = 1,
                )
                val stopping = bindingOutcome as?
                    ConversationGenerationState.RunBindingOutcome.Stopping
                if (stopping != null) {
                    if (!stopEffectHandled) {
                        settleStopEffect(
                            state = generationState,
                            effect = stopping.finalizationEffect,
                            messages = emptyList(),
                        )
                        stopEffectHandled = true
                    }
                } else if (runBound) {
                    val effectIdentity = RunEffectIdentity(
                        conversationId = conversationId,
                        ownerToken = inputEffect?.identity?.ownerToken
                            ?: error("Bound automation Run has no input identity"),
                        runId = runId,
                        pass = 0,
                        effectId = "finalize-$runId-0",
                    )
                    val effect = generationState.commands.requestRunFinalization(
                        identity = effectIdentity,
                        status = RunStatus.FAILED,
                        reason = RunEndReason.PROVIDER_ERROR,
                        markConversationUnread = false,
                    )
                    if (effect != null) {
                        val result = runFinalizationEffects.execute(effect) { requested ->
                            convRepo.finishGeneration(
                                message = failedMessage,
                                conversationId = requested.identity.conversationId,
                                runId = requested.identity.runId,
                                status = requested.status,
                                reason = requested.reason,
                                markConversationUnread = requested.markConversationUnread,
                            )
                        }
                        val success = result is
                            RunFinalizationEffectCoordinator.Result.Succeeded
                        generationState.finishRunFinalization(effect.identity, success)
                        if (success) {
                            generationState.streamUpdate(effect.identity.ownerToken, failedMessage)
                            generationState.streamClear(effect.identity.ownerToken)
                        }
                    }
                } else if (
                    bindingOutcome is ConversationGenerationState.RunBindingOutcome.Rejected
                ) {
                    // The runtime disappeared after Room commit; repair the durable graph even
                    // though no process writer remains to accept a result command.
                    convRepo.finishGeneration(
                        message = failedMessage,
                        conversationId = conversationId,
                        runId = runId,
                        status = RunStatus.FAILED,
                        reason = RunEndReason.PROVIDER_ERROR,
                        markConversationUnread = false,
                    )
                }
            }
            Result.Failure(reason)
        }
    }
}
