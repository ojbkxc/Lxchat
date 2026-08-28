package com.lxseek.chat.ui.chat.message

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.zIndex
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.MutatePriority
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert

import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.lxseek.chat.R
import com.lxseek.chat.util.noOpBringIntoView
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.ToolCallDisplayModes
import com.lxseek.chat.ui.motion.LocalLxChatMotionPolicy
import com.lxseek.chat.ui.theme.MonoFamily
import com.lxseek.chat.ui.theme.ChatType
import com.lxseek.chat.ui.components.*
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding

import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

// ── Timeline / segment rendering (extracted from MessageItem.kt) ──────────────
// Pure code-motion. Entry points used by MessageItem.kt are `internal`; the rest
// stay file-private. Behavior unchanged.

private enum class CompactSegmentIcon {
    THINKING,
    TOOL,
    IMAGE,
}

@Composable
internal fun segmentDetailTitle(
    seg: MessageSegment,
    detailSegments: List<MessageSegment>,
    detailIndex: Int
): String {
    return when (seg.type) {
        "tool" -> toolDisplayName(seg)
        "transcription" -> transcriptionLabel(detailSegments, detailIndex)
        else -> stringResource(R.string.tool_thinking)
    }
}

@Composable
internal fun thoughtDurationTitle(thoughtMs: Long, toolCount: Int): String {
    val seconds = (thoughtMs / 1000).toInt()
    return if (toolCount > 0) {
        if (seconds >= 60) {
            stringResource(R.string.thought_for_minutes_called_tools, seconds / 60, seconds % 60, toolCount)
        } else {
            stringResource(R.string.thought_for_seconds_called_tools, seconds, toolCount)
        }
    } else {
        if (seconds >= 60) {
            stringResource(R.string.thought_for_minutes, seconds / 60, seconds % 60)
        } else {
            stringResource(R.string.thought_for_seconds, seconds)
        }
    }
}

@Composable
internal fun compactSegmentTitle(
    segs: List<MessageSegment>,
    message: ChatMessage,
    useLiveStatus: Boolean
): String {
    val lastSeg = segs.lastOrNull() ?: return ""
    val isLastTool = lastSeg.type == "tool"
    val isToolInProgress = isLastTool &&
        ToolPresentationResolver.resolve(lastSeg).isActive
    val isThinking = useLiveStatus && message.status == MessageStatus.THINKING
    val isToolCalling = useLiveStatus && message.status == MessageStatus.TOOL_CALLING
    val isTranscribing = useLiveStatus && message.status == MessageStatus.TRANSCRIBING
    val toolCount = segs.count { it.type == "tool" && it.toolResult != null }
    val thoughtMs = thoughtDurationMs(segs) ?: message.thoughtTimeMs
    return when {
        isThinking -> message.thoughtTitle ?: stringResource(R.string.thinking_ellipsis)
        isTranscribing -> message.thoughtTitle ?: stringResource(R.string.transcription_ellipsis)
        isToolCalling || isToolInProgress -> toolDisplayName(lastSeg)
        thoughtMs != null && thoughtMs > 0 -> thoughtDurationTitle(thoughtMs, toolCount)
        toolCount > 0 -> stringResource(R.string.called_n_tools, toolCount)
        message.thoughtTitle != null -> message.thoughtTitle
        segs.any { it.type == "transcription" } -> "Image Transcription"
        else -> stringResource(R.string.thinking_complete)
    }
}

