package com.lxseek.chat.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add

import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.trigger.TriggerConfigStore
import com.lxseek.chat.trigger.TriggerRule
import com.lxseek.chat.trigger.TriggerType
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 条件触发设置页。
 *
 * 页面布局：
 * 1. 总开关卡片（启用/禁用整套触发系统）
 * 2. 规则预设卡片（一键添加常用规则：电量低于 20% 提醒、充电完成提醒等）
 * 3. 规则列表卡片（每条显示名称、类型、阈值、开关、删除按钮）
 * 4. 添加规则按钮（弹窗：名称、类型、阈值、条件、提示词、模型选择）
 *
 * 真正的监听逻辑见 [com.lxseek.chat.trigger.BatteryTriggerReceiver] /
 * [com.lxseek.chat.trigger.NetworkTriggerReceiver]，执行逻辑见
 * [com.lxseek.chat.trigger.TriggerExecutorService]。
 */
@Composable
fun TriggerSettingsPage(
    @Suppress("UNUSED_PARAMETER") viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { TriggerConfigStore(context.applicationContext) }

    val config by store.config.collectAsState(initial = com.lxseek.chat.trigger.TriggerConfig())

    var enabled by remember(config) { mutableStateOf(config.enabled) }
    var showAddDialog by remember { mutableStateOf(false) }

    // 可用模型列表（provider -> models），扁平化为 (provider, model) 对。
    val availableModels by viewModel.settings.availableModels.collectAsState()

    fun saveMasterToggle() {
        scope.launch {
            store.update { it.copy(enabled = enabled) }
        }
    }

    fun addRule(rule: TriggerRule) {
        scope.launch {
            store.addRule(rule)
            Toast.makeText(
                context,
                context.getString(R.string.trigger_rule_added),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun removeRule(id: String) {
        scope.launch { store.removeRule(id) }
    }

    fun toggleRule(id: String, on: Boolean) {
        scope.launch {
            store.upsertRule(
                config.rules.first { it.id == id }.copy(enabled = on)
            )
        }
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_trigger),
        onBack = onBack,
        scrollState = rememberScrollState(),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // ── 1. 总开关卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.trigger_enabled),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.trigger_enabled_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        saveMasterToggle()
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 2. 规则预设卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.trigger_presets),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.trigger_presets_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                PresetRow(stringResource(R.string.trigger_preset_battery_low)) {
                    addRule(
                        TriggerRule(
                            id = UUID.randomUUID().toString(),
                            name = context.getString(R.string.trigger_preset_battery_low),
                            type = TriggerType.BATTERY_LOW,
                            threshold = 20,
                            condition = "below",
                            prompt = context.getString(R.string.trigger_preset_battery_low_prompt),
                        )
                    )
                }
                PresetRow(stringResource(R.string.trigger_preset_battery_high)) {
                    addRule(
                        TriggerRule(
                            id = UUID.randomUUID().toString(),
                            name = context.getString(R.string.trigger_preset_battery_high),
                            type = TriggerType.BATTERY_HIGH,
                            threshold = 80,
                            condition = "above",
                            prompt = context.getString(R.string.trigger_preset_battery_high_prompt),
                        )
                    )
                }
                PresetRow(stringResource(R.string.trigger_preset_charging_start)) {
                    addRule(
                        TriggerRule(
                            id = UUID.randomUUID().toString(),
                            name = context.getString(R.string.trigger_preset_charging_start),
                            type = TriggerType.CHARGING_START,
                            prompt = context.getString(R.string.trigger_preset_charging_start_prompt),
                        )
                    )
                }
                PresetRow(stringResource(R.string.trigger_preset_charging_stop)) {
                    addRule(
                        TriggerRule(
                            id = UUID.randomUUID().toString(),
                            name = context.getString(R.string.trigger_preset_charging_stop),
                            type = TriggerType.CHARGING_STOP,
                            prompt = context.getString(R.string.trigger_preset_charging_stop_prompt),
                        )
                    )
                }
                PresetRow(stringResource(R.string.trigger_preset_network_change)) {
                    addRule(
                        TriggerRule(
                            id = UUID.randomUUID().toString(),
                            name = context.getString(R.string.trigger_preset_network_change),
                            type = TriggerType.NETWORK_CHANGE,
                            prompt = context.getString(R.string.trigger_preset_network_change_prompt),
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 3. 规则列表卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.trigger_rules),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.trigger_rules_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                if (config.rules.isEmpty()) {
                    Text(
                        text = stringResource(R.string.trigger_rules_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    config.rules.forEachIndexed { idx, rule ->
                        if (idx > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        RuleRow(
                            rule = rule,
                            onToggle = { toggleRule(rule.id, it) },
                            onDelete = { removeRule(rule.id) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── 4. 添加规则按钮 ──
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.trigger_add_rule))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showAddDialog) {
        AddRuleDialog(
            availableModels = availableModels,
            onDismiss = { showAddDialog = false },
            onConfirm = { rule ->
                addRule(rule)
                showAddDialog = false
            },
        )
    }
}

/** 一行预设：左侧加号图标 + 标签，点击即添加。 */
@Composable
private fun PresetRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 规则列表中的一行：名称 + 类型/阈值摘要 + 开关 + 删除。 */
@Composable
private fun RuleRow(
    rule: TriggerRule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = rule.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = ruleSummary(rule),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.trigger_delete_rule),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 把规则转成一行人类可读摘要。 */
private fun ruleSummary(rule: TriggerRule): String {
    val typePart = when (rule.type) {
        TriggerType.BATTERY_LOW -> "电量低于 ${rule.threshold}%"
        TriggerType.BATTERY_HIGH -> "电量高于 ${rule.threshold}%"
        TriggerType.NETWORK_CHANGE -> "网络变化"
        TriggerType.CHARGING_START -> "开始充电"
        TriggerType.CHARGING_STOP -> "停止充电"
    }
    val cooldownSec = rule.cooldownMs / 1000
    return "$typePart · 冷却 ${cooldownSec}s"
}

/** 添加规则弹窗：名称、类型、阈值、条件、提示词、模型选择。 */
@Composable
private fun AddRuleDialog(
    availableModels: Map<String, List<String>>,
    onDismiss: () -> Unit,
    onConfirm: (TriggerRule) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TriggerType.BATTERY_LOW) }
    var threshold by remember { mutableStateOf("20") }
    var condition by remember { mutableStateOf("below") }
    var prompt by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf<String?>(null) }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    val flatModels = remember(availableModels) {
        availableModels.flatMap { (provider, models) -> models.map { m -> provider to m } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trigger_add_rule)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.trigger_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                // 类型下拉
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = typeLabel(type),
                        onValueChange = { /* 只读 */ },
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.trigger_field_type)) },
                        trailingIcon = {
                            IconButton(onClick = { typeMenuExpanded = true }) {
                                Icon(Icons.Default.ExpandMore, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false },
                    ) {
                        TriggerType.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(typeLabel(t)) },
                                onClick = {
                                    type = t
                                    // 智能填充默认 condition/threshold
                                    when (t) {
                                        TriggerType.BATTERY_LOW -> {
                                            condition = "below"
                                            if (threshold.isBlank()) threshold = "20"
                                        }
                                        TriggerType.BATTERY_HIGH -> {
                                            condition = "above"
                                            if (threshold.isBlank()) threshold = "80"
                                        }
                                        else -> Unit
                                    }
                                    typeMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // 阈值 + 条件（仅 BATTERY_LOW/HIGH 需要）
                if (type == TriggerType.BATTERY_LOW || type == TriggerType.BATTERY_HIGH) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = threshold,
                            onValueChange = { threshold = it.filter { c -> c.isDigit() }.take(3) },
                            label = { Text(stringResource(R.string.trigger_field_threshold)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = condition,
                            onValueChange = { condition = it },
                            label = { Text(stringResource(R.string.trigger_field_condition)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.trigger_field_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Spacer(Modifier.height(8.dp))

                // 模型下拉
                Box(modifier = Modifier.fillMaxWidth()) {
                    val modelDisplay = if (modelId.isNullOrBlank()) {
                        stringResource(R.string.im_channel_settings_follow_default)
                    } else {
                        val mId: String = modelId ?: ""
                        val idx = mId.indexOf(':')
                        if (idx > 0) "${mId.substring(0, idx)} / ${mId.substring(idx + 1)}" else mId
                    }
                    OutlinedTextField(
                        value = modelDisplay,
                        onValueChange = { /* 只读 */ },
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.trigger_field_model)) },
                        trailingIcon = {
                            IconButton(onClick = { modelMenuExpanded = true }) {
                                Icon(Icons.Default.ExpandMore, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.im_channel_settings_follow_default)) },
                            onClick = { modelId = null; modelMenuExpanded = false },
                        )
                        flatModels.forEach { (provider, model) ->
                            DropdownMenuItem(
                                text = { Text("$provider / $model") },
                                onClick = {
                                    modelId = "$provider:$model"
                                    modelMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) return@TextButton
                    onConfirm(
                        TriggerRule(
                            id = UUID.randomUUID().toString(),
                            name = name.trim(),
                            type = type,
                            threshold = threshold.toIntOrNull() ?: 0,
                            condition = condition.trim(),
                            prompt = prompt.trim(),
                            modelId = modelId?.takeIf { it.isNotBlank() },
                        )
                    )
                },
                enabled = name.isNotBlank() && prompt.isNotBlank(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

/** 触发类型的本地化标签。 */
@Composable
private fun typeLabel(type: TriggerType): String = when (type) {
    TriggerType.BATTERY_LOW -> stringResource(R.string.trigger_type_battery_low)
    TriggerType.BATTERY_HIGH -> stringResource(R.string.trigger_type_battery_high)
    TriggerType.NETWORK_CHANGE -> stringResource(R.string.trigger_type_network_change)
    TriggerType.CHARGING_START -> stringResource(R.string.trigger_type_charging_start)
    TriggerType.CHARGING_STOP -> stringResource(R.string.trigger_type_charging_stop)
}