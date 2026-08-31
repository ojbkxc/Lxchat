package com.lxseek.chat.ui.chat.bottombar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.ui.motion.LocalLxChatMotionPolicy
import com.lxseek.chat.viewmodel.QueuedSend

internal val QUEUED_MESSAGE_HEIGHT: Dp = 40.dp
internal val QUEUED_MESSAGE_RADIUS: Dp = 20.dp
internal val QUEUED_MESSAGE_SHAPE = RoundedCornerShape(QUEUED_MESSAGE_RADIUS)
// ChatBottomBar already has 4dp horizontal content padding. This additional 4dp makes the
// capsule concentric with the 28dp outer card: 28dp - 8dp inset = 20dp inner radius.
internal val QUEUED_MESSAGE_HORIZONTAL_INSET: Dp = 4.dp

private val STATUS_ROW_GAP = 4.dp
private const val STATUS_FADE_IN_DURATION_MS = 180
private const val STATUS_FADE_OUT_DURATION_MS = 140
private const val STATUS_SIZE_DURATION_MS = 220

private sealed interface ComposerStatusItem {
    val stableKey: String

    data class Queue(val value: QueuedSend) : ComposerStatusItem {
        override val stableKey: String = "queue:${value.id}"
    }
}

/**
 * Queued sends stay in chronological order above the composer. Loop state is deliberately not
 * part of this stack: [LoopStatusBackdrop] owns its separate back layer directly behind the
 * foreground chat bottom bar.
 */
@Composable
internal fun ComposerStatusColumn(
    queuedSends: List<QueuedSend>,
    onRemoveQueuedSend: (String) -> Unit,
    onClearQueuedSends: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val allowSpatialTransitions = LocalLxChatMotionPolicy.current.allowSpatialTransitions
    val statusItems = remember(queuedSends) {
        queuedSends
            .sortedBy(QueuedSend::createdAt)
            .map { ComposerStatusItem.Queue(it) }
    }

    AnimatedContent(
        targetState = statusItems,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = QUEUED_MESSAGE_HORIZONTAL_INSET),
        contentAlignment = Alignment.BottomCenter,
        contentKey = { items -> items.map(ComposerStatusItem::stableKey) },
        transitionSpec = {
            val fade = fadeIn(
                animationSpec = tween(
                    durationMillis = STATUS_FADE_IN_DURATION_MS,
                    easing = LinearEasing,
                )
            ) togetherWith fadeOut(
                animationSpec = tween(
                    durationMillis = STATUS_FADE_OUT_DURATION_MS,
                    easing = LinearEasing,
                )
            )
            fade.using(
                SizeTransform(
                    clip = false,
                    sizeAnimationSpec = { _, _ ->
                        if (allowSpatialTransitions) {
                            tween(
                                durationMillis = STATUS_SIZE_DURATION_MS,
                                easing = FastOutSlowInEasing,
                            )
                        } else {
                            snap()
                        }
                    },
                )
            )
        },
        label = "composerStatusStack",
    ) { displayedItems ->
        Column(modifier = Modifier.fillMaxWidth()) {
            displayedItems.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.height(STATUS_ROW_GAP))
                key(item.stableKey) {
                    when (item) {
                        is ComposerStatusItem.Queue -> QueuedMessageRow(
                            queued = item.value,
                            onRemove = { onRemoveQueuedSend(item.value.id) },
                        )
                    }
                }
            }
            // Batch clear affordance: more than one queued row makes removing them one by
            // one tedious; the header lets the user drop the whole pending batch at once.
            if (displayedItems.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onClearQueuedSends) {
                        Text(stringResource(R.string.queue_clear_all))
                    }
                }
            }
            if (displayedItems.isNotEmpty()) {
                Spacer(Modifier.height(STATUS_ROW_GAP))
            }
        }
    }
}
