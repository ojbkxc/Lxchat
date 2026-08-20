package com.lxseek.chat.ui.motion

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Semantic motion capabilities for LxChat.
 *
 * Reduced motion is deliberately not a global animation-duration override: opacity and color
 * feedback remain useful, while continuous movement, large spatial transitions, and
 * programmatic travel are the motion-sensitive categories that need an alternative.
 */
@Immutable
data class LxChatMotionPolicy(
    val reduceMotion: Boolean,
) {
    val allowContinuousMotion: Boolean
        get() = !reduceMotion

    val allowSpatialTransitions: Boolean
        get() = !reduceMotion

    val allowProgrammaticScrollMotion: Boolean
        get() = !reduceMotion

    companion object {
        val Default = LxChatMotionPolicy(reduceMotion = false)
    }
}

val LocalLxChatMotionPolicy = staticCompositionLocalOf { LxChatMotionPolicy.Default }

internal fun resolveLxChatMotionPolicy(
    appReduceMotion: Boolean,
    systemAnimationsDisabled: Boolean,
): LxChatMotionPolicy = LxChatMotionPolicy(
    reduceMotion = appReduceMotion || systemAnimationsDisabled,
)

@Composable
fun ProvideLxChatMotionPolicy(
    appReduceMotion: Boolean,
    content: @Composable () -> Unit,
) {
    val systemAnimationsDisabled = rememberSystemAnimationsDisabled()
    val policy = remember(appReduceMotion, systemAnimationsDisabled) {
        resolveLxChatMotionPolicy(
            appReduceMotion = appReduceMotion,
            systemAnimationsDisabled = systemAnimationsDisabled,
        )
    }
    CompositionLocalProvider(LocalLxChatMotionPolicy provides policy, content = content)
}

@Composable
private fun rememberSystemAnimationsDisabled(): Boolean {
    val context = LocalContext.current.applicationContext
    val resolver = context.contentResolver
    var disabled by remember(resolver) {
        mutableStateOf(readSystemAnimationsDisabled(resolver))
    }

    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                disabled = readSystemAnimationsDisabled(resolver)
            }
        }
        systemAnimationScaleKeys.forEach { key ->
            resolver.registerContentObserver(
                Settings.Global.getUriFor(key),
                false,
                observer,
            )
        }
        onDispose {
            resolver.unregisterContentObserver(observer)
        }
    }

    return disabled
}

private val systemAnimationScaleKeys = listOf(
    Settings.Global.ANIMATOR_DURATION_SCALE,
    Settings.Global.TRANSITION_ANIMATION_SCALE,
    Settings.Global.WINDOW_ANIMATION_SCALE,
)

private fun readSystemAnimationsDisabled(resolver: ContentResolver): Boolean =
    systemAnimationScaleKeys.any { key ->
        Settings.Global.getFloat(resolver, key, 1f) == 0f
    }
