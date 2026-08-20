package com.lxseek.chat.ui.chat.bottombar

import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Stop
import com.lxseek.chat.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.lxseek.chat.R
import com.lxseek.chat.model.SelectedAttachment
import com.lxseek.chat.ui.common.LocalLxChatHaptics
import com.lxseek.chat.ui.chat.message.COMPOSER_ICON_CROSSFADE_DURATION_MS
import com.lxseek.chat.viewmodel.SendAcceptance
import com.lxseek.chat.viewmodel.VoiceConversationController
import kotlinx.coroutines.launch

private enum class ComposerActionIcon {
    STOPPING,
    PENDING,
    STOP,
    SEND,
    IDLE,
}

/**
 * Unified composer action FAB (48dp circle). ChatGPT-style: when the composer is
 * empty the button is the waveform entry into the real-time voice conversation
 * (multi-turn); when there is text (or single ASR is recording via the mic button)
 * it becomes a send/stop button; while the LLM is generating or a voice
 * conversation is active it becomes a stop button.
 */
@Composable
internal fun ComposerSendButton(
    textFieldState: TextFieldState,
    composer: ChatComposerState,
    isLoading: Boolean,
    isCompacting: Boolean = false,
    isSwitching: Boolean,
    isStopping: Boolean = false,
    isModelValid: Boolean,
    voiceConversationState: VoiceConversationController.State = VoiceConversationController.State.IDLE,
    voiceConversationEnabled: Boolean = false,
    voiceConversationActive: Boolean = false,
    singleAsrRecording: Boolean = false,
    onSendMessage: suspend (
        String,
        List<SelectedAttachment>,
        suspend () -> Unit,
    ) -> SendAcceptance?,
    onStopGeneration: () -> Unit,
    onCollapse: () -> Unit,
    onVoiceConversationToggle: () -> Unit = {},
    onStopSingleAsr: () -> Unit = {},
) {
    val haptics = LocalLxChatHaptics.current
    val context = LocalContext.current
    val submitScope = rememberCoroutineScope()
    var isSubmitting by remember { mutableStateOf(false) }
    // Snapshot captured at the moment the send button is tapped. pendingSend is shared across
    // conversations (the composer outlives conversation switches), so submitting whatever the
    // shared field holds later could send the *new* conversation's draft (C1).
    var pendingSendText by remember { mutableStateOf("") }
    var pendingSendAttachments by remember { mutableStateOf<List<SelectedAttachment>>(emptyList()) }
    val anyProcessing = composer.processingStates.isNotEmpty()

    suspend fun submit(
        submittedText: String,
        submittedAttachments: List<SelectedAttachment>,
    ) {
        val submittedAttachmentIds = submittedAttachments.map { it.localId }
        isSubmitting = true
        try {
            onSendMessage(
                submittedText,
                submittedAttachments,
            ) {
                if (composer.selectedAttachments.map { it.localId } == submittedAttachmentIds) {
                    composer.clearAttachments()
                }
                if (textFieldState.text.toString() == submittedText) {
                    textFieldState.edit { replace(0, length, "") }
                }
                composer.pendingSend = false
                isSubmitting = false
                onCollapse()
            }
        } finally {
            isSubmitting = false
        }
    }

    LaunchedEffect(composer.pendingSend, anyProcessing) {
        if (composer.pendingSend && !anyProcessing) {
            // Submit the click-time snapshot; clear the flag BEFORE the call so an exception
            // inside onSendMessage cannot leave the FAB spinning in PENDING forever (C4).
            val text = pendingSendText
            val attachments = pendingSendAttachments
            composer.pendingSend = false
            try {
                submit(text, attachments)
            } catch (e: Exception) {
                // finally in submit() already reset isSubmitting; the UI must never hang.
            }
        }
    }
    val textIsEmpty = textFieldState.text.isBlank()
    val attachmentsIsEmpty = composer.selectedAttachments.isEmpty()
    val showStop = isLoading && !isStopping && textIsEmpty && attachmentsIsEmpty

    // hasInput tracks only whether the composer has any user content; it does NOT depend on
    // model validity or switching state. The FAB icon uses hasInput (after stop/ASR states) so
    // the send arrow stays visible whenever there is text/attachments, even when no model is
    // selected or a model switch is in progress. canSend below still gates the actual submit
    // and the primary color highlight.
    val hasInput = textFieldState.text.isNotBlank() || composer.selectedAttachments.isNotEmpty()
    val canSend = hasInput && isModelValid && !isSwitching && !isStopping && !isCompacting && !isSubmitting
            && composer.selectedAttachments.none { it.localPath == null && (it.type == "image" || it.type == "file") }
    val isBusy = isStopping || isCompacting || isSubmitting || composer.pendingSend
    val isActionable = (isLoading || canSend || voiceConversationActive || singleAsrRecording || voiceConversationEnabled) && !isSwitching && !isBusy

    val fabIcon = when {
        isStopping || isCompacting || isSubmitting -> ComposerActionIcon.STOPPING
        composer.pendingSend -> ComposerActionIcon.PENDING
        showStop -> ComposerActionIcon.STOP
        singleAsrRecording -> ComposerActionIcon.SEND
        hasInput -> ComposerActionIcon.SEND
        else -> ComposerActionIcon.IDLE
    }

    val containerColor by animateColorAsState(
        targetValue = when (fabIcon) {
            ComposerActionIcon.STOPPING, ComposerActionIcon.PENDING, ComposerActionIcon.IDLE -> MaterialTheme.colorScheme.surfaceVariant
            ComposerActionIcon.SEND -> if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(durationMillis = 400),
        label = "fabContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = when (fabIcon) {
            ComposerActionIcon.STOPPING, ComposerActionIcon.PENDING, ComposerActionIcon.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
            ComposerActionIcon.SEND -> if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onPrimary
        },
        animationSpec = tween(durationMillis = 400),
        label = "fabContent",
    )
    FloatingActionButton(
        onClick = {
            if (isSwitching || isStopping) return@FloatingActionButton
            when (fabIcon) {
                ComposerActionIcon.STOPPING, ComposerActionIcon.PENDING -> {}
                ComposerActionIcon.IDLE -> {
                    haptics.selection()
                    onVoiceConversationToggle()
                }
                ComposerActionIcon.STOP -> onStopGeneration()
                ComposerActionIcon.SEND -> {
                    if (singleAsrRecording) {
                        onStopSingleAsr()
                        return@FloatingActionButton
                    }
                    if (composer.pendingSend) {
                        haptics.selection()
                        composer.pendingSend = false
                        return@FloatingActionButton
                    }
                    // The send arrow is shown whenever there is input (even with no valid
                    // model selected). Prompt the user to pick a model instead of silently
                    // doing nothing. isSwitching is already handled by the early return above.
                    if (!isModelValid) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_select_model_first),
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@FloatingActionButton
                    }
                    if (canSend) {
                        if (anyProcessing) {
                            composer.pendingSend = true
                            // Capture now; the shared composer may hold different content by the
                            // time processing finishes (C1).
                            pendingSendText = textFieldState.text.toString()
                            pendingSendAttachments = composer.selectedAttachments.toList()
                        } else {
                            val submittedText = textFieldState.text.toString()
                            val submittedAttachments = composer.selectedAttachments.toList()
                            submitScope.launch {
                                submit(submittedText, submittedAttachments)
                            }
                        }
                    }
                }
            }
        },
        containerColor = containerColor,
        contentColor = contentColor,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = if (canSend) 2.dp else 0.dp,
            pressedElevation = 2.dp,
            focusedElevation = 2.dp,
            hoveredElevation = 2.dp,
        ),
    ) {
        Crossfade(
            targetState = fabIcon,
            animationSpec = tween(
                durationMillis = COMPOSER_ICON_CROSSFADE_DURATION_MS,
                easing = LinearEasing,
            ),
            label = "composerActionIcon",
        ) { icon ->
            when (icon) {
                ComposerActionIcon.STOPPING -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ComposerActionIcon.PENDING -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ComposerActionIcon.STOP -> Icon(
                    Icons.Default.Stop,
                    stringResource(R.string.action),
                    modifier = Modifier.size(24.dp),
                )
                ComposerActionIcon.SEND -> Icon(
                    Icons.Default.ArrowUpward,
                    stringResource(R.string.action),
                    modifier = Modifier.size(24.dp),
                )
                ComposerActionIcon.IDLE -> Icon(
                    Icons.Default.GraphicEq,
                    stringResource(R.string.action),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