@Composable
internal fun CompactSegmentBlock(
    segs: List<MessageSegment>,
    segmentIndices: List<Int>,
    message: ChatMessage,
    isStreaming: Boolean,
    useLiveStatus: Boolean,
    expandedStates: SnapshotStateMap<String, Boolean>,
    expansionKey: String,
    cardAppearanceKey: String = "$expansionKey:card",
    segmentAppearanceRegistry: SegmentAppearanceRegistry,
    autoExpansionController: GroupedSegmentAutoExpansionController? = null,
    autoExpansionEnabled: Boolean = false,
    autoExpansionActive: Boolean = false,
    modifier: Modifier = Modifier,
    topPaddingExtra: Dp = 0.dp,
    bottomPaddingExtra: Dp = 6.dp,
    onSegmentClick: (Int) -> Unit,
    onHeaderClick: (() -> Unit)? = null,
    opensDetailSheet: Boolean = false,
    onExpansionStarted: (String) -> Unit = {},
    onExpansionSettled: (String) -> Unit = {},
    onBlockHeightChanged: (Int) -> Unit = {}
) {
    if (segs.isEmpty()) return
    val allowSpatialTransitions = LocalLxChatMotionPolicy.current.allowSpatialTransitions
    val animateCardAppearance = rememberSegmentAppearance(
        registry = segmentAppearanceRegistry,
        animationKey = cardAppearanceKey,
        isStreaming = isStreaming,
    )
    val cardAppearanceModifier = generationLifecycleAppearanceModifier(
        animationKey = cardAppearanceKey,
        animate = animateCardAppearance,
        durationMillis = SEGMENT_ENTER_DURATION_MS,
        initialScale = SEGMENT_ENTER_INITIAL_SCALE,
    )
    val isExpanded by remember(expansionKey) {
        derivedStateOf { expandedStates[expansionKey] ?: false }
    }
    val currentOnExpansionStarted by rememberUpdatedState(onExpansionStarted)
    val currentOnExpansionSettled by rememberUpdatedState(onExpansionSettled)
    LaunchedEffect(
        autoExpansionController,
        expansionKey,
        autoExpansionEnabled,
        autoExpansionActive,
    ) {
        val targetExpanded = when (
            autoExpansionController?.update(
                key = expansionKey,
                isActive = autoExpansionActive,
                enabled = autoExpansionEnabled,
            )
        ) {
            GroupedSegmentAutoExpansionAction.EXPAND -> true
            GroupedSegmentAutoExpansionAction.COLLAPSE -> false
            GroupedSegmentAutoExpansionAction.NONE, null -> null
        }
        if (
            targetExpanded != null &&
            (expandedStates[expansionKey] ?: false) != targetExpanded
        ) {
            currentOnExpansionStarted(expansionKey)
            expandedStates[expansionKey] = targetExpanded
        }
    }
    val lastSeg = segs.last()
    val isLastTool = lastSeg.type == "tool"
    val isToolInProgress = isLastTool &&
        ToolPresentationResolver.resolve(lastSeg).isActive
    val isThinking = useLiveStatus && message.status == MessageStatus.THINKING
    val isToolCalling = useLiveStatus && message.status == MessageStatus.TOOL_CALLING
    val isTranscribing = useLiveStatus && message.status == MessageStatus.TRANSCRIBING
    val toolCount = segs.count { it.type == "tool" && it.toolResult != null }
    val thoughtMs = thoughtDurationMs(segs)
    val hasThought = thoughtMs != null && thoughtMs > 0
    val collapsedTitle = compactSegmentTitle(segs, message, useLiveStatus)
    val collapsedIcon = when {
        isToolCalling || isToolInProgress -> CompactSegmentIcon.TOOL
        !isThinking && !hasThought && toolCount > 0 -> CompactSegmentIcon.TOOL
        isTranscribing || collapsedTitle == "Image Transcription" -> CompactSegmentIcon.IMAGE
        else -> CompactSegmentIcon.THINKING
    }
    val expansionTransition = updateTransition(
        targetState = isExpanded,
        label = "compactSegmentExpansion",
    )
    val mergedBottomPadding = if (allowSpatialTransitions) {
        val animatedPadding by expansionTransition.animateDp(
            transitionSpec = { tween(500) },
            label = "compactSegmentPad",
        ) { expanded ->
            if (expanded) 12.dp else 4.dp
        }
        animatedPadding
    } else if (
        retainExpandedLayoutDuringFade(
            currentExpanded = expansionTransition.currentState,
            targetExpanded = expansionTransition.targetState,
        )
    ) {
        12.dp
    } else {
        4.dp
    }
    LaunchedEffect(expansionTransition, expansionKey) {
        var observedRunning = false
        snapshotFlow { expansionTransition.isRunning }.collect { running ->
            if (running) {
                observedRunning = true
            } else if (observedRunning) {
                observedRunning = false
                currentOnExpansionSettled(expansionKey)
            }
        }
    }
    LaunchedEffect(isExpanded, allowSpatialTransitions, expansionKey) {
        if (!allowSpatialTransitions) {
            currentOnExpansionSettled(expansionKey)
        }
    }
    DisposableEffect(expansionKey) {
        onDispose { currentOnExpansionSettled(expansionKey) }
    }

    Surface(
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.45f),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp + topPaddingExtra, bottom = mergedBottomPadding + bottomPaddingExtra)
            .then(cardAppearanceModifier)
            .noOpBringIntoView()
            .onSizeChanged { onBlockHeightChanged(it.height) }
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        if (onHeaderClick != null) {
                            onHeaderClick()
                        } else {
                            currentOnExpansionStarted(expansionKey)
                            expandedStates[expansionKey] = !isExpanded
                        }
                    }
                    .padding(10.dp)
            ) {
                Crossfade(
                    targetState = collapsedIcon,
                    animationSpec = tween(
                        durationMillis = STATUS_CROSSFADE_DURATION_MS,
                        easing = LinearEasing,
                    ),
                    label = "compactSegmentIcon:$expansionKey",
                    modifier = Modifier.size(16.dp),
                ) { icon ->
                    when (icon) {
                        CompactSegmentIcon.TOOL -> Icon(
                            Icons.Default.Build,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        )
                        CompactSegmentIcon.IMAGE -> Icon(
                            Icons.Filled.Image,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        )
                        CompactSegmentIcon.THINKING -> Icon(
                            androidx.compose.ui.res.painterResource(
                                id = com.lxseek.chat.R.drawable.neurology_24,
                            ),
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Crossfade(
                    targetState = collapsedTitle,
                    animationSpec = tween(
                        durationMillis = STATUS_CROSSFADE_DURATION_MS,
                        easing = LinearEasing,
                    ),
                    label = "compactSegmentTitle:$expansionKey",
                    modifier = Modifier.weight(1f),
                ) { title ->
                    Text(
                        text = title,
                        style = ChatType.thoughtTitle,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    if (opensDetailSheet) {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    } else if (isExpanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            expansionTransition.AnimatedVisibility(
                visible = { it },
                enter = if (allowSpatialTransitions) {
                    fadeIn(tween(400)) + expandVertically(tween(400))
                } else {
                    fadeIn(tween(400))
                },
                exit = if (allowSpatialTransitions) {
                    fadeOut(tween(400)) + shrinkVertically(tween(400))
                } else {
                    fadeOut(tween(400))
                },
            ) {
                Column {
                    Spacer(modifier = Modifier.height(2.dp))
                    segs.forEachIndexed { idx, seg ->
                      val detailIndex = segmentIndices.getOrElse(idx) { idx }
                      AnimatedTimelineBlockAppearance(
                        animationKey = detailSegmentAppearanceKey(
                            message.id,
                            detailIndex,
                            seg,
                        ),
                        appearanceRegistry = segmentAppearanceRegistry,
                        isStreaming = isStreaming,
                      ) {
                       Column {
                        if ((seg.type == "thought" && seg.content.isNotBlank()) || seg.type == "transcription") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onSegmentClick(segmentIndices.getOrElse(idx) { idx })
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    if (seg.type == "transcription") transcriptionLabel(segs, idx) else stringResource(R.string.tool_thinking),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (seg.content.isNotBlank()) {
                                    if (seg.type == "thought") {
                                        StreamingThoughtPreviewText(
                                            content = seg.content,
                                            streaming =
                                                isStreaming &&
                                                    useLiveStatus &&
                                                    idx == segs.lastIndex,
                                        )
                                    } else {
                                        Text(
                                            text = seg.content.replace('\n', ' '),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "Image transcription is empty.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    )
                                }
                            }
                        } else if (seg.type == "tool") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onSegmentClick(segmentIndices.getOrElse(idx) { idx })
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = toolNameHeaderText(seg),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = toolSummary(seg),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (idx < segs.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                            )
                        }
                       }
                      }
                    }
                }
            }
        }
    }
}

/**
 * Tool-card header shown in both the grouped and per-segment timelines: the (localized) tool name
 * plus, once the call has finished, a trailing execution duration. `durationMs` is filled only at
 * completion (see GenerationToolExecutor), so nothing flashes while the tool is still running.
 */

/**
 * Reduced Motion keeps expanded layout space for the whole content fade.
 *
 * On expansion the target state reserves the final layout immediately. On collapse the current
 * state retains that layout until the exit fade finishes. AnimatedVisibility then removes the
 * content in the same transition settlement that releases the card's external spacing.
 */
internal fun retainExpandedLayoutDuringFade(
    currentExpanded: Boolean,
    targetExpanded: Boolean,
): Boolean = currentExpanded || targetExpanded
