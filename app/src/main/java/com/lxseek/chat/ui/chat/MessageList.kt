package com.lxseek.chat.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.lxseek.chat.R
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.ContextBudget
import com.lxseek.chat.api.util.contextWindowRetainedMessageIds
import com.lxseek.chat.api.util.expandSelectedToolProtocolRows
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunMessagePresentation
import com.lxseek.chat.model.RunUiProjection
import com.lxseek.chat.model.StableMessageList
import com.lxseek.chat.model.StableModelAliases
import com.lxseek.chat.model.ToolCallDisplayModes
import com.lxseek.chat.model.ThinkingSegmentDisplayModes
import com.lxseek.chat.ui.chat.message.GroupedSegmentAutoExpansionController
import com.lxseek.chat.ui.chat.message.MessageItem
import com.lxseek.chat.ui.chat.message.ContextCompactProgressPill
import com.lxseek.chat.ui.chat.message.REGENERATION_ABORT_RESTORE_DURATION_MS
import com.lxseek.chat.ui.chat.message.REGENERATION_EXIT_DURATION_MS
import com.lxseek.chat.ui.chat.message.SegmentAppearanceRegistry
import com.lxseek.chat.ui.chat.message.hasActiveAnswerSegment
import com.lxseek.chat.ui.common.LocalLxChatHaptics
import com.lxseek.chat.ui.components.MessageSkeletonRow
import com.lxseek.chat.ui.motion.LocalLxChatMotionPolicy
import com.lxseek.chat.viewmodel.RegenerationTransitionRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

private data class RunProjectionMessageKey(
    val id: String,
    val parentId: String?,
    val participant: Participant,
    val timestamp: Long,
    val runId: String?,
    val runSequence: Long?,
)

