package com.lxseek.chat.ui.chat.message

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import com.lxseek.chat.ui.motion.LocalLxChatMotionPolicy

internal const val MESSAGE_ENTER_DURATION_MS = 320
internal const val SEGMENT_ENTER_DURATION_MS = 420
internal const val SEGMENT_ENTER_INITIAL_SCALE = 0.90f
internal const val STATUS_CROSSFADE_DURATION_MS = 280
internal const val ACTIONS_ENTER_DURATION_MS = 320
internal const val ACTIONS_EXIT_DURATION_MS = 220
internal const val COMPOSER_ICON_CROSSFADE_DURATION_MS = 200
internal const val REGENERATION_EXIT_DURATION_MS = 180
internal const val REGENERATION_ABORT_RESTORE_DURATION_MS = 180

/**
 * A one-shot lifecycle appearance that owns only a draw layer.
 *
 * Content occupies its final measured size from the first frame. The animation therefore cannot
 * resize a LazyColumn item, invalidate Markdown layout, or compete with scroll positioning.
 */
@Composable
internal fun generationLifecycleAppearanceModifier(
    animationKey: String,
    animate: Boolean,
    durationMillis: Int,
    initialScale: Float = 1f,
    transformOrigin: TransformOrigin = TransformOrigin.Center,
): Modifier {
    val allowSpatialTransitions = LocalLxChatMotionPolicy.current.allowSpatialTransitions
    val resolvedInitialScale = if (allowSpatialTransitions) initialScale else 1f
    val play = remember(animationKey) { animate }
    val progress = remember(animationKey) {
        Animatable(if (play) 0f else 1f)
    }
    LaunchedEffect(animationKey) {
        if (play) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = durationMillis,
                    easing = LinearEasing,
                ),
            )
        }
    }
    return Modifier.graphicsLayer {
        val value = progress.value.coerceIn(0f, 1f)
        alpha = value
        val scaleProgress = LinearOutSlowInEasing.transform(value)
        val scale =
            resolvedInitialScale + (1f - resolvedInitialScale) * scaleProgress
        scaleX = scale
        scaleY = scale
        this.transformOrigin = transformOrigin
    }
}

internal fun assistantActionsVisible(
    isStreaming: Boolean,
    regenerateRequested: Boolean,
): Boolean = !isStreaming && !regenerateRequested

internal data class AssistantActionAvailability(
    val informationVisible: Boolean,
    val informationEnabled: Boolean,
    val terminalVisible: Boolean,
    val terminalEnabled: Boolean,
)

/**
 * Copy and message metadata are read-only snapshots, so they stay available during streaming.
 * Tree-mutating actions wait for the active generation to become fully idle.
 */
internal fun assistantActionAvailability(
    isStreaming: Boolean,
    isLoading: Boolean,
    regenerateRequested: Boolean = false,
): AssistantActionAvailability {
    val terminalVisible = assistantActionsVisible(isStreaming, regenerateRequested)
    val informationVisible = !isStreaming && !regenerateRequested
    return AssistantActionAvailability(
        informationVisible = informationVisible,
        informationEnabled = informationVisible,
        terminalVisible = terminalVisible,
        terminalEnabled = terminalVisible && !isLoading,
    )
}
