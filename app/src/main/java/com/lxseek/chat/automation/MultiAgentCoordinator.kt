package com.lxseek.chat.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Status of an agent task in the coordination pipeline.
 */
enum class TaskStatus {
    PENDING,
    ASSIGNED,
    RUNNING,
    COMPLETED,
    FAILED,
}

/**
 * A unit of work submitted to the [MultiAgentCoordinator].
 *
 * @param id Unique identifier (auto-generated UUID).
 * @param description Human-readable task summary.
 * @param prompt The full prompt to send to the assigned agent.
 * @param assignedAgent The agent id that accepted this task, or null if still pending.
 * @param status Current lifecycle status.
 * @param result The result string on success, or null.
* @param result The result string on success, or null if not yet completed.
 * @param createdAt Creation timestamp (epoch ms).
 * @param completedAt Completion timestamp, or null if still running.
 */
data class AgentTask(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val prompt: String,
    val assignedAgent: String? = null,
    val status: TaskStatus = TaskStatus.PENDING,
    val result: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)

/**
 * Result of a coordinated task execution.
 */
data class CoordinationResult(
    val taskId: String,
    val success: Boolean,
    val result: String?,
    val agentId: String?,
    val duration: Long,
)

/**
 * Coordinates multiple sub-agents working on independent tasks.
 *
 * Inspired by zhikuncode-main's multi-agent coordinator pattern: a central
 * coordinator accepts task submissions, assigns them to available agents,
 * tracks lifecycle, and exposes reactive state for UI observation.
 *
 * This class is thread-safe — all mutations go through a single
 * [MutableStateFlow] updated under a synchronized block.
 */
class MultiAgentCoordinator {

    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    val tasks: StateFlow<List<AgentTask>> = _tasks.asStateFlow()

    /**
     * Submits a new task to the coordinator.
     * The task starts in [TaskStatus.PENDING] and will appear in [getPendingTasks].
     */
    fun submitTask(description: String, prompt: String): AgentTask {
        val task = AgentTask(description = description, prompt = prompt)
        update { it + task }
        return task
    }

    /** Assigns a pending task to a specific agent. */
    fun assignTask(taskId: String, agentId: String) {
        update { list ->
            list.map { task ->
                if (task.id == taskId && task.status == TaskStatus.PENDING) {
                    task.copy(assignedAgent = agentId, status = TaskStatus.ASSIGNED)
                } else {
                    task
                }
            }
        }
    }

    /** Marks a task as running. */
    fun startTask(taskId: String) {
        updateStatus(taskId, TaskStatus.RUNNING)
    }

    /** Marks a task as completed with the given result. */
    fun completeTask(taskId: String, result: String) {
        update { list ->
            list.map { task ->
                if (task.id == taskId) {
                    task.copy(
                        status = TaskStatus.COMPLETED,
                        result = result,
                        completedAt = System.currentTimeMillis(),
                    )
                } else {
                    task
                }
            }
        }
    }

    /** Marks a task as failed with the given error message. */
    fun failTask(taskId: String, error: String) {
        update { list ->
            list.map { task ->
                if (task.id == taskId) {
                    task.copy(
                        status = TaskStatus.FAILED,
                        result = error,
                        completedAt = System.currentTimeMillis(),
                    )
                } else {
                    task
                }
            }
        }
    }

    /** Returns all tasks with [TaskStatus.PENDING]. */
    fun getPendingTasks(): List<AgentTask> =
        _tasks.value.filter { it.status == TaskStatus.PENDING }

    /** Returns all tasks with [TaskStatus.ASSIGNED] or [TaskStatus.RUNNING]. */
    fun getActiveTasks(): List<AgentTask> =
        _tasks.value.filter {
            it.status == TaskStatus.ASSIGNED || it.status == TaskStatus.RUNNING
        }

    /** Returns all tasks with [TaskStatus.COMPLETED] or [TaskStatus.FAILED]. */
    fun getCompletedTasks(): List<AgentTask> =
        _tasks.value.filter {
            it.status == TaskStatus.COMPLETED || it.status == TaskStatus.FAILED
        }

    /** Returns all tasks. */
    fun getAllTasks(): List<AgentTask> = _tasks.value

    /** Returns a specific task by id, or null. */
    fun getTask(id: String): AgentTask? = _tasks.value.firstOrNull { it.id == id }

    // ── Internal helpers ──────────────────────────────────────

    private fun updateStatus(taskId: String, status: TaskStatus) {
        update { list ->
            list.map { task ->
                if (task.id == taskId) task.copy(status = status) else task
            }
        }
    }

    private fun update(transform: (List<AgentTask>) -> List<AgentTask>) {
        synchronized(_tasks) {
            _tasks.value = transform(_tasks.value)
        }
    }
}