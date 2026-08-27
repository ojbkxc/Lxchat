package com.lxseek.chat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxseek.chat.tool.AskUserController

/**
 * Agent 提问的结构化面板。
 *
 * 当 AI 需要澄清 / 决策时发出 ask_user 工具，UI 通过 [AskUserController.pendingQuestion]
 * 观察并渲染本面板：给出预设选项（单选或多选）或自由输入框，并附带一个「其他」自定义入口，
 * 用户点击确认后通过 [onConfirm] 回传答案，触发 [AskUserController.resolve] 唤醒挂起的工具调用。
 */
@Composable
fun AskUserQuestionPanel(
    pending: AskUserController.PendingQuestion,
    onConfirm: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    val hasChoices = pending.choices.isNotEmpty()
    // 已选真实选项（不含「其他」占位）。单选/多选都存为集合。
    var selected by remember(pending.id) { mutableStateOf(setOf<String>()) }
    // 「其他」是否展开（展示自定义输入框）。
    var showOther by remember(pending.id) { mutableStateOf(false) }
    // 「其他」输入的自由文本。
    var customText by remember(pending.id) { mutableStateOf("") }
    // 无预设选项时的自由回答（纯文本问题）。
    var freeText by remember(pending.id) { mutableStateOf("") }

    fun toggleOption(label: String) {
        if (pending.multiple) {
            selected = if (label in selected) selected - label else selected + label
        } else {
            selected = if (selected == setOf(label)) emptySet() else setOf(label)
        }
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onCancel,
        icon = {
            Icon(
                Icons.Default.Terminal,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Agent 提问", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(pending.question, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))

                if (hasChoices) {
                    pending.choices.forEach { option ->
                        val isSelected = option in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { toggleOption(option) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (pending.multiple) {
                                Checkbox(checked = isSelected, onCheckedChange = { toggleOption(option) })
                            } else {
                                Box(
                                    modifier = Modifier.size(20.dp).clip(RoundedCornerShape(10.dp))
                                        .clickable { toggleOption(option) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { toggleOption(option) },
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Text(
                                option,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // 「其他」自定义项：点击展开输入框。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showOther = !showOther }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (showOther) {
                                Checkbox(checked = true, onCheckedChange = { showOther = it }, modifier = Modifier.size(20.dp))
                            } else {
                                Checkbox(checked = false, onCheckedChange = { showOther = it }, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        Text("其他（自定义）", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (showOther) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = customText,
                            onValueChange = { customText = it },
                            placeholder = { Text("请输入自定义回答…", style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            shape = RoundedCornerShape(8.dp),
                        )
                    }
                } else {
                    // 纯文本问题：直接提供自由输入框。
                    OutlinedTextField(
                        value = freeText,
                        onValueChange = { freeText = it },
                        placeholder = { Text("请输入你的回答…", style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val answers = buildList {
                        addAll(selected)
                        if (showOther && customText.isNotBlank()) add(customText)
                        if (!hasChoices && freeText.isNotBlank()) add(freeText)
                        if (isEmpty()) add("")
                    }
                    onConfirm(answers)
                },
            ) { Text("确认") }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("取消") }
        },
    )
}