package com.lxseek.chat.ui.chat.bottombar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.command.SlashCommands
import com.lxseek.chat.ui.theme.ChatType

/**
 * 斜杠命令实时建议：当输入以 "/" 开头且尚未命中完整命令时，在输入框上方展示匹配的候选命令，
 * 点击后填入触发词。逻辑自 Compose 的输入状态里读取，仅做展示与回填，不负责命令执行。
 */
@Composable
internal fun SlashCommandSuggestions(
    text: String,
    onPick: (String) -> Unit,
) {
    val suggestions = if (
        text.startsWith("/") &&
        !text.contains("\n") &&
        SlashCommands.findExact(text) == null
    ) {
        SlashCommands.filterByPrefix(text)
    } else emptyList()
    if (suggestions.isEmpty()) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            suggestions.forEach { command ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(command.trigger) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        command.trigger,
                        style = ChatType.input,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        command.description,
                        style = ChatType.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}