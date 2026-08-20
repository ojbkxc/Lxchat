package com.lxseek.chat.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@Composable
internal fun VoiceGradientBackground(
    modifier: Modifier = Modifier,
    colorStart: Color = MaterialTheme.colorScheme.primary,
    colorMid: Color = MaterialTheme.colorScheme.tertiary,
    intensity: Float = 0.15f,
) {
    val transition = rememberInfiniteTransition(label = "gradientAnim")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "gradientProgress",
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val gradientWidth = w * 2f
        val dx = w * progress * intensity
        val brush = Brush.linearGradient(
            colors = listOf(colorStart, colorMid, colorStart),
            start = Offset(dx, 0f),
            end = Offset(dx + gradientWidth, h),
        )
        drawRect(brush = brush)
    }
}
