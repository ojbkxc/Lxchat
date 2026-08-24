package com.lxseek.chat.im.office

import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Harness 会话增量更新。由 [OfficeHarnessSession.ask] 回调回传。
 *
 * - [type] = `"text"`：增量文字（[text] 携带内容）。
 * - [type] = `"tool"`：工具调用通知（[name] 携带工具名）。
 * - [type] = `"status"`：状态消息（[text] 携带内容）。
 */
data class OfficeHarnessUpdate(
    val type: String,
    val text: String = "",
    val name: String = "",
)

/**
 * Harness 审批请求。当工具调用需要人工批准或需要补充信息时由 [OfficeHarnessSession.ask] 回调。
 *
 * - [kind] = `"approval"`：工具审批（[toolName] 携带工具名）。
 * - [kind] = `"question"`：补充问题（[prompt] 携带问题文本）。
 */
data class OfficeApprovalRequest(
    val id: String,
    val kind: String,
    val title: String,
    val prompt: String,
    val toolName: String = "",
)

/** Harness 审批回复（来自 Office 人工面板，通过 SSE `approval.reply` 事件回传）。 */
data class OfficeApprovalReply(
    val decision: String,  // "approved" or "rejected"
    val answer: String,
)

/**
 * Harness 会话抽象。由调用方提供实现，桥接到 Lxchat 的 GenerationManager。
 *
 * 每个 Office Job 创建一个独立的 Harness Session 执行任务，与用户主会话隔离。
 * Session 在 [cancel] 后不可继续使用。
 */
interface OfficeHarnessSession {
    /** 创建会话，返回 sessionId。 */
    suspend fun createSession(): String

    /**
     * 发送 prompt 并等待最终回答。
     *
     * - [onUpdate]：增量回传（文字、工具名、状态）。
     * - [onApproval]：审批请求 → 等待 Office 人工面板回复。
     * @return 最终结果文本。
     */
    suspend fun ask(
        prompt: String,
        onUpdate: suspend (OfficeHarnessUpdate) -> Unit,
        onApproval: suspend (OfficeApprovalRequest) -> OfficeApprovalReply,
    ): String

    /** 取消会话，释放资源。 */
    suspend fun cancel()
}

/**
 * 任务执行器状态快照。
 */
data class OfficeJobExecutorStatus(
    val running: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val lastJobId: String? = null,
    val lastJobAtMs: Long = 0L,
)

/**
 * Office Job 执行器。
 *
 * 管理从 SSE `job.available` 事件触发的任务领取、执行、状态回传、终态写入全流程。
 * 每个任务在独立的 Harness Session 中执行，与用户主会话隔离。
 *
 * 核心保证：
 * - **终态只写入一次**：用 [AtomicBoolean] 保护 [OfficeConnectorApi.completeJob] 调用。
 * - **状态安全回传**：增量文字、工具名通过 [OfficeConnectorApi.postProgress] 回传，
 *   失败时静默忽略（不中断执行）。
 * - **审批等待**：工具审批 / 补充问题通过 [OfficeConnectorApi.requestApproval] 发送到 Office
 *   人工面板（独立端点），然后等待 SSE `approval.reply` 事件回传；续租时额外轮询
 *   `GET /jobs/:id` 的 `approval` 字段作为兜底。
 * - **租约续租**：每 30 秒续租一次，续租后轮询审批状态，续租失败则取消任务。
 * - **失败上报**：使用独立端点 [OfficeConnectorApi.failJob]，不复用 [OfficeConnectorApi.completeJob]。
 *
 * 与 dsh-im `office-job-executor.mjs` 的 `OfficeJobExecutor` 类对齐。
 *
 * @param config 连接器配置（workspaces、instructionPresets、maxConcurrency）。
 * @param api 传输层客户端。
 * @param scope 执行协程作用域。
 * @param createHarness Harness 会话工厂（按 workspace alias 创建独立会话）。
 */
