package com.lxseek.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R

@Composable
internal fun ShareSelectionFab(
    hasSelection: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShareImage: () -> Unit,
    onShareMarkdown: () -> Unit,
    onSaveToGallery: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
            }
            IconButton(
                enabled = hasSelection,
                onClick = onCopy,
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy))
            }
            IconButton(
                enabled = hasSelection,
                onClick = onShareMarkdown,
            ) {
                Icon(Icons.Default.Description, contentDescription = stringResource(R.string.share_markdown))
            }
            IconButton(
                enabled = hasSelection,
                onClick = onShareImage,
            ) {
                Icon(Icons.Default.Image, contentDescription = stringResource(R.string.share_long_image))
            }
            IconButton(
                enabled = hasSelection,
                onClick = onSaveToGallery,
            ) {
                Icon(Icons.Default.SaveAlt, contentDescription = stringResource(R.string.share_save_to_gallery))
            }
            IconButton(
                enabled = hasSelection,
                onClick = onConfirm,
            ) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.conversation_share))
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}
