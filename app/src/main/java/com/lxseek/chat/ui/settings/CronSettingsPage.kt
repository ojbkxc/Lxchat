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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.LxChatApplication
import com.lxseek.chat.R
import com.lxseek.chat.cron.CronExpression
import com.lxseek.chat.cron.CronScheduler
import com.lxseek.chat.cron.CronTask
import com.lxseek.chat.cron.CronTaskStore
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Cron 定时任务设置页。
 *
 * 列出所有 [CronTask]，支持：
 * - 启停每条任务（Switch，即时写回 store + 调度器自动跟进）
 * - 删除任务
 * - 编辑任务（弹窗复用新增弹窗，预填现有字段）
 * - 添加任务（弹窗输入名称、Cron 表达式、提示词、模型选择）
 * - 常用 Cron 表达式预设（每天 9 点、每小时、每 30 分钟等）
 *
 * 调度由 [CronScheduler] 监听 [CronTaskStore.tasks] Flow 自动完成，UI 只操作 store。
 */
@Composable
fun CronSettingsPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as LxChatApplication
    val store = remember(context) { app.container.cronTaskStore }
    val scheduler = remember(context) { app.container.cronScheduler }

    val tasks by store.tasks.collectAsState(initial = emptyList())
    val availableModels by viewModel.settings.availableModels.collectAsState()

    // 新增/编辑弹窗状态。editingTask != null 表示编辑模式，null 表示新增模式。
    var dialogOpen by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<CronTask?>(null) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_cron),
        onBack = onBack,
        scrollState = rememberScrollState(),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // ── 说明卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.cron_intro_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.cron_intro_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 添加按钮 ──
        Button(
            onClick = {
                editingTask = null
                dialogOpen = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.cron_add_task))
        }

        Spacer(Modifier.height(12.dp))

        // ── 任务列表 ──
        if (tasks.isEmpty()) {
            Card(colors = CardDefaults.cardColors()) {
                Text(
                    text = stringResource(R.string.cron_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            tasks.forEach { task ->
                CronTaskCard(
                    task = task,
                    onToggle = { enabled ->
                        scope.launch {
                            store.setEnabled(task.id, enabled)
                            if (!enabled) scheduler.cancel(task.id)
                        }
                    },
                    onDelete = {
                        scope.launch {
                            scheduler.cancel(task.id)
                            store.removeTask(task.id)
                        }
                    },
                    onEdit = {
                        editingTask = task
                        dialogOpen = true
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (dialogOpen) {
        CronTaskEditDialog(
            initial = editingTask,
            availableModels = availableModels,
            onDismiss = { dialogOpen = false; editingTask = null },
            onConfirm = { name, cronExpr, prompt, modelId ->
                scope.launch {
                    val existing = editingTask
                    if (existing == null) {
                        // 新增
                        val task = CronTask(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            cronExpression = cronExpr,
                            prompt = prompt,
                            modelId = modelId?.takeIf { it.isNotBlank() },
                        )
                        store.addTask(task)
                        // 立即调度（不等 Flow 发射，体验更即时）。
                        scheduler.schedule(task)
                        Toast.makeText(
                            context,
                            context.getString(R.string.cron_task_added),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        // 编辑：写回 store 并强制重排（Cron 表达式可能变了）。
                        val updated = existing.copy(
                            name = name,
                            cronExpression = cronExpr,
                            prompt = prompt,
                            modelId = modelId?.takeIf { it.isNotBlank() },
                        )
                        store.updateTask(updated)
                        scheduler.reschedule(updated)
                        Toast.makeText(
                            context,
                            context.getString(R.string.cron_task_updated),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    dialogOpen = false
                    editingTask = null
                }
            },
        )
    }
}

/** 单条 Cron 任务卡片：名称、表达式、提示词预览、上次执行时间、启停开关、编辑/删除按钮。 */
@Composable
private fun CronTaskCard(
    task: CronTask,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = if (task.enabled) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = task.name.ifBlank { task.id.take(8) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = task.cronExpression,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = task.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = task.prompt,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (task.lastRunAt > 0) {
                        "上次执行：${dateFormat.format(Date(task.lastRunAt))}"
                    } else {
                        stringResource(R.string.cron_never_run)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.cron_edit_task),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.cron_delete_task),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** 新增/编辑任务弹窗。含 Cron 表达式预设下拉、即时校验、模型选择。 */
@Composable
private fun CronTaskEditDialog(
    initial: CronTask?,
    availableModels: Map<String, List<String>>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, cronExpr: String, prompt: String, modelId: String?) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var cronExpr by remember(initial) { mutableStateOf(initial?.cronExpression ?: "0 9 * * *") }
    var prompt by remember(initial) { mutableStateOf(initial?.prompt ?: "") }
    var modelId by remember(initial) { mutableStateOf(initial?.modelId) }

    var presetExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    // 即时校验 Cron 表达式
    val cronValid = remember(cronExpr) { CronExpression.tryParse(cronExpr) != null }

    // 下次执行时间预览
    val nextRunPreview = remember(cronExpr, cronValid) {
        if (!cronValid) null
        else runCatching {
            CronExpression.parse(cronExpr).nextRunAfter(System.currentTimeMillis())
        }.getOrNull()
    }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    val presets = remember {
        listOf(
            "0 9 * * *" to "每天 9:00",
            "0 8,12,18 * * *" to "每天 8/12/18 点",
            "0 * * * *" to "每小时整点",
            "*/30 * * * *" to "每 30 分钟",
            "*/15 * * * *" to "每 15 分钟",
            "0 0 * * 1" to "每周一 0:00",
            "0 0 1 * *" to "每月 1 号 0:00",
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial == null) stringResource(R.string.cron_add_task)
                else stringResource(R.string.cron_edit_task)
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.cron_field_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                // Cron 表达式 + 预设下拉
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = cronExpr,
                        onValueChange = { cronExpr = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.cron_field_expression)) },
                        isError = !cronValid,
                        supportingText = {
                            Text(
                                if (!cronValid) stringResource(R.string.cron_expression_invalid)
                                else if (nextRunPreview != null) {
                                    stringResource(
                                        R.string.cron_next_run_preview,
                                        dateFormat.format(Date(nextRunPreview)),
                                    )
                                } else stringResource(R.string.cron_expression_ok)
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { presetExpanded = true }) {
                                Icon(Icons.Default.ExpandMore, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = presetExpanded,
                        onDismissRequest = { presetExpanded = false },
                    ) {
                        presets.forEach { (expr, label) ->
                            DropdownMenuItem(
                                text = { Text("$label  ($expr)") },
                                onClick = { cronExpr = expr; presetExpanded = false },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    minLines = 3,
                    label = { Text(stringResource(R.string.cron_field_prompt)) },
                    placeholder = { Text(stringResource(R.string.cron_field_prompt_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                // 模型选择
                val flatModels = remember(availableModels) {
                    availableModels.flatMap { (provider, models) ->
                        models.map { model -> provider to model }
                    }
                }
                val currentModelDisplay = if (modelId.isNullOrBlank()) {
                    stringResource(R.string.cron_model_follow_default)
                } else {
                    val mId: String = modelId ?: ""
                    val idx = mId.indexOf(':')
                    if (idx > 0) "${mId.substring(0, idx)} / ${mId.substring(idx + 1)}" else mId
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = currentModelDisplay,
                        onValueChange = { /* 只读 */ },
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.cron_field_model)) },
                        trailingIcon = {
                            IconButton(onClick = { modelExpanded = true }) {
                                Icon(Icons.Default.ExpandMore, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cron_model_follow_default)) },
                            onClick = { modelId = null; modelExpanded = false },
                        )
                        HorizontalDivider()
                        flatModels.forEach { (provider, model) ->
                            DropdownMenuItem(
                                text = { Text("$provider / $model") },
                                onClick = { modelId = "$provider:$model"; modelExpanded = false },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), cronExpr.trim(), prompt.trim(), modelId) },
                enabled = name.isNotBlank() && cronValid && prompt.isNotBlank(),
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}