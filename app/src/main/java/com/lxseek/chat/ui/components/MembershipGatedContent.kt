package com.lxseek.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R

/**
 * A reusable wrapper that gates [content] behind Premium membership.
 *
 * When [isPremium] is true, [content] is rendered as-is. When false, the content
 * is dimmed (alpha 0.4) to convey a disabled state, and a semi-transparent scrim
 * with a centered lock card is overlaid on top. The lock card shows a lock icon,
 * the [featureName], an upgrade hint, and an upgrade button invoking
 * [onUpgradeClick]. The overlay follows the app's iOS settings style (rounded
 * card, muted scrim, tonal elevation).
 *
 * Typical usage wraps the inner Column of a Card so the card shape is preserved
 * while its body is gated:
 * ```
 * Card(...) {
 *     MembershipGatedContent(isPremium, "Email", onUpgradeClick) {
 *         Column(Modifier.padding(16.dp)) { ... }
 *     }
 * }
 * ```
 *
 * @param isPremium whether the current user has an active Premium membership.
 * @param featureName display name of the gated feature, shown in the lock card.
 * @param onUpgradeClick invoked when the user taps the upgrade button.
 * @param modifier modifier applied to the outer container.
 * @param content the gated composable content.
 */
@Composable
fun MembershipGatedContent(
    isPremium: Boolean,
    featureName: String,
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        // Render the original content; dim it when locked to convey disabled state.
        Box(modifier = Modifier.alpha(if (isPremium) 1f else 0.4f)) {
            content()
        }
        if (!isPremium) {
            // Consume taps on the scrim so dimmed controls underneath are not reachable.
            val scrimInteractionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = scrimInteractionSource,
                        indication = null,
                    ) { /* swallow taps to block interaction with dimmed content */ },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .width(240.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = stringResource(R.string.membership_gated_locked),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = featureName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.membership_gated_upgrade_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onUpgradeClick,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.membership_gated_upgrade_button))
                        }
                    }
                }
            }
        }
    }
}