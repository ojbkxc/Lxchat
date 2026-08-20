package com.lxseek.chat.ui.chat

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.abs

internal const val AbsoluteBottomSentinelKey = "lxchat:absolute-bottom"

internal enum class AbsoluteBottomScrollPhase {
    IDLE,
    SEEKING,
    FOLLOWING,
    SETTLING,
}

internal val AbsoluteBottomScrollPhase.isActive: Boolean
    get() = this != AbsoluteBottomScrollPhase.IDLE

internal sealed interface AbsoluteBottomScrollEvent {
    data object Requested : AbsoluteBottomScrollEvent
    data object TargetUnavailable : AbsoluteBottomScrollEvent
    data object TargetAvailable : AbsoluteBottomScrollEvent
    data object ExtentChanged : AbsoluteBottomScrollEvent
    data object BottomReached : AbsoluteBottomScrollEvent
    data object Finished : AbsoluteBottomScrollEvent
    data object Cancelled : AbsoluteBottomScrollEvent
}

/**
 * Short-lived ownership used while the IME reduces the chat viewport.
 *
 * The eligibility snapshot is captured before the inset changes, so even a one-frame IME jump
 * cannot make a previously bottom-aligned list look detached. A real drag suppresses reacquisition
 * for the rest of that IME opening cycle.
 */
internal data class ImeBottomAnchorState(
    val observedInsetPx: Int,
    val bottomEligibleBeforeInsetChange: Boolean,
    val active: Boolean = false,
    val suppressedUntilInsetFalls: Boolean = false,
)

internal sealed interface ImeBottomAnchorEvent {
    data class InsetsObserved(
        val insetPx: Int,
        val bottomEligibleNow: Boolean,
        val anchorAllowed: Boolean = true,
    ) : ImeBottomAnchorEvent

    data object CorrectionSettled : ImeBottomAnchorEvent
    data object UserDragStarted : ImeBottomAnchorEvent
    data object ExplicitBottomReached : ImeBottomAnchorEvent
    data object Cancelled : ImeBottomAnchorEvent
}

internal fun reduceImeBottomAnchor(
    current: ImeBottomAnchorState,
    event: ImeBottomAnchorEvent,
): ImeBottomAnchorState = when (event) {
    is ImeBottomAnchorEvent.InsetsObserved -> when {
        !event.anchorAllowed -> current.copy(
            observedInsetPx = event.insetPx,
            bottomEligibleBeforeInsetChange = false,
            active = false,
            suppressedUntilInsetFalls =
                current.suppressedUntilInsetFalls &&
                    event.insetPx >= current.observedInsetPx,
        )

        event.insetPx > current.observedInsetPx -> current.copy(
            observedInsetPx = event.insetPx,
            active =
                !current.suppressedUntilInsetFalls &&
                    (current.active || current.bottomEligibleBeforeInsetChange),
        )

        event.insetPx < current.observedInsetPx -> current.copy(
            observedInsetPx = event.insetPx,
            bottomEligibleBeforeInsetChange = event.bottomEligibleNow,
            active = false,
            suppressedUntilInsetFalls = false,
        )

        current.active || current.suppressedUntilInsetFalls -> current
        else -> current.copy(
            bottomEligibleBeforeInsetChange = event.bottomEligibleNow,
        )
    }

    ImeBottomAnchorEvent.CorrectionSettled -> current.copy(
        bottomEligibleBeforeInsetChange = true,
        active = false,
    )

    ImeBottomAnchorEvent.UserDragStarted -> current.copy(
        bottomEligibleBeforeInsetChange = false,
        active = false,
        // A drag only suppresses reacquisition for an IME cycle that is already in progress.
        // When the IME is closed there is no future inset fall to clear the latch; suppressing
        // here would make an explicit bottom-button click the only way to re-arm anchoring.
        suppressedUntilInsetFalls = current.observedInsetPx > 0,
    )

    ImeBottomAnchorEvent.ExplicitBottomReached -> current.copy(
        bottomEligibleBeforeInsetChange = true,
        active = false,
        suppressedUntilInsetFalls = false,
    )

    ImeBottomAnchorEvent.Cancelled -> current.copy(
        bottomEligibleBeforeInsetChange = false,
        active = false,
    )
}

