package com.lxseek.chat.viewmodel

import android.app.Application
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.api.LlmProvider
import com.lxseek.chat.api.StreamEvent
import com.lxseek.chat.api.router.SmartModelRouterFactory
import com.lxseek.chat.data.MemoryManager

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.model.RequestTokenUsageAccumulator
import com.lxseek.chat.model.TokenUsage
import com.lxseek.chat.model.ToolCallData
import com.lxseek.chat.R
import com.lxseek.chat.service.LxChatForegroundService
import com.lxseek.chat.service.AppForegroundTracker
import com.lxseek.chat.api.util.projectAssistantImagesToLatestUserMessage
import com.lxseek.chat.api.util.projectToolResultImagesToUserMessage
import com.lxseek.chat.tool.ActionTraceBus
import com.lxseek.chat.tool.AskUserController
import com.lxseek.chat.tool.AskUserToolProvider
import com.lxseek.chat.tool.PlanHandler
import com.lxseek.chat.tool.PlanStateHolder
import com.lxseek.chat.tool.PlanToolProvider
import com.lxseek.chat.tool.ToolApprovalRequest
import com.lxseek.chat.tool.ToolApprovalResult
import com.lxseek.chat.tool.ToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val STREAM_UI_UPDATE_INTERVAL_MS = 50L
private const val TOOL_UI_UPDATE_INTERVAL_MS = 50L

