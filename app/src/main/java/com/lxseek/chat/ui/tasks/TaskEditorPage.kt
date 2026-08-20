package com.lxseek.chat.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.automation.CronExpression
import com.lxseek.chat.automation.ScheduleType
import com.lxseek.chat.automation.TaskSchedule
import com.lxseek.chat.data.local.TaskEntity
import java.util.Calendar
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.ui.chat.ChatDeleteConfirmDialog
import com.lxseek.chat.ui.components.clearFocusOnTap
import com.lxseek.chat.ui.settings.AnimatedActionFab
import com.lxseek.chat.ui.settings.CollapsingSettingsLazyScaffold
import com.lxseek.chat.ui.settings.SettingsGroup
import com.lxseek.chat.ui.settings.SettingsIconContent
import com.lxseek.chat.ui.settings.SettingsItem
import com.lxseek.chat.viewmodel.ChatViewModel
import java.util.Locale

/**
 * The schedule editor mode is explicit UI state. In particular, CUSTOM must not be inferred from
 * whether the current text parses: a partially typed cron is expected to be invalid for a moment,
 * but that must not make the editor jump back to Daily.
 */
internal enum class ScheduleEditorMode {
    ONCE,
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
    CUSTOM,
}

internal fun initialScheduleEditorMode(cronExpr: String, runAt: Long?): ScheduleEditorMode {
    val parsed = TaskSchedule.parse(cronExpr, runAt)
    return parsed?.type?.toEditorMode()
        ?: if (cronExpr.isNotBlank()) ScheduleEditorMode.CUSTOM else ScheduleEditorMode.DAILY
}

internal fun isScheduleDraftValid(mode: ScheduleEditorMode, cronExpr: String): Boolean =
    if (mode == ScheduleEditorMode.CUSTOM) {
        cronExpr.isNotBlank() && CronExpression.isValid(cronExpr)
    } else {
        cronExpr.isBlank() || CronExpression.isValid(cronExpr)
    }

private fun ScheduleType.toEditorMode(): ScheduleEditorMode = when (this) {
    ScheduleType.ONCE -> ScheduleEditorMode.ONCE
    ScheduleType.DAILY -> ScheduleEditorMode.DAILY
    ScheduleType.WEEKLY -> ScheduleEditorMode.WEEKLY
    ScheduleType.MONTHLY -> ScheduleEditorMode.MONTHLY
    ScheduleType.YEARLY -> ScheduleEditorMode.YEARLY
}

private fun ScheduleEditorMode.toScheduleType(): ScheduleType? = when (this) {
    ScheduleEditorMode.ONCE -> ScheduleType.ONCE
    ScheduleEditorMode.DAILY -> ScheduleType.DAILY
    ScheduleEditorMode.WEEKLY -> ScheduleType.WEEKLY
    ScheduleEditorMode.MONTHLY -> ScheduleType.MONTHLY
    ScheduleEditorMode.YEARLY -> ScheduleType.YEARLY
    ScheduleEditorMode.CUSTOM -> null
}

/**
 * Preserve a literal time when leaving a custom expression. The rest of a custom cron may be too
 * rich for the structured editor, but its `minute hour` prefix is still useful and lossless.
 */
private fun scheduleSeedFromCron(cronExpr: String): TaskSchedule {
    val fields = cronExpr.trim().split(Regex("\\s+"))
    val minute = fields.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..59 }
    val hour = fields.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..23 }
    val default = TaskSchedule.default()
    return default.copy(
        hour = hour ?: default.hour,
        minute = minute ?: default.minute,
    )
}

// ── Detail ──────────────────────────────────────────────────────────────────

/**
 * Task editor, structured as three Settings-style groups — Details / Schedule / Execution log —
 * so a task reads top-to-bottom as "what it says, when it fires, what it did". Everything a run
 * depends on lives above the log; nothing is hidden behind a dialog except the model list.
 */
