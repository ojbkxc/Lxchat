package com.lxseek.chat.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lxseek.chat.ui.motion.LocalLxChatMotionPolicy
import kotlin.math.PI
import kotlin.math.sin

/**
 * LxChat animated brand mark: a soft glow halo behind a primary→tertiary gradient orb bearing the
 * sparkle. The halo and orb scale/glow through a slow continuous pulse; both collapse to a static
 * frame when reduced motion is active (see [LocalLxChatMotionPolicy]).
 *
 * Central artwork reused across splash, empty states, and onboarding so every surface shares one
 * brand identity rather than each drawing its own logo.
 */
@Composable
fun LxChatBrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    val motion = LocalLxChatMotionPolicy.current
    val pulse = rememberInfiniteTransition(label = "lxchatBrandMark")
    val progress by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "lxchatBrandMarkPulse",
    )
    // sin(0..PI) rises 0→1→0: one soft breath per loop with no step at the seam.
    val breath = if (motion.allowContinuousMotion) sin(progress * PI).toFloat() else 0.5f
    val scale = 1f + 0.045f * breath
    val glowAlpha = 0.10f + 0.12f * breath
    val orb = size * 0.76f
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    Box(
        modifier = modifier.size(size).graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        contentAlignment = Alignment.Center,
    ) {
        // Breathing glow halo.
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(primary.copy(alpha = glowAlpha)),
        )
        // Gradient orb with the sparkle glyph (onPrimary keeps contrast on the brand gradient).
        Box(
            modifier = Modifier
                .size(orb)
                .clip(RoundedCornerShape(orb * 0.34f))
                .background(Brush.linearGradient(listOf(primary, tertiary))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(orb * 0.44f),
            )
        }
    }
}

/**
 * Branded empty-state content. Centers the animated [LxChatBrandMark] above a title and description,
 * with an optional action slot. Replaces bare "nothing here yet" text on empty surfaces
 * (conversation list, chat canvas, search) so every blank surface still reads as LxChat.
 */
@Composable
fun LxChatEmptyState(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    markSize: Dp = 64.dp,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LxChatBrandMark(size = markSize)
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    action()
                }
            }
        }
    }
}

/**
 * [LxChatEmptyState] without the action slot (layout-only helper kept out of the public signature
 * so the common "title + description" case can call the full composable without an action).
 */
@Composable
fun LxChatSimpleEmptyState(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    markSize: Dp = 64.dp,
) {
    LxChatEmptyState(modifier = modifier, title = title, description = description, markSize = markSize)
}