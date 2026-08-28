package com.lxseek.chat.ui.chat.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.util.noOpBringIntoView

/**
 * Timeline node rendering — extracted from [MessageItemTimeline] to keep the
 * main file focused on the timeline container while the per-segment dispatch
 * and card rendering live here.
 *
 * Hosts:
 * - [TimelineSegmentsContent]   — the segment-type dispatch loop (answer / thought / tool / transcription)
 * - [TimelineInfoSegmentCard]   — the collapsed card for a single info segment
 * - [StreamingThoughtPreviewText] — the fading tail preview of a streaming thought
 * - [toolNameHeaderText]        — the tool name + duration label
 */

@Composable
internal fun toolNameHeaderText(seg: MessageSegment): String =
    toolDisplayName(seg) + seg.durationMs
        ?.takeIf { it > 0L }
        ?.let { " \u00b7 " + formatShortDuration(it) }
        .orEmpty()

private fun formatShortDuration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 1 -> "${ms}ms"
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> {
            val m = seconds / 60
            val s = seconds % 60
            if (s == 0L) "${m}m" else "${m}m ${s}s"
        }
        else -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            if (m == 0L) "${h}h" else "${h}h ${m}m"
        }
    }
}

@Composable
internal fun TimelineSegmentsContent(
    segments: List<MessageSegment>,
    detailSegments: List<MessageSegment>,
    message: ChatMessage,
    isStreaming: Boolean,
    groupAdjacentBlocks: Boolean,
    autoExpandActiveGroup: Boolean,
    autoExpansionController: GroupedSegmentAutoExpansionController,
    expandedStates: SnapshotStateMap<String, Boolean>,
    renderContext: ChatMarkdownRenderContext,
    segmentAppearanceRegistry: SegmentAppearanceRegistry,
    onLayoutMutationStarted: (String) -> Unit,
    onLayoutMutationSettled: (String) -> Unit,
    onSegmentClick: (List<Int>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        var detailIndex = 0
        var index = 0
        var groupedBlockIndex = 0
        var previousVisibleWasAnswer = false
        val lastVisibleSegmentIndex = segments.indexOfLast { segment ->
            segment.isVisibleAnswerSegment() || segment.isInfoSegment()
        }
        while (index < segments.size) {
            val seg = segments[index]
            when (seg.type) {
                "answer" -> {
                    if (seg.content.isNotBlank()) {
                        val answerIsStreaming =
                            isStreaming && index == lastVisibleSegmentIndex
                        val answerAppearanceKey =
                            "${segmentAppearanceKey(message.id, index, seg)}:timeline"
                        AnimatedTimelineBlockAppearance(
                            animationKey = answerAppearanceKey,
                            appearanceRegistry = segmentAppearanceRegistry,
                            isStreaming = isStreaming,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = if (index == 0) 0.dp else 6.dp),
                            ) {
                                StreamingMarkdownDocument(
                                    content = seg.content,
                                    isStreaming = answerIsStreaming,
                                    renderContext = renderContext,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .noOpBringIntoView(),
                                    selectionEnabled = !answerIsStreaming,
                                )
                            }
                        }
                        previousVisibleWasAnswer = true
                    }
                    index++
                }
                "thought", "tool", "transcription" -> {
                    if (groupAdjacentBlocks) {
                        val blockSegments = mutableListOf<MessageSegment>()
                        val blockDetailIndices = mutableListOf<Int>()
                        var blockEnd = index
                        while (blockEnd < segments.size && !segments[blockEnd].isVisibleAnswerSegment()) {
                            val blockSeg = segments[blockEnd]
                            if (blockSeg.isInfoSegment()) {
                                blockSegments.add(blockSeg)
                                blockDetailIndices.add(detailIndex)
                                detailIndex++
                            }
                            blockEnd++
                        }
                        val expansionKey = groupedSegmentBlockAppearanceKey(
                            message.id,
                            blockDetailIndices.firstOrNull() ?: index,
                        )
                        val blockTopPaddingExtra = if (groupedBlockIndex > 0) 8.dp else 0.dp
                        val blockContent: @Composable () -> Unit = {
                            CompactSegmentBlock(
                                segs = blockSegments,
                                segmentIndices = blockDetailIndices,
                                message = message,
                                isStreaming = isStreaming,
                                useLiveStatus = isStreaming && blockDetailIndices.lastOrNull() == detailSegments.lastIndex,
                                expandedStates = expandedStates,
                                expansionKey = expansionKey,
                                cardAppearanceKey = "$expansionKey:card",
                                segmentAppearanceRegistry = segmentAppearanceRegistry,
                                autoExpansionController = autoExpansionController,
                                autoExpansionEnabled = autoExpandActiveGroup,
                                autoExpansionActive =
                                    isStreaming && blockEnd == segments.size,
                                topPaddingExtra = blockTopPaddingExtra,
                                bottomPaddingExtra = 0.dp,
                                onExpansionStarted = onLayoutMutationStarted,
                                onExpansionSettled = onLayoutMutationSettled,
                                onSegmentClick = { detailIndex -> onSegmentClick(listOf(detailIndex)) },
                            )
                        }
                        AnimatedTimelineBlockAppearance(
                            animationKey = expansionKey,
                            appearanceRegistry = segmentAppearanceRegistry,
                            isStreaming = isStreaming,
                        ) {
                            blockContent()
                        }
                        groupedBlockIndex++
                        previousVisibleWasAnswer = false
                        index = blockEnd
                    } else {
                        val currentDetailIndex = detailIndex
                        detailIndex++
                        val cardTopPaddingExtra = if (previousVisibleWasAnswer) 8.dp else 0.dp
                        val timelineKey = detailSegmentAppearanceKey(
                            message.id,
                            currentDetailIndex,
                            seg,
                        )
                        val cardContent: @Composable () -> Unit = {
                            TimelineInfoSegmentCard(
                                seg = seg,
                                detailSegments = detailSegments,
                                detailIndex = currentDetailIndex,
                                isStreamingContent =
                                    isStreaming && index == lastVisibleSegmentIndex,
                                animateAppearance = isStreaming,
                                topPaddingExtra = cardTopPaddingExtra,
                                cardAnimationKey = "$timelineKey:card",
                                segmentAppearanceRegistry = segmentAppearanceRegistry,
                                onClick = { onSegmentClick(listOf(currentDetailIndex)) },
                            )
                        }
                        AnimatedTimelineBlockAppearance(
                            animationKey = timelineKey,
                            appearanceRegistry = segmentAppearanceRegistry,
                            isStreaming = isStreaming,
                        ) {
                            cardContent()
                        }
                        previousVisibleWasAnswer = false
                        index++
                    }
                }
                else -> {
                    index++
                }
            }
        }
    }
}