class GenerationManager(
    private val app: Application,
    private val conversations: com.lxseek.chat.data.repository.ConversationRepository,
    private val memoryManager: MemoryManager,
    private val providers: Map<String, LlmProvider>,
    private val context: android.content.Context,
    private val sandboxFactory: com.lxseek.chat.sandbox.SandboxManagerFactory? = null,
    additionalToolProviders: List<ToolProvider> = emptyList(),
    /**
     * 智能模型路由器工厂。非 null 时，每次生成请求的原始 Provider 会被包装为
     * [com.lxseek.chat.api.router.SmartModelRouter]，应用 Fallback Chain、
     * API Key 轮换、速率限制、白名单等策略。null 表示不启用智能路由（向后兼容）。
     */
    private val smartRouterFactory: SmartModelRouterFactory? = null,
    /** Process-scoped token usage tracker for cross-session cost analysis. Null = tracking disabled. */
    private val tokenUsageTracker: com.lxseek.chat.metrics.TokenUsageTracker? = null,
    /** 成长活动日志（journey 数据源），转发给 MemoryToolProvider 记录记忆写操作。Null = 不记录。 */
    private val activityJournal: com.lxseek.chat.data.ActivityJournal? = null,
) {
    var onMessagePersisted: ((messageId: String, text: String) -> Unit)? = null

    /** User-confirmation gate for remote shell mutations. Set by the ViewModel.
     *  Returns true to proceed, false to deny. */
    var onConfirmShellCommand: (suspend (server: String, summary: String) -> Boolean)? = null

    /** User-confirmation gate for tool calls flagged by the approval dispatcher. Set by the
     *  ViewModel. Returns null when no gate is configured (tool proceeds), an [ToolApprovalResult]
     *  otherwise. */
    var onToolApproval: (suspend (ToolApprovalRequest) -> ToolApprovalResult?)? = null

    /** Process-scoped plan state, shared between the tool provider and the context injector. */
    val planStateHolder = PlanStateHolder()

    /** Process-scoped ask-user controller for the ask_user tool. */
    val askUserController = AskUserController()

    private val planToolProvider = PlanToolProvider(planStateHolder)
    private val askUserToolProvider = AskUserToolProvider(askUserController)

    private val toolExecutor = GenerationToolExecutor.createDefault(
        app = app,
        conversations = conversations,
        memoryManager = memoryManager,
        sandboxFactory = sandboxFactory,
        additionalProviders = additionalToolProviders,
        confirmShellCommand = { server, summary ->
            onConfirmShellCommand?.invoke(server, summary) ?: true
        },
        onToolApproval = { request ->
            // Pet face: wait while the user approves a tool call, then resume thinking.
            com.lxseek.chat.pet.PetEmotionController.setEmotion(com.lxseek.chat.pet.PetEmotion.WAITING)
            val result = onToolApproval?.invoke(request)
            com.lxseek.chat.pet.PetEmotionController.setEmotion(com.lxseek.chat.pet.PetEmotion.THINKING)
            result
        },
        planToolProvider = planToolProvider,
        askUserToolProvider = askUserToolProvider,
        planStateHolder = planStateHolder,
        actionTraceBus = ActionTraceBus,
        activityJournal = activityJournal,
    )
    private val providerPassEffects = ProviderPassEffectExecutor()
    private val toolBatchEffects = GenerationToolBatchEffectExecutor(toolExecutor)
    private val toolRoundBuilder = GenerationToolRoundBuilder()
    private val runFinalizationExecutor = GenerationRunFinalizationExecutor(conversations)
    private val apiPathBuilder = GenerationApiPathBuilder(conversations, toolExecutor, planStateHolder)
    private val completionEffects = GenerationCompletionEffectsExecutor(
        isAppInForeground = { AppForegroundTracker.isInForeground },
        releaseForegroundLease = LxChatForegroundService::release,
        notify = { text, conversationId ->
            LxChatForegroundService.showCompletionNotification(app, text, conversationId)
        },
    )

    private val transcriptionStage = GenerationTranscriptionStage(
        TranscriptionManager(providers, conversations, context),
    )

    // Image/video frame extraction lives in ImageProcessor (single source of truth).
    private val imageProcessor = ImageProcessor(app)

    suspend fun processImages(
        uris: List<String>,
        sliceConfigs: Map<String, VideoSliceConfig> = emptyMap()
    ): List<String> = imageProcessor.processImagesAndVideos(uris, sliceConfigs)

    /** Semantic message search — delegates to the RAG tool provider, which owns the
     *  embedding-search logic. Kept here as the entry point used by ChatViewModel's
     *  in-app conversation search. */
    suspend fun semanticSearch(query: String, limit: Int, ctx: GenerationContext): List<Pair<MessageEntity, Float>> =
        toolExecutor.semanticSearch(query, limit, ctx)

    suspend fun generate(
        conversationId: String,
        modelMessageId: String,
        startTime: Long,
        isRegenerate: Boolean,
        replaceMessageId: String?,
        modelName: String,
        runId: String,
        pass: Int,
        ownerToken: Long,
        config: GenerationConfig,
        ctx: GenerationContext,
        generationJob: kotlinx.coroutines.Job?,
        callbacks: GenerationCallbacks,
        streamScope: StreamScope? = null,
        requestTrace: com.lxseek.chat.api.HttpClient.RequestTrace? = null,
    ) = com.lxseek.chat.api.HttpClient.withStreamScope(streamScope, requestTrace) {
        // Bind every provider/tool stream opened by this generation to its coroutine-local
        // StreamScope. Parallel conversations therefore cannot overwrite one another's Stop
        // ownership, while child dispatcher hops inherit the same context element.
        // Destructure into locals so the body below reads exactly as before.
        val (onStreamUpdate, onLoadingChange, onStreamClear, isLatestPersist) = callbacks

        var foregroundLeaseAcquired = false
        // Set when the tool loop ends early because a send was queued behind this generation.
        var interruptedForQueuedSend = false
        var totalText = ""
        var totalThoughts = ""
        var thinkingPlaceholder = ""
        var totalThoughtTitle: String? = null
        var totalTokenCount = 0
        var totalTokenUsage: TokenUsage? = null
        val tokenUsageAccumulator = RequestTokenUsageAccumulator()
        val thoughtTiming = GenerationThoughtTiming()
        var currentStatus = MessageStatus.SENDING
        var retryText: String? = null
        val toolOverlay = GenerationToolOverlay(toolExecutor, config.providerName)
        val generatedImages = mutableListOf<String>()
        var currentAnswerBuf = StringBuilder()
        var currentThoughtBuf = StringBuilder()
        var currentThoughtSignature: String? = null
        var currentThoughtSignatureProvider: String? = null
        var parentId: String? = null
        var modelRunSequence = -1L
        var toolPath = emptyList<ChatMessage>()
        val transcriptionExecution = transcriptionStage.newExecution()
        val checkpoints = GenerationStreamingCheckpoints(
            scope = CoroutineScope(currentCoroutineContext()),
            isLatestPersist = isLatestPersist,
            persist = { message ->
                conversations.updateStreamingMessageCheckpoint(message)
            },
            onFailure = { error ->
                DebugLog.e("LxChatVM", "Failed to persist streaming checkpoint", error)
            },
        )
        var terminalPersisted = false

        fun adoptIncompleteTranscriptionSnapshot() {
            transcriptionExecution.incompleteSnapshot()?.let { snapshot ->
                totalText = snapshot.text
                totalThoughts = snapshot.thoughts.orEmpty()
                totalThoughtTitle = snapshot.thoughtTitle
                totalTokenCount = snapshot.tokenCount
                totalTokenUsage = snapshot.tokenUsage
                thoughtTiming.adoptTotalDuration(snapshot.thoughtTimeMs)
                generatedImages.clear()
                generatedImages.addAll(snapshot.images)
                toolOverlay.replaceAll(snapshot.segments.orEmpty())
            }
        }

        try {
            val rawProvider = requireRegisteredProvider(providers, config.providerName)
            // 智能路由包装：当工厂非 null 时，用 SmartModelRouter 包装原始 Provider，
            // 应用 Fallback Chain / Key 轮换 / 速率限制 / 白名单等策略。
            // 工厂返回 null 表示不包装（保持向后兼容）。
            val provider = smartRouterFactory?.create(rawProvider, config.providerName, config.modelId)
                ?: rawProvider
            onLoadingChange(true)
            // Pet face: thinking while the model works.
            com.lxseek.chat.pet.PetEmotionController.setEmotion(com.lxseek.chat.pet.PetEmotion.THINKING)
            // Slot ownership (generating flag / active set) is claimed synchronously by the
            // controller before this coroutine runs — GenerationManager no longer touches it.
            com.lxseek.chat.util.CrashReporter.note("generate provider=${config.providerName} regen=$isRegenerate")
            thinkingPlaceholder = context.getString(R.string.thinking_ellipsis)
            val loadedMessages = conversations.getMessagesForConversationSnapshot(conversationId)
            val placeholder = checkNotNull(
                loadedMessages.find { it.id == modelMessageId }
            ) { "Generation placeholder $modelMessageId does not exist" }
            check(placeholder.runId == runId) {
                "Generation placeholder $modelMessageId is not owned by Run $runId"
            }
            check(conversations.getRun(runId)?.currentPass == pass) {
                "Generation pass $pass is not current for Run $runId"
            }
            modelRunSequence = placeholder.runSequence
            parentId = placeholder.parentId
            requestTrace?.mark("generation_state_ready")
            if (!ctx.foregroundServiceManagedExternally) {
                foregroundLeaseAcquired = withContext(Dispatchers.Main) {
                    LxChatForegroundService.acquire(app, modelMessageId)
                }
            }

            // Stage 1: Image Transcription
            val transcription = transcriptionExecution.execute(
                request = GenerationTranscriptionStageRequest(
                    conversationId = conversationId,
                    parentId = parentId,
                    context = ctx,
                    generationJob = generationJob,
                    modelMessageId = modelMessageId,
                    startTime = startTime,
                ),
                onSnapshot = { snapshot, forceCheckpoint ->
                    onStreamUpdate(snapshot)
                    checkpoints.persist(snapshot, forceCheckpoint)
                },
            )
            if (transcription.segments.isNotEmpty()) {
                toolOverlay.prependAll(transcription.segments)
            }
            if (transcription.error != null) {
                totalText = transcription.error
                currentStatus = MessageStatus.ERROR
            }

            if (currentStatus != MessageStatus.ERROR) {
            val (currentPath, rawProviderConfig) = apiPathBuilder.build(
                GenerationApiPathRequest(
                    parentId = parentId,
                    conversationId = conversationId,
                    isRegenerate = isRegenerate,
                    replaceMessageId = replaceMessageId,
                    config = config,
                    context = ctx,
                    loadedMessages = loadedMessages,
                ),
            )
            requestTrace?.mark(
                "api_path_ready",
                "messages=${currentPath.size} tools=${rawProviderConfig.tools.orEmpty().size}",
            )
            val providerConfig = if (transcription.performed) {
                rawProviderConfig.copy(includeImages = false)
            } else {
                rawProviderConfig
            }

            var toolCallData: ToolCallData? = null
            var toolCallDataList: List<ToolCallData> = emptyList()
            val roundToolSegments = mutableListOf<MessageSegment>()
            val completedToolCalls = linkedMapOf<String, StreamEvent.ToolCallRequest>()
            var toolRoundSegmentCursor = 0
            var providerRequestOrdinal = 0
            val toolRoundEffects = ToolRoundEffectCoordinator(callbacks)

            var lastEmitMs = 0L
            var firstUiPublishPending = true

            fun modelMessage() = ChatMessage(
                id = modelMessageId, parentId = parentId,
                text = totalText, thoughts = totalThoughts.ifBlank { null },
                thoughtTitle = totalThoughtTitle, tokenCount = totalTokenCount,
                tokenUsage = totalTokenUsage,
                status = currentStatus, participant = Participant.MODEL,
                timestamp = startTime, thoughtTimeMs = thoughtTiming.totalDurationMs,
                modelName = modelName, toolCall = toolCallData,
                images = generatedImages.toList(),
                segments = buildLiveSegments(
                    toolOverlay.snapshot(),
                    currentAnswerBuf,
                    currentThoughtBuf,
                    currentThoughtSignature,
                    currentThoughtSignatureProvider,
                    thoughtTiming.liveDurationMs()
                ),
                retryText = retryText,
                runId = runId,
                runSequence = modelRunSequence,
            )

            suspend fun publishStreamUpdate(forceCheckpoint: Boolean = false) {
                val snapshot = modelMessage()
                onStreamUpdate(snapshot)
                if (firstUiPublishPending) {
                    firstUiPublishPending = false
                    requestTrace?.mark("first_ui_publish")
                }
                checkpoints.persist(snapshot, force = forceCheckpoint)
            }

            fun flushAnswerSegment() {
                if (currentAnswerBuf.isNotEmpty()) {
                    toolOverlay.append(
                        MessageSegment(type = "answer", content = currentAnswerBuf.toString()),
                    )
                    currentAnswerBuf = StringBuilder()
                }
            }

            fun flushThoughtSegment() {
                thoughtTiming.finishCurrent()
                if (currentThoughtBuf.isNotEmpty()) {
                    toolOverlay.append(
                        MessageSegment(
                            type = "thought",
                            content = currentThoughtBuf.toString(),
                            signature = currentThoughtSignature,
                            signatureProvider = currentThoughtSignatureProvider,
                            durationMs = thoughtTiming.currentDurationMs.takeIf { it > 0L },
                        ),
                    )
                    currentThoughtBuf = StringBuilder()
                    currentThoughtSignature = null
                    currentThoughtSignatureProvider = null
                }
                thoughtTiming.resetCurrentDuration()
            }

            fun upsertStreamingToolSegment(
                streamKey: String,
                toolCallId: String?,
                name: String,
                arguments: String,
                signature: String?,
            ): Boolean {
                if (!toolOverlay.hasStream(streamKey)) {
                    flushAnswerSegment()
                    flushThoughtSegment()
                }
                return toolOverlay.upsert(streamKey, toolCallId, name, arguments, signature)
            }

            suspend fun executeAcceptedToolBatch() {
                if (completedToolCalls.isEmpty()) return
                val batchEffect = toolRoundEffects.requireBatchEffect()
                val calls = completedToolCalls.values.toList()
                completedToolCalls.clear()
                currentStatus = MessageStatus.TOOL_CALLING
                val outcome = toolBatchEffects.execute(
                    request = AuthorizedToolBatchRequest(batchEffect, calls, ctx, conversationId),
                    overlay = toolOverlay,
                    callbacks = ToolBatchProgressCallbacks(
                        publish = ::publishStreamUpdate,
                        onPublishedAt = { lastEmitMs = it },
                    ),
                )
                check(outcome.identity == batchEffect.identity)
                generatedImages.addAll(outcome.generatedImages)
                roundToolSegments.addAll(outcome.segments)
                toolCallData = outcome.calls.firstOrNull()
                toolCallDataList = outcome.calls
                toolRoundEffects.completeBatch(batchEffect.identity)
                currentStatus = MessageStatus.SENDING
                publishStreamUpdate(forceCheckpoint = true)
                lastEmitMs = System.currentTimeMillis()
            }

            suspend fun handleStreamEvent(event: StreamEvent) {
                when (event) {
                    is StreamEvent.TextChunk -> {
                        val answerText = if (currentStatus == MessageStatus.THINKING) event.text.trimStart() else event.text
                        if (currentStatus == MessageStatus.THINKING && answerText.isBlank()) {
                            retryText = null
                            return
                        }
                        if (currentStatus == MessageStatus.THINKING) {
                            flushThoughtSegment()
                        }
                        totalText += answerText
                        currentAnswerBuf.append(answerText)
                        if (answerText.isNotBlank()) {
                            currentStatus = MessageStatus.SENDING
                        }
                        retryText = null
                        // Stream the live answer text into the pet's speech bubble.
                        com.lxseek.chat.pet.PetEmotionController.setTipText(totalText)
                    }
                    is StreamEvent.ThoughtChunk -> {
                        flushAnswerSegment()
                        currentStatus = MessageStatus.THINKING
                        retryText = null
                        thoughtTiming.ensureStarted()
                        if (totalThoughts.isEmpty()) totalThoughts = thinkingPlaceholder
                        if (event.thought.isNotEmpty()) {
                            currentThoughtBuf.append(event.thought)
                            if (totalThoughts == thinkingPlaceholder) totalThoughts = event.thought
                            else totalThoughts += event.thought
                        }
                        if (event.title != null) totalThoughtTitle = event.title
                        if (event.signature != null) {
                            currentThoughtSignature = event.signature
                            currentThoughtSignatureProvider = provider.name
                        }
                    }
                    is StreamEvent.UsageUpdate -> {
                        tokenUsageAccumulator.observeRequestSnapshot(event.usage)
                        totalTokenUsage = tokenUsageAccumulator.snapshot()
                        totalTokenCount = totalTokenUsage?.totalTokenCount ?: 0
                        if (totalText.isEmpty() && event.thoughtsTokenCount > 0) {
                            currentStatus = MessageStatus.THINKING
                            thoughtTiming.ensureStarted()
                            if (totalThoughts.isEmpty()) totalThoughts = thinkingPlaceholder
                        }
                    }
                    is StreamEvent.Retrying -> {
                        retryText = context.getString(R.string.generation_retry_attempt, event.attempt, event.maxAttempts)
                        onStreamUpdate(modelMessage())
                    }
                    is StreamEvent.Error -> {
                        flushThoughtSegment()
                        retryText = null
                        toolOverlay.failIncompleteStreams(completedToolCalls.keys)
                        currentStatus = MessageStatus.ERROR
                        if (totalText.isBlank()) {
                            totalText = event.message
                        }
                    }
                    is StreamEvent.ToolCallUpdate -> {
                        val created = upsertStreamingToolSegment(
                            streamKey = event.streamKey,
                            toolCallId = event.id,
                            name = event.name,
                            arguments = event.arguments,
                            signature = event.signature,
                        )
                        currentStatus = MessageStatus.TOOL_CALLING
                        retryText = null
                        val now = System.currentTimeMillis()
                        if (created || now - lastEmitMs >= TOOL_UI_UPDATE_INTERVAL_MS) {
                            publishStreamUpdate(forceCheckpoint = created)
                            lastEmitMs = now
                        }
                    }
                    is StreamEvent.ToolCallRequest -> {
                        upsertStreamingToolSegment(
                            streamKey = event.streamKey,
                            toolCallId = event.id,
                            name = event.name,
                            arguments = event.arguments,
                            signature = event.signature,
                        )
                        currentStatus = MessageStatus.TOOL_CALLING
                        publishStreamUpdate(forceCheckpoint = true)
                        lastEmitMs = System.currentTimeMillis()
                    }
                    is StreamEvent.ToolCallsRequest -> {
                        event.calls.forEach { call ->
                            upsertStreamingToolSegment(
                                streamKey = call.streamKey,
                                toolCallId = call.id,
                                name = call.name,
                                arguments = call.arguments,
                                signature = call.signature,
                            )
                        }
                        currentStatus = MessageStatus.TOOL_CALLING
                        publishStreamUpdate(forceCheckpoint = true)
                        lastEmitMs = System.currentTimeMillis()
                    }
                }

                val now = System.currentTimeMillis()
                val isSignificant = event is StreamEvent.Error
                if (now - lastEmitMs >= STREAM_UI_UPDATE_INTERVAL_MS || isSignificant) {
                    publishStreamUpdate(forceCheckpoint = isSignificant)
                    lastEmitMs = now
                }
            }

            suspend fun collectProviderRequest(
                messages: List<ChatMessage>,
                onFirstEvent: (() -> Unit)? = null,
            ): ProviderPassOutcome {
                tokenUsageAccumulator.beginRequest()
                val proposedIdentity = RunEffectIdentity(
                    conversationId = conversationId,
                    ownerToken = ownerToken,
                    runId = runId,
                    pass = pass,
                    effectId = "provider-$pass-${providerRequestOrdinal++}",
                )
                try {
                    return providerPassEffects.execute(
                        request = ProviderPassExecutionRequest(
                            proposedIdentity = proposedIdentity,
                            provider = provider,
                            messages = messages,
                            config = providerConfig,
                        ),
                        callbacks = ProviderPassExecutionCallbacks(
                            requestEffect = callbacks.onProviderPassRequested,
                            returnConsumerFailure = { identity, result ->
                                callbacks.onProviderPassCompleted(identity, result)
                            },
                            onFirstEvent = onFirstEvent,
                            onEvent = ::handleStreamEvent,
                        ),
                    )
                } finally {
                    tokenUsageAccumulator.finishRequest()
                    totalTokenUsage = tokenUsageAccumulator.snapshot()
                    totalTokenCount = totalTokenUsage?.totalTokenCount ?: totalTokenCount
                    // Record cross-session token usage for cost analysis.
                    totalTokenUsage?.let { usage ->
                        tokenUsageTracker?.record(
                            provider = config.providerName,
                            model = config.modelId,
                            inputTokens = usage.inputTokenCount ?: 0,
                            outputTokens = usage.outputTokenCount ?: 0,
                            cachedTokens = usage.cachedInputTokenCount ?: 0,
                            sessionId = conversationId,
                        )
                    }
                }
            }

            suspend fun acceptProviderPass(outcome: ProviderPassOutcome) {
                val result = outcome.resultType()
                callbacks.onProviderPassCompleted(outcome.identity, result)
                    ?.takeIf { it.identity == outcome.identity && it.result == result }
                    ?: throw CancellationException(
                        "Provider pass ${outcome.identity.effectId} outcome is no longer current",
                    )
                when (outcome) {
                    is ProviderPassOutcome.CompletedText -> Unit
                    is ProviderPassOutcome.CompletedToolCalls -> {
                        check(completedToolCalls.isEmpty()) {
                            "A Provider pass cannot overlap an unconsumed tool batch"
                        }
                        toolRoundEffects.acceptValidatedBatch(outcome.identity)
                        outcome.calls.forEach { call ->
                            completedToolCalls[call.streamKey] = call
                        }
                    }
                    is ProviderPassOutcome.Truncated,
                    is ProviderPassOutcome.Failed,
                    -> check(currentStatus == MessageStatus.ERROR) {
                        "A failed Provider pass must publish its error before closing"
                    }
                    is ProviderPassOutcome.Cancelled -> throw CancellationException(
                        "Provider pass ${outcome.identity.effectId} was cancelled",
                    )
                }
            }

            val projectedPath = projectToolResultImagesToUserMessage(
                projectAssistantImagesToLatestUserMessage(currentPath, providerConfig.includeImages),
                providerConfig.includeImages,
            )
            val apiPath = applyUserTemplateToMessages(
                projectedPath,
                config.userPrepend,
                config.userPostpend,
            )
            requestTrace?.mark("provider_dispatch")
            acceptProviderPass(collectProviderRequest(apiPath) {
                requestTrace?.mark("first_semantic_event")
            })
            thoughtTiming.finishCurrent()
            if (currentStatus != MessageStatus.ERROR) executeAcceptedToolBatch()
            // Publish the final in-memory snapshot without waiting for another Room round trip.
            // The terminal transaction below persists this exact state after fencing the
            // checkpoint writer, while genuine tool lifecycle boundaries remain forced.
            if (generationJob?.isCancelled != true) {
                publishStreamUpdate()
            }

            // Multi-tool loop
            var toolRound = 0
            toolPath = currentPath
            val repeatDetector = ToolRepeatDetector()

            while (toolCallDataList.isNotEmpty() && currentStatus != MessageStatus.ERROR && currentCoroutineContext().isActive) {
                val repeatWarning = repeatDetector.observe(toolCallDataList)
                if (repeatWarning != null) {
                    totalText = repeatWarning
                    currentStatus = MessageStatus.SUCCESS
                    break
                }
                toolRound++
                val roundToolList = roundToolSegments.toList()
                roundToolSegments.clear()
                val thoughtSegs = toolRoundThoughtSegments(
                    segments = toolOverlay.snapshot(),
                    fromIndex = toolRoundSegmentCursor,
                )
                val txedSegments = if (thoughtSegs.isNotEmpty()) thoughtSegs + roundToolList else roundToolList
                toolRoundSegmentCursor = toolOverlay.size
                val prevLastId = if (toolRound == 1) modelMessageId else toolPath.lastOrNull()?.id
                val tcds = toolCallDataList
                val round = toolRoundBuilder.build(
                    previousMessageId = prevLastId,
                    conversationId = conversationId,
                    runId = runId,
                    modelName = modelName,
                    providerName = provider.name,
                    calls = tcds,
                    completedSegments = txedSegments,
                )
                toolPath = toolPath + round.pathMessages
                toolRoundEffects.commitRound { commitIdentity ->
                    conversations.appendToolRoundToRun(
                        messages = round.entities,
                        expectedPass = commitIdentity.pass,
                    )
                }
                callbacks.onToolRoundPersisted()
                toolPath = apiPathBuilder.build(
                    GenerationApiPathRequest(
                        parentId = round.lastResultId,
                        conversationId = conversationId,
                        isRegenerate = false,
                        replaceMessageId = null,
                        config = config,
                        context = ctx,
                    ),
                ).messages

                toolCallData = null
                toolCallDataList = emptyList()

                // Steering: a send queued mid-generation is delivered at this round boundary.
                // The round's tool/result rows are already persisted above, so ending here is
                // clean — the slot release drains the queue (each message its own bubble) and
                // the NEXT generation's path continues from these tool results plus the new
                // user turns, instead of making the user wait out the whole tool loop.
                if (callbacks.hasQueuedSends()) {
                    interruptedForQueuedSend = true
                    break
                }

                lastEmitMs = 0L

                val projectedToolPath = projectToolResultImagesToUserMessage(
                    projectAssistantImagesToLatestUserMessage(toolPath, providerConfig.includeImages),
                    providerConfig.includeImages,
                )
                val apiToolPath = applyUserTemplateToMessages(
                    projectedToolPath,
                    config.userPrepend,
                    config.userPostpend,
                )
                acceptProviderPass(collectProviderRequest(apiToolPath))
                thoughtTiming.finishCurrent()
                if (currentStatus != MessageStatus.ERROR) executeAcceptedToolBatch()
                // Publish the round's final UI state immediately. The next loop boundary or the
                // terminal transaction supplies durability, so blocking here would only duplicate
                // I/O and visibly delay the transition out of generating.
                publishStreamUpdate()
            }

            if (!currentCoroutineContext().isActive) {
                currentStatus = MessageStatus.STOPPED
            }

            if (currentStatus != MessageStatus.ERROR) {
                // A queue-steered interruption is a SUCCESSFUL turn even with no answer text —
                // its value is the persisted tool activity.
                currentStatus = if (totalText.isNotEmpty() || totalThoughts.isNotEmpty() || interruptedForQueuedSend) {
                    MessageStatus.SUCCESS
                } else MessageStatus.ERROR
            }
            // Pet face: celebrate a finished answer.
            if (currentStatus == MessageStatus.SUCCESS) {
                com.lxseek.chat.pet.PetEmotionController.setEmotion(com.lxseek.chat.pet.PetEmotion.HAPPY)
            }
            // Clear the streaming tip text once the generation is finalized.
            com.lxseek.chat.pet.PetEmotionController.setTipText(null)
            if (generationJob?.isCancelled == true && currentStatus != MessageStatus.ERROR) {
                currentStatus = MessageStatus.STOPPED
            }
            } // else { // called buildApiPath when currentStatus == ERROR
        } catch (e: CancellationException) {
            // transcribe() owns its mutable segment list until it returns. If cancellation lands
            // mid-transcription, copy the latest durable/UI snapshot into the terminal accumulator
            // so the final upsert does not overwrite that checkpoint with empty content.
            adoptIncompleteTranscriptionSnapshot()
            toolOverlay.stopIncompleteTools()
            currentStatus = MessageStatus.STOPPED
            com.lxseek.chat.pet.PetEmotionController.setTipText(null)
            throw e
        } catch (e: Exception) {
            adoptIncompleteTranscriptionSnapshot()
            val isCancelled = generationJob?.isCancelled == true
            currentStatus = if (isCancelled) MessageStatus.STOPPED else MessageStatus.ERROR
            if (!isCancelled) {
                totalText = "Error: ${e.localizedMessage ?: "An unexpected error occurred."}"
            }
            // Pet face: react to a failed generation.
            if (currentStatus == MessageStatus.ERROR) {
                com.lxseek.chat.pet.PetEmotionController.setEmotion(com.lxseek.chat.pet.PetEmotion.SAD)
            }
            com.lxseek.chat.pet.PetEmotionController.setTipText(null)
        } finally {
            // Fence the asynchronous checkpoint lane before any terminal transaction. Without
            // this join, an older SENDING snapshot could finish after SUCCESS/STOPPED and revive
            // the exact UI state the terminal write just closed.
            withContext(NonCancellable) {
                checkpoints.close()
            }
            // The mailbox, rather than a mutable token check in this finally block, chooses the
            // one terminal effect that may write Room. A concurrent Stop wins by entering
            // Stopping first; a natural completion wins by entering Finalizing first.
            withContext(NonCancellable) {
                // A cancellation can arrive as ImageGenToolProvider's withContext returns,
                // after the file was queued but before the normal post-tool drain ran.
                generatedImages.addAll(toolExecutor.drainGeneratedImages(conversationId))
                try {
                    val conversationExists = conversations.getConversation(conversationId) != null
                    if (conversationExists) {
                        thoughtTiming.finishCurrent()
                        // Bound the row's toolCallJson aggregate (#51) and the unbounded answer
                        // text column — together they can exceed the 2MB CursorWindow otherwise.
                        val finalMessage = GenerationFinalSnapshot(
                            messageId = modelMessageId,
                            parentId = parentId,
                            text = totalText,
                            images = generatedImages.toList(),
                            thoughts = totalThoughts,
                            thoughtTitle = totalThoughtTitle,
                            tokenCount = totalTokenCount,
                            tokenUsage = totalTokenUsage,
                            status = currentStatus,
                            timestamp = startTime,
                            thoughtTimeMs = thoughtTiming.totalDurationMs,
                            modelName = modelName,
                            flushedSegments = toolOverlay.snapshot(),
                            answerBuffer = currentAnswerBuf.toString(),
                            thoughtBuffer = currentThoughtBuf.toString(),
                            thoughtSignature = currentThoughtSignature,
                            thoughtSignatureProvider = currentThoughtSignatureProvider,
                            thoughtDurationMs = thoughtTiming.currentDurationMs.takeIf { it > 0L },
                            runId = runId,
                            runSequence = modelRunSequence,
                        ).toMessage()
                        val terminalDisposition = generationTerminalDisposition(
                            messageStatus = currentStatus,
                            hasPendingGuidance = callbacks.hasQueuedSends(),
                        )
                        val finalizationIdentity = RunEffectIdentity(
                            conversationId = conversationId,
                            ownerToken = ownerToken,
                            runId = runId,
                            pass = pass,
                            effectId = "finalize-$runId-$pass",
                        )
                        val outcome = runFinalizationExecutor.execute(
                            request = GenerationRunFinalizationRequest(
                                identity = finalizationIdentity,
                                message = finalMessage,
                                status = terminalDisposition.runStatus,
                                reason = terminalDisposition.endReason,
                                markConversationUnread = terminalDisposition.markConversationUnread,
                            ),
                            callbacks = callbacks.runFinalizationCallbacks(),
                        )
                        if (outcome is GenerationRunFinalizationOutcome.Settled) {
                            terminalPersisted = outcome.terminalPersisted
                            // Keep the exact final snapshot as the overlay even when Room failed.
                            // It remains non-authoritative, but gives a later explicit Stop the
                            // complete content to persist instead of an older SENDING checkpoint.
                            onStreamUpdate(finalMessage)
                            if (!terminalPersisted) {
                                val failure =
                                    (outcome.durableResult as? RunFinalizationEffectCoordinator.Result.Failed)
                                        ?.lastFailure
                                val message =
                                    "Terminal generation effect failed after ${outcome.durableResult.attempts} attempts: " +
                                        "message=$modelMessageId run=$runId status=$currentStatus"
                                if (failure != null) DebugLog.e("LxChatVM", message, failure)
                                else DebugLog.e("LxChatVM", message)
                            }
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.e("LxChatVM", "Failed to execute terminal generation effect", e)
                }
            }
            completionEffects.execute(
                request = GenerationCompletionEffectsRequest(
                    terminalPersisted = terminalPersisted,
                    status = currentStatus,
                    interruptedForQueuedSend = interruptedForQueuedSend,
                    text = totalText,
                    conversationId = conversationId,
                    modelMessageId = modelMessageId,
                    foregroundLeaseAcquired = foregroundLeaseAcquired,
                ),
                callbacks = callbacks.completionEffectsCallbacks(onMessagePersisted),
            )
        }
    }
}