private fun ChatMessage.toRunProjectionKey(): RunProjectionMessageKey =
    RunProjectionMessageKey(
        id = id,
        parentId = parentId,
        participant = participant,
        timestamp = timestamp,
        runId = runId,
        runSequence = runSequence,
    )

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageList(
    messages: StableMessageList,
    allMessages: StableMessageList = StableMessageList(),
    conversationId: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    state: LazyListState = rememberLazyListState(),
    userScrollEnabled: Boolean = true,
    isLoading: Boolean = false,
    isCompacting: Boolean = false, compactPreview: String = "",
    isStopping: Boolean = false,
    isSwitching: Boolean = false,
    streamingAutoFollowEnabled: Boolean = isLoading && !isSwitching,
    streamingAutoFollowPaused: Boolean = false,
    streamingTailWithinAttachThreshold: Boolean = false,
    programmaticScrollActive: Boolean = false,
    streamingTailController: StreamingTailController = rememberStreamingTailController(),
    streamingIndicatorVisible: Boolean = isLoading,
    regenerationTransition: RegenerationTransitionRequest? = null,
    onRegenerationFadeOutFinished: (Long) -> Unit = {},
    visualizeContextRollout: Boolean = false,
    toolCallDisplayMode: String = ToolCallDisplayModes.DEFAULT,
    thinkingSegmentDisplayMode: String = ThinkingSegmentDisplayModes.DEFAULT,
    autoExpandActiveGroup: Boolean = true,
    detailedTokenUsage: Boolean = false,
    maxContextWindow: Int = ContextBudget.DEFAULT_TOKENS,
    modelAliases: StableModelAliases = StableModelAliases(),
    bottomBarHeight: androidx.compose.ui.unit.Dp = 0.dp,
    viewportHeight: Int = 0,
    messageHeights: SnapshotStateMap<String, Int> = remember { mutableStateMapOf() },
    onEditMessage: suspend (String, String) -> Boolean = { _, _ -> false },
    onSwitchBranch: (String?, String, Int) -> Unit = { _, _, _ -> },
    onRegenerate: (String) -> Boolean = { false },
    onFork: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    /**
     * Invoked with a message id when a row is swiped to reveal the reply action.
     * Defaults to a no-op; callers wire it to the composer's quote-reply flow.
     */
    onSwipeToReply: (String) -> Unit = {},
    ttsPlayingMessageId: String? = null,
    onToggleTts: (String) -> Unit = {},
    searchQuery: String = "",
    activeSearchMatch: ConversationSearchMatch? = null,
    onSearchMatchDistance: (key: String, distanceToViewportCenter: Float) -> Unit = { _, _ -> },
    selectionMode: Boolean = false,
    selectedMessageIds: Set<String> = emptySet(),
    onToggleMessageSelection: (String) -> Unit = {},
    onMessageLongPress: () -> Unit = {},
    onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    thoughtExpandedStates: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() },
    lifecycleAppearanceRegistry: MessageLifecycleAppearanceRegistry =
        remember { MessageLifecycleAppearanceRegistry() },
    segmentAppearanceRegistry: SegmentAppearanceRegistry =
        remember { SegmentAppearanceRegistry() },
    lifecycleEntranceTargetMessageId: String? = null,
) {
    val motionPolicy = LocalLxChatMotionPolicy.current
    val groupedSegmentAutoExpansionController = remember(conversationId) {
        GroupedSegmentAutoExpansionController()
    }
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var pendingEditMessageId by remember { mutableStateOf<String?>(null) }
    var pendingEditVisualReplacement by remember(conversationId) {
        mutableStateOf<PendingEditVisualReplacement?>(null)
    }
    val editVisualKeyAliases = remember(conversationId) {
        mutableStateMapOf<String, String>()
    }
    var regenerationExitIds by remember(conversationId) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var retainedRegenerationExitMessages by remember(conversationId) {
        mutableStateOf<List<ChatMessage>>(emptyList())
    }
    var retainedRegenerationPresentations by remember(conversationId) {
        mutableStateOf<Map<String, RunMessagePresentation>>(emptyMap())
    }
    val regenerationExitAlpha = remember(conversationId) { Animatable(1f) }
    val latestRegenerationFadeFinished by rememberUpdatedState(onRegenerationFadeOutFinished)
    val mutationAnchorLock = remember(state) { MessageListMutationAnchorLock() }
    val mutationScope = rememberCoroutineScope()
    val pendingMutationSettles = remember(state) { mutableMapOf<String, Job>() }
    val searchMatchCentersInTurn = remember(state) { mutableStateMapOf<String, Float>() }
    var listRootY by remember(state) { mutableFloatStateOf(0f) }
    var streamingTailFollowMode by remember(state, conversationId) {
        mutableStateOf(StreamingTailFollowMode.INACTIVE)
    }
    var streamingTailUserDragInProgress by remember(state, conversationId) {
        mutableStateOf(false)
    }
    val latestIsLoading by rememberUpdatedState(isLoading)
    val latestAutoFollowEnabled by rememberUpdatedState(streamingAutoFollowEnabled)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val tailTolerancePx = with(density) { 2.dp.toPx() }

    fun cancelMutationAnchoring() {
        pendingMutationSettles.values.forEach { it.cancel() }
        pendingMutationSettles.clear()
        mutationAnchorLock.cancel()
    }

    LaunchedEffect(programmaticScrollActive) {
        if (programmaticScrollActive) cancelMutationAnchoring()
    }

    fun setStreamingTailFollowMode(nextMode: StreamingTailFollowMode) {
        streamingTailFollowMode = nextMode
        val attached =
            nextMode == StreamingTailFollowMode.ATTACHED ||
                nextMode == StreamingTailFollowMode.SETTLING
        streamingTailController.isAttached = attached
        if (!attached) streamingTailController.isAutoFollowing = false
    }

    SideEffect {
        streamingTailController.isAttached =
            streamingTailFollowMode == StreamingTailFollowMode.ATTACHED ||
                streamingTailFollowMode == StreamingTailFollowMode.SETTLING
    }

    LaunchedEffect(isSwitching) {
        if (isSwitching) cancelMutationAnchoring()
    }
    LaunchedEffect(state, conversationId) {
        state.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    cancelMutationAnchoring()
                    streamingTailUserDragInProgress = true
                    // A real gesture is authoritative. Clear the externally-observed flag before
                    // changing mode so the scroll-to-bottom button can react in the same frame.
                    streamingTailController.isAutoFollowing = false
                    setStreamingTailFollowMode(
                        reduceStreamingTailFollow(
                            streamingTailFollowMode,
                            StreamingTailFollowEvent.UserDragStarted,
                        ),
                    )
                }

                is DragInteraction.Stop,
                is DragInteraction.Cancel -> {
                    streamingTailUserDragInProgress = false
                }
            }
        }
    }
    DisposableEffect(state, conversationId) {
        onDispose { cancelMutationAnchoring() }
    }

    val visibleProjectionKey = remember(messages) {
        messages.list.map(ChatMessage::toRunProjectionKey)
    }
    val allProjectionKey = remember(allMessages) {
        allMessages.list.map(ChatMessage::toRunProjectionKey)
    }
    val inContextIds = remember(messages, allMessages, maxContextWindow) {
        contextWindowRetainedMessageIds(
            expandSelectedToolProtocolRows(messages.list, allMessages.list),
            maxContextWindow,
        )
    }

    val activeMessageIds = remember(messages) {
        messages.list.mapTo(hashSetOf()) { message -> message.id }
    }
    val presentationMessages = remember(messages, retainedRegenerationExitMessages) {
        mergeRegenerationPresentationMessages(
            activeMessages = messages.list,
            retainedExitMessages = retainedRegenerationExitMessages,
        )
    }
    val turnCache = remember { MessageListTurnCache() }
    val turns = remember(presentationMessages) { turnCache.update(presentationMessages) }
    val lastUserMessage = messages.list.lastOrNull { it.participant == Participant.USER }
    val resolvedEditReplacement = remember(messages, pendingEditVisualReplacement) {
        resolvePendingEditReplacement(
            messages = messages.list,
            pending = pendingEditVisualReplacement,
        )
    }
    val pendingReplacementVisualKey =
        pendingEditVisualReplacement
            ?.takeIf { resolvedEditReplacement != null }
            ?.stableVisualKey

    fun stableVisualKey(messageId: String): String =
        editVisualKeyAliases[messageId]
            ?: if (resolvedEditReplacement?.id == messageId) {
                pendingReplacementVisualKey ?: messageId
            } else {
                messageId
            }

    SideEffect {
        val replacement = resolvedEditReplacement
        val stableKey = pendingReplacementVisualKey
        if (replacement != null && stableKey != null) {
            editVisualKeyAliases[replacement.id] = stableKey
            pendingEditVisualReplacement = null
        }
    }
    val answeringTailVisible =
        isLoading &&
            !isStopping &&
            messages.list.lastOrNull { it.participant == Participant.MODEL }?.let { message ->
                message.status == MessageStatus.SENDING && message.hasActiveAnswerSegment()
            } == true

    LaunchedEffect(regenerationTransition?.id) {
        val transition = regenerationTransition
        if (transition == null) {
            if (regenerationExitIds.any { exitId ->
                    messages.list.any { message -> message.id == exitId }
                }
            ) {
                regenerationExitAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = REGENERATION_ABORT_RESTORE_DURATION_MS,
                        easing = LinearEasing,
                    ),
                )
            } else {
                regenerationExitAlpha.snapTo(1f)
            }
            retainedRegenerationExitMessages = emptyList()
            retainedRegenerationPresentations = emptyMap()
            regenerationExitIds = emptySet()
            return@LaunchedEffect
        }

        retainedRegenerationExitMessages = regenerationExitMessages(
            messages = messages.list,
            oldMessageId = transition.oldMessageId,
        )
        regenerationExitIds =
            retainedRegenerationExitMessages.mapTo(linkedSetOf()) { message -> message.id }
        retainedRegenerationPresentations =
            RunUiProjection.project(messages.list, allMessages.list)
                .filterKeys(regenerationExitIds::contains)
        if (transition.stage != com.lxseek.chat.viewmodel.RegenerationTransitionStage.ANIMATING) {
            regenerationExitAlpha.snapTo(0f)
            return@LaunchedEffect
        }
        regenerationExitAlpha.snapTo(1f)
        regenerationExitAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = REGENERATION_EXIT_DURATION_MS,
                easing = LinearEasing,
            ),
        )
        latestRegenerationFadeFinished(transition.id)
    }

    LaunchedEffect(
        state,
        conversationId,
        isLoading,
        streamingAutoFollowEnabled,
        streamingAutoFollowPaused,
        lastUserMessage?.id,
    ) {
        if (!isLoading) {
            streamingTailUserDragInProgress = false
        }
        if (!isLoading || streamingAutoFollowPaused || !streamingAutoFollowEnabled) {
            setStreamingTailFollowMode(
                reduceStreamingTailGenerationAvailability(
                    current = streamingTailFollowMode,
                    active = isLoading,
                    autoFollowEnabled = streamingAutoFollowEnabled,
                    autoFollowPaused = streamingAutoFollowPaused,
                ),
            )
            return@LaunchedEffect
        }
        val nextMode = reduceStreamingTailGenerationAvailability(
            current = streamingTailFollowMode,
            active = isLoading,
            autoFollowEnabled = streamingAutoFollowEnabled,
            autoFollowPaused = streamingAutoFollowPaused,
        )
        if (nextMode == StreamingTailFollowMode.ATTACHED) {
            cancelMutationAnchoring()
        }
        setStreamingTailFollowMode(nextMode)
    }

    LaunchedEffect(
        state,
        conversationId,
        isLoading,
        streamingAutoFollowEnabled,
        streamingAutoFollowPaused,
        streamingTailWithinAttachThreshold,
    ) {
        snapshotFlow {
            state.isScrollInProgress to streamingTailFollowMode
        }
            .distinctUntilChanged()
            .collect { (scrollInProgress, _) ->
                if (
                    !isLoading ||
                    !streamingAutoFollowEnabled ||
                    streamingAutoFollowPaused
                ) {
                    return@collect
                }
                val nextMode = reduceStreamingTailFollow(
                    streamingTailFollowMode,
                    StreamingTailFollowEvent.ViewportProximityChanged(
                        withinAttachThreshold = streamingTailWithinAttachThreshold,
                        scrollInProgress = scrollInProgress,
                    ),
                )
                if (
                    nextMode == StreamingTailFollowMode.ATTACHED &&
                    streamingTailFollowMode != StreamingTailFollowMode.ATTACHED
                ) {
                    cancelMutationAnchoring()
                }
                setStreamingTailFollowMode(nextMode)
            }
    }

    // One frame-driven actor owns attached scrolling. It reads the newest cumulative geometry on
    // every display frame, coalesces all token/layout deltas into one critically damped correction,
    // and is cancelled immediately by a real drag or any competing transition.
    LaunchedEffect(
        state,
        conversationId,
        isLoading,
        streamingAutoFollowEnabled,
        streamingTailFollowMode,
    ) {
        val followingActiveGeneration =
            isLoading &&
                streamingAutoFollowEnabled &&
                streamingTailFollowMode == StreamingTailFollowMode.ATTACHED
        val settlingCompletedGeneration =
            !isLoading &&
                streamingTailFollowMode == StreamingTailFollowMode.SETTLING
        if (!followingActiveGeneration && !settlingCompletedGeneration) {
            streamingTailController.isAutoFollowing = false
            return@LaunchedEffect
        }
        cancelMutationAnchoring()
        streamingTailController.isAutoFollowing = true
        val minimumStepPx = with(density) { 2.dp.toPx() }
        var previousFrameNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
        val settlingStartNanos = previousFrameNanos
        var stableFrames = 0
        try {
            // Attachment is a layout correction, not a user-visible scroll gesture. Raw one-frame
            // deltas deliberately avoid LazyList's MutatorMutex and isScrollInProgress, so an
            // attached list never cancels taps or competes with the horizontal drawer recognizer.
            // A real vertical drag still emits DragInteraction.Start above and detaches first.
            while (
                currentCoroutineContext().isActive &&
                (
                    (
                        streamingTailFollowMode == StreamingTailFollowMode.ATTACHED &&
                            latestIsLoading &&
                            latestAutoFollowEnabled
                    ) ||
                        (
                            streamingTailFollowMode == StreamingTailFollowMode.SETTLING &&
                                !latestIsLoading
                        )
                ) &&
                !streamingTailUserDragInProgress
            ) {
                val frameNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
                val elapsedSeconds =
                    ((frameNanos - previousFrameNanos).coerceAtLeast(1L) / 1_000_000_000f)
                        .coerceAtMost(0.05f)
                previousFrameNanos = frameNanos
                val absoluteBottom = absoluteBottomLayoutSnapshot(
                    layoutInfo = state.layoutInfo,
                    canScrollForward = state.canScrollForward,
                )
                // Attachment has exactly one authority: the page's physical end sentinel.
                // The visual tail dot is deliberately absent from this calculation.
                val error = absoluteBottom.remainingDistancePx
                    ?: if (state.canScrollForward) {
                        absoluteBottom.viewportSizePx * 0.5f
                    } else {
                        0f
                    }
                if (error > 0.5f) {
                    val step = coalescedScrollStep(
                        errorPx = error,
                        elapsedSeconds = elapsedSeconds,
                        timeConstantSeconds = 0.055f,
                        maximumVelocityPxPerSecond = 2_800f,
                        minimumStepPx = minimumStepPx,
                    )
                    if (abs(step) > 0.05f) {
                        val modeStillOwnsAttachment =
                            streamingTailFollowMode == StreamingTailFollowMode.ATTACHED ||
                                streamingTailFollowMode == StreamingTailFollowMode.SETTLING
                        if (!streamingTailUserDragInProgress && modeStillOwnsAttachment) {
                            state.dispatchRawDelta(step)
                        }
                    }
                }

                if (streamingTailFollowMode == StreamingTailFollowMode.SETTLING) {
                    stableFrames = if (error <= tailTolerancePx) stableFrames + 1 else 0
                    val settlingElapsedMs =
                        (frameNanos - settlingStartNanos).coerceAtLeast(0L) / 1_000_000L
                    val settledAfterFinalAnimations =
                        settlingElapsedMs >= 700L && stableFrames >= 8
                    val settlingTimedOut = settlingElapsedMs >= 1_600L
                    if (settledAfterFinalAnimations || settlingTimedOut) {
                        setStreamingTailFollowMode(
                            reduceStreamingTailFollow(
                                streamingTailFollowMode,
                                StreamingTailFollowEvent.SettlingFinished,
                            ),
                        )
                    }
                }
            }
        } finally {
            streamingTailController.isAutoFollowing = false
        }
    }

    // Text/status/tool deltas do not change branch/run structure. Cache this O(n) projection by its
    // structural fields; copy text is read from the live MessageItem below.
    val runPresentation = remember(visibleProjectionKey, allProjectionKey) {
        RunUiProjection.project(messages.list, allMessages.list)
    }

    val tailMinHeightPx = if (lastUserMessage == null || viewportHeight == 0) {
        0
    } else {
        calculateTailMinHeightPx(
            viewportHeightPx = viewportHeight,
            targetTopPx = with(density) { 140.dp.roundToPx() },
            bottomObstructionPx = with(density) {
                (bottomBarHeight + 8.dp).roundToPx()
            },
        )
    }
    val tailMinHeight = with(density) { tailMinHeightPx.toDp() }

    // One progressive actor owns the complete search movement. Far-away turns are approached in
    // bounded per-frame steps; once composed, the same actor retargets against exact glyph
    // geometry. There is no animateScrollToItem teleport and no second correction animation.
    LaunchedEffect(
        activeSearchMatch?.key,
        motionPolicy.allowProgrammaticScrollMotion,
    ) {
        val match = activeSearchMatch ?: return@LaunchedEffect
        val turnIndex = messageListTurnIndex(turns, match.messageId)
        if (turnIndex < 0) return@LaunchedEffect
        cancelMutationAnchoring()
        val topInsetPx = with(density) { 140.dp.toPx() }
        val bottomInsetPx = with(density) { bottomBarHeight.toPx() }
        val targetCenterY = topInsetPx +
            ((viewportHeight - bottomInsetPx - topInsetPx).coerceAtLeast(0f) / 2f)
        val fallbackHeightPx = with(density) { 160.dp.toPx() }
        val estimatedTurnHeights = FloatArray(turns.size) { index ->
            estimateMessageListTurnHeightPx(
                turn = turns[index],
                messageHeights = messageHeights,
                fallbackHeightPx = fallbackHeightPx,
            )
        }
        val heightPrefix = FloatArray(turns.size + 1)
        for (index in estimatedTurnHeights.indices) {
            heightPrefix[index + 1] = heightPrefix[index] + estimatedTurnHeights[index]
        }
        val estimatedAnchorInTurn = estimateSearchMatchCenterInTurnPx(
            turn = turns[turnIndex],
            match = match,
            messageHeights = messageHeights,
            fallbackHeightPx = fallbackHeightPx,
        )
        if (!motionPolicy.allowProgrammaticScrollMotion) {
            state.scrollToItem(
                index = turnIndex,
                scrollOffset = (
                    listRootY +
                        estimatedAnchorInTurn -
                        targetCenterY
                    ).roundToInt(),
            )
            return@LaunchedEffect
        }

        state.smoothSeekToItem(
            targetIndex = { turnIndex },
            targetErrorPx = { visibleTarget ->
                val anchorInRootCoordinates =
                    searchMatchCentersInTurn[match.key]
                        ?: (listRootY + estimatedAnchorInTurn)
                visibleTarget.offset + anchorInRootCoordinates - targetCenterY
            },
            estimatedErrorPx = {
                val firstVisible = state.layoutInfo.visibleItemsInfo
                    .minByOrNull { item -> item.index }
                    ?: return@smoothSeekToItem null
                val firstIndex = firstVisible.index.coerceIn(0, turns.size)
                val distanceFromFirstToTarget =
                    heightPrefix[turnIndex] - heightPrefix[firstIndex]
                listRootY +
                    firstVisible.offset +
                    distanceFromFirstToTarget +
                    estimatedAnchorInTurn -
                    targetCenterY
            },
            exactTargetReady = {
                searchMatchCentersInTurn.containsKey(match.key)
            },
            minimumStepPx = with(density) { 2.dp.toPx() },
        )
    }

    fun restoreAnchor(anchor: MessageListViewportAnchor): Boolean {
        val turnIndex = messageListTurnIndex(turns, anchor.messageId)
        if (turnIndex < 0) return false
        state.requestScrollToItem(
            turnIndex,
            anchor.scrollOffsetPx,
        )
        return true
    }

    val renderMessage: @Composable (ChatMessage) -> Unit = { message ->
        val isRetainedRegenerationExit =
            message.id in regenerationExitIds && message.id !in activeMessageIds
        val isInContext = !isRetainedRegenerationExit && inContextIds.contains(message.id)
        // Once the new branch commits, the active Run projection no longer contains the
        // transparent old answer. Retain its exact presentation until the regeneration handoff
        // releases that composition, otherwise the action row is conditionally removed instead
        // of participating in the fade.
        val presentation =
            runPresentation[message.id] ?: retainedRegenerationPresentations[message.id]
        val messageIsStreaming = message.participant == Participant.MODEL &&
            message.status in setOf(
                MessageStatus.SENDING,
                MessageStatus.THINKING,
                MessageStatus.TOOL_CALLING,
                MessageStatus.TRANSCRIBING,
            )
        val animateLifecycleEntrance =
            !isRetainedRegenerationExit &&
            message.id != resolvedEditReplacement?.id &&
                shouldAnimateMessageLifecycleEntrance(
                    message = message,
                    isKnown = lifecycleAppearanceRegistry.isKnown(message.id),
                    isLoading = isLoading,
                    isStreaming = messageIsStreaming,
                    lastUserMessageId = lastUserMessage?.id,
                    requestedTargetMessageId = lifecycleEntranceTargetMessageId,
                )
        // LazyColumn items are subcomposed on demand. Marking the whole projected list in the
        // parent composition races ahead of that subcomposition and makes a brand-new Send look
        // historical before its bubble gets a first frame. Claim "known" only after this concrete
        // item has composed and captured its one-shot entrance decision.
        SideEffect {
            lifecycleAppearanceRegistry.markKnown(message.id)
        }

        // Wrap the row in a swipe-to-reveal layer so horizontal drags expose delete
        // (left) and reply (right) actions. Disabled during selection and for retained
        // regeneration-exit placeholders to avoid stealing gestures from the fade.
        val swipeEnabled = !selectionMode && !isRetainedRegenerationExit
        SwipeToRevealMessage(
            enabled = swipeEnabled,
            onDelete = { onDelete(message.id) },
            onReply = { onSwipeToReply(message.id) },
        ) {
        MessageItem(
            message = message,
            segmentAppearanceRegistry = segmentAppearanceRegistry,
            modifier = if (message.id in regenerationExitIds) {
                Modifier.graphicsLayer {
                    alpha = regenerationExitAlpha.value
                }
            } else {
                Modifier
            },
            animateEntrance = animateLifecycleEntrance,
            onEdit = { id, text ->
                if (!isRetainedRegenerationExit && pendingEditMessageId == null) {
                    val source = messages.list.firstOrNull { message -> message.id == id }
                    pendingEditVisualReplacement = source?.let { message ->
                        PendingEditVisualReplacement(
                            sourceMessageId = message.id,
                            sourceParentId = message.parentId,
                            submittedText = text,
                            stableVisualKey = stableVisualKey(message.id),
                        )
                    }
                    pendingEditMessageId = id
                    mutationScope.launch {
                        val accepted = try {
                            onEditMessage(id, text)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        if (accepted && editingMessageId == id) {
                            editingMessageId = null
                        }
                        if (pendingEditMessageId == id) {
                            pendingEditMessageId = null
                        }
                        if (!accepted &&
                            pendingEditVisualReplacement?.sourceMessageId == id
                        ) {
                            pendingEditVisualReplacement = null
                        }
                    }
                }
            },
            // Every active MODEL owns its streaming renderer until its own terminal status.
            // Appending a queued USER must not dispose the previous turn's incremental renderer.
            isStreaming = messageIsStreaming,
            isLoading = isLoading || pendingEditMessageId == message.id,
            isRegenerationExiting = message.id in regenerationExitIds,
            isEditingAllowed = !isRetainedRegenerationExit &&
                !selectionMode &&
                (editingMessageId == null || editingMessageId == message.id) &&
                !isLoading,
            isEditing = editingMessageId == message.id,
            isSwitching = isSwitching,
            isInContext = isInContext,
            modelAliases = modelAliases,
            visualizeContextRollout = visualizeContextRollout,
            toolCallDisplayMode = toolCallDisplayMode,
            thinkingSegmentDisplayMode = thinkingSegmentDisplayMode,
            autoExpandActiveGroup = autoExpandActiveGroup,
            detailedTokenUsage = detailedTokenUsage,
            groupedSegmentAutoExpansionController =
                groupedSegmentAutoExpansionController,
            onStartEdit = {
                if (!isRetainedRegenerationExit) editingMessageId = message.id
            },
            onCancelEdit = { editingMessageId = null },
            showActions = !selectionMode && presentation?.showActions == true,
            actionCopyText = presentation
                ?.takeIf { it.showActions }
                ?.let { message.text.takeIf(String::isNotBlank) },
            showBranchSelector = !selectionMode && presentation?.showBranchSelector == true,
            branchIndex = presentation?.branchIndex ?: 0,
            totalBranches = presentation?.totalBranches ?: 1,
            onSwitchBranch = { direction ->
                val anchorId = presentation?.branchAnchorMessageId
                if (anchorId != null) {
                    onSwitchBranch(
                        presentation.branchAnchorParentId,
                        anchorId,
                        direction,
                    )
                }
            },
            onRegenerate = onRegenerate,
            onFork = onFork,
            onShare = onShare,
            onDelete = onDelete,
            isTtsPlaying = ttsPlayingMessageId == message.id,
            onToggleTts = { onToggleTts(message.id) },
            onMediaClick = onMediaClick,
            onFileContentClick = onFileContentClick,
            onPdfPagesClick = onPdfPagesClick,
            searchQuery = searchQuery,
            activeSearchMatch = activeSearchMatch,
            onSearchMatchPosition = { key, centerY ->
                val turnIndex = messageListTurnIndex(turns, message.id)
                val visibleTurn = state.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == turnIndex }
                if (visibleTurn != null) {
                    searchMatchCentersInTurn[key] = centerY - visibleTurn.offset
                }
                val topInsetPx = with(density) { 140.dp.toPx() }
                val bottomInsetPx = with(density) { bottomBarHeight.toPx() }
                val viewportCenterY = topInsetPx +
                    ((viewportHeight - bottomInsetPx - topInsetPx).coerceAtLeast(0f) / 2f)
                onSearchMatchDistance(
                    key,
                    kotlin.math.abs(centerY - viewportCenterY),
                )
            },
            selectionMode = selectionMode,
            selected = !isRetainedRegenerationExit && message.id in selectedMessageIds,
            onToggleSelection = {
                if (!isRetainedRegenerationExit) onToggleMessageSelection(message.id)
            },
            onLongPress = onMessageLongPress,
            onHeightChanged = { height ->
                if (height > 0 && messageHeights[message.id] != height) {
                    val mode = messageListLayoutMode(
                        isSwitching = isSwitching,
                        isScrollInProgress =
                            state.isScrollInProgress || programmaticScrollActive,
                    )
                    // Measurement remains available to explicit scrolling calculations, but
                    // bottom geometry no longer reads it. The tail's minimum height absorbs
                    // content changes atomically in the same measure pass.
                    messageHeights[message.id] = height
                    if (
                        mode == MessageListLayoutMode.STABLE &&
                        streamingTailFollowMode != StreamingTailFollowMode.ATTACHED
                    ) {
                        val lockedAnchor = mutationAnchorLock.anchor
                        if (lockedAnchor != null) {
                            restoreAnchor(lockedAnchor)
                        }
                    }
                }
            },
            onLayoutMutationStarted = { mutationKey ->
                pendingMutationSettles.remove(mutationKey)?.cancel()
                if (
                    streamingTailFollowMode != StreamingTailFollowMode.ATTACHED &&
                    messageListLayoutMode(
                        isSwitching = isSwitching,
                        isScrollInProgress =
                            state.isScrollInProgress || programmaticScrollActive,
                    ) == MessageListLayoutMode.STABLE
                ) {
                    val anchorMessage = turns
                        .getOrNull(state.firstVisibleItemIndex)
                        ?.messages
                        ?.firstOrNull()
                    val anchor = mutationAnchorLock.begin(
                        key = mutationKey,
                        candidate = anchorMessage?.let {
                            MessageListViewportAnchor(
                                messageId = it.id,
                                scrollOffsetPx = state.firstVisibleItemScrollOffset,
                            )
                        },
                    )
                    // Pre-arm the very first remeasure. Waiting for onSizeChanged is one frame
                    // too late when an AnimatedVisibility reverses under rapid taps.
                    if (anchor != null) restoreAnchor(anchor)
                }
            },
            onLayoutMutationSettled = { mutationKey ->
                pendingMutationSettles.remove(mutationKey)?.cancel()
                pendingMutationSettles[mutationKey] = mutationScope.launch {
                    // Transition.isRunning reaches false before the final size has necessarily
                    // propagated through the parent LazyColumn. Keep the original anchor through
                    // two complete frames; a reversing tap cancels this pending release.
                    withFrameNanos { }
                    withFrameNanos { }
                    mutationAnchorLock.finish(mutationKey)
                    pendingMutationSettles.remove(mutationKey)
                    // onSizeChanged already held the exact pre-mutation anchor throughout the
                    // transition. A final requestScrollToItem here produced a visible end-frame
                    // correction after the animation was otherwise complete.
                }
            },
            thoughtExpandedStates = thoughtExpandedStates,
            onSwipeToDelete = { onDelete(message.id) },
            onSwipeToReply = { onSwipeToReply(message.id) },
        )
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    listRootY = coordinates.positionInRoot().y
                },
            contentPadding = contentPadding,
            reverseLayout = false,
            state = state,
            userScrollEnabled = userScrollEnabled
        ) {
            // Skeleton placeholders while the conversation history is still loading and no
            // messages have been projected yet. Keeps the list from looking empty during the
            // initial database fetch and mirrors the shape of a real message row.
            if (messages.list.isEmpty() && isLoading) {
                items(count = 6, key = { index -> "lxchat:message-skeleton:$index" }) {
                    MessageSkeletonRow()
                }
            }
            items(turns, key = { turn -> stableVisualKey(turn.key) }) { turn ->
                // A turn's key and composition survive when the next USER is appended. Only the
                // new turn enters; the previous assistant never moves to a different Lazy item.
                Box(
                    modifier = Modifier,
                ) {
                    // The last turn atomically absorbs bottom space. Earlier turns keep the same
                    // Column call site with a zero minimum, so losing tail status cannot dispose
                    // or recreate any child message.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(
                                min = if (turn.key == lastUserMessage?.id) tailMinHeight else 0.dp,
                            ),
                    ) {
                        val lastActiveMessageIndex = turn.messages.indexOfLast { message ->
                            message.id in activeMessageIds
                        }
                        turn.messages.forEachIndexed { index, message ->
                            key(stableVisualKey(message.id)) {
                                renderMessage(message)
                            }
                            if (
                                turn.key == lastUserMessage?.id &&
                                index == lastActiveMessageIndex
                            ) {
                                key("lxchat:streaming-tail:${turn.key}") {
                                    StreamingTailIndicator(
                                        // Text-bottom placement belongs only to the visual dot.
                                        // Page attachment is owned by AbsoluteBottomSentinelKey.
                                        visible =
                                            streamingIndicatorVisible && answeringTailVisible,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (isCompacting) {
                item(key = "lxchat:context-compact-progress:$conversationId") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ContextCompactProgressPill(conversationId, compactPreview)
                    }
                }
            }
            // A stable physical-end target, deliberately separate from the streaming-tail
            // indicator. Reaching this item and exhausting canScrollForward means the actual
            // LazyColumn maximum extent has been reached.
            item(key = AbsoluteBottomSentinelKey) {
                Spacer(Modifier.fillMaxWidth().height(1.dp))
            }
        }
    }
}

