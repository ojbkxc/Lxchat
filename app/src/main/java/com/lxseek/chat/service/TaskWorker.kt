package com.lxseek.chat.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lxseek.chat.LxChatApplication
import com.lxseek.chat.R
import com.lxseek.chat.automation.TaskManager
import com.lxseek.chat.util.DebugLog
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.CancellationException

/**
 * Runs a single scheduled task execution off the main thread, reliably and across process death.
 *
 * Delegates to the process-scoped [com.lxseek.chat.automation.TaskManager], which drives the
 * generation through the shared engine. The engine already raises [LxChatForegroundService] for
 * the duration of the LLM call, so this worker does not manage its own foreground state.
 */
class TaskWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val executionId = inputData.getString(KEY_EXECUTION_ID) ?: return Result.failure()
        val scheduledAt = inputData.getLong(KEY_SCHEDULED_AT, 0L)
        val container = (applicationContext as LxChatApplication).container
        return try {
            setForeground(
                AutomationForegroundInfo.create(
                    context = applicationContext,
                    text = applicationContext.getString(R.string.task_running_notification),
                    notificationId = AutomationForegroundInfo.taskNotificationId(taskId, id),
                    conversationId = executionId,
                )
            )
            when (val outcome = container.taskManager.executeById(taskId, executionId, scheduledAt)) {
                is TaskManager.ExecutionResult.Success -> {
                    container.taskManager.finishScheduledRun(taskId, scheduledAt)
                    Result.success()
                }
                is TaskManager.ExecutionResult.Skipped -> {
                    // A skipped occurrence still consumed the AlarmManager slot. If the schedule is
                    // intact (stale/disabled/incomplete), advance nextRunAt so the Room flow emits
                    // and the scheduler re-arms the next occurrence; otherwise (already running /
                    // task gone) there is nothing to advance and advancing would corrupt a live run.
                    if (outcome.advancesSchedule) {
                        container.taskManager.finishScheduledRun(taskId, scheduledAt)
                    }
                    Result.success()
                }
                is TaskManager.ExecutionResult.Deferred -> {
                    // Direct-only runtime admission persisted nothing, so retrying this exact
                    // deterministic occurrence cannot duplicate a model/tool side effect.
                    if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        Result.retry()
                    } else {
                        container.taskManager.finishScheduledRun(taskId, scheduledAt)
                        Result.failure(workDataOf(KEY_ERROR to outcome.reason))
                    }
                }
                is TaskManager.ExecutionResult.Failure -> {
                    if (outcome.retryable && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        Result.retry()
                    } else {
                        container.taskManager.finishScheduledRun(taskId, scheduledAt)
                        Result.failure(workDataOf(KEY_ERROR to outcome.reason))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("TaskWorker", "Task execution failed for $taskId", e)
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                runCatching { container.taskManager.finishScheduledRun(taskId, scheduledAt) }
                    .onFailure { finishError ->
                        DebugLog.e("TaskWorker", "Failed to finalize schedule for $taskId", finishError)
                    }
                Result.failure(workDataOf(KEY_ERROR to (e.localizedMessage ?: "Unexpected error")))
            }
        }
    }

    companion object {
        private const val KEY_TASK_ID = "task_id"
        private const val KEY_EXECUTION_ID = "execution_id"
        private const val KEY_SCHEDULED_AT = "scheduled_at"
        private const val KEY_ERROR = "error"
        private const val MAX_RETRY_ATTEMPTS = 2

        fun enqueue(context: Context, taskId: String, scheduledAt: Long = 0L) {
            val executionId = UUID.nameUUIDFromBytes("$taskId:$scheduledAt".toByteArray(UTF_8)).toString()
            val request = OneTimeWorkRequestBuilder<TaskWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(AutomationForegroundInfo.executionConstraints)
                .setInputData(workDataOf(
                    KEY_TASK_ID to taskId,
                    KEY_EXECUTION_ID to executionId,
                    KEY_SCHEDULED_AT to scheduledAt,
                ))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(tag(taskId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName(taskId, scheduledAt), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, taskId: String) {
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(tag(taskId))
        }

        private fun uniqueName(taskId: String, scheduledAt: Long): String =
            "task_${taskId}_$scheduledAt"

        private fun tag(taskId: String): String = "task_$taskId"
    }
}
