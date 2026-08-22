package com.lxseek.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lxseek.chat.LxChatApplication
import com.lxseek.chat.R
import com.lxseek.chat.automation.WorkflowConfigCodec
import com.lxseek.chat.data.local.WorkflowEntity
import com.lxseek.chat.data.local.WorkflowStepConfig
import com.lxseek.chat.data.local.WorkflowStepEntity
import com.lxseek.chat.data.local.WorkflowStepType
import com.lxseek.chat.viewmodel.ChatViewModel
import com.lxseek.chat.viewmodel.WorkflowViewModel
import java.util.UUID

private const val NEW_WORKFLOW = "__new__"

/**
 * Workflow management page. Lists saved workflows (run / edit / delete) and hosts the editor that
 * chains "reply" and "wait" steps. The editor is a simple ordered list with reorder, edit and
 * delete controls — no drag-and-drop, keeping the interaction predictable on mobile.
 */
@Composable
fun SettingsWorkflowPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as LxChatApplication).container
    val workflowViewModel: WorkflowViewModel =
        viewModel(factory = remember { container.workflowViewModelFactory() })
    val workflows by workflowViewModel.workflows.collectAsState()
    val runningIds by workflowViewModel.runningWorkflowIds.collectAsState()
    var editingTarget by rememberSaveable { mutableStateOf<String?>(null) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_workflow),
        onBack = onBack,
    ) {
        val target = editingTarget
        if (target == null) {
            WorkflowListContent(
                workflows = workflows,
                runningIds = runningIds,
                vm = workflowViewModel,
                onCreate = { editingTarget = NEW_WORKFLOW },
                onEdit = { editingTarget = it },
            )
        } else {
            WorkflowEditorContent(
                vm = workflowViewModel,
                target = target,
                onClose = { editingTarget = null },
            )
        }
    }
}

@Composable
private fun WorkflowListContent(
    workflows: List<WorkflowEntity>,
    runningIds: Set<String>,
    vm: WorkflowViewModel,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    OutlinedButton(
        onClick = onCreate,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.workflow_new))
    }
    Spacer(modifier = Modifier.height(12.dp))

    if (workflows.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.AccountTree,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.workflow_empty),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.workflow_empty_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return@Column
    }

    SettingsGroupColumn(modifier = Modifier.fillMaxWidth()) {
        workflows.forEach { workflow ->
            val steps by vm.observeWorkflowSteps(workflow.id).collectAsState(initial = emptyList())
            val isRunning = workflow.id in runningIds
            SettingsItem(
                headlineContent = { Text(workflow.name, fontWeight = FontWeight.Medium) },
                supportingContent = {
                    Text(
                        if (steps.isEmpty()) {
                            stringResource(R.string.workflow_no_steps)
                        } else {
                            stringResource(R.string.workflow_steps, steps.size)
                        }
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Default.AccountTree,
                        null,
                        tint = if (workflow.enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { vm.runNow(workflow) },
                            enabled = !isRunning && workflow.enabled,
                        ) {
                            Icon(
                                if (isRunning) Icons.Default.Schedule else Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.workflow_run),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(onClick = { onEdit(workflow.id) }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.workflow_edit_step),
                            )
                        }
                        IconButton(onClick = { deleteTarget = workflow.id }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.workflow_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                modifier = Modifier.clickable { onEdit(workflow.id) },
            )
        }
    }

    val deleting = deleteTarget
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.workflow_delete)) },
            text = { Text(stringResource(R.string.workflow_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteWorkflow(deleting)
                        deleteTarget = null
                    },
                ) {
                    Text(stringResource(R.string.workflow_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.workflow_cancel))
                }
            },
        )
    }
}

/** Mutable draft of one step while the editor is open. */
private data class DraftStep(
    val id: String,
    val type: String,
    val title: String,
    val prompt: String = "",
    val delayMs: Long = 60_000L,
)

