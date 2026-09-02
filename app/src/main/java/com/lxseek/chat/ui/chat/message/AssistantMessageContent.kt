package com.lxseek.chat.ui.chat.message

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import com.lxseek.chat.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.lxseek.chat.R
import com.lxseek.chat.util.NoAutoScrollSelectionContainer
import com.lxseek.chat.util.noOpBringIntoView
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.TokenUsage
import com.lxseek.chat.model.ToolCallDisplayModes
import com.lxseek.chat.model.ThinkingSegmentDisplayModes
import com.lxseek.chat.ui.common.LocalLxChatHaptics
import com.lxseek.chat.ui.theme.ChatType
import com.lxseek.chat.ui.theme.LxDesign

internal val AssistantMessageHorizontalInset = 8.dp

private enum class AssistantStatusKind {
    ACTIVE,
    THINKING,
    SUCCESS,
    STOPPED,
    INFO,
}

private data class AssistantStatusPresentation(
    val text: String,
    val kind: AssistantStatusKind,
)

internal data class TokenUsagePresentation(
    val input: Int?,
    val cachedInput: Int?,
    val output: Int?,
)

internal fun tokenUsagePresentation(
    usage: TokenUsage?,
): TokenUsagePresentation {
    if (usage == null) return TokenUsagePresentation(null, null, null)
    // TokenUsage 来自 :core:model，跨模块属性无法 smart cast，先绑定局部变量。
    val cachedInput = usage.cachedInputTokenCount
    val uncachedInput = usage.uncachedInputTokenCount
    val input = usage.inputTokenCount
        ?: if (cachedInput != null && uncachedInput != null) {
            TokenUsage.addCounts(
                cachedInput,
                uncachedInput,
            )
        } else {
            usage.outputTokenCount
                ?.let { output -> (usage.totalTokenCount - output).takeIf { it >= 0 } }
        }
    val output = usage.outputTokenCount
        ?: input?.let { inputCount ->
            (usage.totalTokenCount - inputCount).takeIf { it >= 0 }
        }
    return TokenUsagePresentation(
        input = input,
        cachedInput = usage.cachedInputTokenCount,
        output = output,
    )
}

