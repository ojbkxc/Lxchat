package com.lxseek.chat.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lxseek.chat.LxChatApplication
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Runs one persisted Loop cycle under WorkManager foreground execution. */
class LoopWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val conversationId = inputData.getString(KEY_CONVERSATION_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        val scheduledAt = inputData.getLong(KEY_SCHEDULED_AT, 0L)

        return try {
            setForeground(AutomationForegroundInfo.forLoop(applicationContext, conversationId, id))
            val container = (applicationContext as LxChatApplication).container
            // A model-level Failure is a completed attempt: LoopManager counts it and schedules
            // the next cycle. Retrying it here could append a duplicate turn to the conversation.
            when (val outcome = container.loopManager.executeByConversationId(conversationId, scheduledAt)) {
                is com.lxseek.chat.automation.LoopManager.ExecutionResult.NotDue -> {
                    // An alarm should not normally fire early, but wall-clock changes can make it
                    // happen. The old one-shot has been consumed, so force a fresh arm.
                    container.automationScheduler.cancelLoop(conversationId)
                    container.automationScheduler.refreshAndAwait()
                }
                else -> Unit
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(
                "LoopWorker",
                "Infrastructure failure for loop=$conversationId attempt=$runAttemptCount",
                e,
            )
            if (shouldRetry(runAttemptCount)) {
                Result.retry()
            } else {
                runCatching {
                    val container = (applicationContext as LxChatApplication).container
                    container.loopManager.deferAfterInfrastructureFailure(conversationId)
                }.onFailure { repairError ->
                    DebugLog.e("LoopWorker", "Failed to defer loop=$conversationId", repairError)
                }
                Result.failure(workDataOf(KEY_ERROR to (e.localizedMessage ?: e.javaClass.simpleName)))
            }
        }
    }

    companion object {
        private const val KEY_CONVERSATION_ID = "conversation_id"
        private const val KEY_SCHEDULED_AT = "scheduled_at"
        private const val KEY_ERROR = "error"
        internal const val MAX_RETRIES = 2

        internal fun shouldRetry(runAttemptCount: Int): Boolean = runAttemptCount < MAX_RETRIES

        fun enqueue(context: Context, conversationId: String, scheduledAt: Long = 0L) {
            val appContext = context.applicationContext
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                uniqueName(conversationId, scheduledAt),
                ExistingWorkPolicy.KEEP,
                request(conversationId, scheduledAt),
            )
        }

        fun cancel(context: Context, conversationId: String) {
            WorkManager.getInstance(context.applicationContext)
                .cancelAllWorkByTag(tag(conversationId))
        }

        private fun request(conversationId: String, scheduledAt: Long) =
            OneTimeWorkRequestBuilder<LoopWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(AutomationForegroundInfo.executionConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_CONVERSATION_ID to conversationId,
                        KEY_SCHEDULED_AT to scheduledAt,
                    )
                )
                .addTag(tag(conversationId))
                .build()

        private fun uniqueName(conversationId: String, scheduledAt: Long): String =
            "loop_${conversationId}_$scheduledAt"

        private fun tag(conversationId: String): String = "loop_$conversationId"
    }
}
