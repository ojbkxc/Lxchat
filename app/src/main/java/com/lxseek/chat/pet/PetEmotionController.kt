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
 * ([HAPPY], [SAD], [ERROR], [THINKING]) automatically fall back to [IDLE].
 */
enum class PetEmotion { IDLE, THINKING, HAPPY, SAD, ERROR }

/**
 * Process-wide singleton that routes generation lifecycle signals to the pet overlay face.
 * [GenerationManager] calls [setEmotion] from background threads; [PetFloatingView] observes
 * [emotion] and re-draws. Non-IDLE states revert to [IDLE] after [FALLBACK_DELAY_MS] so the
 * bubble never stays stuck in a stale expression.
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

    private const val FALLBACK_DELAY_MS = 4_000L
}
