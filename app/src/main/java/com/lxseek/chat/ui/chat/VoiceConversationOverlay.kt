package com.lxseek.chat.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.viewmodel.VoiceConversationController
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val SPECTRUM_BARS = 42
private const val SPECTRUM_TURN_MS = 14000

/**
 * Full-screen voice overlay for the multi-turn real-time voice conversation.
 * Immersive dark backdrop with an animated gradient, a rotating halo ring and a live
 * radial spectrum (voiceprint) whose bars respond to the mic amplitude; the centre icon
 * and status text follow the session state. Exit finishes the session gracefully (the
 * controller transcribes an in-flight recording instead of discarding it). Single-shot
 * ASR uses the compact [SingleAsrOverlay] instead of this full-screen view.
 */
@Composable
internal fun VoiceConversationOverlay(
    state: VoiceConversationController.State,
    partialTranscript: String,
    amplitude: Float,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = state != VoiceConversationController.State.IDLE
    if (!isActive) return

    val title = stringResource(R.string.voice_conversation_title)
    val stateText = when (state) {
        VoiceConversationController.State.LISTENING -> stringResource(R.string.voice_conversation_listening)
        VoiceConversationController.State.TRANSCRIBING -> stringResource(R.string.asr_remote_transcribing)
        VoiceConversationController.State.PROCESSING -> stringResource(R.string.voice_conversation_processing)
        VoiceConversationController.State.SPEAKING -> stringResource(R.string.voice_conversation_speaking)
        else -> ""
    }
    val stateIcon = when (state) {
        VoiceConversationController.State.LISTENING -> Icons.Default.Mic
        VoiceConversationController.State.TRANSCRIBING -> Icons.Default.GraphicEq
        VoiceConversationController.State.PROCESSING -> Icons.Default.Lightbulb
        VoiceConversationController.State.SPEAKING -> Icons.Default.VolumeUp
        else -> Icons.Default.Mic
    }
    val accent = when (state) {
        VoiceConversationController.State.LISTENING -> MaterialTheme.colorScheme.primary
        VoiceConversationController.State.TRANSCRIBING -> MaterialTheme.colorScheme.tertiary
        VoiceConversationController.State.PROCESSING -> MaterialTheme.colorScheme.tertiary
        VoiceConversationController.State.SPEAKING -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
        contentAlignment = Alignment.Center,
    ) {
        VoiceGradientBackground(
            colorStart = accent.copy(alpha = 0.22f),
            colorMid = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
            intensity = 0.35f,
            modifier = Modifier.fillMaxSize(),
        )

        IconButton(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 8.dp)
                .size(48.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.voice_conversation_exit),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            Spacer(modifier = Modifier.height(28.dp))

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(360.dp)) {
                HaloRing(
                    color = accent,
                    amplitude = if (state == VoiceConversationController.State.LISTENING) amplitude else 0.22f,
                    modifier = Modifier.fillMaxSize(),
                )
                VoiceSpectrumRing(
                    amplitude = if (state == VoiceConversationController.State.LISTENING) amplitude else 0.22f,
                    accent = accent,
                    modifier = Modifier.size(345.dp),
                )
                Surface(
                    shape = CircleShape,
                    color = accent.copy(alpha = 0.16f),
                    modifier = Modifier.size(144.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = stateIcon,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(60.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
            Text(
                text = stateText,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            if (state == VoiceConversationController.State.LISTENING && partialTranscript.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "\u201C$partialTranscript\u201D",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                )
            }
        }

        // Bottom mic-level strip while recording
        if (state == VoiceConversationController.State.LISTENING) {
            LevelMeter(amplitude = amplitude, accent = accent, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Slowly rotating conic halo behind the spectrum ring. Opacity and stroke width
 *  grow with [amplitude] so the halo "breathes" with the user's voice. */
@Composable
private fun HaloRing(
    color: Color,
    amplitude: Float,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "haloSpin")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(SPECTRUM_TURN_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "haloRotation",
    )
    val fade by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "haloFade",
    )
    val smoothAmp = remember { Animatable(0f) }
    LaunchedEffect(amplitude) {
        smoothAmp.animateTo(
            targetValue = amplitude.coerceIn(0f, 1f),
            animationSpec = tween(160),
        )
    }
    val amp = smoothAmp.value
    Canvas(modifier = modifier.rotate(rotation)) {
        val stroke = size.minDimension * (0.006f + 0.022f * amp)
        val alphaBoost = 0.16f + 0.64f * amp
        drawArc(
            brush = Brush.sweepGradient(
                0f to color.copy(alpha = 0f),
                0.25f to color.copy(alpha = 0.44f * fade * alphaBoost),
                0.5f to color.copy(alpha = 0f),
                0.75f to color.copy(alpha = 0.44f * fade * alphaBoost),
                1f to color.copy(alpha = 0f),
            ),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(stroke, stroke),
            size = Size(size.width - stroke * 2, size.height - stroke * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * Radial voiceprint: bars arranged around a circle whose length is driven by the live mic
 * [amplitude] plus a slow per-bar phase ripple, with a gentle whole-ring rotation.
 */
@Composable
internal fun VoiceSpectrumRing(
    amplitude: Float,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "spectrumTime")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(SPECTRUM_TURN_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spectrumT",
    )
    val smoothAmp = remember { Animatable(0f) }
    LaunchedEffect(amplitude) {
        smoothAmp.animateTo(
            targetValue = amplitude.coerceIn(0f, 1f),
            animationSpec = tween(160),
        )
    }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(size.width, size.height) / 2f
        val innerR = radius * 0.42f
        val outerR = radius * 0.96f
        val barWidth = ((2f * PI * innerR) / SPECTRUM_BARS).toFloat() * 0.42f
        val amp = smoothAmp.value
        // amplitude is already boosted into ~0.25-1.0 during speech by the controller; stretch
        // it across the full bar range so quiet speech shows and loud speech pushes bars out.
        val boosted = (amp * 1.9f).coerceIn(0f, 1f)
        val drift = t * 0.004f
        val step = 360f / SPECTRUM_BARS

        for (i in 0 until SPECTRUM_BARS) {
            val angleDeg = i * step
            val rad = angleDeg * (PI / 180f).toFloat()
            // Multi-frequency phase ripple plus per-bar noise: the ring "breathes" even at
            // rest and pulses hard with the speaker's volume — the classic live waveform feel.
            val wave = 0.5f + 0.3f * sin(rad * 3f + drift * 6f) +
                0.2f * sin(rad * 7f - drift * 3f)
            val noise = 0.7f + 0.3f * sin(i * 4.7f + drift * 9f)
            val len = (0.05f + boosted * 0.95f * wave.coerceIn(0f, 1f) * noise).coerceIn(0.04f, 1f)
            val r0 = innerR + (outerR - innerR) * 0.05f
            val r1 = innerR + (outerR - innerR) * len
            val x0 = cx + cos(rad) * r0
            val y0 = cy + sin(rad) * r0
            val x1 = cx + cos(rad) * r1
            val y1 = cy + sin(rad) * r1
            val alpha = 0.16f + 0.64f * len
            drawLine(
                color = accent.copy(alpha = alpha),
                start = Offset(x0, y0),
                end = Offset(x1, y1),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Thin horizontal mic-level meter pinned to the bottom edge while listening. */
@Composable
private fun LevelMeter(
    amplitude: Float,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val smoothAmp = remember { Animatable(0f) }
    LaunchedEffect(amplitude) {
        smoothAmp.animateTo(
            targetValue = amplitude.coerceIn(0f, 1f),
            animationSpec = tween(120),
        )
    }
    val displayAmp = smoothAmp.value

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 56.dp)
            .padding(bottom = 40.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            modifier = Modifier.fillMaxWidth().height(4.dp),
        ) {}
        Surface(
            shape = CircleShape,
            color = accent.copy(alpha = 0.9f),
            modifier = Modifier
                .fillMaxWidth(0.15f + 0.85f * displayAmp)
                .height(4.dp)
                .alpha(0.85f),
        ) {}
    }
}