internal fun reduceAbsoluteBottomScroll(
    current: AbsoluteBottomScrollPhase,
    event: AbsoluteBottomScrollEvent,
): AbsoluteBottomScrollPhase = when (event) {
    AbsoluteBottomScrollEvent.Requested,
    AbsoluteBottomScrollEvent.TargetUnavailable,
    -> AbsoluteBottomScrollPhase.SEEKING

    AbsoluteBottomScrollEvent.TargetAvailable,
    AbsoluteBottomScrollEvent.ExtentChanged,
    -> if (current.isActive) AbsoluteBottomScrollPhase.FOLLOWING else current

    AbsoluteBottomScrollEvent.BottomReached ->
        if (current.isActive) AbsoluteBottomScrollPhase.SETTLING else current

    AbsoluteBottomScrollEvent.Finished,
    AbsoluteBottomScrollEvent.Cancelled,
    -> AbsoluteBottomScrollPhase.IDLE
}

internal data class AbsoluteBottomLayoutSnapshot(
    val totalItemsCount: Int,
    val canScrollForward: Boolean,
    val viewportStartOffsetPx: Int,
    val viewportEndOffsetPx: Int,
    val afterContentPaddingPx: Int,
    val sentinelOffsetPx: Int?,
    val sentinelSizePx: Int?,
    val lastVisibleIndex: Int? = null,
    val lastVisibleEndOffsetPx: Int? = null,
) {
    val sentinelVisible: Boolean
        get() = sentinelOffsetPx != null && sentinelSizePx != null

    val remainingDistancePx: Float?
        get() {
            val offset = sentinelOffsetPx ?: return null
            val size = sentinelSizePx ?: return null
            val contentEnd = viewportEndOffsetPx - afterContentPaddingPx
            return (offset + size - contentEnd).toFloat().coerceAtLeast(0f)
        }

    val viewportSizePx: Int
        get() = (viewportEndOffsetPx - viewportStartOffsetPx).coerceAtLeast(0)

    /**
     * Returns the physical distance to the final sentinel even while the sentinel itself is just
     * outside the viewport. LazyColumn exposes the end of the immediately preceding item, so this
     * remains exact for the final one-item gap and avoids turning a nominal 64dp threshold into an
     * effective 1dp threshold.
     */
    fun estimatedRemainingDistancePx(estimatedSentinelSizePx: Float): Float? {
        remainingDistancePx?.let { return it }
        val lastIndex = lastVisibleIndex ?: return null
        val lastEnd = lastVisibleEndOffsetPx ?: return null
        if (lastIndex != totalItemsCount - 2) return null
        val contentEnd = viewportEndOffsetPx - afterContentPaddingPx
        return (lastEnd + estimatedSentinelSizePx - contentEnd).coerceAtLeast(0f)
    }
}

internal fun absoluteBottomLayoutSnapshot(
    layoutInfo: LazyListLayoutInfo,
    canScrollForward: Boolean,
): AbsoluteBottomLayoutSnapshot {
    val sentinelIndex = layoutInfo.totalItemsCount - 1
    val sentinel = if (sentinelIndex >= 0) {
        layoutInfo.visibleItemsInfo.firstOrNull { item -> item.index == sentinelIndex }
    } else {
        null
    }
    val lastVisible = layoutInfo.visibleItemsInfo.maxByOrNull { item -> item.index }
    return AbsoluteBottomLayoutSnapshot(
        totalItemsCount = layoutInfo.totalItemsCount,
        canScrollForward = canScrollForward,
        viewportStartOffsetPx = layoutInfo.viewportStartOffset,
        viewportEndOffsetPx = layoutInfo.viewportEndOffset,
        afterContentPaddingPx = layoutInfo.afterContentPadding,
        sentinelOffsetPx = sentinel?.offset,
        sentinelSizePx = sentinel?.size,
        lastVisibleIndex = lastVisible?.index,
        lastVisibleEndOffsetPx = lastVisible?.let { item -> item.offset + item.size },
    )
}

