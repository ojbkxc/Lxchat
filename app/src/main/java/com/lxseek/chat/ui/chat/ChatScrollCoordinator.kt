package com.lxseek.chat.ui.chat

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lxseek.chat.model.ChatConversation
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.Participant
import com.lxseek.chat.ui.common.LxChatHaptics
import com.lxseek.chat.ui.motion.LxChatMotionPolicy
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.viewmodel.AnimatedScrollDestination
import com.lxseek.chat.viewmodel.AnimatedScrollRequest
import com.lxseek.chat.viewmodel.ChatViewModel
import com.lxseek.chat.viewmodel.RegenerationTransitionRequest
import com.lxseek.chat.viewmodel.RegenerationTransitionStage
import com.lxseek.chat.viewmodel.SwitchingRequestKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull
private const val SCROLL_SETTLE_TIMEOUT_MS = 8_000L
private const val STABLE_LAYOUT_SAMPLES = 3
private const val LAYOUT_SAMPLE_INTERVAL_MS = 32L
private val SEND_FEEDBACK_SCROLL_SPEC = DefaultFeedbackScrollSpec.copy(
    startup = FeedbackScrollStartupSpec(
        durationMillis = 240L,
        easing = FastOutSlowInEasing,
    ),
)

@Stable
internal class ChatScrollCoordinator internal constructor(
    val listState: LazyListState,
    private val absoluteBottomScrollPhaseState: MutableState<AbsoluteBottomScrollPhase>,
    private val absoluteBottomRequestTokenState: MutableLongState,
    private val absoluteBottomRequestFeedbackSpecState: MutableState<FeedbackScrollSpec>,
    private val isNearAbsoluteBottomState: MutableState<Boolean>,
    private val isWithinAbsoluteBottomAttachThresholdState: MutableState<Boolean>,
    private val composerInputFocusedState: MutableState<Boolean>,
    private val imeBottomAnchorStateHolder: MutableState<ImeBottomAnchorState>,
    private val viewportHeightState: MutableIntState,
    val messageHeights: SnapshotStateMap<String, Int>,
    val messageLifecycleAppearanceRegistry: MessageLifecycleAppearanceRegistry,
    val streamingTailController: StreamingTailController,
) {
    val absoluteBottomScrollPhase: AbsoluteBottomScrollPhase
        get() = absoluteBottomScrollPhaseState.value
    val isNearAbsoluteBottom: Boolean
        get() = isNearAbsoluteBottomState.value
    val isWithinAbsoluteBottomAttachThreshold: Boolean
        get() = isWithinAbsoluteBottomAttachThresholdState.value
    val imeBottomAnchorState: ImeBottomAnchorState
        get() = imeBottomAnchorStateHolder.value
    val viewportHeightPx: Int
        get() = viewportHeightState.intValue
    fun recordViewportHeight(heightPx: Int) { viewportHeightState.intValue = heightPx }
    fun setComposerInputFocused(focused: Boolean) {
        if (composerInputFocusedState.value != focused) {
            composerInputFocusedState.value = focused
        }
    }
    fun requestAbsoluteBottomScroll(
        feedbackSpec: FeedbackScrollSpec = DefaultFeedbackScrollSpec,
    ): Boolean {
        if (absoluteBottomScrollPhase.isActive) return false
        imeBottomAnchorStateHolder.value = reduceImeBottomAnchor(
            imeBottomAnchorState,
            ImeBottomAnchorEvent.Cancelled,
        )
        absoluteBottomScrollPhaseState.value = reduceAbsoluteBottomScroll(
            absoluteBottomScrollPhase,
            AbsoluteBottomScrollEvent.Requested,
        )
        absoluteBottomRequestFeedbackSpecState.value = feedbackSpec
        absoluteBottomRequestTokenState.longValue =
            if (absoluteBottomRequestTokenState.longValue == Long.MAX_VALUE) 1L
            else absoluteBottomRequestTokenState.longValue + 1L
        return true
    }
    @Composable
    internal fun BindLayoutObservation(
        currentConversationId: String?,
        loadedMessagesConversationId: String?,
        imeBottomPx: Int,
        density: Density,
    ) {
        val imeBottomEligibleNow =
            currentConversationId != null &&
                loadedMessagesConversationId == currentConversationId &&
                composerInputFocusedState.value &&
                isWithinAbsoluteBottomAttachThreshold
        SideEffect {
            val next = reduceImeBottomAnchor(
                current = imeBottomAnchorState,
                event = ImeBottomAnchorEvent.InsetsObserved(
                    insetPx = imeBottomPx,
                    bottomEligibleNow = imeBottomEligibleNow,
                    anchorAllowed = composerInputFocusedState.value,
                ),
            )
            if (next != imeBottomAnchorState) imeBottomAnchorStateHolder.value = next
        }
        val bottomButtonHideThresholdPx = with(density) { 64.dp.toPx() }
        val bottomButtonShowThresholdPx = with(density) { 96.dp.toPx() }
        LaunchedEffect(
            listState,
            currentConversationId,
            bottomButtonHideThresholdPx,
            bottomButtonShowThresholdPx,
        ) {
            val estimatedSentinelSizePx = with(density) { 1.dp.toPx() }
            snapshotFlow {
                val snapshot = absoluteBottomLayoutSnapshot(
                    layoutInfo = listState.layoutInfo,
                    canScrollForward = listState.canScrollForward,
                )
                snapshot to snapshot.estimatedRemainingDistancePx(estimatedSentinelSizePx)
            }
                .distinctUntilChanged()
                .collect { (snapshot, remainingDistancePx) ->
                    isWithinAbsoluteBottomAttachThresholdState.value =
                        isWithinAbsoluteBottomAttachThreshold(
                            snapshot = snapshot,
                            remainingDistancePx = remainingDistancePx,
                            thresholdPx = bottomButtonHideThresholdPx,
                        )
                    isNearAbsoluteBottomState.value = reduceAbsoluteBottomProximity(
                        wasNearBottom = isNearAbsoluteBottom,
                        canScrollForward = snapshot.canScrollForward,
                        remainingDistancePx = remainingDistancePx,
                        hideThresholdPx = bottomButtonHideThresholdPx,
                        showThresholdPx = bottomButtonShowThresholdPx,
                    )
                }
        }
    }

    @Composable
    internal fun BindTransitionEffects(
        currentConversationId: String?,
        currentConversation: ChatConversation?,
        loadedMessagesConversationId: String?,
        messages: State<List<ChatMessage>>,
        density: Density,
        motionPolicy: LxChatMotionPolicy,
        bottomBarHeight: Dp,
        shareSelectionBarSpace: Dp,
        imeBottomPx: Int,
        viewModel: ChatViewModel,
        haptics: LxChatHaptics,
    ) {
        val latestCurrentConversationId by rememberUpdatedState(currentConversationId)
        val latestCurrentConversation by rememberUpdatedState(currentConversation)
        val latestLoadedMessagesConversationId by rememberUpdatedState(
            loadedMessagesConversationId,
        )
        val latestImeBottomAnchorState by rememberUpdatedState(imeBottomAnchorState)
        val latestImeBottomPx by rememberUpdatedState(imeBottomPx)
        LaunchedEffect(currentConversationId, imeBottomAnchorState.active) {
            if (!imeBottomAnchorState.active) return@LaunchedEffect

            val actorStartNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
            var lastObservedInsetPx = latestImeBottomPx
            var lastInsetChangeNanos = actorStartNanos
            var stableFrames = 0
            while (true) {
                val frameNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
                if (!latestImeBottomAnchorState.active) return@LaunchedEffect
                if (latestImeBottomPx != lastObservedInsetPx) {
                    lastObservedInsetPx = latestImeBottomPx
                    lastInsetChangeNanos = frameNanos
                }

                val layout = absoluteBottomLayoutSnapshot(
                    layoutInfo = listState.layoutInfo,
                    canScrollForward = listState.canScrollForward,
                )
                val remainingDistancePx =
                    layout.remainingDistancePx
                        ?: estimateRemainingAbsoluteBottomDistance(
                            messages = messages.value,
                            density = density,
                            bottomBarHeight = bottomBarHeight,
                            shareSelectionBarSpace = shareSelectionBarSpace,
                        )
                        ?: if (listState.canScrollForward) {
                            layout.viewportSizePx * 0.5f
                        } else {
                            0f
                        }

                if (remainingDistancePx > 0.5f) {
                    // IME anchoring is a positional correction, not navigational travel. Consume
                    // each newly exposed gap in one frame so list and keyboard remain attached.
                    listState.dispatchRawDelta(remainingDistancePx)
                    stableFrames = 0
                } else {
                    val insetStableForNanos = frameNanos - lastInsetChangeNanos
                    stableFrames =
                        if (insetStableForNanos >= 80_000_000L) stableFrames + 1 else 0
                    if (stableFrames >= 3) {
                        imeBottomAnchorStateHolder.value = reduceImeBottomAnchor(
                            latestImeBottomAnchorState,
                            ImeBottomAnchorEvent.CorrectionSettled,
                        )
                        return@LaunchedEffect
                    }
                }

                if (frameNanos - actorStartNanos >= 1_600_000_000L) {
                    imeBottomAnchorStateHolder.value = reduceImeBottomAnchor(
                        latestImeBottomAnchorState,
                        ImeBottomAnchorEvent.CorrectionSettled,
                    )
                    return@LaunchedEffect
                }
            }
        }

        val switchingScrollRequest by viewModel.switchingScrollRequest.collectAsState()
        LaunchedEffect(switchingScrollRequest?.id, switchingScrollRequest?.readyForUi) {
            val request = switchingScrollRequest ?: return@LaunchedEffect
            if (!request.readyForUi || request.kind == SwitchingRequestKind.NEW_CHAT) {
                return@LaunchedEffect
            }
            var terminalized = false
            try {
                val targetConversationId = request.conversationId
                if (targetConversationId == null) {
                    viewModel.failSwitchingScroll(request.id, "conversation disappeared")
                    terminalized = true
                    return@LaunchedEffect
                }

                if (request.kind == SwitchingRequestKind.CONVERSATION) {
                    snapshotFlow {
                        Triple(
                            latestCurrentConversationId,
                            latestCurrentConversation?.id,
                            latestLoadedMessagesConversationId,
                        )
                    }.filter { (currentId, loadedConversationId, loadedMessagesId) ->
                        currentId == targetConversationId &&
                            loadedConversationId == targetConversationId &&
                            loadedMessagesId == targetConversationId
                    }.first()
                } else if (currentConversationId != targetConversationId) {
                    viewModel.failSwitchingScroll(request.id, "conversation changed")
                    terminalized = true
                    return@LaunchedEffect
                }

                if (settleCoveredTransition(messages, request.targetMessageId)) {
                    val completed = viewModel.completeSwitchingScroll(request.id)
                    if (
                        completed &&
                        request.kind == SwitchingRequestKind.CONVERSATION &&
                        request.hapticOnCompletion
                    ) {
                        haptics.confirm()
                    }
                } else {
                    viewModel.failSwitchingScroll(request.id, "layout failed to stabilize")
                }
                terminalized = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DebugLog.e("LxChatUI", "Switching request ${request.id} failed", error)
                viewModel.failSwitchingScroll(request.id, "unexpected UI failure")
                terminalized = true
            } finally {
                if (!terminalized) {
                    viewModel.failSwitchingScroll(request.id, "switching effect cancelled")
                }
            }
        }

        LaunchedEffect(currentConversationId) {
            if (viewModel.suppressNextOpenScroll) {
                viewModel.suppressNextOpenScroll = false
            }
        }
    }

    @Composable
    internal fun BindRequestEffects(
        currentConversationId: String?,
        isNewChatMode: Boolean,
        isLoading: Boolean,
        isStopping: Boolean,
        isSwitching: Boolean,
        conversationSearchActive: Boolean,
        shareSelectionActive: Boolean,
        regenerationTransition: RegenerationTransitionRequest?,
        animatedScrollRequest: AnimatedScrollRequest?,
        messages: State<List<ChatMessage>>,
        density: Density,
        motionPolicy: LxChatMotionPolicy,
        bottomBarHeight: Dp,
        shareSelectionBarSpace: Dp,
        viewModel: ChatViewModel,
    ) {
        val latestGenerationCanGrow by rememberUpdatedState(isLoading && !isStopping)
        LaunchedEffect(
            absoluteBottomRequestTokenState.longValue,
            currentConversationId,
            motionPolicy.allowProgrammaticScrollMotion,
        ) {
            if (absoluteBottomRequestTokenState.longValue == 0L) return@LaunchedEffect
            try {
                val reachedBottom = if (motionPolicy.allowProgrammaticScrollMotion) {
                    listState.animateToAbsoluteBottom(
                        isGenerationActive = { latestGenerationCanGrow },
                        estimateRemainingDistancePx = {
                            estimateRemainingAbsoluteBottomDistance(
                                messages = messages.value,
                                density = density,
                                bottomBarHeight = bottomBarHeight,
                                shareSelectionBarSpace = shareSelectionBarSpace,
                            )
                        },
                        minimumStepPx = with(density) { 2.dp.toPx() },
                        onPhaseChanged = { phase ->
                            absoluteBottomScrollPhaseState.value = phase
                        },
                        feedbackSpec = absoluteBottomRequestFeedbackSpecState.value,
                    )
                } else {
                    absoluteBottomScrollPhaseState.value = AbsoluteBottomScrollPhase.SEEKING
                    val lastIndex = listState.layoutInfo.totalItemsCount - 1
                    if (lastIndex >= 0) {
                        listState.scrollToItem(lastIndex)
                        withFrameNanos { }
                        !listState.canScrollForward
                    } else {
                        false
                    }
                }
                if (reachedBottom) {
                    imeBottomAnchorStateHolder.value = reduceImeBottomAnchor(
                        imeBottomAnchorState,
                        ImeBottomAnchorEvent.ExplicitBottomReached,
                    )
                }
            } finally {
                if (absoluteBottomScrollPhase.isActive) {
                    absoluteBottomScrollPhaseState.value = reduceAbsoluteBottomScroll(
                        absoluteBottomScrollPhase,
                        AbsoluteBottomScrollEvent.Cancelled,
                    )
                }
            }
        }
        LaunchedEffect(listState, currentConversationId) {
            listState.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start) {
                    imeBottomAnchorStateHolder.value = reduceImeBottomAnchor(
                        imeBottomAnchorState,
                        ImeBottomAnchorEvent.UserDragStarted,
                    )
                    if (absoluteBottomScrollPhase.isActive) {
                        absoluteBottomScrollPhaseState.value = reduceAbsoluteBottomScroll(
                            absoluteBottomScrollPhase,
                            AbsoluteBottomScrollEvent.Cancelled,
                        )
                        absoluteBottomRequestTokenState.longValue = 0L
                    }
                }
            }
        }
        LaunchedEffect(
            conversationSearchActive,
            shareSelectionActive,
            isSwitching,
            regenerationTransition?.id,
            animatedScrollRequest?.id,
            imeBottomAnchorState.active,
        ) {
            val competingTransition =
                conversationSearchActive ||
                    shareSelectionActive ||
                    isSwitching ||
                    regenerationTransition != null ||
                    animatedScrollRequest != null
            if (competingTransition && absoluteBottomScrollPhase.isActive) {
                absoluteBottomScrollPhaseState.value = reduceAbsoluteBottomScroll(
                    absoluteBottomScrollPhase,
                    AbsoluteBottomScrollEvent.Cancelled,
                )
                absoluteBottomRequestTokenState.longValue = 0L
            }
            if (competingTransition && imeBottomAnchorState.active) {
                imeBottomAnchorStateHolder.value = reduceImeBottomAnchor(
                    imeBottomAnchorState,
                    ImeBottomAnchorEvent.Cancelled,
                )
            }
        }
        LaunchedEffect(regenerationTransition?.id, currentConversationId) {
            val request = regenerationTransition ?: return@LaunchedEffect
            if (request.scrollFinished) return@LaunchedEffect
            if (request.conversationId != currentConversationId) {
                viewModel.acknowledgeRegenerationScroll(request.id, success = false)
                return@LaunchedEffect
            }
            try {
                val success = animateToUserMessage(
                    messages = messages.value,
                    targetMessageId = request.targetUserMessageId,
                    easing = SCROLL_EASING,
                    density = density,
                    motionPolicy = motionPolicy,
                )
                viewModel.acknowledgeRegenerationScroll(request.id, success)
            } catch (error: CancellationException) {
                viewModel.acknowledgeRegenerationScroll(request.id, success = false)
                throw error
            }
        }
        LaunchedEffect(
            regenerationTransition?.id,
            regenerationTransition?.stage,
            regenerationTransition?.scrollFinished,
            currentConversationId,
        ) {
            val request = regenerationTransition
                ?.takeIf {
                    it.stage == RegenerationTransitionStage.COMMITTED && it.scrollFinished
                }
                ?: return@LaunchedEffect
            if (request.conversationId == currentConversationId) {
                snapshotFlow {
                    messages.value.none { message -> message.id == request.oldMessageId }
                }.first { oldPathRemoved -> oldPathRemoved }
                withFrameNanos { }
            }
            viewModel.completeRegenerationTransition(request.id)
        }
        LaunchedEffect(animatedScrollRequest?.id, currentConversationId) {
            val request = animatedScrollRequest ?: return@LaunchedEffect
            if (request.conversationId != currentConversationId) {
                if (currentConversationId != null || !isNewChatMode) {
                    viewModel.completeAnimatedScroll(request.id)
                }
                return@LaunchedEffect
            }
            when (request.destination) {
                AnimatedScrollDestination.MESSAGE -> {
                    try {
                        if (!animateAfterTargetCommitted(
                                messages = messages,
                                targetMessageId = request.targetMessageId,
                                density = density,
                                motionPolicy = motionPolicy,
                            )
                        ) {
                            DebugLog.e(
                                "LxChatUI",
                                "Animated scroll target was not committed: ${request.targetMessageId}",
                            )
                        }
                    } finally {
                        viewModel.completeAnimatedScroll(request.id)
                    }
                }
                AnimatedScrollDestination.ABSOLUTE_BOTTOM -> {
                    val targetCommitted = try {
                        awaitScrollTargetCommitted(messages, request.targetMessageId)
                    } finally {
                        viewModel.completeAnimatedScroll(request.id)
                    }
                    if (targetCommitted && request.conversationId == currentConversationId) {
                        val shouldScroll =
                            !request.attachedOnly || isWithinAbsoluteBottomAttachThreshold
                        if (shouldScroll) {
                            requestAbsoluteBottomScroll(feedbackSpec = SEND_FEEDBACK_SCROLL_SPEC)
                        }
                    } else if (!targetCommitted) {
                        DebugLog.e(
                            "LxChatUI",
                            "Absolute-bottom scroll target was not committed: ${request.targetMessageId}",
                        )
                    }
                }
            }
        }
    }

    private suspend fun animateToUserMessage(
        messages: List<ChatMessage>,
        targetMessageId: String? = null,
        easing: Easing = FastOutSlowInEasing,
        density: Density,
        motionPolicy: LxChatMotionPolicy,
    ): Boolean {
        if (messages.isEmpty() || viewportHeightPx == 0) return false
        val layoutTurns = buildMessageListTurns(messages)
        val targetIndex = resolveScrollTargetIndex(messages, targetMessageId)
        if (targetIndex == -1) return false
        if (!motionPolicy.allowProgrammaticScrollMotion) {
            listState.scrollToItem(targetIndex, 0)
            return true
        }

        val firstVisibleIndex = listState.firstVisibleItemIndex
        val visibleSizes = listState.layoutInfo.visibleItemsInfo.associate {
            it.index to it.size
        }
        val fallbackHeight = visibleSizes.values
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat()
            ?: with(density) { 72.dp.toPx() }
        fun heightAt(index: Int): Float {
            visibleSizes[index]?.let { return it.toFloat() }
            val turn = layoutTurns.getOrNull(index) ?: return fallbackHeight
            return estimateMessageListTurnHeightPx(turn, messageHeights, fallbackHeight)
        }

        val distance = if (targetIndex >= firstVisibleIndex) {
            var value = -listState.firstVisibleItemScrollOffset.toFloat()
            for (index in firstVisibleIndex until targetIndex) value += heightAt(index)
            value
        } else {
            var value = -listState.firstVisibleItemScrollOffset.toFloat()
            for (index in targetIndex until firstVisibleIndex) value -= heightAt(index)
            value
        }
        if (kotlin.math.abs(distance) > 2f) {
            listState.animateScrollBy(distance, tween(600, easing = easing))
        }
        return true
    }

    private fun estimateRemainingAbsoluteBottomDistance(
        messages: List<ChatMessage>,
        density: Density,
        bottomBarHeight: Dp,
        shareSelectionBarSpace: Dp,
    ): Float? {
        val layout = listState.layoutInfo
        val lastVisible = layout.visibleItemsInfo.maxByOrNull { item -> item.index }
            ?: return null
        val layoutTurns = buildMessageListTurns(messages)
        val visibleSizes = layout.visibleItemsInfo.associate { item -> item.index to item.size }
        val fallbackHeight = visibleSizes.values
            .filter { size -> size > 1 }
            .takeIf { sizes -> sizes.isNotEmpty() }
            ?.average()
            ?.toFloat()
            ?: with(density) { 72.dp.toPx() }
        val lastUserMessageId = messages
            .lastOrNull { message -> message.participant == Participant.USER }
            ?.id
        val tailMinimumHeightPx = if (lastUserMessageId == null || viewportHeightPx == 0) {
            0f
        } else {
            calculateTailMinHeightPx(
                viewportHeightPx = viewportHeightPx,
                targetTopPx = with(density) { 140.dp.roundToPx() },
                bottomObstructionPx = with(density) {
                    (bottomBarHeight + shareSelectionBarSpace + 8.dp).roundToPx()
                },
            ).toFloat()
        }
        val sentinelHeightPx = with(density) { 1.dp.toPx() }

        fun estimatedItemSize(index: Int): Float {
            visibleSizes[index]?.let { size -> return size.toFloat() }
            val turn = layoutTurns.getOrNull(index) ?: return sentinelHeightPx
            val estimated = estimateMessageListTurnHeightPx(
                turn = turn,
                messageHeights = messageHeights,
                fallbackHeightPx = fallbackHeight,
            )
            return if (turn.key == lastUserMessageId) {
                maxOf(estimated, tailMinimumHeightPx)
            } else {
                estimated
            }
        }

        return estimateAbsoluteBottomDistancePx(
            lastVisibleIndex = lastVisible.index,
            lastVisibleEndOffsetPx = lastVisible.offset + lastVisible.size,
            viewportEndOffsetPx = layout.viewportEndOffset,
            afterContentPaddingPx = layout.afterContentPadding,
            totalItemsCount = layout.totalItemsCount,
            estimatedItemSizePx = ::estimatedItemSize,
        )
    }

    private suspend fun awaitScrollTargetCommitted(
        messages: State<List<ChatMessage>>,
        targetMessageId: String?,
    ): Boolean = withTimeoutOrNull(SCROLL_SETTLE_TIMEOUT_MS) {
        snapshotFlow {
            val index = resolveScrollTargetIndex(messages.value, targetMessageId)
            index to listState.layoutInfo.totalItemsCount
        }.first { (index, itemCount) -> index >= 0 && index < itemCount }
        true
    } == true

    private suspend fun animateAfterTargetCommitted(
        messages: State<List<ChatMessage>>,
        targetMessageId: String?,
        density: Density,
        motionPolicy: LxChatMotionPolicy,
    ): Boolean {
        if (!awaitScrollTargetCommitted(messages, targetMessageId)) return false
        return animateToUserMessage(
            messages = messages.value,
            targetMessageId = targetMessageId,
            density = density,
            motionPolicy = motionPolicy,
        )
    }

    private suspend fun settleCoveredTransition(
        messages: State<List<ChatMessage>>,
        targetMessageId: String?,
    ): Boolean = withTimeoutOrNull(SCROLL_SETTLE_TIMEOUT_MS) {
        var stableSamples = 0
        var previousSignature: List<Any>? = null
        while (stableSamples < STABLE_LAYOUT_SAMPLES) {
            delay(LAYOUT_SAMPLE_INTERVAL_MS)
            val currentMessages = messages.value
            if (currentMessages.isEmpty()) {
                val signature = listOf(0, viewportHeightPx)
                if (signature == previousSignature) stableSamples += 1
                else {
                    previousSignature = signature
                    stableSamples = 1
                }
                continue
            }
            val targetIndex = resolveScrollTargetIndex(currentMessages, targetMessageId)
            val target = resolveScrollTargetMessage(currentMessages, targetMessageId)
            if (targetIndex == -1 || target == null || viewportHeightPx <= 0) {
                stableSamples = 0
                previousSignature = null
                continue
            }
            val requestedTarget = targetMessageId?.let { id ->
                currentMessages.firstOrNull { it.id == id }
            }
            if (targetMessageId != null && requestedTarget == null) {
                stableSamples = 0
                previousSignature = null
                continue
            }
            val requestedTargetHeight = requestedTarget?.let { messageHeights[it.id] }
            if (
                requestedTarget != null &&
                (requestedTargetHeight == null || requestedTargetHeight <= 0)
            ) {
                stableSamples = 0
                previousSignature = null
                continue
            }

            val positioned =
                listState.firstVisibleItemIndex == targetIndex &&
                    listState.firstVisibleItemScrollOffset <= 2
            if (!positioned) {
                listState.scrollToItem(targetIndex, 0)
                stableSamples = 0
                previousSignature = null
                continue
            }

            val targetInfo = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == targetIndex }
            val measuredHeight = messageHeights[target.id]
            if (targetInfo == null || measuredHeight == null || measuredHeight <= 0) {
                stableSamples = 0
                previousSignature = null
                continue
            }
            val signature = listOf(
                targetIndex,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                targetInfo.offset,
                targetInfo.size,
                measuredHeight,
                viewportHeightPx,
                currentMessages.size,
                requestedTarget?.id.orEmpty(),
                requestedTargetHeight ?: 0,
            )
            if (signature == previousSignature) stableSamples += 1
            else {
                previousSignature = signature
                stableSamples = 1
            }
        }
        true
    } == true
}