@Composable
internal fun TaskDetailPage(
    viewModel: ChatViewModel,
    task: TaskEntity,
    isNew: Boolean,
    onBack: () -> Unit,
    onOpenConversation: (taskId: String, conversationId: String) -> Unit,
) {
    val running by viewModel.runningTaskIds.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()

    var name by rememberSaveable(task.id) { mutableStateOf(task.name) }
    var prompt by rememberSaveable(task.id) { mutableStateOf(task.prompt) }
    var modelId by rememberSaveable(task.id) { mutableStateOf(task.modelId) }
    var cronExpr by rememberSaveable(task.id) { mutableStateOf(task.cronExpr) }
    var runAt by rememberSaveable(task.id) { mutableStateOf(task.runAt) }
    var scheduleEditorModeName by rememberSaveable(task.id) {
        mutableStateOf(initialScheduleEditorMode(task.cronExpr, task.runAt).name)
    }
    var enabled by rememberSaveable(task.id) { mutableStateOf(task.enabled) }
    var showModelPicker by remember { mutableStateOf(false) }
    var executionToDelete by remember { mutableStateOf<com.lxseek.chat.automation.TaskManager.ExecutionSummary?>(null) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    val isRunning = task.id in running
    val executions by viewModel.executionSummariesForTask(task.id).collectAsState(initial = emptyList())

    val scheduleEditorMode = ScheduleEditorMode.valueOf(scheduleEditorModeName)
    val cronValid = isScheduleDraftValid(scheduleEditorMode, cronExpr)
    val isComplete = name.isNotBlank() && prompt.isNotBlank() && cronValid

    fun current() = task.copy(
        name = name.trim(), prompt = prompt, modelId = modelId,
        cronExpr = cronExpr, runAt = runAt, enabled = enabled,
    )
    fun save() { if (isComplete) viewModel.saveTask(current()) }
    // Back still saves — an editor that silently discards work on the system back gesture is a
    // trap. The explicit Save button exists to make the commit point visible, not to gate it.
    fun leave() { save(); onBack() }

    BackHandler { leave() }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) focusManager.clearFocus()
    }

    CollapsingSettingsLazyScaffold(
        title = name.ifBlank { stringResource(if (isNew) R.string.task_new else R.string.task_edit) },
        onBack = { leave() },
        modifier = Modifier.clearFocusOnTap(),
        listState = listState,
        actions = {
            IconButton(enabled = isComplete, onClick = { leave() }) {
                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.task_save))
            }
        },
        floatingActionButton = {
            AnimatedActionFab(
                label = stringResource(if (isRunning) R.string.task_running else R.string.task_run_now),
                icon = Icons.Default.PlayArrow,
                onClick = {
                    viewModel.runTaskNow(current())
                },
                enabled = isComplete && !isRunning,
                loading = isRunning,
            )
        },
    ) {
        item {
            SettingsGroup(
                title = stringResource(R.string.task_section_details),
                items = listOf(
                    {
                        LabeledField(
                            label = stringResource(R.string.task_name),
                            icon = Icons.Default.Label,
                            value = name,
                            onValueChange = { name = it },
                            placeholder = stringResource(R.string.task_name_hint),
                            singleLine = true,
                        )
                    },
                    {
                        LabeledField(
                            label = stringResource(R.string.task_prompt),
                            icon = Icons.Default.Psychology,
                            value = prompt,
                            onValueChange = { prompt = it },
                            placeholder = stringResource(R.string.task_prompt_hint),
                            singleLine = false,
                        )
                    },
                    {
                        SettingsItem(
                            modifier = Modifier.clickable { showModelPicker = true },
                            headlineContent = { Text(stringResource(R.string.task_model)) },
                            supportingContent = {
                                Text(
                                    modelId?.let { modelAliases[it] ?: ModelId.parse(it).apiModelName }
                                        ?: stringResource(R.string.task_model_default)
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.primary)
                            },
                        )
                    },
                ),
            )
            Spacer(Modifier.height(24.dp))
        }

        item {
            ScheduleGroup(
                cronExpr = cronExpr,
                runAt = runAt,
                onScheduleChange = { newCron, newRunAt -> cronExpr = newCron; runAt = newRunAt },
                editorMode = scheduleEditorMode,
                onEditorModeChange = { scheduleEditorModeName = it.name },
                enabled = enabled,
                onEnabledChange = { enabled = it },
            )
            Spacer(Modifier.height(24.dp))
        }

        item {
            Text(
                stringResource(R.string.task_execution_log),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        if (executions.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    SettingsItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.task_no_executions),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(R.string.task_no_executions_desc),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        },
                        modifier = Modifier.heightIn(min = 64.dp),
                    )
                }
            }
        } else {
            itemsIndexed(executions, key = { _, e -> e.conversation.id }) { index, execution ->
                ExecutionRow(
                    execution = execution,
                    shape = stackedShape(index, executions.size),
                    onClick = { onOpenConversation(task.id, execution.conversation.id) },
                    menuEnabled = !isRunning,
                    onDelete = { executionToDelete = execution },
                )
                if (index < executions.lastIndex) Spacer(Modifier.height(STACK_GAP))
            }
        }
        item(key = "task_detail_fab_spacing") {
            Spacer(Modifier.height(80.dp))
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            enabledModels = enabledModels.toList(),
            modelAliases = modelAliases,
            selected = modelId,
            onSelect = { modelId = it; showModelPicker = false },
            onDismiss = { showModelPicker = false },
        )
    }
    executionToDelete?.let { execution ->
        ChatDeleteConfirmDialog(
            onConfirm = {
                viewModel.deleteConversation(execution.conversation.id)
                executionToDelete = null
            },
            onDismiss = { executionToDelete = null },
        )
    }
}

