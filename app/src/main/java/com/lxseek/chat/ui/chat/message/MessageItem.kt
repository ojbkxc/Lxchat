package com.lxseek.chat.ui.chat.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.input.*

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.isContextCompact
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.StableModelAliases
import com.lxseek.chat.model.ToolCallDisplayModes
import com.lxseek.chat.model.ThinkingSegmentDisplayModes
import com.lxseek.chat.ui.chat.ConversationSearchMatch
import com.lxseek.chat.ui.chat.conversationSearchMatchRanges
import com.lxseek.chat.ui.common.LocalLxChatHaptics
import com.lxseek.chat.ui.components.*
import com.lxseek.chat.ui.motion.LocalLxChatMotionPolicy
import com.lxseek.chat.ui.theme.LxDesign
import com.mikepenz.markdown.compose.components.markdownComponents

internal fun usesExplicitDetailBackHandler(thinkingSegmentDisplayMode: String): Boolean =
    ThinkingSegmentDisplayModes.normalize(thinkingSegmentDisplayMode) ==
        ThinkingSegmentDisplayModes.BOTTOM_SHEET

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun MessageItem(
    message: ChatMessage,
    onEdit: (String, String) -> Unit,
    segmentAppearanceRegistry: SegmentAppearanceRegistry,
    modifier: Modifier = Modifier,
    animateEntrance: Boolean = false,
    isStreaming: Boolean = false,
    isLoading: Boolean = false,
    isRegenerationExiting: Boolean = false,
    isEditingAllowed: Boolean = true,
    isEditing: Boolean = false,
    isSwitching: Boolean = false,
    isInContext: Boolean = false,
    modelAliases: StableModelAliases = StableModelAliases(),
    visualizeContextRollout: Boolean = false,
    toolCallDisplayMode: String = ToolCallDisplayModes.DEFAULT,
    thinkingSegmentDisplayMode: String = ThinkingSegmentDisplayModes.DEFAULT,
    autoExpandActiveGroup: Boolean = true,
    detailedTokenUsage: Boolean = false,
    groupedSegmentAutoExpansionController: GroupedSegmentAutoExpansionController =
        remember { GroupedSegmentAutoExpansionController() },
    onStartEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    showActions: Boolean = true,
    actionCopyText: String? = message.text,
    showBranchSelector: Boolean = true,
    branchIndex: Int = 0,
    totalBranches: Int = 1,
    onSwitchBranch: (Int) -> Unit = {},
    onRegenerate: (String) -> Boolean = { false },
    onResume: (String) -> Boolean = { false },
    onFork: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    deleteTargetMessageId: String = message.id,
    onDelete: (String) -> Unit = {},
    onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    onHeightChanged: (Int) -> Unit = {},
    searchQuery: String = "",
    activeSearchMatch: ConversationSearchMatch? = null,
    onSearchMatchPosition: (key: String, centerYInRoot: Float) -> Unit = { _, _ -> },
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onLayoutMutationStarted: (String) -> Unit = {},
    onLayoutMutationSettled: (String) -> Unit = {},
    thoughtExpandedStates: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() },
    isTtsPlaying: Boolean = false,
    onToggleTts: () -> Unit = {},
    /**
     * Optional callback invoked when the row is swiped to reveal the delete action.
     * When null, the surrounding list falls back to the standard delete confirmation flow.
     * Supplied by [com.lxseek.chat.ui.chat.MessageList]'s swipe-to-reveal gesture.
     */
    onSwipeToDelete: (() -> Unit)? = null,
    /**
     * Optional callback invoked when the row is swiped to reveal the reply action.
     * When null, the reply gesture is a no-op. Supplied by
     * [com.lxseek.chat.ui.chat.MessageList]'s swipe-to-reveal gesture.
     */
    onSwipeToReply: (() -> Unit)? = null,
) {
    var showSegmentDetail by remember { mutableStateOf(false) }
    var detailUsesExplicitBackHandler by remember { mutableStateOf(false) }
    var selectedSegmentIndex by remember { mutableIntStateOf(-1) }
    var selectedSegmentIndices by remember { mutableStateOf<List<Int>>(emptyList()) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showLongPressMenu by remember { mutableStateOf(false) }
    var showCompactDetail by remember(message.id) { mutableStateOf(false) }
    val haptics = LocalLxChatHaptics.current
    val motionPolicy = LocalLxChatMotionPolicy.current
    val clipboardManager = LocalClipboardManager.current

    // Long-press opens an action menu (copy / edit / select / delete) instead of
    // jumping straight into share-selection mode, matching platform conventions.
    if (showLongPressMenu) {
        DropdownMenu(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 6.dp,
            shape = LxDesign.shapeS,
            expanded = true,
            onDismissRequest = { showLongPressMenu = false },
        ) {
            val canCopy = !message.text.isNullOrBlank() &&
                !message.isContextCompact()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_menu_copy)) },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                enabled = canCopy,
                onClick = {
                    showLongPressMenu = false
                    message.text?.let { clipboardManager.setText(AnnotatedString(it)) }
                    haptics.confirm()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_menu_edit)) },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                enabled = isEditingAllowed && message.participant == Participant.USER &&
                    !message.isContextCompact(),
                onClick = {
                    showLongPressMenu = false
                    onStartEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_menu_select)) },
                leadingIcon = { Icon(Icons.Default.Checklist, null) },
                onClick = {
                    showLongPressMenu = false
                    onLongPress()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_menu_delete)) },
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                enabled = !isLoading,
                onClick = {
                    showLongPressMenu = false
                    showDeleteConfirm = true
                },
            )
        }
    }

    if (showInfoDialog) {
        MessageInfoDialog(
            message = message,
            modelAliases = modelAliases.map,
            onDismiss = { showInfoDialog = false }
        )
    }

    if (showDeleteConfirm) {
        MessageDeleteDialog(
            onConfirm = {
                showDeleteConfirm = false
                haptics.destructiveConfirmed()
                onDelete(deleteTargetMessageId)
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    val alignment = when (message.participant) {
        Participant.USER -> Alignment.End
        Participant.MODEL -> Alignment.Start
        Participant.ERROR -> Alignment.CenterHorizontally
    }

    val backgroundColor = when (message.participant) {
        Participant.USER -> MaterialTheme.colorScheme.primaryContainer
        Participant.MODEL -> Color.Transparent
        Participant.ERROR -> MaterialTheme.colorScheme.errorContainer
    }

    val textColor = when (message.participant) {
        Participant.USER -> MaterialTheme.colorScheme.onPrimaryContainer
        Participant.MODEL -> MaterialTheme.colorScheme.onSurface
        Participant.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    // Bubble geometry from LxDesign tokens: the user bubble uses an asymmetric
    // bottom-end corner (8dp) as a "tail" toward the sender side, reinforcing
    // left/right authorship at a glance without any decoration.
    val shape = when (message.participant) {
        Participant.USER -> LxDesign.shapeBubbleUser
        Participant.MODEL -> LxDesign.shapeBubbleModel
        Participant.ERROR -> LxDesign.shapeBubbleModel
    }
    val selectionRippleShape = when (message.participant) {
        Participant.MODEL -> LxDesign.shapeBubbleModel
        else -> shape
    }

    val searchHighlight = searchQuery.takeIf { it.isNotBlank() }?.let { query ->
        val active = activeSearchMatch?.takeIf { it.messageId == message.id }
        val matchKeys = conversationSearchMatchRanges(message, query).map { range ->
            "${message.id}:${range.first}:${range.last + 1}"
        }
        SearchHighlightSpec(
            query = query,
            activeRange = active?.let { it.start until it.endExclusive },
            activeKey = active?.key,
            matchKeys = matchKeys,
            onMatchPosition = onSearchMatchPosition,
        )
    }
    val markdownAssets = rememberChatMarkdownAssets(textColor, searchHighlight)
    val markdownRenderContext = markdownAssets.renderContext
    val thoughtMarkdownRenderContext = markdownAssets.thoughtRenderContext

    val entranceModifier = generationLifecycleAppearanceModifier(
        animationKey = "message:${message.id}",
        animate = animateEntrance && !isSwitching,
        durationMillis = MESSAGE_ENTER_DURATION_MS,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged {
                onHeightChanged(it.height)
            }
            .padding(vertical = 8.dp)
            .then(entranceModifier),
        verticalAlignment = Alignment.Top,
    ) {
        AnimatedVisibility(
            visible = selectionMode,
            enter = if (motionPolicy.allowSpatialTransitions) {
                fadeIn() + expandIn()
            } else {
                fadeIn()
            },
            exit = if (motionPolicy.allowSpatialTransitions) {
                shrinkOut() + fadeOut()
            } else {
                fadeOut()
            },
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.padding(top = 2.dp, end = 4.dp),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .pointerInput(selectionMode) {
                    if (!selectionMode) detectTapGestures(onLongPress = { showLongPressMenu = true })
                },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = alignment,
            ) {
                val contextAlpha = if (visualizeContextRollout && !isInContext) {
                    Modifier.alpha(0.38f)
                } else {
                    Modifier
                }
                if (message.isContextCompact()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(contextAlpha),
                        contentAlignment = Alignment.Center,
                    ) {
                        ContextCompactPill(
                            onClick = { showCompactDetail = true },
                        )
                    }
                } else if (message.participant == Participant.USER) {
                    UserMessageBubble(
                        message = message,
                        shape = shape,
                        backgroundColor = backgroundColor,
                        textColor = textColor,
                        contextAlpha = contextAlpha,
                        isEditing = isEditing,
                        isLoading = isLoading,
                        isEditingAllowed = isEditingAllowed,
                        showActions = showActions,
                        actionCopyText = actionCopyText,
                        showBranchSelector = showBranchSelector,
                        branchIndex = branchIndex,
                        totalBranches = totalBranches,
                        onEdit = onEdit,
                        onCancelEdit = onCancelEdit,
                        onStartEdit = onStartEdit,
                        onSwitchBranch = onSwitchBranch,
                        onMediaClick = onMediaClick,
                        onFileContentClick = onFileContentClick,
                        onPdfPagesClick = onPdfPagesClick,
                        onShowInfo = { showInfoDialog = true },
                        onShowDelete = { showDeleteConfirm = true },
                        searchHighlight = searchHighlight,
                    )
                } else {
                    AssistantMessageContent(
                        message = message,
                        segmentAppearanceRegistry = segmentAppearanceRegistry,
                        contextAlpha = contextAlpha,
                        isStreaming = isStreaming,
                        isLoading = isLoading,
                        isRegenerationExiting = isRegenerationExiting,
                        isEditingAllowed = isEditingAllowed,
                        showActions = showActions,
                        actionCopyText = actionCopyText,
                        showBranchSelector = showBranchSelector,
                        toolCallDisplayMode = toolCallDisplayMode,
                        thinkingSegmentDisplayMode = thinkingSegmentDisplayMode,
                        autoExpandActiveGroup = autoExpandActiveGroup &&
                            ThinkingSegmentDisplayModes.normalize(thinkingSegmentDisplayMode) ==
                                ThinkingSegmentDisplayModes.CARD,
                        detailedTokenUsage = detailedTokenUsage,
                        groupedSegmentAutoExpansionController =
                            groupedSegmentAutoExpansionController,
                        thoughtExpandedStates = thoughtExpandedStates,
                        renderContext = markdownRenderContext,
                        branchIndex = branchIndex,
                        totalBranches = totalBranches,
                        onSwitchBranch = onSwitchBranch,
                        onRegenerate = onRegenerate,
                        onResume = onResume,
                        onFork = { onFork(message.id) },
                        onShare = { onShare(message.id) },
                        onMediaClick = onMediaClick,
                        onShowInfo = { showInfoDialog = true },
                        onShowDelete = { showDeleteConfirm = true },
                        onSegmentSelected = { indices ->
                            selectedSegmentIndices = indices
                            selectedSegmentIndex = indices.firstOrNull() ?: -1
                            detailUsesExplicitBackHandler =
                                usesExplicitDetailBackHandler(thinkingSegmentDisplayMode)
                            showSegmentDetail = true
                        },
                        onLayoutMutationStarted = onLayoutMutationStarted,
                        onLayoutMutationSettled = onLayoutMutationSettled,
                        setThoughtBlockHeight = {},
                        isTtsPlaying = isTtsPlaying,
                        onToggleTts = onToggleTts,
                    )
                }
            }
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(selectionRippleShape)
                        .clickable(onClick = onToggleSelection),
                )
            }
        }
    }

    if (showCompactDetail) {
        val compactDetailMessage = remember(message.id, message.text) {
            message.copy(
                segments = listOf(MessageSegment(type = "thought", content = message.text)),
            )
        }
        SegmentDetailSheet(
            message = compactDetailMessage,
            selectedSegmentIndex = 0,
            selectedSegmentIndices = listOf(0),
            isStreaming = false,
            markdownRenderContext = thoughtMarkdownRenderContext,
            onMediaClick = onMediaClick,
            titleOverride = stringResource(com.lxseek.chat.R.string.context_compact),
            detailFooter = {
                TextButton(
                    onClick = { showCompactDetail = false; showDeleteConfirm = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(top = 18.dp),
                ) {
                    Text(stringResource(com.lxseek.chat.R.string.delete))
                }
            },
            handleBackInternally = true,
            onDismiss = { showCompactDetail = false },
        )
    }

    // Segment detail bottom sheet (self-contained draggable sheet + FSM).
    if (showSegmentDetail && selectedSegmentIndex >= 0) {
        SegmentDetailSheet(
            message = message,
            selectedSegmentIndex = selectedSegmentIndex,
            selectedSegmentIndices = selectedSegmentIndices,
            isStreaming = isStreaming,
            markdownRenderContext = thoughtMarkdownRenderContext,
            onMediaClick = onMediaClick,
            handleBackInternally = detailUsesExplicitBackHandler,
            onDismiss = { showSegmentDetail = false }
        )
    }
}



