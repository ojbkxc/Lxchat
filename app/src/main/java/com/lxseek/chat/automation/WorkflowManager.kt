package com.lxseek.chat.automation

import com.lxseek.chat.data.local.ChatEntity
import com.lxseek.chat.data.local.WorkflowEntity
import com.lxseek.chat.data.local.WorkflowStepConfig
import com.lxseek.chat.data.local.WorkflowStepEntity
import com.lxseek.chat.data.local.WorkflowStepType
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.TaskRepository
import com.lxseek.chat.util.DebugLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App-scoped orchestrator for saved [WorkflowEntity]s.
 *
 * A workflow is an ordered list of steps executed top-to-bottom: each "task" step runs one
 * headless generation (persisted as a fresh execution conversation owned by the workflow), and
 * each "delay" step pauses the run. One execution per workflow at a time; a second run is ignored
 * while the first is still in flight.
 */
class WorkflowManager(
    private val taskRepository: TaskRepository,
    private val conversationRepository: ConversationRepository,
    private val engine: TaskExecutionEngine,
    private val scope: CoroutineScope,
) {
    sealed interface RunResult {
        data class Success(val conversationId: String) : RunResult
        data class Failure(val reason: String) : RunResult
        data class Skipped(val reason: String) : RunResult
    }

    val workflows: StateFlow<List<WorkflowEntity>> =
        taskRepository.getAllWorkflows().stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    private val manualJobs = ConcurrentHashMap<String, Job>()
    private val reservedWorkflowIds = mutableSetOf<String>()
    private val _runningWorkflowIds = MutableStateFlow<Set<String>>(emptySet())
    val runningWorkflowIds: StateFlow<Set<String>> = _runningWorkflowIds.asStateFlow()

    fun observeWorkflowSteps(workflowId: String): Flow<List<WorkflowStepEntity>> =
        taskRepository.observeWorkflowSteps(workflowId)

    suspend fun getWorkflow(id: String): WorkflowEntity? = taskRepository.getWorkflow(id)

    suspend fun getWorkflowSteps(workflowId: String): List<WorkflowStepEntity> =
        taskRepository.getWorkflowSteps(workflowId)

    /** Persists a workflow and atomically replaces its steps with [steps] (re-ordered positions). */
    suspend fun saveWorkflow(workflow: WorkflowEntity, steps: List<WorkflowStepEntity>) {
        if (workflow.name.isBlank()) return
        taskRepository.upsertWorkflow(workflow.copy(name = workflow.name.trim(), updatedAt = System.currentTimeMillis()))
        taskRepository.deleteWorkflowSteps(workflow.id)
        if (steps.isNotEmpty()) {
            taskRepository.upsertWorkflowSteps(
                steps.mapIndexed { index, step ->
                    step.copy(workflowId = workflow.id, position = index)
                }
            )
        }
    }

    suspend fun deleteWorkflow(id: String) {
        manualJobs.remove(id)?.cancel()
        taskRepository.deleteWorkflowSteps(id)
        taskRepository.deleteWorkflow(id)
    }

    /** Runs one workflow end-to-end. A second tap while already running is ignored. */
    fun runNow(workflow: WorkflowEntity) {
        if (!reserve(workflow.id)) return
        lateinit var job: Job
        job = scope.launch {
            try {
                val result = runSteps(workflow)
                when (result) {
                    is RunResult.Failure -> DebugLog.e("WorkflowManager", "workflow=${workflow.id} failed")
                    is RunResult.Skipped -> DebugLog.d("WorkflowManager", "workflow=${workflow.id} skipped")
                    is RunResult.Success -> DebugLog.d("WorkflowManager", "workflow=${workflow.id} finished conversation=${result.conversationId}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e("WorkflowManager", "workflow=${workflow.id} threw unexpectedly", e)
            } finally {
                release(workflow.id)
            }
        }
        manualJobs[workflow.id] = job
        job.invokeOnCompletion { manualJobs.remove(workflow.id, job) }
    }

    private suspend fun runSteps(workflow: WorkflowEntity): RunResult {
        if (!workflow.enabled) return RunResult.Skipped("Workflow is disabled")
        val steps = taskRepository.getWorkflowSteps(workflow.id)
        if (steps.isEmpty()) return RunResult.Skipped("Workflow has no steps")

        var lastConversationId: String? = null
        for (step in steps) {
            val config = WorkflowConfigCodec.decode(step.type, step.configJson)
            when (step.type) {
                WorkflowStepType.DELAY -> {
                    val delayMs = (config as? WorkflowStepConfig.Delay)?.delayMs ?: 0L
                    if (delayMs > 0L) delay(delayMs)
                }
                WorkflowStepType.TASK -> {
                    val task = config as? WorkflowStepConfig.Task
                        ?: return RunResult.Failure("步骤 ${step.title} 配置无效")
                    val conversationId = UUID.randomUUID().toString()
                    conversationRepository.upsertConversation(
                        ChatEntity(
                            id = conversationId,
                            title = workflow.name,
                            modelId = task.modelId,
                            taskId = workflow.id,
                            origin = "task",
                        )
                    )
                    lastConversationId = conversationId
                    val outcome = engine.runOnce(
                        conversationId = conversationId,
                        userText = task.prompt,
                        modelId = task.modelId,
                        foregroundServiceManagedExternally = false,
                    )
                    when (outcome) {
                        is TaskExecutionEngine.Result.Success -> Unit
                        is TaskExecutionEngine.Result.Busy ->
                            return RunResult.Failure("步骤 ${step.title} 执行冲突: ${outcome.reason}")
                        is TaskExecutionEngine.Result.Failure ->
                            return RunResult.Failure("步骤 ${step.title} 失败: ${outcome.reason}")
                    }
                }
            }
        }
        return RunResult.Success(lastConversationId ?: "")
    }

    private fun reserve(workflowId: String): Boolean = synchronized(this) {
        if (!reservedWorkflowIds.add(workflowId)) return@synchronized false
        _runningWorkflowIds.value = reservedWorkflowIds.toSet()
        true
    }

    private fun release(workflowId: String) = synchronized(this) {
        reservedWorkflowIds.remove(workflowId)
        _runningWorkflowIds.value = reservedWorkflowIds.toSet()
    }
}
