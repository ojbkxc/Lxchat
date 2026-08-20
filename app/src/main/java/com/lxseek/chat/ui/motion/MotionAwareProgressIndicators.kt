package com.lxseek.chat.ui.motion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.CircularProgressIndicator as MaterialCircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator as MaterialLinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Keeps indeterminate status visible without an endlessly rotating element in reduced-motion
 * mode. The stationary open ring intentionally conveys "in progress" without pretending to show
 * a percentage.
 */
@Composable
fun MotionAwareCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: androidx.compose.ui.unit.Dp = 4.dp,
) {
    if (LocalLxChatMotionPolicy.current.allowContinuousMotion) {
        MaterialCircularProgressIndicator(
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth,
        )
        return
    }

    Canvas(
        modifier = modifier
            .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
    ) {
        val widthPx = strokeWidth.toPx().coerceAtMost(size.minDimension)
        val inset = widthPx / 2f
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(
                width = (size.width - widthPx).coerceAtLeast(0f),
                height = (size.height - widthPx).coerceAtLeast(0f),
            ),
            style = Stroke(width = widthPx, cap = StrokeCap.Round),
        )
    }
}

/** Determinate progress is informative rather than decorative, so it remains a direct value. */
@Composable
fun MotionAwareCircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: androidx.compose.ui.unit.Dp = 4.dp,
) {
    MaterialCircularProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        strokeWidth = strokeWidth,
    )
}

/** A stationary segment replaces the looping indeterminate linear sweep. */
@Composable
fun MotionAwareLinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    if (LocalLxChatMotionPolicy.current.allowContinuousMotion) {
        MaterialLinearProgressIndicator(
            modifier = modifier,
            color = color,
            trackColor = trackColor,
        )
        return
    }

    Canvas(
        modifier = modifier
            .defaultMinSize(minWidth = 240.dp, minHeight = 4.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
    ) {
        val radius = size.height / 2f
        drawRoundRect(
            color = trackColor,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.25f, 0f),
            size = Size(size.width * 0.5f, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
    }
}
