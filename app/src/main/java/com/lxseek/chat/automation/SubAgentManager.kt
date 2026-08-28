package com.lxseek.chat.automation

import com.lxseek.chat.data.local.TaskEntity
import com.lxseek.chat.model.MessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 子代理（subagent）管理器 —— 复用一个「一次性 Task」来做后台异步委托执行。
 *
 * 每个子代理 = 一条仅运行一次的 Task：给它一个全新会话和独立上下文，由现有的
 * [TaskManager] 在自己的后台作用域里跑（一条 AI 生成），父对话不再等待也能通过
 * [latestOutput] 取回结果。这样既隔离了上下文，又天然支持并发上限与取消。
 *
 * 支持能力：
 * - 上下文传递：[spawn] 的 `context` 参数会作为前缀注入到子代理的 prompt 中，
 *   让子代理从「父对话摘要 / 关键事实」开始而非空白。
 * - 超时：[spawn] 的 `timeoutMs` 参数会在超时后自动取消该子代理并标记 [SubAgent.timedOut]。
 * - 进度报告：[progress] 返回运行状态、已耗时、输出长度与输出预览。
 * - 并发上限可配置：通过 [maxRunning] 构造参数控制，默认 5，向后兼容。
 */
class SubAgentManager(
    private val taskManager: TaskManager,
    private val scope: CoroutineScope,
    val maxRunning: Int = DEFAULT_MAX_RUNNING,
) {

    data class SubAgent(
        val id: String,
        val description: String,
        val createdAt: Long,
        /** 是否因超时被自动取消。 */
        val timedOut: Boolean = false,
    )

    /**
     * 子代理进度快照。供 UI / 父对话轮询展示「还在跑、已跑多久、目前产出了多少字」。
     */
    data class SubAgentProgress(
        /** running / completed / failed / timeout */
        val status: String,
        /** 自创建以来经过的毫秒数。 */
        val elapsedMs: Long,
        /** 当前最新输出的字符长度。 */
        val outputLength: Int,
        /** 最新输出的预览片段（截断），便于上层展示。 */
        val lastOutputPreview: String,
    )

    private val _subAgents = MutableStateFlow<Map<String, SubAgent>>(emptyMap())
    val subAgents: StateFlow<Map<String, SubAgent>> = _subAgents.asStateFlow()

    /** 是否已达并发上限。 */
    val isFull: Boolean get() = runningCount >= maxRunning

    /** 当前运行中的子代理数量（任务仍在执行中）。 */
    val runningCount: Int
        get() = taskManager.runningTaskIds.value.count { id -> _subAgents.value.containsKey(id) }

    /**
     * 生成一个子代理并立即在后台执行。
     *
     * @param prompt 子代理的完整指令。
     * @param description 显示名。
     * @param modelId 指定模型；null 表示用默认模型。
     * @param context 可选上下文。非空时作为前缀注入到 prompt 之前，让子代理从关键事实开始。
     * @param timeoutMs 可选超时毫秒数。非空时到期自动取消该子代理并标记 [SubAgent.timedOut]。
     * @return 稳定的 [SubAgent]（其 id 同时作为底层 Task 的 id）；已达并发上限时返回 null。
     */
    fun spawn(
        prompt: String,
        description: String,
        modelId: String?,
        context: String? = null,
        timeoutMs: Long? = null,
    ): SubAgent? {
        if (isFull) return null
        val id = UUID.randomUUID().toString()
        val title = description.trim().ifBlank { "子代理任务" }
        // 上下文注入：把父对话提供的事实作为前缀拼到指令之前，子代理从「已知信息」开始而非空白。
        val effectivePrompt = if (!context.isNullOrBlank()) {
            "--- Provided Context ---\n$context\n--- End Context ---\n\n${prompt.trim()}"
        } else {
            prompt.trim()
        }
        val task = TaskEntity(
            id = id,
            name = NAME_PREFIX + title,
            prompt = effectivePrompt,
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

        // 超时守护：到点若仍在运行则取消底层 Task，并把该子代理标记为 timedOut。
        if (timeoutMs != null && timeoutMs > 0L) {
            scope.launch {
                delay(timeoutMs)
                if (isRunning(id)) {
                    runCatching { taskManager.cancelTask(id) }
                    _subAgents.value = _subAgents.value[id]?.let { current ->
                        _subAgents.value + (id to current.copy(timedOut = true))
                    } ?: _subAgents.value
                }
            }
        }
        return sub
    }

    /** 读取某子代理的最新一条模型输出；尚未产出或不存在时返回空串。 */
    suspend fun latestOutput(id: String): String {
        val summaries = taskManager.executionSummariesForTask(id).first()
        return summaries.lastOrNull()?.preview ?: ""
    }

    /**
     * 读取某子代理的进度快照。不存在时返回 null。
     * 状态判定优先级：timedOut > running > completed/failed（按底层执行摘要末条 status）。
     */
    suspend fun progress(id: String): SubAgentProgress? {
        val sub = _subAgents.value[id] ?: return null
        val summaries = taskManager.executionSummariesForTask(id).first()
        val last = summaries.lastOrNull()
        val output = last?.preview.orEmpty()
        val elapsed = System.currentTimeMillis() - sub.createdAt
        val status = when {
            sub.timedOut -> "timeout"
            isRunning(id) -> "running"
            last?.status == MessageStatus.ERROR -> "failed"
            last != null -> "completed"
            else -> "running"
        }
        return SubAgentProgress(
            status = status,
            elapsedMs = elapsed,
            outputLength = output.length,
            lastOutputPreview = output.take(PROGRESS_PREVIEW_LENGTH),
        )
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
        const val DEFAULT_MAX_RUNNING = 5
        const val PROGRESS_PREVIEW_LENGTH = 200
    }
}
