package com.lxseek.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.lxseek.chat.ui.motion.LocalLxChatMotionPolicy
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SettingsOverlayScrimAlpha = 0.45f
private const val SettingsOverlayEnterOffsetFraction = 0.25f
private const val SettingsOverlayEnterScale = 0.92f
private const val SettingsOverlayExitScale = 0.94f
private const val SettingsOverlaySpringVisibilityThreshold = 0.001f

internal data class SettingsOverlayTransform(
    val pageOffsetFraction: Float,
    val pageScale: Float,
)

internal fun settingsOverlayEnterTransform(
    allowSpatialTransitions: Boolean,
): SettingsOverlayTransform = if (allowSpatialTransitions) {
    SettingsOverlayTransform(
        pageOffsetFraction = SettingsOverlayEnterOffsetFraction,
        pageScale = SettingsOverlayEnterScale,
    )
} else {
    SettingsOverlayTransform(pageOffsetFraction = 0f, pageScale = 1f)
}

internal fun settingsOverlayExitTransform(
    allowSpatialTransitions: Boolean,
): SettingsOverlayTransform = if (allowSpatialTransitions) {
    SettingsOverlayTransform(
        pageOffsetFraction = 1f,
        pageScale = SettingsOverlayExitScale,
    )
} else {
    SettingsOverlayTransform(pageOffsetFraction = 0f, pageScale = 1f)
}

@Composable
internal fun SettingsOverlayHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onEnterFinished: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val allowSpatialTransitions = LocalLxChatMotionPolicy.current.allowSpatialTransitions
    val scrimAlpha = remember { Animatable(0f) }
    val pageOffsetFraction = remember { Animatable(0f) }
    val pageAlpha = remember { Animatable(1f) }
    val pageScale = remember { Animatable(1f) }
    var renderOverlay by remember { mutableStateOf(visible) }
    val latestOnEnterFinished by rememberUpdatedState(onEnterFinished)

    // Motion policy changes while the overlay is already visible must not replay its entrance.
    // The latest policy is still observed when `visible` changes and a new enter/exit begins.
    LaunchedEffect(visible) {
        if (visible) {
            val enterTransform = settingsOverlayEnterTransform(allowSpatialTransitions)
            renderOverlay = true
            scrimAlpha.snapTo(0f)
            pageOffsetFraction.snapTo(enterTransform.pageOffsetFraction)
            pageAlpha.snapTo(0f)
            pageScale.snapTo(enterTransform.pageScale)
            if (allowSpatialTransitions) {
                listOf(
                    launch {
                        scrimAlpha.animateTo(
                            SettingsOverlayScrimAlpha,
                            animationSpec = tween(300, delayMillis = 50),
                        )
                    },
                    launch {
                        pageOffsetFraction.animateTo(
                            0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessLow,
                                visibilityThreshold = SettingsOverlaySpringVisibilityThreshold,
                            ),
                        )
                    },
                    launch { pageAlpha.animateTo(1f, animationSpec = tween(300)) },
                    launch {
                        pageScale.animateTo(
                            1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessLow,
                                visibilityThreshold = SettingsOverlaySpringVisibilityThreshold,
                            ),
                        )
                    },
                ).joinAll()
            } else {
                pageOffsetFraction.snapTo(0f)
                pageScale.snapTo(1f)
                listOf(
                    launch {
                        scrimAlpha.animateTo(
                            SettingsOverlayScrimAlpha,
                            animationSpec = tween(300),
                        )
                    },
                    launch { pageAlpha.animateTo(1f, animationSpec = tween(300)) },
                ).joinAll()
            }
            latestOnEnterFinished()
        } else if (renderOverlay) {
            val exitTransform = settingsOverlayExitTransform(allowSpatialTransitions)
            if (allowSpatialTransitions) {
                listOf(
                    launch {
                        scrimAlpha.animateTo(
                            0f,
                            animationSpec = tween(400, easing = FastOutSlowInEasing),
                        )
                    },
                    launch {
                        pageOffsetFraction.animateTo(
                            exitTransform.pageOffsetFraction,
                            animationSpec = tween(400, easing = FastOutSlowInEasing),
                        )
                    },
                    launch {
                        pageAlpha.animateTo(
                            0f,
                            animationSpec = tween(400, easing = FastOutSlowInEasing),
                        )
                    },
                    launch {
                        pageScale.animateTo(
                            exitTransform.pageScale,
                            animationSpec = tween(400, easing = FastOutSlowInEasing),
                        )
                    },
                ).joinAll()
            } else {
                pageOffsetFraction.snapTo(exitTransform.pageOffsetFraction)
                pageScale.snapTo(exitTransform.pageScale)
                listOf(
                    launch {
                        scrimAlpha.animateTo(
                            0f,
                            animationSpec = tween(300),
                        )
                    },
                    launch { pageAlpha.animateTo(0f, animationSpec = tween(300)) },
                ).joinAll()
            }
            renderOverlay = false
        }
    }

    if (!renderOverlay) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val pageOffsetX = if (allowSpatialTransitions) {
            (widthPx * pageOffsetFraction.value).roundToInt()
        } else {
            0
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0f)
                .background(
                    Color.Black.copy(
                        alpha = scrimAlpha.value.coerceIn(0f, SettingsOverlayScrimAlpha),
                    ),
                )
                .pointerInput(onDismiss) {
                    detectTapGestures { onDismiss() }
                },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f)
                .offset { IntOffset(pageOffsetX, 0) }
                .alpha(pageAlpha.value.coerceIn(0f, 1f))
                .graphicsLayer {
                    val resolvedScale = if (allowSpatialTransitions) pageScale.value else 1f
                    scaleX = resolvedScale
                    scaleY = resolvedScale
                },
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                content()
            }

            if (!visible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .consumePointerInput(),
                )
            }
        }
    }
}

private fun Modifier.consumePointerInput(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            }
        }
    }
