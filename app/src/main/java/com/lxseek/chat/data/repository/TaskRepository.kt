package com.lxseek.chat.data.repository

import com.lxseek.chat.data.local.ChatDao
import com.lxseek.chat.data.local.LoopEntity
import com.lxseek.chat.data.local.TaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository for the automation layer — saved [TaskEntity] templates and per-conversation
 * [LoopEntity] state. Kept separate from [ConversationRepository] so the conversation/message
 * concern stays focused; both wrap the single [ChatDao].
 */
class TaskRepository(
    private val chatDao: ChatDao
) {
    // ── Tasks ─────────────────────────────────────────────────

    fun getAllTasks(): Flow<List<TaskEntity>> = chatDao.getAllTasks()

    suspend fun getTask(id: String): TaskEntity? = chatDao.getTask(id)

    suspend fun getEnabledTasks(): List<TaskEntity> = chatDao.getEnabledTasks()

    suspend fun getAllTasksSnapshot(): List<TaskEntity> = chatDao.getAllTasksList()

    suspend fun upsertTask(task: TaskEntity) = chatDao.upsertTask(task)

    suspend fun updateTaskNextRunAtIfUnchanged(
        task: TaskEntity,
        replacementNextRunAt: Long,
    ): Boolean = chatDao.updateTaskNextRunAtIfUnchanged(
        id = task.id,
        expectedCronExpr = task.cronExpr,
        expectedNextRunAt = task.nextRunAt,
        replacementNextRunAt = replacementNextRunAt,
    ) > 0

    suspend fun deleteTask(id: String) = chatDao.deleteTask(id)

    suspend fun deleteAllTasks() = chatDao.deleteAllTasks()

    // ── Loops ─────────────────────────────────────────────────

    fun getLoop(conversationId: String): Flow<LoopEntity?> = chatDao.getLoop(conversationId)

    suspend fun getActiveLoops(): List<LoopEntity> = chatDao.getActiveLoops()

    suspend fun getAllLoopsSnapshot(): List<LoopEntity> = chatDao.getAllLoopsList()

    fun observeActiveLoops(): Flow<List<LoopEntity>> = chatDao.observeActiveLoops()

    suspend fun upsertLoop(loop: LoopEntity) = chatDao.upsertLoop(loop)

    suspend fun updateLoopNextFireAtIfUnchanged(
        loop: LoopEntity,
        replacementNextFireAt: Long,
    ): Boolean = chatDao.updateLoopNextFireAtIfUnchanged(
        conversationId = loop.conversationId,
        expectedRevision = loop.revision,
        expectedCycleCount = loop.cycleCount,
        expectedIntervalMs = loop.intervalMs,
        expectedNextFireAt = loop.nextFireAt,
        replacementNextFireAt = replacementNextFireAt,
    ) > 0

    suspend fun deactivateLoopIfUnchanged(
        loop: LoopEntity,
        normalizedMaxCycles: Int,
    ): Boolean = chatDao.deactivateLoopIfUnchanged(
        conversationId = loop.conversationId,
        expectedRevision = loop.revision,
        expectedCycleCount = loop.cycleCount,
        expectedIntervalMs = loop.intervalMs,
        expectedNextFireAt = loop.nextFireAt,
        normalizedMaxCycles = normalizedMaxCycles,
    ) > 0

    suspend fun deleteLoop(conversationId: String) = chatDao.deleteLoop(conversationId)

    suspend fun deleteAllLoops() = chatDao.deleteAllLoops()
}
