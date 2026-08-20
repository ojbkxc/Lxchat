package com.lxseek.chat.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shimmer placeholder box for loading states. Renders a rounded rectangle
 * with a sweeping gradient highlight to indicate content is loading.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val xShimmer by transition.animateFloat(
        initialValue = -300f,
        targetValue = 300f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerX",
    )
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    val highlightColor = MaterialTheme.colorScheme.surface
    val brush = Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(xShimmer, 0f),
        end = Offset(xShimmer + 300f, 0f),
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush),
    )
}

/**
 * A skeleton placeholder for a chat message row: an avatar circle followed by
 * a few text lines of varying width. Used to fill the message list while the
 * real conversation history is still loading.
 */
@Composable
fun MessageSkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShimmerBox(modifier = Modifier.size(36.dp), cornerRadius = 18.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.4f).height(14.dp))
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.9f).height(14.dp))
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.7f).height(14.dp))
        }
    }
}