/** A group row whose value is typed in place. Icon-bearing fields use the same leading-icon
 *  content column as the Proxy settings page, so the label and field share its left inset. */
@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    isError: Boolean = false,
    supporting: String? = null,
    supportingIsError: Boolean = false,
) {
    val fieldContent: @Composable () -> Unit = {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 4,
            isError = isError,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        )
        if (supporting != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = if (supportingIsError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (icon != null) {
        SettingsIconContent(icon = icon) {
            fieldContent()
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
            fieldContent()
        }
    }
}

internal fun formatDateTime(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT
    ).format(java.util.Date(millis))

private fun formatTimeOfDay(hour: Int, minute: Int): String =
    String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

/** One-line recurrence summary for a task card ("Daily", "Weekly", a raw cron, …). */
@Composable
internal fun taskRepeatSummary(task: TaskEntity): String {
    val schedule = TaskSchedule.parse(task.cronExpr, task.runAt)
    return if (schedule != null) repeatLabel(schedule.type)
    else task.cronExpr.ifBlank { stringResource(R.string.task_schedule_not_set) }
}

@Composable
private fun repeatLabel(type: ScheduleType): String = stringResource(
    when (type) {
        ScheduleType.ONCE -> R.string.task_repeat_once
        ScheduleType.DAILY -> R.string.task_repeat_daily
        ScheduleType.WEEKLY -> R.string.task_repeat_weekly
        ScheduleType.MONTHLY -> R.string.task_repeat_monthly
        ScheduleType.YEARLY -> R.string.task_repeat_yearly
    }
)

@Composable
private fun repeatLabel(mode: ScheduleEditorMode): String =
    if (mode == ScheduleEditorMode.CUSTOM) {
        stringResource(R.string.task_schedule_custom)
    } else {
        repeatLabel(checkNotNull(mode.toScheduleType()))
    }

/** Short weekday names in the user's locale, indexed 0=Sunday..6=Saturday to match cron. */
@Composable
internal fun weekdayNames(): List<String> {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        val cal = Calendar.getInstance()
        val fmt = java.text.SimpleDateFormat("EEE", locale)
        (0..6).map { dow ->
            cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY + dow)
            fmt.format(cal.time)
        }
    }
}

/**
 * Schedule group: WHAT recurrence (Repeat), WHICH date within it (On), and WHAT time (At) — plus
 * whether the whole thing is armed (the switch). "Manual only" is not a repeat option; it is the
 * switch being off, so no two controls express the same state.
 *
 * The On row's editor depends on the repeat type, because "which date" means something different
 * for each: daily has no On row at all, weekly picks weekdays, monthly picks a day number, yearly
 * and once pick a calendar date. Once additionally stores an absolute epoch instead of a cron —
 * a 5-field cron has no year, so "once on March 3rd" would silently repeat every year.
 *
 * A cron this model cannot express (a legacy hourly preset, a hand-written step expression) is
 * left untouched and shown as a custom expression until the user picks a repeat type.
 */
