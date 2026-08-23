package com.lxseek.chat.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Minimal ring progress. Value animates automatically; the track is a thin
 * outline ring, the sweep is a full-round primary arc with a round cap.
 *
 * Ported from HyX: theme adapted to LxChat's Material3 colorScheme.
 *   - HyxGreen (sweep) → colorScheme.primary
 *   - Slate300  (track) → colorScheme.outline
 * API unchanged.
 */
@Composable
fun RingProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 140.dp,
    trackThickness: Dp = 10.dp,
    progressThickness: Dp = 10.dp
) {
    val animated by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f))
    val sweepColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outline
    Canvas(modifier = modifier) {
        val strokeWidth = progressThickness.toPx()
        val gap = 3.dp.toPx()
        val radius = (size.toPx() - strokeWidth) / 2f - gap
        val startAngle = -90f
        val sweep = animated * 360f
        drawCircle(
            color = trackColor.copy(alpha = 0.35f),
            radius = radius + strokeWidth,
            style = Stroke(width = trackThickness.toPx())
        )
        drawArc(
            color = sweepColor,
            startAngle = startAngle,
            sweepAngle = sweep,
            useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}