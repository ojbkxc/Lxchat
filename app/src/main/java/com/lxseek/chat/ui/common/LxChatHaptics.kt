package com.lxseek.chat.ui.common

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import com.lxseek.chat.service.AppForegroundTracker

@Stable
interface LxChatHaptics {
    fun selection()
    fun toggle(isOn: Boolean)
    fun longPress()
    fun confirm()
    fun reject()
    fun interrupt()
    fun destructiveConfirmed()
    fun startAnsweringTexture()
    fun stopAnsweringTexture()
}

object NoOpLxChatHaptics : LxChatHaptics {
    override fun selection() = Unit
    override fun toggle(isOn: Boolean) = Unit
    override fun longPress() = Unit
    override fun confirm() = Unit
    override fun reject() = Unit
    override fun interrupt() = Unit
    override fun destructiveConfirmed() = Unit
    override fun startAnsweringTexture() = Unit
    override fun stopAnsweringTexture() = Unit
}

val LocalLxChatHaptics = compositionLocalOf<LxChatHaptics> { NoOpLxChatHaptics }

@Composable
fun rememberLxChatHaptics(enabled: Boolean): LxChatHaptics {
    val view = LocalView.current
    val enabledState = rememberUpdatedState(enabled)
    val haptics = remember(view) {
        PlatformLxChatHaptics(view) { enabledState.value }
    }
    DisposableEffect(haptics) {
        val listener: (Boolean) -> Unit = { inForeground ->
            if (!inForeground) haptics.stopAnsweringTexture()
        }
        AppForegroundTracker.addListener(listener)
        onDispose {
            AppForegroundTracker.removeListener(listener)
            haptics.stopAnsweringTexture()
        }
    }
    return haptics
}

private class PlatformLxChatHaptics(
    private val view: View,
    private val enabled: () -> Boolean
) : LxChatHaptics {
    private val vibrator: Vibrator? = view.context.applicationContext.findVibrator()
    private var answeringTextureRequested = false
    private var answeringTextureActive = false
    private val resumeAnsweringTexture = Runnable {
        textureResumeScheduled = false
        if (answeringTextureRequested) startAnsweringTextureNow()
    }
    private var textureResumeScheduled = false

    override fun selection() =
        performDiscrete(selectionFeedbackForSdk(Build.VERSION.SDK_INT))

    override fun toggle(isOn: Boolean) =
        performDiscrete(toggleFeedbackForSdk(Build.VERSION.SDK_INT, isOn))

    override fun longPress() = performDiscrete(HapticFeedbackConstants.LONG_PRESS)

    override fun confirm() =
        performDiscrete(confirmFeedbackForSdk(Build.VERSION.SDK_INT))

    override fun reject() =
        performDiscrete(rejectFeedbackForSdk(Build.VERSION.SDK_INT))

    override fun interrupt() {
        performTerminalFeedback()
    }

    override fun destructiveConfirmed() {
        performTerminalFeedback()
    }

    private fun performTerminalFeedback() {
        stopAnsweringTexture()
        performDiscrete(HapticFeedbackConstants.CONTEXT_CLICK, resumeTexture = false)
    }

    override fun startAnsweringTexture() {
        answeringTextureRequested = true
        if (!textureResumeScheduled) startAnsweringTextureNow()
    }

    private fun startAnsweringTextureNow() {
        if (!answeringTextureRequested || !isAllowed() || answeringTextureActive) return
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        answeringTextureActive = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()) {
            // Keep the continuous answering texture, but at a low duty cycle. Starting each cycle
            // with silence prevents a resume from producing an immediate burst after a discrete
            // tap, while the unequal pair avoids a mechanical metronome feel.
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(80L, 3L, 24L, 2L, 91L),
                    intArrayOf(0, 4, 0, 3, 0),
                    0
                )
            )
        } else {
            // Timing-only devices render every pulse at full strength. Sparse 1-2ms pulses keep
            // the feature continuous without the near-solid full-power buzz of the old 43ms loop.
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(80L, 2L, 40L, 1L, 77L), 0)
        }
    }

    override fun stopAnsweringTexture() {
        answeringTextureRequested = false
        view.removeCallbacks(resumeAnsweringTexture)
        textureResumeScheduled = false
        if (!answeringTextureActive) return
        answeringTextureActive = false
        vibrator?.cancel()
    }

    private fun performDiscrete(type: Int, resumeTexture: Boolean = true) {
        if (!isAllowed()) return
        view.removeCallbacks(resumeAnsweringTexture)
        textureResumeScheduled = false
        if (answeringTextureActive) {
            answeringTextureActive = false
            vibrator?.cancel()
        }
        view.performHapticFeedback(type)
        if (resumeTexture && answeringTextureRequested) {
            textureResumeScheduled = true
            view.postDelayed(resumeAnsweringTexture, TEXTURE_RESUME_DELAY_MS)
        }
    }

    private fun isAllowed(): Boolean = enabled() && AppForegroundTracker.isInForeground

    private companion object {
        const val TEXTURE_RESUME_DELAY_MS = 160L
    }
}

internal fun selectionFeedbackForSdk(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        HapticFeedbackConstants.SEGMENT_TICK
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }

internal fun toggleFeedbackForSdk(sdkInt: Int, isOn: Boolean): Int =
    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        if (isOn) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }

internal fun confirmFeedbackForSdk(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.VIRTUAL_KEY
    }

internal fun rejectFeedbackForSdk(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.REJECT
    } else {
        HapticFeedbackConstants.LONG_PRESS
    }

private fun Context.findVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
