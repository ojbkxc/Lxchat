package com.lxseek.chat.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import com.lxseek.chat.ui.motion.MotionAwareLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.lxseek.chat.R
import com.lxseek.chat.model.ChatConversation
import com.lxseek.chat.ui.motion.LocalLxChatMotionPolicy
import com.lxseek.chat.ui.theme.ChatType

/**
 * The chat screen's top bar: a title capsule (drawer menu + brand/conversation
 * title with optional token subtitle) and an actions capsule (system prompt +
 * new chat). Extracted from [ChatApp]; all behavior is routed through callbacks.
 */
@Composable
internal fun ChatTopBar(
    isNewChatMode: Boolean,
    conversations: List<ChatConversation>,
    currentConversationId: String?,
    currentConversationTitle: String? = null,
    totalTokens: Int,
    /**
     * Configured context window for the active conversation, in tokens. When > 0 and
     * [totalTokens] > 0, a thin usage progress bar is rendered below the top bar content.
     * The bar turns orange above 80% utilization and red above 95%.
     */
    contextWindow: Int = 0,
    appName: String = stringResource(R.string.app_name),
    searchActive: Boolean = false,
    searchQuery: String = "",
    searchMatchIndex: Int = -1,
    searchMatchCount: Int = 0,
    onNavigateBack: (() -> Unit)? = null,
    onOpenDrawer: () -> Unit,
    onSearchQueryChange: (String) -> Unit = {},
    onSearchPrevious: () -> Unit = {},
    onSearchNext: () -> Unit = {},
    onSearchDismiss: () -> Unit = {},
    onNewChat: () -> Unit,
    shareSelectionActive: Boolean = false,
    shareSelectionCount: Int = 0,
    shareAllSelected: Boolean = false,
    onDismissShareSelection: () -> Unit = {},
    onShareToggleAll: () -> Unit = {},
) {
    if (shareSelectionActive) {
        ShareSelectionTopBar(
            selectedCount = shareSelectionCount,
            allSelected = shareAllSelected,
            onDismiss = onDismissShareSelection,
            onToggleAll = onShareToggleAll,
        )
        return
    }
    val allowSpatialTransitions = LocalLxChatMotionPolicy.current.allowSpatialTransitions
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchActive) {
        if (searchActive) {
            // Let AnimatedContent commit the search field before asking the IME for focus.
            // Requesting focus on the state-change frame makes the keyboard and enter
            // transition compete for the first layout and produces a visible flash.
            withFrameNanos { }
            searchFocusRequester.requestFocus()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 80.dp)
            .background(
                Brush.verticalGradient(
                    0.0f to MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
                    0.6f to MaterialTheme.colorScheme.background.copy(alpha = 0.80f),
                    1.0f to Color.Transparent
                )
            )
    ) {
        AnimatedContent(
            targetState = searchActive,
            transitionSpec = {
                val contentTransform = if (!allowSpatialTransitions) {
                    fadeIn(tween(360, easing = FastOutSlowInEasing))
                        .togetherWith(
                            fadeOut(tween(300, easing = FastOutSlowInEasing)),
                        )
                } else if (targetState) {
                    (
                        fadeIn(tween(400, easing = FastOutSlowInEasing)) +
                            scaleIn(
                                initialScale = 0.94f,
                                animationSpec = tween(400, easing = FastOutSlowInEasing),
                            )
                        ).togetherWith(
                        fadeOut(tween(300, easing = FastOutSlowInEasing)) +
                            scaleOut(
                                targetScale = 0.97f,
                                animationSpec = tween(300, easing = FastOutSlowInEasing),
                            )
                    )
                } else {
                    (
                        fadeIn(tween(360, easing = FastOutSlowInEasing)) +
                            scaleIn(
                                initialScale = 0.97f,
                                animationSpec = tween(360, easing = FastOutSlowInEasing),
                            )
                        ).togetherWith(
                        fadeOut(tween(320, easing = FastOutSlowInEasing)) +
                            scaleOut(
                                targetScale = 0.94f,
                                animationSpec = tween(320, easing = FastOutSlowInEasing),
                            )
                    )
                }
                contentTransform.using(SizeTransform(clip = false))
            },
            contentAlignment = Alignment.Center,
            label = "ChatTopBarSearchTransition",
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                .height(52.dp),
        ) { targetSearchActive ->
            Row(
                modifier = Modifier
                    .fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (targetSearchActive) {
                    ChatTopBarCapsule(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.width(5.dp))
                            IconButton(
                                onClick = onSearchDismiss,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChange,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Search,
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(searchFocusRequester),
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                stringResource(R.string.conversation_search_hint),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    .copy(alpha = 0.6f),
                                                maxLines = 1,
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                            Text(
                                text = if (searchMatchCount == 0) {
                                    "0/0"
                                } else {
                                    "${searchMatchIndex + 1}/$searchMatchCount"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                            IconButton(
                                enabled = searchMatchIndex > 0,
                                onClick = onSearchPrevious,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    contentDescription = null,
                                )
                            }
                            IconButton(
                                enabled = searchMatchIndex >= 0 &&
                                    searchMatchIndex < searchMatchCount - 1,
                                onClick = onSearchNext,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                )
                            }
                            Spacer(Modifier.width(5.dp))
                        }
                    }
                } else {
                // Resolve the active conversation's title; null in new-chat mode OR
                // before the conversation/title has loaded. Both the brand TEXT and the
                // brand font SIZE are gated on this single value, so the title never
                // changes size before the text swaps (no transient "LxChat at 17sp").
                val resolvedTitle = if (isNewChatMode) null else {
                    currentConversationTitle?.takeIf { it.isNotBlank() }
                        ?: conversations.find { it.id == currentConversationId }?.title?.takeIf { it.isNotBlank() }
                }
                val showBrandTitle = resolvedTitle == null

                // Title capsule: menu + title
                ChatTopBarCapsule(
                    modifier = Modifier.fillMaxHeight().widthIn(max = 220.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(5.dp))
                        IconButton(
                            onClick = onNavigateBack ?: onOpenDrawer,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = if (onNavigateBack != null) {
                                    Icons.AutoMirrored.Filled.ArrowBack
                                } else {
                                    Icons.Default.Menu
                                },
                                contentDescription = stringResource(
                                    if (onNavigateBack != null) R.string.back else R.string.menu
                                ),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(5.dp))
                        if (showBrandTitle) {
                            Text(
                                text = appName,
                                style = ChatType.brandTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 180.dp)
                            )
                        } else {
                            Column(modifier = Modifier.widthIn(max = 180.dp)) {
                                Text(
                                    text = resolvedTitle,
                                    // Single-line (no token subtitle) uses a slightly-smaller-than-brand
                                    // solo size; with the token subtitle stacked below, the compact size.
                                    style = if (totalTokens > 0) ChatType.conversationTitle else ChatType.conversationTitleSolo,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (totalTokens > 0) {
                                    Text(
                                        text = stringResource(R.string.total_tokens, totalTokens),
                                        style = ChatType.micro,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(20.dp))
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Actions capsule: system prompt + new chat
                ChatTopBarCapsule(
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Row(
                        modifier = Modifier.fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(5.dp))
                        IconButton(onClick = onNewChat, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_chat), modifier = Modifier.size(24.dp))
                        }
                        Spacer(modifier = Modifier.width(5.dp))
                    }
                }
                }
            }
        }
        // Token usage progress bar: shown only when both used tokens and the configured
        // context window are positive. Color escalates from primary to orange (>80%) to
        // error red (>95%) so users can anticipate a needed context compact.
        if (contextWindow > 0 && totalTokens > 0) {
            val usageRatio = (totalTokens.toFloat() / contextWindow).coerceIn(0f, 1f)
            val usageColor = when {
                usageRatio > 0.95f -> MaterialTheme.colorScheme.error
                usageRatio > 0.80f -> Color(0xFFFF9800)
                else -> MaterialTheme.colorScheme.primary
            }
            LinearProgressIndicator(
                progress = { usageRatio },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(2.dp),
                color = usageColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatTopBarCapsule(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier,
        shape = shape,
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

@Composable
private fun ShareSelectionTopBar(
    selectedCount: Int,
    allSelected: Boolean,
    onDismiss: () -> Unit,
    onToggleAll: () -> Unit,
) {
    // Apply statusBarsPadding so the "Selected N" title and action buttons are not
    // obscured by the system status bar. ChatTopBar's normal (non-share) branch uses
    // statusBarsPadding() on its AnimatedContent (see line ~165); the share-selection
    // branch must do the same to stay within the safe top inset.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cancel),
                )
            }
            Text(
                text = stringResource(R.string.share_selected_count, selectedCount),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onToggleAll) {
                Text(
                    stringResource(
                        if (allSelected) R.string.deselect_all else R.string.select_all
                    )
                )
            }
        }
    }
}