internal fun isWithinAbsoluteBottomAttachThreshold(
    snapshot: AbsoluteBottomLayoutSnapshot,
    remainingDistancePx: Float?,
    thresholdPx: Float,
): Boolean {
    require(thresholdPx >= 0f)
    val layoutReady = snapshot.totalItemsCount > 0 && snapshot.viewportSizePx > 0
    if (!layoutReady) return false
    if (!snapshot.canScrollForward) return true
    return remainingDistancePx != null && remainingDistancePx <= thresholdPx
}

internal fun estimateAbsoluteBottomDistancePx(
    lastVisibleIndex: Int,
    lastVisibleEndOffsetPx: Int,
    viewportEndOffsetPx: Int,
    afterContentPaddingPx: Int,
    totalItemsCount: Int,
    estimatedItemSizePx: (Int) -> Float,
): Float {
    if (lastVisibleIndex < 0 || totalItemsCount <= 0) return 0f
    var estimatedContentEnd = lastVisibleEndOffsetPx.toFloat()
    for (index in (lastVisibleIndex + 1) until totalItemsCount) {
        estimatedContentEnd += estimatedItemSizePx(index).coerceAtLeast(0f)
    }
    val viewportContentEnd = viewportEndOffsetPx - afterContentPaddingPx
    return (estimatedContentEnd - viewportContentEnd).coerceAtLeast(0f)
}

internal fun shouldShowAbsoluteBottomButton(
    isNewChatMode: Boolean,
    isSwitching: Boolean,
    conversationContentReady: Boolean,
    shareSelectionActive: Boolean,
    hasItems: Boolean,
    canScrollForward: Boolean,
    isNearBottom: Boolean,
    isStreamingAutoFollowing: Boolean,
    scrollPhase: AbsoluteBottomScrollPhase,
    competingProgrammaticScrollActive: Boolean = false,
): Boolean =
    !isNewChatMode &&
        !isSwitching &&
        conversationContentReady &&
        !shareSelectionActive &&
        hasItems &&
        canScrollForward &&
        !isNearBottom &&
        !isStreamingAutoFollowing &&
        !scrollPhase.isActive &&
        !competingProgrammaticScrollActive

/**
 * Hysteretic proximity latch for the absolute-bottom button.
 *
 * A single threshold makes the button flicker when a layout/IME update moves the sentinel by a
 * pixel or two. Once hidden, the button therefore stays hidden until the viewport has moved a
 * meaningful distance away from the physical end.
 */
internal fun reduceAbsoluteBottomProximity(
    wasNearBottom: Boolean,
    canScrollForward: Boolean,
    remainingDistancePx: Float?,
    hideThresholdPx: Float,
    showThresholdPx: Float,
): Boolean {
    require(hideThresholdPx >= 0f)
    require(showThresholdPx >= hideThresholdPx)
    if (!canScrollForward) return true
    val distance = remainingDistancePx ?: return false
    return if (wasNearBottom) {
        distance <= showThresholdPx
    } else {
        distance <= hideThresholdPx
    }
}

/**
 * Smoothly reaches the list's physical maximum extent. While seeking, every layout change
 * retargets the same actor; once it reaches the bottom of an active generation, ownership is
 * handed to MessageList's attached-tail actor. The stable final sentinel makes the target
 * independent from the streaming-tail indicator; [LazyListState.canScrollForward] remains the
 * completion authority.
 *
 * No scroll mutation is held while already at the bottom. That keeps touch input responsive.
 * A real drag cancels the owning effect in [ChatApp]; new content wakes the suspended snapshot
 * observer and is absorbed by the same frame-coalesced actor.
 */
