package com.lxseek.chat.ui.chat.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.ui.theme.LxDesign

/**
 * ChatGPT 风格空状态建议问题：品牌空状态下方一排 2×2 建议问题网格。
 * 点击任一项时回调 [onSuggestionClick]，由调用方把文本填入输入框并聚焦。
 */
@Composable
internal fun ChatEmptySuggestions(
    modifier: Modifier = Modifier,
    onSuggestionClick: (String) -> Unit = {},
) {
    val suggestions = listOf(
        stringResource(R.string.chat_empty_suggest_1),
        stringResource(R.string.chat_empty_suggest_2),
        stringResource(R.string.chat_empty_suggest_3),
        stringResource(R.string.chat_empty_suggest_4),
    )
    Column(
        modifier = modifier
            .widthIn(max = 720.dp)
            .align(Alignment.CenterHorizontally),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        suggestions.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems.forEach { text ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(LxDesign.shapeM)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .clickable { onSuggestionClick(text) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}