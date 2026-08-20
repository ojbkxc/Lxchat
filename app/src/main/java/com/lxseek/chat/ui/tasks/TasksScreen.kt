package com.lxseek.chat.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import com.lxseek.chat.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.automation.hasSchedule
import com.lxseek.chat.data.local.TaskEntity
import com.lxseek.chat.ui.settings.CollapsingSettingsLazyScaffold
import com.lxseek.chat.ui.settings.GuardedAnimatedContent
import com.lxseek.chat.ui.settings.SettingsGroup
import com.lxseek.chat.ui.settings.SettingsItem
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.UUID

/**
 * Tasks feature root: a saved prompt + model you can run on demand or on a schedule.
 *
 * List ↔ detail is an in-overlay switch driven by [GuardedAnimatedContent] — the SAME transition
 * Settings uses for its sub-pages, so entering the Tasks page and entering a task feel identical.
 * The open task is tracked by ID (not entity) so live Room updates — countdown, run status — flow
 * into the detail page without restarting the transition.
 */
@Composable
fun TasksScreen(
    viewModel: ChatViewModel,
    taskListState: LazyListState,
    initialTaskId: String? = null,
    onInitialTaskHandled: () -> Unit = {},
    onBack: () -> Unit,
    onOpenConversation: (taskId: String, conversationId: String) -> Unit,
) {
    val tasks by viewModel.tasks.collectAsState()
    // Seed the navigation target synchronously. In particular, returning from a history
    // conversation must compose the detail page on the overlay's first visible frame instead of
    // briefly composing the list and then animating list -> detail from a LaunchedEffect.
    var openTaskId by remember { mutableStateOf(initialTaskId) }
    var resolvingInitialTaskId by remember { mutableStateOf(initialTaskId) }
    var initialTaskSnapshot by remember { mutableStateOf<TaskEntity?>(null) }
    // A brand-new task only reaches Room once it has a name + prompt, so backing out of an
    // untouched draft leaves nothing behind. Until then it lives here.
    var draft by remember { mutableStateOf<TaskEntity?>(null) }

    LaunchedEffect(initialTaskId) {
        val id = initialTaskId ?: return@LaunchedEffect
        resolvingInitialTaskId = id
        val initialTask = viewModel.getTask(id)
        initialTaskSnapshot = initialTask
        if (initialTask != null) {
            draft = null
            openTaskId = initialTask.id
        } else if (openTaskId == id) {
            openTaskId = null
        }
        resolvingInitialTaskId = null
        onInitialTaskHandled()
    }

    LaunchedEffect(tasks, initialTaskSnapshot?.id) {
        val snapshotId = initialTaskSnapshot?.id ?: return@LaunchedEffect
        if (tasks.any { it.id == snapshotId }) {
            initialTaskSnapshot = null
        }
    }

    GuardedAnimatedContent(
        targetState = openTaskId,
        forward = openTaskId != null,
    ) { taskId ->
        if (taskId == null) {
            TasksListPage(
                viewModel = viewModel,
                tasks = tasks,
                listState = taskListState,
                onBack = onBack,
                onNewTask = {
                    val newTask = TaskEntity(
                        id = UUID.randomUUID().toString(),
                        name = "", prompt = "", cronExpr = "", nextRunAt = 0L
                    )
                    draft = newTask
                    openTaskId = newTask.id
                },
                onOpenTask = { draft = null; openTaskId = it.id },
            )
        } else {
            val task = tasks.firstOrNull { it.id == taskId }
                ?: initialTaskSnapshot?.takeIf { it.id == taskId }
                ?: draft?.takeIf { it.id == taskId }
            if (task == null) {
                if (resolvingInitialTaskId == taskId) {
                    // Hold the overlay background until the initial task is resolved. Rendering
                    // the list here would expose both navigation destinations during return.
                    Box(modifier = Modifier.fillMaxSize())
                } else {
                    // Deleted (or never persisted) while open — fall back to the list instead of
                    // rendering an empty editor.
                    LaunchedEffect(taskId) { openTaskId = null }
                }
            } else {
                TaskDetailPage(
                    viewModel = viewModel,
                    task = task,
                    isNew = draft?.id == taskId,
                    onBack = { openTaskId = null },
                    onOpenConversation = onOpenConversation,
                )
            }
        }
    }
}

// ── List ────────────────────────────────────────────────────────────────────

