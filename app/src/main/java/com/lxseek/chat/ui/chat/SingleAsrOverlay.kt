package com.lxseek.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.viewmodel.VoiceConversationController

/**
 * Compact, non-full-screen recording card for single-shot ASR: a bottom sheet-style
 * card with the live voiceprint, the partial transcript and finish/cancel actions.
 * Tapping the stop button finishes the recording and the transcript lands in the
 * composer; cancel discards it. The multi-turn conversation uses the full-screen
 * [VoiceConversationOverlay] instead.
 */
@Composable
internal fun SingleAsrOverlay(
    state: VoiceConversationController.State,
    partialTranscript: String,
    amplitude: Float,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = state != VoiceConversationController.State.IDLE
    if (!isActive) return

    val isListening = state == VoiceConversationController.State.LISTENING
    val accent = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val stateText = stringResource(
        if (isListening) R.string.voice_conversation_listening else R.string.asr_remote_transcribing
    )

    // Full-size container with the card pinned to the bottom edge (the caller supplies padding).
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = isActive,
            enter = fadeIn(tween(200)) + slideInVertically(tween(220)) { it },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(220)) { it },
        ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                TextButton(onClick = onCancel, enabled = isListening) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp)) {
                    VoiceSpectrumRing(
                        amplitude = if (isListening) amplitude else 0.18f,
                        accent = accent,
                        modifier = Modifier.size(78.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stateText,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = accent,
                    )
                    if (isListening && partialTranscript.isNotBlank()) {
                        Text(
                            text = partialTranscript,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    onClick = onFinish,
                    enabled = isListening,
                    shape = CircleShape,
                    color = if (isListening) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = stringResource(R.string.voice_conversation_tap_to_stop),
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
    }
}
