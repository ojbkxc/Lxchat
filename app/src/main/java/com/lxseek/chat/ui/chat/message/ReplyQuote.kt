package com.lxseek.chat.ui.chat.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.model.MessageReplyRef
import com.lxseek.chat.ui.theme.ChatType

/** Stable sender label for a [MessageReplyRef] using existing shared resources. */
@Composable
internal fun replySenderLabel(senderName: String): String = stringResource(
    if (senderName == "user") R.string.share_label_user else R.string.share_label_ai,
)

/**
 * Quote block rendered inside a message bubble when the message carries a reply reference.
 * A vertical accent bar plus the sender label and a single-line snippet preview.
 */
@Composable
internal fun MessageReplyQuote(
    reply: MessageReplyRef,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = replySenderLabel(reply.senderName),
                style = ChatType.quoteLabel,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Text(
                text = reply.textSnippet.ifBlank { "…" },
                style = ChatType.quoteBody,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Dismissible quote block shown above the composer while a reply is armed.
 */
@Composable
internal fun ComposerReplyQuote(
    reply: MessageReplyRef,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 6.dp),
        ) {
            Text(
                text = replySenderLabel(reply.senderName),
                style = ChatType.quoteLabel,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Text(
                text = reply.textSnippet.ifBlank { "…" },
                style = ChatType.quoteBody,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.message_menu_reply),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}
