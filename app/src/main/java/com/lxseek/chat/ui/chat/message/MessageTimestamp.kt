package com.lxseek.chat.ui.chat.message

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.lxseek.chat.ui.theme.ChatType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Lightweight HH:mm timestamp for message action rows. Today shows the time only;
 * older messages prefix the date (M/d) so history stays disambiguated.
 */
internal fun formatMessageTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "M/d HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}

@Composable
internal fun MessageTimestampText(
    timestamp: Long,
    modifier: Modifier = Modifier,
) {
    val text = formatMessageTime(timestamp)
    if (text.isEmpty()) return
    Text(
        text = text,
        style = ChatType.micro,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