@Composable
private fun TimelineInfoSegmentCard(
    seg: MessageSegment,
    detailSegments: List<MessageSegment>,
    detailIndex: Int,
    isStreamingContent: Boolean,
    animateAppearance: Boolean,
    topPaddingExtra: Dp = 0.dp,
    cardAnimationKey: String,
    segmentAppearanceRegistry: SegmentAppearanceRegistry,
    onClick: () -> Unit,
) {
    val animateCardAppearance = rememberSegmentAppearance(
        registry = segmentAppearanceRegistry,
        animationKey = cardAnimationKey,
        isStreaming = animateAppearance,
    )
    val cardAppearanceModifier = generationLifecycleAppearanceModifier(
        animationKey = cardAnimationKey,
        animate = animateCardAppearance,
        durationMillis = SEGMENT_ENTER_DURATION_MS,
        initialScale = SEGMENT_ENTER_INITIAL_SCALE,
    )
    Surface(
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp + topPaddingExtra, bottom = 6.dp)
            .then(cardAppearanceModifier)
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                onClick()
            }
            .noOpBringIntoView(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            val isTool = seg.type == "tool"
            val isTranscription = seg.type == "transcription"
            if (isTool) {
                Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
            } else if (isTranscription) {
                Icon(Icons.Filled.Image, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
            } else {
                Icon(painterResource(id = R.drawable.neurology_24), null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (seg.type) {
                        "tool" -> toolNameHeaderText(seg)
                        "transcription" -> transcriptionLabel(detailSegments, detailIndex)
                        else -> stringResource(R.string.tool_thinking)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (seg.type == "thought" && seg.content.isNotBlank()) {
                    StreamingThoughtPreviewText(
                        content = seg.content,
                        streaming = isStreamingContent,
                    )
                } else {
                    val summary = when (seg.type) {
                        "tool" -> toolSummary(seg)
                        "transcription" -> seg.content.takeIf { it.isNotBlank() }
                            ?: "Image transcription is empty."
                        else -> ""
                    }
                    if (summary.isNotBlank()) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

private const val STREAMING_THOUGHT_PREVIEW_CODE_POINTS = 60

private fun thoughtPreviewTail(
    content: AnnotatedString,
    maximumCodePoints: Int = STREAMING_THOUGHT_PREVIEW_CODE_POINTS,
): AnnotatedString {
    if (content.isEmpty() || maximumCodePoints <= 0) return content
    val raw = content.text
    val codePointCount = raw.codePointCount(0, raw.length)
    if (codePointCount <= maximumCodePoints) return content
    val start = raw.offsetByCodePoints(0, codePointCount - maximumCodePoints)
    return AnnotatedString.Builder().apply {
        append("…")
        append(content.subSequence(start, content.length))
    }.toAnnotatedString()
}

@Composable
internal fun StreamingThoughtPreviewText(
    content: String,
    streaming: Boolean,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val flat = remember(content) { content.replace('\n', ' ') }
    val annotated = remember(flat) { AnnotatedString(flat) }
    val faded = rememberStreamingGlyphFade(
        content = annotated,
        color = color,
        enabled = streaming,
    )
    val preview = remember(faded, streaming) {
        if (streaming) thoughtPreviewTail(faded) else faded
    }
    Text(
        text = preview,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}