package com.lxseek.chat.pet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Pet emotional states driving the floating bubble's face. Transient emotions
 * ([HAPPY], [SAD], [ERROR], [THINKING], [WAITING]) automatically fall back to [IDLE].
 */
enum class PetEmotion { IDLE, THINKING, HAPPY, SAD, ERROR, WAITING }

/**
 * Process-wide singleton that routes generation lifecycle signals to the pet overlay.
 * [GenerationManager] calls [setEmotion] from background threads; [PetFloatingView] observes
 * [emotion] and switches its animation state. Non-IDLE states revert to [IDLE] after
 * [FALLBACK_DELAY_MS] so the pet never stays stuck in a stale animation.
 */
object PetEmotionController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _emotion = MutableStateFlow(PetEmotion.IDLE)
    val emotion: StateFlow<PetEmotion> = _emotion

    /** Master switch backed by the user setting; when off, transient states are ignored. */
    @Volatile
    var enabled: Boolean = true

    private var fallbackJob: Job? = null

    /** Sets [e]; schedules a return to [PetEmotion.IDLE] for transient states. */
    fun setEmotion(e: PetEmotion) {
        if (!enabled && e != PetEmotion.IDLE) {
            _emotion.value = PetEmotion.IDLE
            return
        }
        _emotion.value = e
        fallbackJob?.cancel()
        if (e != PetEmotion.IDLE) {
            fallbackJob = scope.launch {
                delay(FALLBACK_DELAY_MS)
                _emotion.value = PetEmotion.IDLE
            }
        }
    }

    /**
     * Called on every streaming text chunk. Re-arms the transient-emotion fallback timer so
     * a long generation keeps the pet's THINKING animation alive; without this the 4s timer
     * expired mid-stream and the pet snapped back to idle while text was still streaming.
     */
    fun keepAliveDuringStream() {
        if (!enabled) return
        if (_emotion.value == PetEmotion.IDLE) return
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            delay(FALLBACK_DELAY_MS)
            _emotion.value = PetEmotion.IDLE
        }
    }

    private const val FALLBACK_DELAY_MS = 4_000L
}
