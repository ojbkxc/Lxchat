package com.lxseek.chat.viewmodel

import android.content.Context
import com.lxseek.chat.R
import com.lxseek.chat.util.SnackbarEvent
import com.lxseek.chat.util.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TTS_START_GRACE_MS = 5_000L

/**
 * Extracted TTS playback helper — keeps ChatViewModel under the 999-line source-size cap.
 * Called from both the manual toggle path and the auto-play-on-stream-commit path.
 */
internal fun playTtsForMessageInternal(
    appContext: Context,
    messageId: String,
    text: String,
    language: String,
    rate: Float,
    playingIdFlow: MutableStateFlow<String?>,
    snackbarFlow: MutableSharedFlow<SnackbarEvent>,
    scope: CoroutineScope,
    showFailureSnackbar: Boolean = false,
) {
    val plainText = TtsManager.stripMarkdown(text)
    if (plainText.isBlank()) return
    if (!TtsManager.isAvailable.value) {
        TtsManager.init(appContext)
    }
    playingIdFlow.value = messageId
    if (!TtsManager.speak(text = plainText, language = language, rate = rate)) {
        playingIdFlow.value = null
        if (showFailureSnackbar) {
            scope.launch {
                snackbarFlow.emit(SnackbarEvent(appContext.getString(R.string.tts_not_available)))
            }
        }
        return
    }
    scope.launch {
        withTimeoutOrNull(TTS_START_GRACE_MS) {
            TtsManager.isPlaying.first { it }
        }
        if (!TtsManager.isPlaying.value && playingIdFlow.value == messageId) {
            playingIdFlow.value = null
        }
    }
}