@Composable
private fun ScheduleGroup(
    cronExpr: String,
    runAt: Long?,
    onScheduleChange: (cron: String, runAt: Long?) -> Unit,
    editorMode: ScheduleEditorMode,
    onEditorModeChange: (ScheduleEditorMode) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val parsedSchedule = remember(cronExpr, runAt) { TaskSchedule.parse(cronExpr, runAt) }
    val isCustomCron = editorMode == ScheduleEditorMode.CUSTOM
    val schedule = parsedSchedule ?: remember(cronExpr) { scheduleSeedFromCron(cronExpr) }

    var showRepeatMenu by remember { mutableStateOf(false) }
    var showWeekdayDialog by remember { mutableStateOf(false) }
    var showDayOfMonthDialog by remember { mutableStateOf(false) }
    var showMonthDayDialog by remember { mutableStateOf(false) }
    var showDateDialog by remember { mutableStateOf(false) }
    var showTimeDialog by remember { mutableStateOf(false) }

    fun apply(next: TaskSchedule) = onScheduleChange(next.toCron(), next.toRunAt())
    fun selectMode(nextMode: ScheduleEditorMode) {
        onEditorModeChange(nextMode)
        if (nextMode == ScheduleEditorMode.CUSTOM) {
            // ONCE has no cron to preserve. Seed Custom with the same time-of-day as a daily cron.
            val seedCron = cronExpr.ifBlank {
                schedule.copy(type = ScheduleType.DAILY, onceAtMillis = 0L).toCron()
            }
            onScheduleChange(seedCron, null)
        } else {
            apply(schedule.switchedTo(checkNotNull(nextMode.toScheduleType())))
        }
    }

    val armable = cronExpr.isNotBlank() || (runAt != null && runAt > 0L)
    val scheduleDraftValid = isScheduleDraftValid(editorMode, cronExpr)
    val oncePast = schedule.type == ScheduleType.ONCE &&
        (runAt ?: 0L) in 1 until System.currentTimeMillis()
    val canToggleSchedule = armable && scheduleDraftValid && (!oncePast || enabled)

    SettingsGroup(
        title = stringResource(R.string.task_schedule),
        items = buildList {
            // ── Repeat ──
            add {
                Box {
                    SettingsItem(
                        modifier = Modifier.clickable { showRepeatMenu = true },
                        headlineContent = { Text(stringResource(R.string.task_repeat)) },
                        supportingContent = {
                            Text(repeatLabel(editorMode))
                        },
                        leadingContent = {
                            Icon(Icons.Default.Repeat, null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                    DropdownMenu(
                        expanded = showRepeatMenu,
                        onDismissRequest = { showRepeatMenu = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        ScheduleEditorMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(repeatLabel(mode)) },
                                leadingIcon = {
                                    if (editorMode == mode) {
                                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = {
                                    showRepeatMenu = false
                                    selectMode(mode)
                                },
                            )
                        }
                    }
                }
            }

            // ── On (absent for DAILY, which has no date to choose) ──
            if (!isCustomCron && schedule.type != ScheduleType.DAILY) {
                add {
                    val names = weekdayNames()
                    val onValue = when (schedule.type) {
                        ScheduleType.WEEKLY ->
                            if (schedule.daysOfWeek.isEmpty()) stringResource(R.string.task_schedule_not_set)
                            else schedule.daysOfWeek.sorted().joinToString(", ") { names[it] }
                        ScheduleType.MONTHLY -> stringResource(R.string.task_day_ordinal, schedule.dayOfMonth)
                        ScheduleType.YEARLY, ScheduleType.ONCE -> schedule.formatOnDate()
                        ScheduleType.DAILY -> ""
                    }
                    SettingsItem(
                        modifier = Modifier.clickable {
                            when (schedule.type) {
                                ScheduleType.WEEKLY -> showWeekdayDialog = true
                                ScheduleType.MONTHLY -> showDayOfMonthDialog = true
                                ScheduleType.YEARLY -> showMonthDayDialog = true
                                ScheduleType.ONCE -> showDateDialog = true
                                ScheduleType.DAILY -> Unit
                            }
                        },
                        headlineContent = {
                            Text(
                                when (schedule.type) {
                                    ScheduleType.WEEKLY -> stringResource(R.string.task_days_of_week)
                                    ScheduleType.MONTHLY -> stringResource(R.string.task_day_of_month)
                                    else -> stringResource(R.string.task_on)
                                }
                            )
                        },
                        supportingContent = { Text(onValue) },
                        leadingContent = {
                            Icon(Icons.Default.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }

            // ── At ──
            if (!isCustomCron) {
                add {
                    SettingsItem(
                        modifier = Modifier.clickable { showTimeDialog = true },
                        headlineContent = { Text(stringResource(R.string.task_at)) },
                        supportingContent = { Text(formatTimeOfDay(schedule.hour, schedule.minute)) },
                        leadingContent = {
                            Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }

            // ── Custom cron passthrough ──
            if (isCustomCron) {
                add {
                    LabeledField(
                        label = stringResource(R.string.task_schedule_custom),
                        icon = Icons.Default.Code,
                        value = cronExpr,
                        onValueChange = { onScheduleChange(it, null) },
                        placeholder = stringResource(R.string.task_cron_hint),
                        singleLine = true,
                        isError = cronExpr.isBlank() || !CronExpression.isValid(cronExpr),
                        supporting = if (cronExpr.isBlank() || !CronExpression.isValid(cronExpr)) {
                            stringResource(R.string.task_cron_invalid)
                        } else {
                            null
                        },
                        supportingIsError = true,
                    )
                }
            }

            // ── Armed switch ──
            add {
                val nextRun = remember(cronExpr, runAt, enabled) {
                    when {
                        !enabled -> null
                        runAt != null && runAt > System.currentTimeMillis() -> runAt
                        cronExpr.isNotBlank() ->
                            CronExpression.parse(cronExpr)?.next(System.currentTimeMillis())
                        else -> null
                    }
                }
                SettingsItem(
                    modifier = Modifier.clickable(enabled = canToggleSchedule) {
                        onEnabledChange(!enabled)
                    },
                    headlineContent = {
                        Text(
                            stringResource(R.string.task_enabled),
                            color = if (armable) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    },
                    supportingContent = {
                        Text(
                            when {
                                !armable -> stringResource(R.string.task_enabled_needs_schedule)
                                oncePast -> stringResource(R.string.task_once_past)
                                nextRun != null -> stringResource(R.string.task_next_run, formatDateTime(nextRun))
                                else -> stringResource(R.string.task_enabled_desc)
                            },
                            color = if (oncePast) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = enabled && armable,
                            enabled = canToggleSchedule,
                            onCheckedChange = onEnabledChange,
                        )
                    },
                )
            }
        },
    )

    if (showWeekdayDialog) {
        WeekdayDialog(
            selected = schedule.daysOfWeek,
            onConfirm = { days -> apply(schedule.copy(daysOfWeek = days)); showWeekdayDialog = false },
            onDismiss = { showWeekdayDialog = false },
        )
    }
    if (showDayOfMonthDialog) {
        DayOfMonthDialog(
            selected = schedule.dayOfMonth,
            onSelect = { day -> apply(schedule.copy(dayOfMonth = day)); showDayOfMonthDialog = false },
            onDismiss = { showDayOfMonthDialog = false },
        )
    }
    if (showMonthDayDialog) {
        TaskMonthDayPickerDialog(
            schedule = schedule,
            onConfirm = {
                apply(it)
                showMonthDayDialog = false
            },
            onDismiss = { showMonthDayDialog = false },
        )
    }
    if (showDateDialog) {
        TaskDatePickerDialog(
            schedule = schedule,
            onConfirm = {
                apply(it)
                showDateDialog = false
            },
            onDismiss = { showDateDialog = false },
        )
    }
    if (showTimeDialog) {
        TaskTimePickerDialog(
            schedule = schedule,
            use24HourFormat = android.text.format.DateFormat.is24HourFormat(context),
            onConfirm = {
                apply(it)
                showTimeDialog = false
            },
            onDismiss = { showTimeDialog = false },
        )
    }
}
