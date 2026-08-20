package com.lxseek.chat.ui.chat.message

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.lxseek.chat.R
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageSegment

internal fun mergeAdjacentSegments(segs: List<MessageSegment>): List<MessageSegment> {
    val merged = mutableListOf<MessageSegment>()
    for (seg in segs) {
        val last = merged.lastOrNull()
        // Only continuous answer/reasoning text is merged into one flowing block.
        // Transcriptions stay separate: each describes a distinct image, so a
        // 1:1 image↔block correspondence must be preserved.
        if (last != null && last.type == seg.type && (seg.type == "answer" || seg.type == "thought")) {
            merged[merged.lastIndex] = last.copy(
                content = last.content + seg.content,
                durationMs = mergeDurationMs(last.durationMs, seg.durationMs)
            )
        } else {
            merged.add(seg)
        }
    }
    // Drop content-less thought segments AFTER merging (a streamed thought arrives as blank
    // fragments that concatenate into real content). Models that signal thinking without
    // emitting a summary would otherwise render an empty "Thinking" block — and because every
    // renderer funnels through here, dropping them keeps the block keys, timeline dots, and
    // detail sheet indices all consistent with what is actually drawn.
    return merged.filterNot { it.type == "thought" && it.content.isBlank() }
}

private fun mergeDurationMs(first: Long?, second: Long?): Long? {
    val merged = (first ?: 0L) + (second ?: 0L)
    return merged.takeIf { it > 0L }
}

internal fun thoughtDurationMs(segs: List<MessageSegment>): Long? {
    return segs.sumOf { seg ->
        if (seg.type == "thought") seg.durationMs ?: 0L else 0L
    }.takeIf { it > 0L }
}

private fun MessageSegment.isBlankAnswerSegment(): Boolean =
    type == "answer" && content.isBlank()

internal fun MessageSegment.isVisibleAnswerSegment(): Boolean =
    type == "answer" && content.isNotBlank()

internal fun MessageSegment.isInfoSegment(): Boolean =
    (type == "thought" && content.isNotBlank()) || type == "tool" || type == "transcription"

internal fun ChatMessage.hasActiveAnswerSegment(): Boolean {
    val lastVisibleSegment = segments?.lastOrNull { !it.isBlankAnswerSegment() }
    return if (lastVisibleSegment != null) {
        lastVisibleSegment.isVisibleAnswerSegment()
    } else {
        text.isNotBlank()
    }
}

/**
 * Stable render identity for one merged message segment.
 *
 * Segment content, tool arguments/results, and lifecycle state are deliberately excluded: those
 * fields grow in place while streaming and must never restart the one-shot entrance animation.
 * The append-only merged index plus type changes only when a genuinely new visible segment enters
 * the message timeline.
 */
internal fun segmentAppearanceKey(
    messageId: String,
    mergedIndex: Int,
    segment: MessageSegment,
): String = "$messageId:segment:$mergedIndex:${segment.type}"

/**
 * Stable identity for one non-answer segment across compact, grouped, and timeline layouts.
 *
 * The detail index is append-only within a message. Keeping layout mode and payload fields out of
 * this key prevents a settings change or a streaming payload update from replaying the entrance.
 */
internal fun detailSegmentAppearanceKey(
    messageId: String,
    detailIndex: Int,
    segment: MessageSegment,
): String = "$messageId:detail-segment:$detailIndex:${segment.type}"

internal fun compactSegmentBlockAppearanceKey(messageId: String): String =
    "$messageId:compact"

internal fun groupedSegmentBlockAppearanceKey(
    messageId: String,
    firstDetailIndex: Int,
): String = "$messageId:group:$firstDetailIndex"

internal fun buildTimelineBlockKeys(
    messageId: String,
    segments: List<MessageSegment>,
    groupAdjacentBlocks: Boolean
): Set<String> {
    val keys = linkedSetOf<String>()
    var detailIndex = 0
    var index = 0
    while (index < segments.size) {
        val seg = segments[index]
        when {
            seg.type == "answer" -> {
                index++
            }
            seg.isInfoSegment() -> {
                if (groupAdjacentBlocks) {
                    var blockEnd = index
                    var firstDetailIndex: Int? = null
                    while (blockEnd < segments.size && !segments[blockEnd].isVisibleAnswerSegment()) {
                        val blockSeg = segments[blockEnd]
                        if (blockSeg.isInfoSegment()) {
                            if (firstDetailIndex == null) firstDetailIndex = detailIndex
                            detailIndex++
                        }
                        blockEnd++
                    }
                    keys += "$messageId:group:${firstDetailIndex ?: index}"
                    index = blockEnd
                } else {
                    keys += "$messageId:timeline:$detailIndex"
                    detailIndex++
                    index++
                }
            }
            else -> {
                index++
            }
        }
    }
    return keys
}

