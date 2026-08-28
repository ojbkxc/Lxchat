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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.command.SlashCommands
import com.lxseek.chat.ui.theme.ChatType

/**
 * 极简会话提及（`#`）条目：仅携带切换所需的 id 与展示标题，避免 UI 层耦合完整会话模型。
 */
data class ConversationMention(
    val id: String,
    val title: String,
)

/** 输入框里当前处于激活态的提及上下文。 */
private sealed interface ActiveMention {
    /** 整行以 `/` 开头的斜杠命令。 */
    data class Command(val query: String) : ActiveMention
    /** 句中 `@` 点名工具。 */
    data class Tool(val query: String, val startIndex: Int) : ActiveMention
    /** 句中 `#` 点名会话。 */
    data class Session(val query: String, val startIndex: Int) : ActiveMention
}

/**
 * 统一命令面板：根据输入框当前文本，智能弹出 `/命令`、`@工具` 或 `#会话` 候选。
 *
 * - `/`：整行命中或前缀过滤斜杠命令，选出后整体替换输入框文本（沿用原斜杠命令语义）。
 * - `@`：前缀过滤工具目录，选出后在 `@` 起始处写回 `@工具名 `，给文本模型一个明确指向。
 * - `#`：前缀过滤历史会话，选出后通过 [onSwitchConversation] 切换，不改动输入草稿。
 */
@Composable
internal fun ComposerSuggestions(
    text: String,
    onCompleteText: (String) -> Unit,
    onSwitchConversation: (String) -> Unit,
    toolMentions: List<ToolMentions.Mention> = ToolMentions.all,
    conversations: List<ConversationMention> = emptyList(),
) {
    val mention = detectMention(text) ?: return
    when (mention) {
        is ActiveMention.Command -> {
            val suggestions = if (
                SlashCommands.findExact(mention.query) == null
            ) {
                SlashCommands.filterByPrefix(mention.query)
            } else {
                emptyList()
            }
            if (suggestions.isEmpty()) return
            SuggestionSurface {
                suggestions.forEach { command ->
                    SuggestionRow(
                        onClick = { onCompleteText(command.trigger) },
                        accent = command.trigger,
                        main = command.description,
                    )
                }
            }
        }

        is ActiveMention.Tool -> {
            val suggestions = ToolMentions.filter(mention.query)
            if (suggestions.isEmpty()) return
            SuggestionSurface {
                ToolMentions.Group.values().forEach { group ->
                    val groupItems = suggestions.filter { it.group == group }
                    if (groupItems.isNotEmpty()) {
                        GroupHeader(group)
                        groupItems.forEach { tool ->
                            SuggestionRow(
                                onClick = {
                                    val kept = text.substring(0, mention.startIndex)
                                    onCompleteText("$kept@${tool.name} ")
                                },
                                accent = tool.label,
                                main = tool.description,
                            )
                        }
                    }
                }
            }
        }

        is ActiveMention.Session -> {
            val query = mention.query.trim()
            val suggestions = if (query.isEmpty()) {
                conversations.take(8)
            } else {
                conversations.filter {
                    it.title.contains(query, ignoreCase = true)
                }.take(8)
            }
            if (suggestions.isEmpty()) return
            SuggestionSurface {
                suggestions.forEach { session ->
                    SuggestionRow(
                        onClick = { onSwitchConversation(session.id) },
                        accent = null,
                        main = session.title,
                        trailing = "\u21B5",
                    )
                }
            }
        }
    }
}

/**
 * 从末尾向前解析当前激活的提及。规则：
 * - `/` 必须位于行首且不含换行（斜杠命令只能整行触发）。
 * - `@` / `#` 必须位于行首或紧跟在空白之后，且其后的“查询词”直到行尾都不能包含空白，
 *   这样能避开邮箱等普通文本里的 `@` 误判。
 */
private fun detectMention(text: String): ActiveMention? {
    if (text.isEmpty()) return null
    if (text.startsWith("/") && !text.contains("\n")) {
        return ActiveMention.Command(text)
    }
    var hitIndex = -1
    var hitChar: Char? = null
    for ((index, ch) in text.withIndex()) {
        if (ch == '@' || ch == '#') {
            val atStart = index == 0
            val afterWhitespace = index > 0 && text[index - 1].isWhitespace()
            if (atStart || afterWhitespace) {
                hitIndex = index
                hitChar = ch
            }
        }
    }
    if (hitIndex < 0 || hitChar == null) return null
    val query = text.substring(hitIndex + 1)
    if (query.any { it.isWhitespace() }) return null
    return when (hitChar) {
        '@' -> ActiveMention.Tool(query, hitIndex)
        '#' -> ActiveMention.Session(query, hitIndex)
        else -> null
    }
}

@Composable
private fun GroupHeader(group: ToolMentions.Group) {
    Text(
        text = when (group) {
            ToolMentions.Group.FILE -> "文件"
            ToolMentions.Group.WEB -> "联网"
            ToolMentions.Group.MEMORY -> "记忆"
            ToolMentions.Group.SEARCH -> "检索"
            ToolMentions.Group.SHELL -> "执行"
            ToolMentions.Group.TASK -> "任务"
            ToolMentions.Group.IMAGE -> "图像"
            ToolMentions.Group.AGENT -> "操控"
            ToolMentions.Group.SYSTEM -> "系统"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun SuggestionSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SuggestionRow(
    onClick: () -> Unit,
    accent: String?,
    main: String,
    trailing: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (accent != null) {
            Text(
                text = accent,
                style = ChatType.input,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = main,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}