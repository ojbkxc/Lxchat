package com.lxseek.chat.ui.chat.bottombar

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R

/**
 * Single-shot ASR (automatic speech recognition) trigger button.
 *
 * Renders a 48dp icon button that toggles between an idle microphone and a
 * stop glyph while recording. The button is intentionally compact because
 * the multi-turn voice conversation entry point lives on the send FAB
 * (see [ComposerSendButton]); this control only covers the press-to-talk
 * single-utterance flow.
 *
 * Extracted from [ChatBottomBar] so the bottom-bar container no longer needs
 * to carry the mic/stop icon imports and the recording state branching.
 */
@Composable
internal fun ComposerVoiceButton(
    singleAsrRecording: Boolean,
    onSingleAsrToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onSingleAsrToggle,
        modifier = modifier.size(48.dp),
    ) {
        Icon(
            imageVector = if (singleAsrRecording) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = stringResource(
                if (singleAsrRecording) R.string.voice_conversation_tap_to_stop
                else R.string.voice_conversation_tap_to_speak,
            ),
            tint = if (singleAsrRecording) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )
    }
}