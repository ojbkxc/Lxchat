package com.lxseek.chat.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.lxseek.chat.R
import com.lxseek.chat.ui.common.LocalLxChatHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Swipe-to-reveal wrapper for a message row. A horizontal drag displaces the
 * foreground content to expose a delete action (left swipe, red) or a reply
 * action (right swipe, themed primary). Releasing past 90% of the reveal
 * width — a deliberate full drag, not a stray flick — invokes the delete
 * confirmation flow or the reply callback; anything less springs back.
 *
 * The gesture is limited to the horizontal axis so the enclosing LazyColumn
 * keeps full authority over vertical scrolling and inner tap/long-press
 * recognizers continue to receive events that are not horizontal drags.
 */
@Composable
internal fun SwipeToRevealMessage(
    enabled: Boolean,
    onDelete: () -> Unit,
    onReply: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val revealWidthPx = with(density) { 72.dp.toPx() }
    // Activation requires a near-full deliberate drag (90% of the reveal width).
    // A half-way 36dp flick during list scrolling must never arm the action.
    val dragThresholdPx = revealWidthPx * 0.9f
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val haptics = LocalLxChatHaptics.current
    val deleteLabel = stringResource(R.string.delete)
    val replyLabel = stringResource(R.string.reply)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) {
                    Modifier.pointerInput(enabled) {
                        detectHorizontalDragGestures(
                            onDragCancel = {
                                scope.launch { offset.animateTo(0f, tween(180)) }
                            },
                            onDragEnd = {
                                val current = offset.value
                                scope.launch {
                                    if (current < -dragThresholdPx) {
                                        haptics.destructiveConfirmed()
                                        onDelete()
                                    } else if (current > dragThresholdPx) {
                                        haptics.selection()
                                        onReply()
                                    }
                                    offset.animateTo(0f, tween(180))
                                }
                            },
                            onHorizontalDrag = { change, delta ->
                                change.consume()
                                scope.launch {
                                    offset.snapTo(
                                        (offset.value + delta)
                                            .coerceIn(-revealWidthPx, revealWidthPx),
                                    )
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val currentOffset = offset.value
        // Background action layer: only painted while the row is displaced, so a
        // resting row renders exactly as before (no colored rectangle behind it).
        if (abs(currentOffset) > 1f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        if (currentOffset < 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
                contentAlignment = if (currentOffset < 0) {
                    Alignment.CenterEnd
                } else {
                    Alignment.CenterStart
                },
            ) {
                Icon(
                    imageVector = if (currentOffset < 0) {
                        Icons.Default.Delete
                    } else {
                        Icons.AutoMirrored.Filled.Reply
                    },
                    contentDescription = if (currentOffset < 0) deleteLabel else replyLabel,
                    tint = if (currentOffset < 0) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        // Foreground content, translated horizontally by the live drag offset.
        Box(modifier = Modifier.graphicsLayer { translationX = offset.value }) {
            content()
        }
    }
}