class OfficeJobExecutor(
    private val config: OfficeConnectorSettings,
    private val api: OfficeConnectorApi,
    private val scope: CoroutineScope,
    private val createHarness: (workspaceAlias: String) -> OfficeHarnessSession,
) {
    /** Job id 格式：`job-` + 32 位十六进制。 */
    private val JOB_ID_REGEX = Regex("""^job-[a-f0-9]{32}$""")

    /** 活跃任务：jobId → 协程 Job。 */
    private val active = ConcurrentHashMap<String, Job>()
    /** 待领取任务队列（由 [offer] / [drain] 同步保护）。 */
    private val queued = mutableSetOf<String>()
    /** 已完成任务去重集（防止重复领取）。 */
    private val completed = ConcurrentHashMap.newKeySet<String>()
    /** 等待中的审批：`"$jobId:$approvalId"` → Deferred。 */
    private val pendingApprovals =
        ConcurrentHashMap<String, CompletableDeferred<OfficeApprovalReply>>()

    @Volatile private var closed = false

    private val _status = MutableStateFlow(OfficeJobExecutorStatus())
    /** 执行器状态流。 */
    val status: StateFlow<OfficeJobExecutorStatus> = _status

    /**
     * 提供一个任务。如果任务 id 合法且未在活跃/队列/已完成集合中，加入队列并触发调度。
     * @return true 当任务被接受。
     */
    @Synchronized
    fun offer(jobId: String): Boolean {
        if (closed || !JOB_ID_REGEX.matches(jobId)) return false
        if (active.containsKey(jobId) || jobId in queued || completed.contains(jobId)) return false
        queued.add(jobId)
        drain()
        return true
    }

    /**
     * 处理 SSE 事件。根据事件类型分发到 [offer] / [cancel] / 审批回复。
     * @return true 当事件被处理。
     */
    fun handleEvent(event: OfficeSseEvent): Boolean {
        val jobId = event.data["jobId"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: return false
        if (!JOB_ID_REGEX.matches(jobId)) return false
        return when (event.type) {
            "job.available" -> offer(jobId)
            "job.cancel" -> cancel(jobId)
            "approval.reply" -> {
                val approvalId = event.data["approvalId"]
                    ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return false
                val key = "$jobId:$approvalId"
                val pending = pendingApprovals[key] ?: return false
                val decision = if (event.data["decision"]
                        ?.let { (it as? JsonPrimitive)?.contentOrNull } == "approved"
                ) "approved" else "rejected"
                val answer = event.data["answer"]
                    ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
                pending.complete(OfficeApprovalReply(decision, answer))
                true
            }
            else -> false
        }
    }

    /** 取消任务。从队列移除并取消活跃协程。 */
    fun cancel(jobId: String): Boolean {
        synchronized(queued) { queued.remove(jobId) }
        val job = active.remove(jobId)
        job?.cancel()
        return job != null
    }

    /** 关闭执行器，取消所有活跃任务并清空队列。 */
    fun close() {
        closed = true
        synchronized(queued) { queued.clear() }
        active.values.forEach { it.cancel() }
        active.clear()
        // 拒绝所有等待中的审批
        pendingApprovals.values.forEach {
            it.complete(OfficeApprovalReply("rejected", "执行器已关闭"))
        }
        pendingApprovals.clear()
    }

    /** 调度：在并发上限内从队列取出任务并启动执行协程。 */
    @Synchronized
    private fun drain() {
        while (!closed && active.size < config.maxConcurrency && queued.isNotEmpty()) {
            val jobId = queued.first()
            queued.remove(jobId)
            val job = scope.launch {
                try {
                    executeJob(jobId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.w("OfficeJob", "Job $jobId failed: ${e.message}")
                }
            }
            active[jobId] = job
            _status.value = _status.value.copy(running = active.size)
            job.invokeOnCompletion {
                active.remove(jobId)
                _status.value = _status.value.copy(running = active.size)
                drain()
            }
        }
    }

    /** 执行单个任务的完整流程：查询 → 领取 → 续租 → 创建 Session → 执行 → 终态写入。 */
    private suspend fun executeJob(jobId: String) {
        val completedFlag = AtomicBoolean(false)
        var renewJob: Job? = null
        var session: OfficeHarnessSession? = null
        var leaseToken: String? = null
        try {
            // 1. 两步 get+accept 流程：先 GET 查询任务详情，再 POST accept 确认领取
            val fetched = api.getJob(jobId)
            val job = fetched["job"]?.jsonObject
                ?: throw OfficeTransportException("Office returned no job payload")
            val fetchedId = job["id"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            if (fetchedId != jobId) {
                throw OfficeTransportException("Office returned an invalid Job payload (id mismatch)")
            }
            val accepted = api.acceptJob(jobId)
            leaseToken = accepted["leaseToken"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: throw OfficeTransportException("Office returned an invalid Job lease")

            val workspaceAlias = job["workspaceAlias"]
                ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
            val instructionPreset = job["instructionPreset"]
                ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
            val instruction = job["instruction"]
                ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
            val markdown = job["markdown"]
                ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""

            // 2. 校验 alias
            val workspace = config.workspaces[workspaceAlias]
                ?: throw OfficeTransportException(
                    "Workspace alias '$workspaceAlias' not configured",
                    "office-job-alias-invalid",
                )
            val preset = config.instructionPresets[instructionPreset]
                ?: throw OfficeTransportException(
                    "Preset alias '$instructionPreset' not configured",
                    "office-job-alias-invalid",
                )

            // 3. 启动续租循环（续租后轮询审批状态）
            val currentLeaseToken = leaseToken
            renewJob = scope.launch {
                while (isActive) {
                    delay(OfficeProtocol.RENEW_INTERVAL_MS)
                    try {
                        api.renewJob(jobId, currentLeaseToken)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DebugLog.w("OfficeJob", "renew failed for $jobId: ${e.message}")
                        return@launch
                    }
                    // 续租后轮询审批状态（与 dsh-im office-job-executor.mjs #renew 对齐）
                    try {
                        pollApprovalStatus(jobId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DebugLog.w("OfficeJob", "approval poll failed for $jobId: ${e.message}")
                    }
                }
            }

            // 4. 创建独立 Harness Session
            session = createHarness(workspace)
            postProgressSafe(jobId, currentLeaseToken, "status",
                "已领取 Job，准备 Workspace alias：$workspaceAlias")

            val sessionId = session.createSession()
            postProgressSafe(jobId, currentLeaseToken, "status", "Harness Session 已创建。")

            // 5. 执行
            val prompt = renderPrompt(instruction, markdown, preset)
            val answer = session.ask(
                prompt = prompt,
                onUpdate = { update ->
                    val msg = when (update.type) {
                        "tool" -> "正在使用 ${update.name.take(160).ifEmpty { "Harness 工具" }}…"
                        else -> update.text.take(4000)
                    }
                    if (msg.isNotBlank()) {
                        postProgressSafe(jobId, currentLeaseToken,
                            if (update.type == "tool") "tool" else update.type.ifEmpty { "text" },
                            msg)
                    }
                },
                onApproval = { request ->
                    // 通过独立审批端点发送请求到 Office 人工面板
                    try {
                        api.requestApproval(jobId, currentLeaseToken, buildJsonObject {
                            put("id", request.id)
                            put("kind", request.kind)
                            put("title", request.title)
                            put("prompt", request.prompt)
                            if (request.toolName.isNotBlank()) put("toolName", request.toolName)
                        })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DebugLog.w("OfficeJob", "approval request failed: ${e.message}")
                    }
                    // 等待 SSE approval.reply 回传（续租轮询也会兜底 resolve）
                    val deferred = CompletableDeferred<OfficeApprovalReply>()
                    val key = "$jobId:${request.id}"
                    pendingApprovals[key] = deferred
                    val reply = deferred.await()
                    pendingApprovals.remove(key)
                    reply
                },
            )

            // 6. 终态写入（只一次，使用 result 端点）
            if (completedFlag.compareAndSet(false, true)) {
                api.completeJob(jobId, currentLeaseToken, buildJsonObject {
                    put("resultMarkdown", answer)
                    put("sessionId", sessionId)
                })
                _status.value = _status.value.copy(completed = _status.value.completed + 1)
            }
            completed.add(jobId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.w("OfficeJob", "Job $jobId execution failed: ${e.message}")
            // 失败上报：使用独立 fail 端点，不复用 complete
            if (completedFlag.compareAndSet(false, true) && leaseToken != null) {
                try {
                    api.failJob(jobId, leaseToken!!, buildJsonObject {
                        put("error", safeFailure(e))
                    })
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ce: Exception) {
                    DebugLog.w("OfficeJob", "fail report failed for $jobId: ${ce.message}")
                }
                _status.value = _status.value.copy(failed = _status.value.failed + 1)
            }
            // 冲突 / Hook 不可用 → 标记已完成，避免重复领取
            if (e is OfficeTransportException &&
                (e.code == "office-job-conflict" || e.code == "office-hook-unavailable")) {
                completed.add(jobId)
            }
        } finally {
            renewJob?.cancel()
            val s = session
            if (s != null) {
                try {
                    s.cancel()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    // 静默忽略取消失败
                }
            }
            _status.value = _status.value.copy(
                running = active.size,
                lastJobId = jobId,
                lastJobAtMs = System.currentTimeMillis(),
            )
        }
    }

    /**
     * 续租时轮询审批状态。查询 `GET /jobs/:id` 的 `job.approval` 字段，
     * 如果审批已决断（approved/rejected），resolve 等待中的 approval deferred。
     * 与 dsh-im `office-job-executor.mjs` 的 `#renew` 审批轮询逻辑对齐。
     */
    private suspend fun pollApprovalStatus(jobId: String) {
        val snapshot = api.getJob(jobId)
        val approval = snapshot["job"]?.jsonObject?.get("approval")?.jsonObject ?: return
        val approvalId = approval["id"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return
        val status = approval["status"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return
        if (status != "approved" && status != "rejected") return
        val key = "$jobId:$approvalId"
        val pending = pendingApprovals[key] ?: return
        val answer = approval["answer"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
        pending.complete(OfficeApprovalReply(status, answer))
    }

    /** 安全回传进度：失败时静默忽略，不中断执行。 */
    private suspend fun postProgressSafe(
        jobId: String,
        leaseToken: String,
        kind: String,
        message: String,
    ) {
        try {
            api.postProgress(jobId, leaseToken, buildJsonObject {
                put("kind", kind)
                put("message", message.take(4000))
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d("OfficeJob", "progress post failed: ${e.message}")
        }
    }

    /** 根据异常类型生成用户友好的失败消息。 */
    private fun safeFailure(e: Exception): String = when {
        e is OfficeTransportException && e.code == "office-job-alias-invalid" ->
            e.message ?: "alias invalid"
        e is OfficeTransportException && e.code == "office-job-conflict" ->
            "Office Job 已被领取、取消或结束。"
        else -> "本机 Harness 未能完成任务；请检查 Harness 会话后重试。"
    }

    /** 构建 Office Handoff prompt（与 dsh-im `renderPrompt` 对齐）。 */
    private fun renderPrompt(instruction: String, markdown: String, preset: String): String =
        buildString {
            appendLine("# AI Office Handoff")
            appendLine()
            appendLine("你正在本机 DeepSeek Harness 中继续一个来自 AI Office 的任务。")
            appendLine("只在当前 Workspace 内行动。完成后必须返回：结果摘要、改动文件、验证证据、未解决风险。")
            appendLine()
            appendLine("## 本机 Instruction Preset")
            appendLine(preset)
            appendLine()
            if (instruction.isNotBlank()) {
                appendLine("## 本轮补充指令")
                appendLine(instruction)
                appendLine()
            }
            appendLine("## Office 时间线")
            append(markdown.take(200_000))
        }
}