/**
 * Session-scoped first-appearance memory for every rendered message segment.
 *
 * This deliberately lives above LazyColumn items: local remember state is lost when an off-screen
 * message is disposed, which would replay the entrance when it is composed again.
 */
@Stable
internal class SegmentAppearanceRegistry {
    private val seenKeys = HashSet<String>()

    fun shouldAnimate(key: String, isStreaming: Boolean): Boolean =
        isStreaming && key !in seenKeys

    fun markSeen(keys: Iterable<String>) {
        seenKeys.addAll(keys)
    }

    fun markSeen(key: String) {
        seenKeys += key
    }
}

internal enum class GroupedSegmentAutoExpansionAction {
    NONE,
    EXPAND,
    COLLAPSE,
}

/**
 * Session-scoped lifecycle memory for Grouped cards.
 *
 * This lives above LazyColumn items so recomposition and off-screen disposal cannot replay an
 * automatic expansion. A completed group is terminal: append-only message segments may create a
 * new group with a new key, but an old group never becomes active again.
 */
@Stable
internal class GroupedSegmentAutoExpansionController {
    private enum class State {
        ACTIVE,
        FINISHED,
    }

    private val states = HashMap<String, State>()

    fun update(
        key: String,
        isActive: Boolean,
        enabled: Boolean,
    ): GroupedSegmentAutoExpansionAction {
        if (!enabled) {
            if (isActive) {
                states.remove(key)
            } else {
                states[key] = State.FINISHED
            }
            return GroupedSegmentAutoExpansionAction.NONE
        }

        return when (states[key]) {
            null -> {
                states[key] = if (isActive) State.ACTIVE else State.FINISHED
                if (isActive) {
                    GroupedSegmentAutoExpansionAction.EXPAND
                } else {
                    GroupedSegmentAutoExpansionAction.NONE
                }
            }
            State.ACTIVE -> {
                if (isActive) {
                    GroupedSegmentAutoExpansionAction.NONE
                } else {
                    states[key] = State.FINISHED
                    GroupedSegmentAutoExpansionAction.COLLAPSE
                }
            }
            State.FINISHED -> GroupedSegmentAutoExpansionAction.NONE
        }
    }
}

@Composable
internal fun AnimatedTimelineBlockAppearance(
    animationKey: String,
    animate: Boolean? = null,
    appearanceRegistry: SegmentAppearanceRegistry? = null,
    isStreaming: Boolean = false,
    content: @Composable () -> Unit
) {
    key(animationKey) {
        // Claim appearance at the component that actually enters composition. A parent-side
        // bulk mark can consume a key before a conditional child is emitted, silently suppressing
        // the very first scale/fade.
        val play = remember(animationKey, appearanceRegistry) {
            animate ?: appearanceRegistry?.shouldAnimate(animationKey, isStreaming) ?: false
        }
        SideEffect {
            appearanceRegistry?.markSeen(animationKey)
        }
        val appearanceModifier = generationLifecycleAppearanceModifier(
            animationKey = animationKey,
            animate = play,
            durationMillis = SEGMENT_ENTER_DURATION_MS,
            initialScale = SEGMENT_ENTER_INITIAL_SCALE,
        )
        Box(
            modifier = appearanceModifier,
        ) {
            content()
        }
    }
}

@Composable
internal fun rememberSegmentAppearance(
    registry: SegmentAppearanceRegistry,
    animationKey: String,
    isStreaming: Boolean,
): Boolean {
    val play = remember(registry, animationKey) {
        registry.shouldAnimate(animationKey, isStreaming)
    }
    SideEffect {
        registry.markSeen(animationKey)
    }
    return play
}

// Label a transcription segment; numbers them ("Image Transcription 1/2/…") only
// when more than one is present, so a single image keeps the clean unnumbered name.
@Composable
internal fun transcriptionLabel(segs: List<MessageSegment>, index: Int): String {
    val total = segs.count { it.type == "transcription" }
    if (total <= 1) return stringResource(R.string.transcription_label)
    val ordinal = segs.take(index + 1).count { it.type == "transcription" }
    return stringResource(R.string.transcription_label_numbered, ordinal)
}