@Composable
private fun AssistantStatusRow(status: AssistantStatusPresentation) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when (status.kind) {
                AssistantStatusKind.ACTIVE,
                AssistantStatusKind.THINKING,
                -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = if (status.kind == AssistantStatusKind.THINKING) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    strokeWidth = 2.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                AssistantStatusKind.SUCCESS -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Completed",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                AssistantStatusKind.STOPPED -> Icon(
                    Icons.Default.Stop,
                    contentDescription = "Stopped",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                AssistantStatusKind.INFO -> Icon(
                    Icons.Default.Info,
                    contentDescription = "Error",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            status.text,
            style = ChatType.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The left-aligned assistant (and error) message content: the streaming status header,
 * the thinking / tool-call timeline or compact segment block, the debounced markdown
 * body, any generated images, the stopped indicator, and the regenerate/overflow
 * action row.
 *
 * Extracted from [MessageItem]. The parent owns the reported-height bookkeeping and the
 * segment-detail sheet, so this composable reports the thought block height through
 * [setThoughtBlockHeight] and surfaces clicked segments through [onSegmentSelected].
 */
@Composable
internal fun AssistantMessageContent(
    message: ChatMessage,
    segmentAppearanceRegistry: SegmentAppearanceRegistry,
    contextAlpha: Modifier,
    isStreaming: Boolean,
    isLoading: Boolean,
    isRegenerationExiting: Boolean,
    isEditingAllowed: Boolean,
    showActions: Boolean,
    actionCopyText: String?,
    showBranchSelector: Boolean,
    toolCallDisplayMode: String,
    thinkingSegmentDisplayMode: String,
    autoExpandActiveGroup: Boolean,
    detailedTokenUsage: Boolean,
    groupedSegmentAutoExpansionController: GroupedSegmentAutoExpansionController,
    thoughtExpandedStates: SnapshotStateMap<String, Boolean>,
    renderContext: ChatMarkdownRenderContext,
    thoughtRenderContext: ChatMarkdownRenderContext = renderContext,
    branchIndex: Int,
    totalBranches: Int,
    onSwitchBranch: (Int) -> Unit,
    onRegenerate: (String) -> Boolean,
    onResume: (String) -> Boolean = { false },
    onFork: () -> Unit,
    onShare: () -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    onShowInfo: () -> Unit,
    onShowDelete: () -> Unit,
    onSegmentSelected: (List<Int>) -> Unit,
    onLayoutMutationStarted: (String) -> Unit,
    onLayoutMutationSettled: (String) -> Unit,
    setThoughtBlockHeight: (Int) -> Unit,
    isTtsPlaying: Boolean = false,
    onToggleTts: () -> Unit = {},
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalLxChatHaptics.current
    var showMenu by remember(message.id) { mutableStateOf(false) }
    var regenerateRequested by remember(message.id) { mutableStateOf(false) }
    var observedRegenerationExit by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(isRegenerationExiting) {
        if (isRegenerationExiting) {
            observedRegenerationExit = true
        } else if (observedRegenerationExit) {
            // An aborted transition keeps the old answer composed. Restore its controls only
            // after the externally-owned regeneration state has genuinely ended.
            regenerateRequested = false
            observedRegenerationExit = false
        }
    }
    val regenerationActionsExiting = regenerateRequested || isRegenerationExiting
    LaunchedEffect(regenerationActionsExiting) {
        if (regenerationActionsExiting) showMenu = false
    }
    // During generation, eat horizontal nested-scroll so code blocks
    // cannot be panned. Vertical scroll and taps (thinking header,
    // stop button) pass through normally. Text selection is already
    // prevented during streaming by the stable Markdown selection host.
    val horizontalScrollEater = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                Offset(available.x, 0f)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AssistantMessageHorizontalInset)
            .then(contextAlpha)
            .then(if (isStreaming) Modifier.nestedScroll(horizontalScrollEater) else Modifier)
    ) {
        Column {
            // Status Header
            if (message.participant == Participant.MODEL) {
                val thinkingStatus = stringResource(R.string.thinking_ellipsis)
                val answeringStatus = stringResource(R.string.answering_ellipsis)
                val thinkingNow = message.status == MessageStatus.THINKING
                val isToolCalling = message.status == MessageStatus.TOOL_CALLING
                val isTranscribing = message.status == MessageStatus.TRANSCRIBING
                val hasInFlightStatus = message.status == MessageStatus.SENDING ||
                    thinkingNow || isToolCalling || isTranscribing
                val hasActiveAnswer = message.hasActiveAnswerSegment()
                val toolCallingStatus = stringResource(R.string.tool_calling_ellipsis)
                val transcribingStatus = stringResource(R.string.transcription_ellipsis)
                val detailedUsage = if (detailedTokenUsage) {
                    tokenUsagePresentation(message.tokenUsage)
                        .takeIf { it.input != null || it.output != null }
                } else {
                    null
                }
                val completedUsageText = detailedUsage?.let { usage ->
                    val input = usage.input?.toString() ?: "—"
                    val output = usage.output?.toString() ?: "—"
                    if (usage.cachedInput != null) {
                        stringResource(
                            R.string.token_usage_detail_cached,
                            input,
                            usage.cachedInput.toString(),
                            output,
                        )
                    } else {
                        stringResource(
                            R.string.token_usage_detail,
                            input,
                            output,
                        )
                    }
                } ?: stringResource(
                    R.string.cost_tokens,
                    message.tokenCount.coerceAtLeast(0),
                )
                val displayText = when {
                    // Keep the header's measured row across stream → terminal even when a
                    // provider omits usage. Removing it for tokenCount=0 shifts every Markdown
                    // line upward on the exact frame generation completes.
                    message.status == MessageStatus.SUCCESS -> completedUsageText
                    message.status == MessageStatus.STOPPED -> stringResource(R.string.generation_stopped)
                    isStreaming && isTranscribing -> transcribingStatus
                    isStreaming && isToolCalling -> toolCallingStatus
                    isStreaming && thinkingNow -> thinkingStatus
                    isStreaming && hasActiveAnswer -> answeringStatus
                    isStreaming -> stringResource(R.string.sending_ellipsis)
                    else -> null
                }.let { base ->
                    if (base != null && message.retryText != null) "$base (${message.retryText})"
                    else base
                }

                if (displayText != null) {
                    val statusKind = when {
                        message.status == MessageStatus.SUCCESS -> AssistantStatusKind.SUCCESS
                        message.status == MessageStatus.STOPPED -> AssistantStatusKind.STOPPED
                        (isStreaming || hasInFlightStatus) && thinkingNow ->
                            AssistantStatusKind.THINKING
                        isStreaming || hasInFlightStatus -> AssistantStatusKind.ACTIVE
                        else -> AssistantStatusKind.INFO
                    }
                    Crossfade(
                        targetState = AssistantStatusPresentation(displayText, statusKind),
                        animationSpec = tween(
                            durationMillis = STATUS_CROSSFADE_DURATION_MS,
                            easing = LinearEasing,
                        ),
                        label = "assistantStatus:${message.id}",
                    ) { status ->
                        AssistantStatusRow(status)
                    }
                }
            }

            // GenerationManager already publishes a bounded stream cadence. A second UI debounce
            // delayed every chunk, retained a stale text job through Stop, and then replaced the
            // whole document at terminalization. Feed the latest immutable snapshot directly to
            // the off-main Markdown parser.
            val renderedText = message.text

            Column {
                val isError = message.status == MessageStatus.ERROR || message.participant == Participant.ERROR

                // Only zero out thought height when legacy thought block is not shown
                if (message.segments != null || message.thoughts.isNullOrBlank()) {
                    setThoughtBlockHeight(0)
                }

                val segmentsOrNull = message.segments
                val mergedSegments = remember(segmentsOrNull) {
                    mergeAdjacentSegments(segmentsOrNull.orEmpty())
                }
                val normalizedToolCallDisplayMode = ToolCallDisplayModes.normalize(toolCallDisplayMode)
                val useThinkingSheet =
                    ThinkingSegmentDisplayModes.normalize(thinkingSegmentDisplayMode) ==
                        ThinkingSegmentDisplayModes.BOTTOM_SHEET
                val groupAdjacentTimelineTools = normalizedToolCallDisplayMode == ToolCallDisplayModes.GROUPED_TIMELINE
                val useTimelineSegments =
                    !useThinkingSheet &&
                    normalizedToolCallDisplayMode != ToolCallDisplayModes.COMPACT &&
                        (
                            mergedSegments.any { it.type == "answer" } ||
                                (
                                    groupAdjacentTimelineTools &&
                                        mergedSegments.any { it.isInfoSegment() }
                                )
                        )
                val detailSegments = remember(mergedSegments) {
                    mergedSegments.filter { it.type != "answer" }
                }
                val compactVisible = !useTimelineSegments && detailSegments.isNotEmpty()
                val sheetCollapsedStates = remember(message.id) {
                    mutableStateMapOf<String, Boolean>()
                }
                val compactAppearanceKey = compactSegmentBlockAppearanceKey(message.id)
                val compactCardAppearanceKey = "$compactAppearanceKey:card"
                val latestVisibleAnswerIndex =
                    mergedSegments.indexOfLast { it.isVisibleAnswerSegment() }
                val latestVisibleAnswer = mergedSegments.getOrNull(latestVisibleAnswerIndex)
                val compactAnswerAppearanceKey = latestVisibleAnswer?.let { segment ->
                    "${segmentAppearanceKey(
                        message.id,
                        latestVisibleAnswerIndex,
                        segment,
                    )}:compact-answer"
                }

                if (useTimelineSegments) {
                    TimelineSegmentsContent(
                        segments = mergedSegments,
                        detailSegments = detailSegments,
                        message = message,
                        isStreaming = isStreaming,
                        groupAdjacentBlocks = groupAdjacentTimelineTools,
                        autoExpandActiveGroup =
                            groupAdjacentTimelineTools && autoExpandActiveGroup,
                        autoExpansionController = groupedSegmentAutoExpansionController,
                        expandedStates = thoughtExpandedStates,
                        renderContext = thoughtRenderContext,
                        answerRenderContext = renderContext,
                        segmentAppearanceRegistry = segmentAppearanceRegistry,
                        onLayoutMutationStarted = onLayoutMutationStarted,
                        onLayoutMutationSettled = onLayoutMutationSettled,
                        onSegmentClick = { indices ->
                            onSegmentSelected(indices)
                        }
                    )
                }

                // Compact segment block: single block, newest title/icon when collapsed.
                // Answer segments are timeline anchors only; compact mode still renders
                // message.text below as the complete answer.
                if (compactVisible) {
                    AnimatedTimelineBlockAppearance(
                        animationKey = compactAppearanceKey,
                        appearanceRegistry = segmentAppearanceRegistry,
                        isStreaming = isStreaming,
                    ) {
                        CompactSegmentBlock(
                            segs = detailSegments,
                            segmentIndices = detailSegments.indices.toList(),
                            message = message,
                            isStreaming = isStreaming,
                            useLiveStatus = true,
                            expandedStates = if (useThinkingSheet) sheetCollapsedStates else thoughtExpandedStates,
                            expansionKey = message.id,
                            cardAppearanceKey = compactCardAppearanceKey,
                            segmentAppearanceRegistry = segmentAppearanceRegistry,
                            renderContext = thoughtRenderContext,
                            onExpansionStarted = onLayoutMutationStarted,
                            onExpansionSettled = onLayoutMutationSettled,
                            onSegmentClick = { index ->
                                if (useThinkingSheet) onSegmentSelected(detailSegments.indices.toList())
                                else onSegmentSelected(listOf(index))
                            },
                            onHeaderClick = if (useThinkingSheet) {
                                { onSegmentSelected(detailSegments.indices.toList()) }
                            } else {
                                null
                            },
                            opensDetailSheet = useThinkingSheet,
                            onBlockHeightChanged = setThoughtBlockHeight,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .noOpBringIntoView()
                ) {
                    if (isError) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f), contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = LxDesign.shapeM, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Default.Info, contentDescription = "Error", modifier = Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    NoAutoScrollSelectionContainer {
                                        Text(
                                            renderedText.ifEmpty { stringResource(R.string.failed_to_generate) },
                                            style = ChatType.errorBody,
                                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                                // Differentiated recovery action based on the inferred
                                // GenerationError category. The error text comes from
                                // GenerationError.userMessage(), so we match its known patterns.
                                val errorAction = inferErrorAction(renderedText)
                                TextButton(
                                    onClick = { onRegenerate(message.id) },
                                    modifier = Modifier.padding(start = 28.dp, top = 4.dp),
                                ) {
                                    Text(stringResource(errorAction.labelRes))
                                }
                            }
                        }
                    } else if (renderedText.isNotEmpty() && !useTimelineSegments) {
                        if (compactAnswerAppearanceKey != null) {
                            AnimatedTimelineBlockAppearance(
                                animationKey = compactAnswerAppearanceKey,
                                appearanceRegistry = segmentAppearanceRegistry,
                                isStreaming = isStreaming,
                            ) {
                                StreamingMarkdownDocument(
                                    content = renderedText,
                                    isStreaming = isStreaming,
                                    renderContext = renderContext,
                                    modifier = Modifier.fillMaxWidth(),
                                    selectionEnabled = !isStreaming,
                                )
                            }
                        } else {
                            StreamingMarkdownDocument(
                                content = renderedText,
                                isStreaming = isStreaming,
                                renderContext = renderContext,
                                modifier = Modifier.fillMaxWidth(),
                                selectionEnabled = !isStreaming,
                            )
                        }
                    }
                }
                if (message.participant == Participant.MODEL && message.images.isNotEmpty()) {
                    val genImages = message.images
                    // Generated images are primary output, not input references:
                    // render as a full-width square card, image cropped to fill
                    // with rounded corners, tap to view fullscreen.
                    Column(
                        modifier = Modifier.padding(top = if (renderedText.isNotEmpty()) 8.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        genImages.forEachIndexed { idx, path ->
                            coil.compose.AsyncImage(
                                model = path,
                                contentDescription = "Generated image",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(LxDesign.shapeS)
                                    .combinedClickable(
                                        onClick = { onMediaClick(genImages, idx) },
                                        onLongClick = { haptics.longPress() },
                                        hapticFeedbackEnabled = false,
                                    )
                            )
                        }
                    }
                }
                if (message.participant == Participant.MODEL && showActions) {
                    val actionAvailability = assistantActionAvailability(
                        isStreaming = isStreaming,
                        isLoading = isLoading,
                        regenerateRequested = regenerationActionsExiting,
                    )
                    val informationActionsAlpha by animateFloatAsState(
                        targetValue = if (actionAvailability.informationVisible) 1f else 0f,
                        animationSpec = tween(
                            durationMillis = if (actionAvailability.informationVisible) {
                                ACTIONS_ENTER_DURATION_MS
                            } else {
                                ACTIONS_EXIT_DURATION_MS
                            },
                            easing = LinearEasing,
                        ),
                        label = "assistantInformationActions:${message.id}",
                    )
                    val terminalActionsAlpha by animateFloatAsState(
                        targetValue = if (actionAvailability.terminalVisible) 1f else 0f,
                        animationSpec = tween(
                            durationMillis = if (actionAvailability.terminalVisible) {
                                ACTIONS_ENTER_DURATION_MS
                            } else {
                                ACTIONS_EXIT_DURATION_MS
                            },
                            easing = LinearEasing,
                        ),
                        label = "assistantActions:${message.id}",
                    )
                    val enabledActionTint =
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    val terminalActionTint =
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (actionAvailability.terminalEnabled) 0.6f else 0.3f
                        )
                    val destructiveActionTint =
                        MaterialTheme.colorScheme.error.copy(
                            alpha = if (actionAvailability.terminalEnabled) 1f else 0.38f
                        )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Reserve the terminal action row from the first Sending frame. Only
                            // its draw alpha changes, so completion cannot grow the message item.
                            .height(44.dp)
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!actionCopyText.isNullOrBlank()) {
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(actionCopyText))
                                    haptics.confirm()
                                },
                                enabled = actionAvailability.informationEnabled,
                                modifier = Modifier
                                    .size(32.dp)
                                    .graphicsLayer { alpha = informationActionsAlpha },
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    modifier = Modifier.size(16.dp),
                                    tint = enabledActionTint,
                                )
                            }
                            IconButton(
                                onClick = onToggleTts,
                                enabled = actionAvailability.informationEnabled,
                                modifier = Modifier
                                    .size(32.dp)
                                    .graphicsLayer { alpha = informationActionsAlpha },
                            ) {
                                Icon(
                                    if (isTtsPlaying) Icons.Default.Pause else Icons.Default.VolumeUp,
                                    contentDescription = stringResource(
                                        if (isTtsPlaying) R.string.tts_stop else R.string.tts_play
                                    ),
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isTtsPlaying) MaterialTheme.colorScheme.primary else enabledActionTint,
                                )
                            }
                            if (isTtsPlaying) {
                                Text(
                                    text = stringResource(R.string.tts_playing),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.graphicsLayer { alpha = informationActionsAlpha },
                                )
                            }
                        }
                        if (message.status == MessageStatus.STOPPED) {
                            IconButton(
                                onClick = { onResume(message.id) },
                                enabled = actionAvailability.terminalEnabled,
                                modifier = Modifier
                                    .size(32.dp)
                                    .graphicsLayer { alpha = terminalActionsAlpha },
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = stringResource(R.string.continue_generating),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                if (onRegenerate(message.id)) {
                                    regenerateRequested = true
                                    showMenu = false
                                }
                            },
                            enabled = actionAvailability.terminalEnabled,
                            modifier = Modifier
                                .size(32.dp)
                                .graphicsLayer { alpha = terminalActionsAlpha },
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Retry",
                                modifier = Modifier.size(19.dp),
                                tint = terminalActionTint,
                            )
                        }
                        IconButton(
                            onClick = onFork,
                            enabled = actionAvailability.terminalEnabled,
                            modifier = Modifier
                                .size(32.dp)
                                .graphicsLayer { alpha = terminalActionsAlpha },
                        ) {
                            Icon(
                                Icons.Default.CallSplit,
                                contentDescription = stringResource(R.string.conversation_fork_from_here),
                                modifier = Modifier.size(18.dp),
                                tint = terminalActionTint,
                            )
                        }
                        IconButton(
                            onClick = onShare,
                            enabled = actionAvailability.terminalEnabled,
                            modifier = Modifier
                                .size(32.dp)
                                .graphicsLayer { alpha = terminalActionsAlpha },
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = stringResource(R.string.conversation_share),
                                modifier = Modifier.size(16.dp),
                                tint = terminalActionTint,
                            )
                        }
                        Box {
                            IconButton(
                                onClick = {
                                    showMenu = true
                                },
                                enabled = actionAvailability.informationEnabled,
                                modifier = Modifier
                                    .size(32.dp)
                                    .graphicsLayer { alpha = informationActionsAlpha },
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "More actions",
                                    modifier = Modifier.size(18.dp),
                                    tint = enabledActionTint,
                                )
                            }
                            DropdownMenu(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 6.dp,
                                shape = LxDesign.shapeS,
                                expanded = showMenu && actionAvailability.informationVisible,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.info)) },
                                    onClick = {
                                        showMenu = false
                                        onShowInfo()
                                    },
                                    enabled = actionAvailability.informationEnabled,
                                    leadingIcon = { Icon(Icons.Default.Info, null) },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.delete),
                                            color = destructiveActionTint,
                                        )
                                    },
                                    onClick = {
                                        if (actionAvailability.terminalEnabled) {
                                            showMenu = false
                                            onShowDelete()
                                        }
                                    },
                                    enabled = actionAvailability.terminalEnabled,
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = destructiveActionTint,
                                        )
                                    },
                                )
                            }
                        }

                        if (showBranchSelector && totalBranches > 1) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .graphicsLayer { alpha = terminalActionsAlpha }
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .padding(horizontal = 4.dp),
                            ) {
                                IconButton(
                                    onClick = { onSwitchBranch(-1) },
                                    enabled =
                                        actionAvailability.terminalEnabled &&
                                            branchIndex > 0 &&
                                            isEditingAllowed,
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                        contentDescription = "Previous branch",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Text(
                                    "${branchIndex + 1} / $totalBranches",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                IconButton(
                                    onClick = { onSwitchBranch(1) },
                                    enabled = actionAvailability.terminalEnabled &&
                                        branchIndex < totalBranches - 1 &&
                                        isEditingAllowed,
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = "Next branch",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Differentiated recovery action for an assistant error bubble.
 *
 * [GenerationError] is not stored on [ChatMessage] — only its [userMessage] text survives —
 * so [inferErrorAction] matches the known English patterns produced by [userMessage] to
 * pick the most helpful button label. All actions currently trigger regeneration; the
 * label itself is the user-facing differentiation (e.g. "检查网络" vs "重试").
 */
private enum class ErrorAction(@StringRes val labelRes: Int) {
    CHECK_NETWORK(R.string.err_action_check_network),
    RETRY_LATER(R.string.err_action_retry_later),
    INCREASE_MAX_TOKENS(R.string.err_action_increase_max_tokens),
    RETRY(R.string.retry),
}

private fun inferErrorAction(text: String): ErrorAction {
    val lower = text.lowercase()
    return when {
        // GenerationError.Network — "Network error (...)", "Connection refused", "Unknown host"
        lower.contains("network error") ||
            lower.contains("connection refused") ||
            lower.contains("unknown host") ||
            lower.contains("connection reset") ||
            lower.contains("tls failure") -> ErrorAction.CHECK_NETWORK

        // GenerationError.Api with rate-limit code — "Rate limit exceeded" or code contains "rate_limit"
        lower.contains("rate limit") ||
            lower.contains("rate_limit") -> ErrorAction.RETRY_LATER

        // GenerationError.OutputTruncated — "Response hit the output token limit"
        lower.contains("token limit") ||
            lower.contains("cut off") ||
            lower.contains("max_tokens") ||
            lower.contains("output token") -> ErrorAction.INCREASE_MAX_TOKENS

        // GenerationError.IncompleteStream — "ended the response early", "incomplete"
        lower.contains("ended the response early") ||
            lower.contains("incomplete") -> ErrorAction.RETRY

        // Everything else (Api, SseParse, ToolExecution, Transcription, etc.) → generic retry
        else -> ErrorAction.RETRY
    }
}
