package com.lxseek.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.viewmodel.ChatViewModel

/**
 * 智能路由设置页面（优化6）。
 *
 * 暴露优化2（SmartModelRouter）和优化4（SubAgentManager）的配置项：
 * - 复杂度路由开关
 * - 简单任务模型选择
 * - 复杂任务模型选择
 * - 子代理最大并发数（Slider 1-20）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoutingPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val complexityRoutingEnabled by viewModel.settings.complexityRoutingEnabled.collectAsState()
    val simpleTaskModel by viewModel.settings.simpleTaskModel.collectAsState()
    val complexTaskModel by viewModel.settings.complexTaskModel.collectAsState()
    val subagentMaxRunning by viewModel.settings.subagentMaxRunning.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()

    var showSimpleModelDialog by remember { mutableStateOf(false) }
    var showComplexModelDialog by remember { mutableStateOf(false) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_complexity_routing),
        onBack = onBack,
    ) {
        SettingsGroupColumn {
            // ── 智能模型路由组 ──────────────────────────────────
            SettingsGroup(
                title = stringResource(R.string.settings_complexity_routing),
                items = buildList {
                    // 复杂度路由开关
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.settings_complexity_routing_enabled)) },
                            supportingContent = { Text(stringResource(R.string.settings_complexity_routing_desc)) },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Bolt,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = complexityRoutingEnabled,
                                    onCheckedChange = { viewModel.settings.setComplexityRoutingEnabled(it) },
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setComplexityRoutingEnabled(!complexityRoutingEnabled)
                            },
                        )
                    }
                    // 简单任务模型
                    if (complexityRoutingEnabled) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.settings_simple_task_model)) },
                                supportingContent = {
                                    Text(routingModelDisplayName(simpleTaskModel, modelAliases))
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Check,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                modifier = Modifier.clickable { showSimpleModelDialog = true },
                            )
                        }
                        // 复杂任务模型
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.settings_complex_task_model)) },
                                supportingContent = {
                                    Text(routingModelDisplayName(complexTaskModel, modelAliases))
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.AccountTree,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                modifier = Modifier.clickable { showComplexModelDialog = true },
                            )
                        }
                    }
                },
            )
            // ── 子代理组 ──────────────────────────────────────
            SettingsGroup(
                title = stringResource(R.string.settings_subagent_max_running),
                items = listOf {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_subagent_max_running),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_routing_subagent_range),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "1",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                value = subagentMaxRunning.toFloat(),
                                onValueChange = { viewModel.settings.setSubagentMaxRunning(it.toInt()) },
                                valueRange = 1f..20f,
                                steps = 18,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
                            )
                            Text(
                                text = "20",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = subagentMaxRunning.toString(),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
            )
        }
    }

    // 简单任务模型选择对话框
    if (showSimpleModelDialog) {
        ModelSelectionDialog(
            title = stringResource(R.string.settings_simple_task_model),
            currentModel = simpleTaskModel,
            enabledModels = enabledModels.toList(),
            modelAliases = modelAliases,
            onDismiss = { showSimpleModelDialog = false },
            onSelect = { model ->
                viewModel.settings.setSimpleTaskModel(model)
                showSimpleModelDialog = false
            },
        )
    }

    // 复杂任务模型选择对话框
    if (showComplexModelDialog) {
        ModelSelectionDialog(
            title = stringResource(R.string.settings_complex_task_model),
            currentModel = complexTaskModel,
            enabledModels = enabledModels.toList(),
            modelAliases = modelAliases,
            onDismiss = { showComplexModelDialog = false },
            onSelect = { model ->
                viewModel.settings.setComplexTaskModel(model)
                showComplexModelDialog = false
            },
        )
    }
}

/** 把模型 ID 转成可读名称；null 显示"未设置（使用默认）"。 */
@Composable
private fun routingModelDisplayName(
    model: String?,
    modelAliases: Map<String, String>,
): String {
    if (model == null) return stringResource(R.string.settings_routing_model_not_set)
    val alias = modelAliases[model]
    return alias ?: com.lxseek.chat.model.ModelId.parse(model).apiModelName
}

/** 复用的模型选择对话框（参照 SettingsTitleGenPage 的模式）。 */
@Composable
private fun ModelSelectionDialog(
    title: String,
    currentModel: String?,
    enabledModels: List<String>,
    modelAliases: Map<String, String>,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    SettingsItem(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.settings_routing_model_not_set),
                                fontWeight = if (currentModel == null) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        leadingContent = {
                            RadioButton(selected = currentModel == null, onClick = { onSelect(null) })
                        },
                        modifier = Modifier.clickable { onSelect(null) },
                    )
                }
                items(enabledModels, key = { it }) { model ->
                    val alias = modelAliases[model]
                    val titleParsed = com.lxseek.chat.model.ModelId.parse(model)
                    val displayName = alias ?: titleParsed.apiModelName
                    SettingsItem(
                        headlineContent = {
                            Text(
                                text = displayName,
                                fontWeight = if (currentModel == model) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = titleParsed.providerName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        },
                        leadingContent = {
                            RadioButton(selected = currentModel == model, onClick = { onSelect(model) })
                        },
                        modifier = Modifier.clickable { onSelect(model) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.provider_cancel))
            }
        },
    )
}