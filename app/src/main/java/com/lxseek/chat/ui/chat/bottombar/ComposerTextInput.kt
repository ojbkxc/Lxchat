package com.lxseek.chat.ui.chat.bottombar

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.ui.theme.ChatType

/**
 * Composer text input area with conversation variable insertion support.
 *
 * Hosts the multi-line [TextField] together with the inline [ComposerSuggestions]
 * surface that offers slash-command (`/`), tool mention (`@`) and conversation
 * mention (`#`) completions while the user types. The field is intentionally
 * transparent (no container/indicator color) so it can be embedded inside the
 * shared composer occlusion surface without double backgrounds.
 *
 * Extracted from [ChatBottomBar] to keep the bottom-bar container focused on
 * mode-switching state while the text-entry concerns (cursor, scrollbar,
 * suggestion completion) live next to each other.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerTextInput(
    textFieldState: TextFieldState,
    scrollState: ScrollState,
    focusRequester: FocusRequester,
    onInputFocusChanged: (Boolean) -> Unit,
    conversations: List<ConversationMention>,
    onSwitchConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ComposerSuggestions(
        text = textFieldState.text.toString(),
        conversations = conversations,
        onCompleteText = { completed ->
            textFieldState.edit { replace(0, length, completed) }
        },
        onSwitchConversation = onSwitchConversation,
    )

    TextField(
        state = textFieldState,
        scrollState = scrollState,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                onInputFocusChanged(focusState.isFocused)
            }
            .verticalScrollbar(scrollState, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        placeholder = {
            Text(
                stringResource(R.string.ask_lxchat),
                style = ChatType.input,
                // 占位符更淡（alpha=0.5f）以降低视觉权重
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        },
        enabled = true,
        lineLimits = TextFieldLineLimits.MultiLine(1, 6),
        // 圆角背景 + surfaceVariant 容器色，内边距 horizontal 16dp vertical 12dp
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        textStyle = ChatType.input.copy(color = MaterialTheme.colorScheme.onSurface),
    )
}