@Composable
internal fun ContextCompactPill(
    inProgress: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (inProgress) {
                com.lxseek.chat.ui.motion.MotionAwareCircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Compress,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                stringResource(
                    if (inProgress) com.lxseek.chat.R.string.context_compacting
                    else com.lxseek.chat.R.string.context_compact,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
internal fun ContextCompactProgressPill(
    conversationId: String?,
    preview: String,
) {
    // This belongs to one live Compact effect. Do not restore an open preview into a later
    // Compact operation for the same conversation after the progress item leaves composition.
    var showDetail by remember(conversationId) { mutableStateOf(false) }
    val textColor = MaterialTheme.colorScheme.onSurface
    val markdownAssets = rememberChatMarkdownAssets(textColor, searchHighlight = null)
    val previewMessage = remember(conversationId, preview) {
        ChatMessage(
            id = "compact_progress_${conversationId.orEmpty()}",
            text = preview,
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
            segments = listOf(MessageSegment(type = "thought", content = preview)),
        )
    }

    ContextCompactPill(
        inProgress = true,
        onClick = { showDetail = true },
    )
    if (showDetail) {
        SegmentDetailSheet(
            message = previewMessage,
            selectedSegmentIndex = 0,
            selectedSegmentIndices = listOf(0),
            isStreaming = true,
            markdownRenderContext = markdownAssets.thoughtRenderContext,
            onMediaClick = { _, _ -> },
            titleOverride = stringResource(com.lxseek.chat.R.string.context_compact),
            handleBackInternally = true,
            onDismiss = { showDetail = false },
        )
    }
}
