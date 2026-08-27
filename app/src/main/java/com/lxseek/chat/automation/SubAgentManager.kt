package com.lxseek.chat.automation

import com.lxseek.chat.data.local.TaskEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * 子代理（subagent）管理器 —— 复用一个「一次性 Task」来做后台异步委托执行。
 *
 * 每个子代理 = 一条仅运行一次的 Task：给它一个全新会话和独立上下文，由现有的
 * [TaskManager] 在自己的后台作用域里跑（一条 AI 生成），父对话不再等待也能通过
 * [latestOutput] 取回结果。这样既隔离了上下文，又天然支持并发上限与取消。
 */
class SubAgentManager(
    private val taskManager: TaskManager,
) {

    data class SubAgent(
        val id: String,
        val description: String,
        val createdAt: Long,
    )

    private val _subAgents = MutableStateFlow<Map<String, SubAgent>>(emptyMap())
    val subAgents: StateFlow<Map<String, SubAgent>> = _subAgents.asStateFlow()

    /** 是否已达并发上限（5）。 */
    val isFull: Boolean get() = runningCount >= MAX_RUNNING

    /** 当前运行中的子代理数量（任务仍在执行中）。 */
    val runningCount: Int
        get() = taskManager.runningTaskIds.value.count { id -> _subAgents.value.containsKey(id) }

    /**
     * 生成一个子代理并立即在后台执行。
     * 返回稳定的 [SubAgent]（其 id 同时作为底层 Task 的 id，供 read/delete 使用）。
     */
    fun spawn(prompt: String, description: String, modelId: String?): SubAgent? {
        if (isFull) return null
        val id = UUID.randomUUID().toString()
        val title = description.trim().ifBlank { "子代理任务" }
        val task = TaskEntity(
            id = id,
            name = NAME_PREFIX + title,
            prompt = prompt.trim(),
            modelId = modelId,
            cronExpr = "",
            nextRunAt = 0L,
            enabled = true,
        )
        // runNow 负责持久化并立即在后台作用域启动一次性执行。
        taskManager.runNow(task)
        val sub = SubAgent(
            id = id,
            description = title,
            createdAt = System.currentTimeMillis(),
        )
        _subAgents.value = _subAgents.value + (id to sub)
        return sub
    }

    /** 读取某子代理的最新一条模型输出；尚未产出或不存在时返回空串。 */
    suspend fun latestOutput(id: String): String {
        val summaries = taskManager.executionSummariesForTask(id).first()
        return summaries.lastOrNull()?.preview ?: ""
    }

    /** 子代理是否仍在运行。 */
    fun isRunning(id: String): Boolean = taskManager.runningTaskIds.value.contains(id)

    /** 删除子代理及其执行会话。 */
    suspend fun remove(id: String) {
        taskManager.deleteTask(id)
        _subAgents.value = _subAgents.value - id
    }

    fun list(): List<SubAgent> = _subAgents.value.values.sortedBy { it.createdAt }

    private companion object {
        const val NAME_PREFIX = "子代理: "
        const val MAX_RUNNING = 5
    }
}