internal suspend fun LazyListState.animateToAbsoluteBottom(
    isGenerationActive: () -> Boolean,
    estimateRemainingDistancePx: () -> Float?,
    minimumStepPx: Float,
    onPhaseChanged: (AbsoluteBottomScrollPhase) -> Unit,
    feedbackSpec: FeedbackScrollSpec = DefaultFeedbackScrollSpec,
): Boolean {
    var phase = AbsoluteBottomScrollPhase.IDLE
    var followedActiveGeneration = isGenerationActive()

    fun dispatch(event: AbsoluteBottomScrollEvent) {
        val next = reduceAbsoluteBottomScroll(phase, event)
        if (next != phase) {
            phase = next
            onPhaseChanged(next)
        }
    }

    dispatch(AbsoluteBottomScrollEvent.Requested)
    try {
        while (currentCoroutineContext().isActive) {
            followedActiveGeneration = followedActiveGeneration || isGenerationActive()
            var layout = absoluteBottomLayoutSnapshot(layoutInfo, canScrollForward)
            if (!layout.sentinelVisible) dispatch(AbsoluteBottomScrollEvent.TargetUnavailable)

            val reached = smoothSeekToItem(
                targetIndex = { (layoutInfo.totalItemsCount - 1).coerceAtLeast(0) },
                targetErrorPx = { sentinel ->
                    dispatch(AbsoluteBottomScrollEvent.TargetAvailable)
                    val current = absoluteBottomLayoutSnapshot(
                        layoutInfo = layoutInfo,
                        canScrollForward = canScrollForward,
                    )
                    val contentEnd =
                        current.viewportEndOffsetPx - current.afterContentPaddingPx
                    (sentinel.offset + sentinel.size - contentEnd)
                        .toFloat()
                        .coerceAtLeast(0f)
                },
                estimatedErrorPx = estimateRemainingDistancePx,
                exactTargetReady = {
                    absoluteBottomLayoutSnapshot(
                        layoutInfo = layoutInfo,
                        canScrollForward = canScrollForward,
                    ).sentinelVisible
                },
                minimumStepPx = minimumStepPx,
                feedbackSpec = feedbackSpec,
            )
            if (!reached) {
                dispatch(AbsoluteBottomScrollEvent.Finished)
                return false
            }

            dispatch(AbsoluteBottomScrollEvent.BottomReached)
            layout = absoluteBottomLayoutSnapshot(layoutInfo, canScrollForward)
            if (isGenerationActive()) {
                // Do not keep a second long-lived follow owner for the rest of generation.
                // ChatApp exits the handoff phase and explicitly attaches MessageList's one
                // frame-driven tail actor, which a real upward drag can cancel immediately.
                dispatch(AbsoluteBottomScrollEvent.Finished)
                return true
            }

            val minimumSettlingMs = if (followedActiveGeneration) 700L else 192L
            val settlingStartNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
            var previousLayout = layout
            var stableFrames = 0
            while (currentCoroutineContext().isActive) {
                val frameNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
                layout = absoluteBottomLayoutSnapshot(layoutInfo, canScrollForward)
                if (isGenerationActive() || layout.canScrollForward || !layout.sentinelVisible) {
                    followedActiveGeneration =
                        followedActiveGeneration || isGenerationActive()
                    dispatch(AbsoluteBottomScrollEvent.ExtentChanged)
                    break
                }
                stableFrames = if (layout == previousLayout) stableFrames + 1 else 0
                previousLayout = layout
                val settlingElapsedMs =
                    (frameNanos - settlingStartNanos).coerceAtLeast(0L) / 1_000_000L
                if (
                    (settlingElapsedMs >= minimumSettlingMs && stableFrames >= 6) ||
                    settlingElapsedMs >= 1_600L
                ) {
                    dispatch(AbsoluteBottomScrollEvent.Finished)
                    return true
                }
            }
        }
        return false
    } finally {
        if (phase.isActive) dispatch(AbsoluteBottomScrollEvent.Cancelled)
    }
}
