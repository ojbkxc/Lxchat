package com.lxseek.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Symmetric dashboard card row: N metric cards of identical size, aligned at
 * the base line, each labelled + big value. Keeps the layout clean without a
 * stray progress bar (progress lives in the ring).
 *
 * Ported from HyX: package + theme adapted to LxChat's Material3 colorScheme
 * (HyxGreen → colorScheme.primary). API unchanged.
 */
@Composable
fun MetricCardRow(
    metrics: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    cardHeight: Dp = 76.dp
) {
    Row(modifier = modifier.fillMaxWidth()) {
        metrics.forEachIndexed { i, (label, value) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (i == 0) 0.dp else 6.dp, end = if (i == metrics.lastIndex) 0.dp else 6.dp)
            ) {
                MetricCard(label, value, height = cardHeight)
            }
        }
    }
}

@Composable
fun MetricCard(label: String, value: String, modifier: Modifier = Modifier, height: Dp = 76.dp) {
    Column(
        modifier = modifier
            .height(height)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

/**
 * Accent chip for status / direction labels.
 *
 * [active] uses [MaterialTheme.colorScheme.primary] as the accent (was HyxGreen
 * in HyX); inactive falls back to surfaceVariant.
 */
@Composable
fun StatusBadge(text: String, modifier: Modifier = Modifier, active: Boolean = false) {
    val primary = MaterialTheme.colorScheme.primary
    val bg = if (active) primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text,
        modifier = modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = fg
    )
}