@Composable
private fun TasksListPage(
    viewModel: ChatViewModel,
    tasks: List<TaskEntity>,
    listState: LazyListState,
    onBack: () -> Unit,
    onNewTask: () -> Unit,
    onOpenTask: (TaskEntity) -> Unit,
) {
    val running by viewModel.runningTaskIds.collectAsState()
    var pendingDelete by remember { mutableStateOf<TaskEntity?>(null) }

    BackHandler { onBack() }

    CollapsingSettingsLazyScaffold(
        title = stringResource(R.string.tasks),
        onBack = onBack,
        listState = listState,
    ) {
        val totalRows = tasks.size + 1
        if (tasks.isEmpty()) {
            item(key = "tasks_empty") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = stackedShape(0, 2),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    SettingsItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.task_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(R.string.task_empty_desc),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.Repeat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        },
                        modifier = Modifier.heightIn(min = 64.dp),
                    )
                }
                Spacer(Modifier.height(STACK_GAP))
            }
        } else {
            itemsIndexed(tasks, key = { _, task -> task.id }) { index, task ->
                val executions by viewModel.executionSummariesForTask(task.id)
                    .collectAsState(initial = emptyList())
                TaskCard(
                    task = task,
                    isRunning = task.id in running,
                    lastRunAt = executions.firstOrNull()?.timestamp?.takeIf { it > 0L },
                    shape = stackedShape(index, totalRows),
                    onClick = { onOpenTask(task) },
                    onRun = { viewModel.runTaskNow(task) },
                    onToggleEnabled = { enabled -> viewModel.saveTask(task.copy(enabled = enabled)) },
                    onDelete = { pendingDelete = task },
                )
                Spacer(Modifier.height(STACK_GAP))
            }
        }
        item(key = "new_automation") {
            NewAutomationRow(
                shape = stackedShape(if (tasks.isEmpty()) 1 else tasks.size, if (tasks.isEmpty()) 2 else totalRows),
                onClick = onNewTask,
            )
        }
    }

    pendingDelete?.let { task ->
        val displayName = task.name.ifBlank { stringResource(R.string.task_name_hint) }
        // Identical shape to MessageDeleteDialog — the app's one destructive-confirm style.
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.task_delete), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.task_delete_confirm, displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTask(task.id)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun TaskCard(
    task: TaskEntity,
    isRunning: Boolean,
    lastRunAt: Long?,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    onRun: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var now by remember(task.id, task.nextRunAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(task.id, task.enabled, task.nextRunAt) {
        if (task.enabled && task.nextRunAt > 0L) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }
    // Same surface language as a SettingsGroup card: surface + 1dp tonal elevation, stacked corners.
    // Surface(onClick=) — NOT Modifier.clickable on the passed-in modifier, which sits outside the
    // Surface's own clip and lets the ripple bleed out to a rectangle.
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.name.ifBlank { stringResource(R.string.task_name_hint) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (task.prompt.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = task.prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(5.dp))
                // Armed → recurrence summary + live countdown. Not armed → "Manual only": the
                // switch is the single place that state is expressed, so the recurrence isn't
                // shown as if it were about to fire.
                val scheduleText = if (task.enabled && task.hasSchedule()) {
                    listOfNotNull(
                        taskRepeatSummary(task),
                        if (task.nextRunAt > 0L) {
                            stringResource(R.string.task_next_run, formatTaskCountdown(task.nextRunAt - now))
                        } else null,
                    ).joinToString(" · ")
                } else {
                    stringResource(R.string.task_schedule_manual)
                }
                Text(
                    text = scheduleText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (task.enabled) 1f else 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = when {
                        isRunning -> stringResource(R.string.task_running)
                        lastRunAt != null -> stringResource(R.string.task_last_run_at, formatDateTime(lastRunAt))
                        else -> stringResource(R.string.task_never_run)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isRunning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 12.dp).size(24.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Switch(
                    checked = task.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.padding(end = 2.dp),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_run_now)) },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                            onClick = { menuOpen = false; onRun() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewAutomationRow(
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.task_new_task),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * Corner treatment for a vertically stacked list of cards — identical to what [SettingsGroup]
 * applies to its items (12dp on the outer edges, 4dp where two cards meet, 2dp between them),
 * so task rows and execution rows read as the same component as every settings card.
 */
internal fun stackedShape(index: Int, count: Int): RoundedCornerShape = when {
    count <= 1 -> RoundedCornerShape(12.dp)
    index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
    index == count - 1 -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
    else -> RoundedCornerShape(4.dp)
}

internal val STACK_GAP = 2.dp

internal fun formatTaskCountdown(remainingMs: Long): String {
    val clampedMs = remainingMs.coerceAtLeast(0L)
    val totalSeconds = clampedMs / 1_000L + if (clampedMs % 1_000L == 0L) 0L else 1L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