@Composable
internal fun rememberChatScrollCoordinator(
    currentConversationId: String?,
    imeBottomPx: Int,
): ChatScrollCoordinator {
    val listState = rememberLazyListState()
    val absoluteBottomScrollPhaseState = remember(currentConversationId) {
        mutableStateOf(AbsoluteBottomScrollPhase.IDLE)
    }
    val absoluteBottomRequestTokenState = remember(currentConversationId) {
        mutableLongStateOf(0L)
    }
    val absoluteBottomRequestFeedbackSpecState = remember(currentConversationId) {
        mutableStateOf(DefaultFeedbackScrollSpec)
    }
    val isNearAbsoluteBottomState = remember(currentConversationId) { mutableStateOf(true) }
    val isWithinAbsoluteBottomAttachThresholdState = remember(currentConversationId) {
        mutableStateOf(false)
    }
    val composerInputFocusedState = remember { mutableStateOf(false) }
    val imeBottomAnchorStateHolder = remember(currentConversationId) {
        mutableStateOf(
            ImeBottomAnchorState(
                observedInsetPx = imeBottomPx,
                bottomEligibleBeforeInsetChange = false,
            )
        )
    }
    val viewportHeightState = remember { mutableIntStateOf(0) }
    val messageHeights = remember(currentConversationId) { mutableStateMapOf<String, Int>() }
    val messageLifecycleAppearanceRegistry = remember { MessageLifecycleAppearanceRegistry() }
    val streamingTailController = rememberStreamingTailController(currentConversationId)

    return remember(
        listState,
        absoluteBottomScrollPhaseState,
        absoluteBottomRequestTokenState,
        absoluteBottomRequestFeedbackSpecState,
        isNearAbsoluteBottomState,
        isWithinAbsoluteBottomAttachThresholdState,
        composerInputFocusedState,
        imeBottomAnchorStateHolder,
        viewportHeightState,
        messageHeights,
        messageLifecycleAppearanceRegistry,
        streamingTailController,
    ) {
        ChatScrollCoordinator(
            listState = listState,
            absoluteBottomScrollPhaseState = absoluteBottomScrollPhaseState,
            absoluteBottomRequestTokenState = absoluteBottomRequestTokenState,
            absoluteBottomRequestFeedbackSpecState = absoluteBottomRequestFeedbackSpecState,
            isNearAbsoluteBottomState = isNearAbsoluteBottomState,
            isWithinAbsoluteBottomAttachThresholdState =
                isWithinAbsoluteBottomAttachThresholdState,
            composerInputFocusedState = composerInputFocusedState,
            imeBottomAnchorStateHolder = imeBottomAnchorStateHolder,
            viewportHeightState = viewportHeightState,
            messageHeights = messageHeights,
            messageLifecycleAppearanceRegistry = messageLifecycleAppearanceRegistry,
            streamingTailController = streamingTailController,
        )
    }
}
