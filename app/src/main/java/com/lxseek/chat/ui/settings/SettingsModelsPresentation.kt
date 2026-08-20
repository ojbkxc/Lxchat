package com.lxseek.chat.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Section title matching SettingsGroup's label style.
 * [firstInPage] = true for the first section on the page (no extra top gap);
 * subsequent sections get a 24dp gap above to match SettingsGroup's bottom padding.
 */
@Composable
internal fun SectionLabel(text: String, firstInPage: Boolean) {
    val topPadding = if (firstInPage) 12.dp else 36.dp
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = topPadding, bottom = 12.dp)
    )
}

/**
 * A single Surface card matching SettingsGroup's style.
 * [addTopGap] adds a 2dp gap above when true (for items after the first in a group).
 */
@Composable
internal fun CardSurface(
    shape: Shape,
    addTopGap: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(if (addTopGap) Modifier.padding(top = 2.dp) else Modifier)
    ) {
        content()
    }
}
