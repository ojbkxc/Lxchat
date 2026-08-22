package com.lxseek.chat.data.local

import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Task and Loop persistence declarations inherited by [ChatDao].
 *
 * This interface owns no mutable state and is not a second Room DAO; [ChatDao] remains the sole
 * annotated database access surface.
 */
interface ChatAutomationDao {
    // ── Tasks ─────────────────────────────────────────────────
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTask(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE enabled = 1")
    suspend fun getEnabledTasks(): List<TaskEntity>

    @Upsert
    suspend fun upsertTask(task: TaskEntity)

    @Query("UPDATE tasks SET modelId = :newModelId WHERE modelId = :oldModelId")
    suspend fun replaceTaskModelReferences(
        oldModelId: String,
        newModelId: String?,
    ): Int

    @Query(
        """
        UPDATE tasks
        SET modelId = :newProvider || substr(modelId, length(:oldProvider) + 1)
        WHERE modelId LIKE :oldProvider || ':%'
        """
    )
    suspend fun renameTaskProviderModelReferences(
        oldProvider: String,
        newProvider: String,
    ): Int

    /** Clock-change CAS: never overwrite a concurrent edit/disable/execution advancement. */
    @Query(
        """
        UPDATE tasks SET nextRunAt = :replacementNextRunAt
        WHERE id = :id AND enabled = 1 AND cronExpr = :expectedCronExpr
          AND nextRunAt = :expectedNextRunAt
        """
    )
    suspend fun updateTaskNextRunAtIfUnchanged(
        id: String,
        expectedCronExpr: String,
        expectedNextRunAt: Long,
        replacementNextRunAt: Long,
    ): Int

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()

    // Bulk export/import
    @Query("SELECT * FROM tasks")
    suspend fun getAllTasksList(): List<TaskEntity>

    // ── Loops ─────────────────────────────────────────────────
    @Query("SELECT * FROM loops WHERE conversationId = :conversationId")
    fun getLoop(conversationId: String): Flow<LoopEntity?>

    @Query("SELECT * FROM loops WHERE active = 1")
    suspend fun getActiveLoops(): List<LoopEntity>

    @Query("SELECT * FROM loops WHERE active = 1")
    fun observeActiveLoops(): Flow<List<LoopEntity>>

    @Upsert
    suspend fun upsertLoop(loop: LoopEntity)

    /** Clock-change CAS: only the observed active loop revision/cycle may be moved. */
    @Query(
        """
        UPDATE loops SET nextFireAt = :replacementNextFireAt
        WHERE conversationId = :conversationId AND active = 1
          AND revision = :expectedRevision AND cycleCount = :expectedCycleCount
          AND intervalMs = :expectedIntervalMs AND nextFireAt = :expectedNextFireAt
        """
    )
    suspend fun updateLoopNextFireAtIfUnchanged(
        conversationId: String,
        expectedRevision: Long,
        expectedCycleCount: Int,
        expectedIntervalMs: Long,
        expectedNextFireAt: Long,
        replacementNextFireAt: Long,
    ): Int

    /** Safely quarantines an invalid legacy loop without reviving or clobbering a newer state. */
    @Query(
        """
        UPDATE loops
        SET active = 0, nextFireAt = 0, revision = revision + 1,
            maxCycles = :normalizedMaxCycles
        WHERE conversationId = :conversationId AND active = 1
          AND revision = :expectedRevision AND cycleCount = :expectedCycleCount
          AND intervalMs = :expectedIntervalMs AND nextFireAt = :expectedNextFireAt
        """
    )
    suspend fun deactivateLoopIfUnchanged(
        conversationId: String,
        expectedRevision: Long,
        expectedCycleCount: Int,
        expectedIntervalMs: Long,
        expectedNextFireAt: Long,
        normalizedMaxCycles: Int,
    ): Int

    @Query("DELETE FROM loops WHERE conversationId = :conversationId")
    suspend fun deleteLoop(conversationId: String)

    @Query("DELETE FROM loops")
    suspend fun deleteAllLoops()

    @Query("SELECT * FROM loops")
    suspend fun getAllLoopsList(): List<LoopEntity>

    // ── Workflows ────────────────────────────────────────────
    @Query("SELECT * FROM workflows ORDER BY createdAt DESC")
    fun getAllWorkflows(): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workflows ORDER BY createdAt DESC")
    suspend fun getAllWorkflowsList(): List<WorkflowEntity>

    @Query("SELECT * FROM workflows WHERE id = :id")
    suspend fun getWorkflow(id: String): WorkflowEntity?

    @Upsert
    suspend fun upsertWorkflow(workflow: WorkflowEntity)

    @Query("DELETE FROM workflows WHERE id = :id")
    suspend fun deleteWorkflow(id: String)

    @Query("SELECT * FROM workflow_steps WHERE workflowId = :workflowId ORDER BY position ASC")
    suspend fun getWorkflowSteps(workflowId: String): List<WorkflowStepEntity>

    @Query("SELECT * FROM workflow_steps WHERE workflowId = :workflowId ORDER BY position ASC")
    fun observeWorkflowSteps(workflowId: String): Flow<List<WorkflowStepEntity>>

    @Upsert
    suspend fun upsertWorkflowSteps(steps: List<WorkflowStepEntity>)

    @Query("DELETE FROM workflow_steps WHERE workflowId = :workflowId")
    suspend fun deleteWorkflowSteps(workflowId: String)

    @Query("DELETE FROM workflow_steps WHERE id = :stepId")
    suspend fun deleteWorkflowStep(stepId: String)

    @Query("DELETE FROM workflows")
    suspend fun deleteAllWorkflows()
}