@Composable
private fun WorkflowEditorContent(
    vm: WorkflowViewModel,
    target: String,
    onClose: () -> Unit,
) {
    val isNew = target == NEW_WORKFLOW
    var loaded by remember { mutableStateOf(isNew) }
    var name by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var createdAt by remember { mutableStateOf(0L) }
    var nameError by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf<List<DraftStep>>(emptyList()) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(target) {
        if (isNew) return@LaunchedEffect
        val workflow = vm.getWorkflow(target) ?: return@LaunchedEffect
        val existing = vm.getWorkflowSteps(target)
        name = workflow.name
        enabled = workflow.enabled
        createdAt = workflow.createdAt
        steps = existing.map { step ->
            val config = WorkflowConfigCodec.decode(step.type, step.configJson)
            when (config) {
                is WorkflowStepConfig.Task ->
                    DraftStep(step.id, step.type, step.title, prompt = config.prompt)
                is WorkflowStepConfig.Delay ->
                    DraftStep(step.id, step.type, step.title, delayMs = config.delayMs)
                null -> DraftStep(step.id, step.type, step.title)
            }
        }
        loaded = true
    }

    if (!loaded) return

    fun save() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            nameError = true
            return
        }
        val id = if (isNew) UUID.randomUUID().toString() else target
        val workflow = WorkflowEntity(
            id = id,
            name = trimmed,
            enabled = enabled,
            createdAt = if (isNew) System.currentTimeMillis() else createdAt,
        )
        val entitySteps = steps.mapIndexed { index, draft ->
            WorkflowStepEntity(
                id = if (isNew) UUID.randomUUID().toString() else draft.id,
                workflowId = id,
                position = index,
                type = draft.type,
                title = draft.title,
                configJson = WorkflowConfigCodec.encode(
                    when (draft.type) {
                        WorkflowStepType.TASK -> WorkflowStepConfig.Task(draft.prompt)
                        else -> WorkflowStepConfig.Delay(draft.delayMs)
                    }
                ),
            )
        }
        vm.saveWorkflow(workflow, entitySteps)
        onClose()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                nameError = false
            },
            label = { Text(stringResource(R.string.workflow_name)) },
            placeholder = { Text(stringResource(R.string.workflow_name_hint)) },
            singleLine = true,
            isError = nameError,
            supportingText = if (nameError) {
                { Text(stringResource(R.string.workflow_name_required)) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = enabled, onCheckedChange = { enabled = it })
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.workflow_enabled))
        }

        if (steps.isEmpty()) {
            Text(
                text = stringResource(R.string.workflow_no_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            steps.forEachIndexed { index, draft ->
                WorkflowStepRow(
                    draft = draft,
                    canMoveUp = index > 0,
                    canMoveDown = index < steps.lastIndex,
                    onMoveUp = {
                        steps = steps.toMutableList().also { list ->
                            val item = list.removeAt(index)
                            list.add(index - 1, item)
                        }
                    },
                    onMoveDown = {
                        steps = steps.toMutableList().also { list ->
                            val item = list.removeAt(index)
                            list.add(index + 1, item)
                        }
                    },
                    onEdit = { editingIndex = index },
                    onDelete = { steps = steps.filterIndexed { i, _ -> i != index } },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    steps = steps + DraftStep(
                        id = UUID.randomUUID().toString(),
                        type = WorkflowStepType.TASK,
                        title = WorkflowConfigCodec.defaultTitle(WorkflowStepType.TASK),
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.workflow_add_task_step))
            }
            OutlinedButton(
                onClick = {
                    steps = steps + DraftStep(
                        id = UUID.randomUUID().toString(),
                        type = WorkflowStepType.DELAY,
                        title = WorkflowConfigCodec.defaultTitle(WorkflowStepType.DELAY),
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.workflow_add_delay_step))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::save, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.workflow_save))
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.workflow_cancel))
            }
        }
    }

    val index = editingIndex
    if (index != null && index < steps.size) {
        StepEditDialog(
            draft = steps[index],
            onConfirm = { updated ->
                steps = steps.toMutableList().also { list -> list[index] = updated }
                editingIndex = null
            },
            onDismiss = { editingIndex = null },
        )
    }
}

@Composable
private fun WorkflowStepRow(
    draft: DraftStep,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (draft.type == WorkflowStepType.TASK) Icons.Default.Chat else Icons.Default.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = onEdit),
        ) {
            Text(draft.title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (draft.type == WorkflowStepType.TASK) {
                    draft.prompt.ifBlank { stringResource(R.string.workflow_no_steps) }
                } else {
                    stringResource(R.string.workflow_seconds_format, draft.delayMs / 1000L)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Default.ArrowUpward, contentDescription = null)
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Default.ArrowDownward, contentDescription = null)
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StepEditDialog(
    draft: DraftStep,
    onConfirm: (DraftStep) -> Unit,
    onDismiss: () -> Unit,
) {
    var promptText by remember(draft.id) { mutableStateOf(draft.prompt) }
    var delayText by remember(draft.id) {
        mutableStateOf((draft.delayMs / 1000L).toString())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workflow_edit_step)) },
        text = {
            when (draft.type) {
                WorkflowStepType.TASK -> OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    label = { Text(stringResource(R.string.workflow_step_prompt_label)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> OutlinedTextField(
                    value = delayText,
                    onValueChange = { delayText = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.workflow_step_delay_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        if (draft.type == WorkflowStepType.TASK) {
                            draft.copy(prompt = promptText)
                        } else {
                            draft.copy(delayMs = (delayText.toLongOrNull() ?: 0L).coerceAtLeast(0L) * 1000L)
                        }
                    )
                },
            ) {
                Text(stringResource(R.string.workflow_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.workflow_cancel))
            }
        },
    )
}
