package com.lxseek.chat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.util.DebugLog

// ── Bot settings panel (autoReplyModel / proactive / humanize) ─────────────

/**
 * 已绑定机器人的可展开设置面板。本地状态用 [remember] + [mutableStateOf] 初始化为 [bot]
 * 的当前值；点击"保存设置"时用修改后的值 copy 出新的 [ImGatewayConfig] 并回调 [onSave]。
 *
 * 模型选择器候选项 = "跟随默认"（空串）+ [availableModels] 扁平化为 `provider:model` 格式
 * （与 `settings.selectedModel` 一致）；显示格式 `provider / model` 更友好。
 */
@Composable
internal fun BotSettingsPanel(
    bot: ImGatewayConfig,
    availableModels: Map<String, List<String>>,
    onSave: (ImGatewayConfig) -> Unit,
) {
    // 本地编辑状态，初始化为 bot 当前值。
    var autoReplyModel by remember { mutableStateOf(bot.autoReplyModel) }
    var proactiveEnabled by remember { mutableStateOf(bot.proactiveEnabled) }
    var proactiveIdleMinutes by remember { mutableStateOf(bot.proactiveIdleMinutes.toString()) }
    var proactiveSilentStart by remember { mutableStateOf(bot.proactiveSilentStart) }
    var proactiveSilentEnd by remember { mutableStateOf(bot.proactiveSilentEnd) }
    var proactiveIgnoreGroups by remember { mutableStateOf(bot.proactiveIgnoreGroups) }
    var humanizeMessages by remember { mutableStateOf(bot.humanizeMessages) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    val strFollowDefault = stringResource(R.string.im_channel_settings_follow_default)
    val strAutoReplyModel = stringResource(R.string.im_channel_settings_auto_reply_model)
    val strAutoReplyModelHint = stringResource(R.string.im_channel_settings_auto_reply_model_hint)
    val strProactive = stringResource(R.string.im_channel_settings_proactive)
    val strProactiveEnable = stringResource(R.string.im_channel_settings_proactive_enable)
    val strProactiveIdle = stringResource(R.string.im_channel_settings_proactive_idle_minutes)
    val strProSilentStart = stringResource(R.string.im_channel_settings_proactive_silent_start)
    val strProSilentEnd = stringResource(R.string.im_channel_settings_proactive_silent_end)
    val strProIgnoreGroups = stringResource(R.string.im_channel_settings_proactive_ignore_groups)
    val strHumanize = stringResource(R.string.im_channel_settings_humanize)
    val strSave = stringResource(R.string.im_channel_settings_save)

    // 扁平化模型列表：List<Pair<providerName, modelName>>，保留 provider 信息用于显示与存储。
    val flatModels = remember(availableModels) {
        availableModels.flatMap { (provider, models) ->
            models.map { model -> provider to model }
        }
    }
    // 当前选中模型的显示文本：空串 → "跟随默认"；否则 `provider / model`。
    val currentModelDisplay = if (autoReplyModel.isBlank()) strFollowDefault
    else {
        val idx = autoReplyModel.indexOf(':')
        if (idx > 0) "${autoReplyModel.substring(0, idx)} / ${autoReplyModel.substring(idx + 1)}"
        else autoReplyModel
    }

    Column(modifier = Modifier.fillMaxWidth().padding(start = 30.dp, top = 4.dp)) {
        // ── 自动回复模型 ──
        Text(
            text = strAutoReplyModel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            OutlinedTextField(
                value = currentModelDisplay,
                onValueChange = { /* 只读，由下方 Dropdown 选择 */ },
                readOnly = true,
                label = { Text(strAutoReplyModel) },
                supportingText = { Text(strAutoReplyModelHint) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { modelMenuExpanded = true }) {
                        Icon(imageVector = Icons.Default.ExpandMore, contentDescription = null)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(strFollowDefault) },
                    onClick = { autoReplyModel = ""; modelMenuExpanded = false },
                )
                flatModels.forEach { (provider, model) ->
                    DropdownMenuItem(
                        text = { Text("$provider / $model") },
                        onClick = {
                            autoReplyModel = "$provider:$model"
                            modelMenuExpanded = false
                        },
                    )
                }
            }
        }

        // ── 主动消息 ──
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = strProactive,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = proactiveEnabled,
                onCheckedChange = { proactiveEnabled = it },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = strProactiveEnable)
        }
        // 启用主动消息后才显示子设置：空闲阈值 / 静默时段 / 群聊过滤。
        if (proactiveEnabled) {
            Column(modifier = Modifier.padding(start = 12.dp)) {
                OutlinedTextField(
                    value = proactiveIdleMinutes,
                    onValueChange = { newVal ->
                        // 只保留数字，避免非法输入。
                        proactiveIdleMinutes = newVal.filter { it.isDigit() }
                    },
                    label = { Text(strProactiveIdle) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = proactiveSilentStart,
                        onValueChange = { proactiveSilentStart = it },
                        label = { Text(strProSilentStart) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = proactiveSilentEnd,
                        onValueChange = { proactiveSilentEnd = it },
                        label = { Text(strProSilentEnd) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = proactiveIgnoreGroups,
                        onCheckedChange = { proactiveIgnoreGroups = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = strProIgnoreGroups)
                }
            }
        }

        // ── 其他：人性化消息 ──
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = humanizeMessages,
                onCheckedChange = { humanizeMessages = it },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = strHumanize)
        }

        // ── 保存按钮 ──
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = {
                val idleMinutes = proactiveIdleMinutes.trim().toIntOrNull()?.takeIf { it > 0 } ?: 120
                val updated = bot.copy(
                    autoReplyModel = autoReplyModel.trim(),
                    proactiveEnabled = proactiveEnabled,
                    proactiveIdleMinutes = idleMinutes,
                    proactiveSilentStart = proactiveSilentStart.trim(),
                    proactiveSilentEnd = proactiveSilentEnd.trim(),
                    proactiveIgnoreGroups = proactiveIgnoreGroups,
                    humanizeMessages = humanizeMessages,
                )
                DebugLog.d(
                    "ImGatewayUI",
                    "saveSettings: ${updated.effectiveChannelId} model=${updated.autoReplyModel} " +
                        "proactive=${updated.proactiveEnabled} idle=${updated.proactiveIdleMinutes} " +
                        "silent=${updated.proactiveSilentStart}-${updated.proactiveSilentEnd} " +
                        "ignoreGroups=${updated.proactiveIgnoreGroups} humanize=${updated.humanizeMessages}",
                )
                onSave(updated)
            }) {
                Text(strSave)
            }
        }
    }
}