package com.lxseek.chat.cron

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lxseek.chat.LxChatApplication
import com.lxseek.chat.data.local.ChatEntity
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 执行单次 Cron 任务的 WorkManager Worker。
 *
 * 流程：
 * 1. 从 inputData 读取 task_id，从 [CronTaskStore] 加载 [CronTask]。
 * 2. 为该任务派生一个稳定的 conversationId（基于 task id 的 UUID v3），首次执行时创建会话，
 *    后续执行复用——这样同一定时任务的多次执行共享上下文（AI 能记住之前的摘要）。
 * 3. 通过 [com.lxseek.chat.automation.TaskExecutionEngine.runOnce] 跑一次完整生成。
 * 4. 用 NonCancellable 包裹 markRun + reschedule，确保即使调度器并发重排也不会丢失
 *    「上次执行时间」和「下一次调度」这两个关键副作用。
 * 5. 调用 [CronScheduler.reschedule] 排下一次，形成自驱动链。
 *
 * Worker 不管理前台通知：[com.lxseek.chat.automation.TaskExecutionEngine] 内部会在 LLM
 * 调用期间提升 [com.lxseek.chat.service.LxChatForegroundService]，本 Worker 只需跑生成。
 */
class CronWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val container = (applicationContext as LxChatApplication).container
        val store = container.cronTaskStore
        val engine = container.taskExecutionEngine
        val scheduler = container.cronScheduler

        val task = try {
            store.currentTasks().firstOrNull { it.id == taskId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to load cron task $taskId", e)
            null
        } ?: run {
            DebugLog.w(TAG, "Cron task $taskId not found, skipping")
            return Result.success()
        }

        if (!task.enabled) {
            DebugLog.d(TAG, "Cron task ${task.name} ($taskId) disabled, skipping")
            return Result.success()
        }
        if (task.prompt.isBlank()) {
            DebugLog.w(TAG, "Cron task ${task.name} ($taskId) has empty prompt, skipping")
            return Result.success()
        }

        return try {
            val conversationId = ensureConversation(container, task)
            DebugLog.d(TAG, "Running cron task '${task.name}' ($taskId) in conversation $conversationId")
            when (val outcome = engine.runOnce(
                conversationId = conversationId,
                userText = task.prompt,
                modelId = task.modelId?.takeIf { it.isNotBlank() },
            )) {
                is com.lxseek.chat.automation.TaskExecutionEngine.Result.Success ->
                    DebugLog.d(TAG, "Cron task '${task.name}' succeeded")
                is com.lxseek.chat.automation.TaskExecutionEngine.Result.Busy ->
                    DebugLog.w(TAG, "Cron task '${task.name}' skipped: ${outcome.reason}")
                is com.lxseek.chat.automation.TaskExecutionEngine.Result.Failure ->
                    DebugLog.w(TAG, "Cron task '${task.name}' failed: ${outcome.reason}")
            }
            // 关键副作用必须在 NonCancellable 内完成：调度器监听 tasks Flow，
            // markRun 触发的 Flow 发射可能让 CronScheduler.start() 用 KEEP 调度，
            // 但本 Worker 仍需自己排下一次（KEEP 不会覆盖已 SUCCEEDED 的同名 work）。
            withContext(NonCancellable) {
                runCatching { store.markRun(taskId) }
                    .onFailure { DebugLog.e(TAG, "markRun failed for $taskId", it) }
                runCatching { scheduler.reschedule(task) }
                    .onFailure { DebugLog.e(TAG, "reschedule failed for $taskId", it) }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "Cron task '${task.name}' ($taskId) threw", e)
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                // 即使最终失败，也要排下一次，避免链断裂。
                withContext(NonCancellable) {
                    runCatching { scheduler.reschedule(task) }
                }
                Result.failure()
            }
        }
    }

    /**
     * 为 [task] 派生稳定的 conversationId（基于 task id 的 UUID v3），
     * 若会话不存在则 upsert 一个用该固定 id、以任务名命名的会话。
     * 后续每次执行都复用同一会话，使 AI 能延续历次执行的记忆。
     */
    private suspend fun ensureConversation(
        container: com.lxseek.chat.di.AppContainer,
        task: CronTask,
    ): String {
        val conversationId = UUID.nameUUIDFromBytes(
            "cron:${task.id}".toByteArray(Charsets.UTF_8)
        ).toString()
        val repo = container.conversationRepository
        if (repo.getConversation(conversationId) == null) {
            repo.upsertConversation(
                ChatEntity(
                    id = conversationId,
                    title = task.name.ifBlank { "Cron Task" },
                    systemPromptId = null,
                    modelId = task.modelId?.takeIf { it.isNotBlank() },
                    origin = "task",
                )
            )
        }
        return conversationId
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        private const val TAG = "CronWorker"
        private const val MAX_RETRY_ATTEMPTS = 2
    }
}
