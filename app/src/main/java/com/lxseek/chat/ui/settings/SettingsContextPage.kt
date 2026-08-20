package com.lxseek.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.model.ContextBudget
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContextPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val window by viewModel.settings.maxContextWindow.collectAsState()
    val visualize by viewModel.settings.visualizeContextRollout.collectAsState()
    val compact by viewModel.settings.contextCompactEnabled.collectAsState()
    val compactModel by viewModel.settings.contextCompactModel.collectAsState()
    val compactPrompt by viewModel.settings.contextCompactPrompt.collectAsState()
    val retainCount by viewModel.settings.contextCompactRetainCount.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val aliases by viewModel.settings.modelAliases.collectAsState()
    var modelDialog by remember { mutableStateOf(false) }
    var promptDialog by remember { mutableStateOf(false) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.context_title),
        onBack = onBack,
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.context_window_default),
                items = listOf(
                    {
                        val presets = ContextBudget.PRESETS
                        fun nearestIndex(value: Int): Int = presets.indices.minByOrNull {
                            kotlin.math.abs(presets[it] - value)
                        } ?: 3
                        var draftIndex by remember(window) {
                            mutableFloatStateOf(nearestIndex(window).toFloat())
                        }
                        val draft = presets[draftIndex.toInt().coerceIn(0, presets.lastIndex)]
                        SettingsIconContent(Icons.Default.Memory) {
                            Text(stringResource(R.string.context_window), fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(
                                    R.string.context_token_budget_value,
                                    ContextBudget.compactLabel(draft),
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                value = draftIndex,
                                onValueChange = { draftIndex = it },
                                valueRange = 0f..presets.lastIndex.toFloat(),
                                steps = presets.size - 2,
                                onValueChangeFinished = {
                                    viewModel.settings.setMaxContextWindow(draft)
                                },
                            )
                        }
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.context_visualize)) },
                            supportingContent = { Text(stringResource(R.string.context_visualize_desc)) },
                            leadingContent = { Icon(Icons.Default.Visibility, null) },
                            trailingContent = {
                                Switch(
                                    checked = visualize,
                                    onCheckedChange = viewModel.settings::setVisualizeContextRollout,
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setVisualizeContextRollout(!visualize)
                            },
                        )
                    },
                ),
            )
            SettingsGroup(
                title = stringResource(R.string.context_compact),
                items = buildList {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.context_compact_auto)) },
                            supportingContent = { Text(stringResource(R.string.context_compact_auto_desc)) },
                            leadingContent = { Icon(Icons.Default.Compress, null) },
                            trailingContent = {
                                Switch(
                                    checked = compact,
                                    onCheckedChange = viewModel.settings::setContextCompactEnabled,
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setContextCompactEnabled(!compact)
                            },
                        )
                    }
                    if (compact) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.context_compact_model)) },
                                supportingContent = {
                                    Text(
                                        compactModel?.let {
                                            aliases[it] ?: ModelId.parse(it).apiModelName
                                        } ?: stringResource(R.string.title_gen_current_model)
                                    )
                                },
                                leadingContent = { Icon(Icons.Default.Chat, null) },
                                modifier = Modifier.clickable { modelDialog = true },
                            )
                        }
                    }
                    add {
                        var draft by remember(retainCount) { mutableFloatStateOf(retainCount.toFloat()) }
                        SettingsIconContent(Icons.Default.Memory) {
                            Text(stringResource(R.string.context_compact_retain), fontWeight = FontWeight.Medium)
                            Text(draft.toInt().toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = draft,
                                onValueChange = { draft = it },
                                valueRange = 0f..20f,
                                steps = 19,
                                onValueChangeFinished = {
                                    viewModel.settings.setContextCompactRetainCount(draft.toInt())
                                },
                            )
                        }
                    }
                    add {
                        PromptSettingItem(
                            title = stringResource(R.string.context_compact_prompt),
                            description = stringResource(R.string.context_compact_prompt_desc),
                            prompt = compactPrompt,
                            onClick = { promptDialog = true },
                        )
                    }
                },
            )
        }
    }

    if (modelDialog) {
        AlertDialog(
            onDismissRequest = { modelDialog = false },
            title = { Text(stringResource(R.string.context_compact_select_model)) },
            text = {
                LazyColumn {
                    item {
                        CompactModelItem(
                            label = stringResource(R.string.title_gen_current_model),
                            selected = compactModel == null,
                            onClick = {
                                viewModel.settings.setContextCompactModel(null)
                                modelDialog = false
                            },
                        )
                    }
                    items(enabledModels.toList(), key = { it }) { model ->
                        CompactModelItem(
                            label = aliases[model] ?: ModelId.parse(model).apiModelName,
                            selected = compactModel == model,
                            onClick = {
                                viewModel.settings.setContextCompactModel(model)
                                modelDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { modelDialog = false }) {
                    Text(stringResource(R.string.provider_cancel))
                }
            },
        )
    }
    if (promptDialog) {
        PromptEditDialog(
            title = stringResource(R.string.context_compact_prompt),
            initialPrompt = compactPrompt,
            onDismiss = { promptDialog = false },
            onSave = viewModel.settings::setContextCompactPrompt,
        )
    }
}

@Composable
private fun CompactModelItem(label: String, selected: Boolean, onClick: () -> Unit) {
    SettingsItem(
        headlineContent = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
