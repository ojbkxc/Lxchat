package com.lxseek.chat.automation

import android.content.Context
import com.lxseek.chat.data.local.LoopEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.TaskRepository
import com.lxseek.chat.service.LoopWorker
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-scoped owner of persistent per-conversation Loops.
 *
 * [AutomationScheduler] observes the Room loop flow and owns alarm scheduling. A [LoopWorker]
 * executes exactly one fired cycle, then this manager advances persistent state; that Room write
 * causes the scheduler to arm the next alarm. Configuration changes increment
 * [LoopEntity.revision], so a turn already in flight can never overwrite a stop/restart that
 * happened while the model was running.
 */
class LoopManager(
    private val taskRepository: TaskRepository,
    private val conversationRepository: ConversationRepository,
    private val engine: TaskExecutionEngine,
    private val cancelWork: (String) -> Unit = {},
    private val cancelAlarm: suspend (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    executionCoordinator: ConversationExecutionCoordinator? = null,
    private val executionGate: AutomationExecutionGate = AutomationExecutionGate(),
) {
    /** Production convenience constructor; the primary constructor stays fully JVM-testable. */
    constructor(
        context: Context,
        taskRepository: TaskRepository,
        conversationRepository: ConversationRepository,
        engine: TaskExecutionEngine,
        executionCoordinator: ConversationExecutionCoordinator,
        executionGate: AutomationExecutionGate,
    ) : this(
        taskRepository = taskRepository,
        conversationRepository = conversationRepository,
        engine = engine,
        cancelWork = { conversationId -> LoopWorker.cancel(context, conversationId) },
        executionCoordinator = executionCoordinator,
        executionGate = executionGate,
    )

    sealed interface StartResult {
        data class Started(val loop: LoopEntity) : StartResult
        data class Conflict(val existing: LoopEntity) : StartResult
        data class Invalid(val reason: String) : StartResult
        data object ConversationMissing : StartResult
    }

    sealed interface StopResult {
        data object Stopped : StopResult
        data object AlreadyStopped : StopResult
        data object NotFound : StopResult
    }

    sealed interface ExecutionResult {
        data object NotFound : ExecutionResult
        data object Inactive : ExecutionResult
        data class NotDue(val nextFireAt: Long) : ExecutionResult
        data class Superseded(val current: LoopEntity?) : ExecutionResult
        data class Finished(
            val generationResult: TaskExecutionEngine.Result,
            val loop: LoopEntity,
        ) : ExecutionResult
    }

    private val stateMutex = Mutex()
    // Use the SHARED coordinator when injected (production), so a Loop fire on conversation X
    // is serialized against a user Send on X. Falls back to a private instance for JVM tests
    // that don't supply one (S4: same-conversation race fix).
    private val executionCoordinator = executionCoordinator ?: ConversationExecutionCoordinator()

    private val _runningConversationIds = MutableStateFlow<Set<String>>(emptySet())
    val runningConversationIds = _runningConversationIds.asStateFlow()

    fun getLoop(conversationId: String): Flow<LoopEntity?> =
        taskRepository.getLoop(conversationId)

    fun loopForConversation(conversationId: String): Flow<LoopEntity?> = getLoop(conversationId)

    /** Cancels every queued/running occurrence and removes its alarm before graph import. */
    suspend fun cancelAllExecutionsForImport() {
        taskRepository.getAllLoopsSnapshot().forEach { loop ->
            cancelWorkBestEffort(loop.conversationId)
            runCatching { cancelAlarm(loop.conversationId) }
                .onFailure {
                    DebugLog.w(
                        "LoopManager",
                        "Failed to cancel alarm for ${loop.conversationId}",
                        it,
                    )
                }
        }
    }

    suspend fun startLoop(
        conversationId: String,
        intervalMs: Long,
        prompt: String? = null,
        maxCycles: Int = LoopPolicy.DEFAULT_MAX_CYCLES,
    ): StartResult {
        if (conversationId.isBlank()) return StartResult.Invalid("conversationId must not be blank")
        LoopPolicy.validate(intervalMs, maxCycles)?.let { return StartResult.Invalid(it) }

        return stateMutex.withLock {
            if (conversationRepository.getConversation(conversationId) == null) {
                return@withLock StartResult.ConversationMissing
            }
            val existing = taskRepository.getLoop(conversationId).first()
            if (existing?.active == true) return@withLock StartResult.Conflict(existing)

            val revision = if (existing == null) 0L else LoopPolicy.nextRevision(existing.revision)
            val loop = LoopEntity(
                conversationId = conversationId,
                intervalMs = intervalMs,
                prompt = LoopPolicy.normalizePrompt(prompt),
                nextFireAt = LoopPolicy.nextFireAt(clock(), intervalMs),
                cycleCount = 0,
                maxCycles = maxCycles,
                active = true,
                revision = revision,
            )
            taskRepository.upsertLoop(loop)
            StartResult.Started(loop)
        }
    }

    /**
     * Stops the timer, and only the timer.
     *
     * A Loop is a scheduler: its sole job is deciding *when* a cycle fires. Stopping it must
     * therefore (a) prevent all future cycles and (b) leave any generation already in flight
     * completely untouched, exactly as if the user had sent that turn by hand.
     *
     * Persisting `active=false` with a bumped revision is what actually stops the schedule: the
     * revision guard makes every in-flight and queued cycle claim fail, and AutomationScheduler
     * observes the Room write and drops the alarm.
     *
     * Cancelling WorkManager is a *separate* concern and is deliberately conditional.
     * [LoopWorker.cancel] uses `cancelAllWorkByTag`, which cannot distinguish a queued worker from
     * the one currently hosting a generation, so cancelling while a cycle runs would kill that
     * generation. When a cycle is in flight the revision bump alone is sufficient, and the running
     * worker is left to finish; the durable state already says inactive, so it schedules nothing.
     */
    suspend fun stopLoop(conversationId: String): StopResult = stateMutex.withLock {
        val existing = taskRepository.getLoop(conversationId).first()
            ?: return@withLock StopResult.NotFound
        // A generation belonging to this conversation is mid-flight. Cancelling tagged work would
        // terminate it, which Stop must never do.
        val generationInFlight = conversationId in _runningConversationIds.value
        if (!existing.active) {
            // The final cycle claims itself by persisting active=false *before* generation starts,
            // so an inactive Loop can still own a queued worker. Clean it up, unless a generation
            // is currently running under that same tag.
            if (!generationInFlight) cancelWorkBestEffort(conversationId)
            return@withLock StopResult.AlreadyStopped
        }

        taskRepository.upsertLoop(
            existing.copy(
                active = false,
                revision = LoopPolicy.nextRevision(existing.revision),
            )
        )
        // Drop the worker that would host the *next* cycle. Skipped while a generation is in
        // flight because the tag cannot separate the two; the revision bump already neutralizes
        // any queued claim in that case.
        if (!generationInFlight) cancelWorkBestEffort(conversationId)
        StopResult.Stopped
    }

    private fun cancelWorkBestEffort(conversationId: String) {
        runCatching { cancelWork(conversationId) }
            .onFailure { DebugLog.w("LoopManager", "Failed to cancel work for $conversationId", it) }
    }

    /**
     * Executes at most one due cycle. Normal model failures are returned as [Finished] and count
     * as a cycle; only infrastructure exceptions escape for [com.lxseek.chat.service.LoopWorker]
     * to retry. This avoids replaying a model turn that may already have been persisted.
     */
    suspend fun executeByConversationId(
        conversationId: String,
        expectedFireAt: Long = 0L,
    ): ExecutionResult = executionGate.withExecution {
        executionCoordinator.withAutomationConversationLock(conversationId) {
            executeWithAutomationGuardsHeld(conversationId, expectedFireAt)
        }
    }

    private suspend fun executeWithAutomationGuardsHeld(
        conversationId: String,
        expectedFireAt: Long,
    ): ExecutionResult {
        val snapshot = stateMutex.withLock {
            val loop = taskRepository.getLoop(conversationId).first()
                ?: return@withLock null
            if (!loop.active) return@withLock loop

            val maxCycles = loop.maxCycles ?: LoopPolicy.DEFAULT_MAX_CYCLES
            if (
                LoopPolicy.validate(loop.intervalMs, maxCycles) != null ||
                loop.cycleCount >= maxCycles
            ) {
                val inactive = loop.copy(
                    active = false,
                    maxCycles = maxCycles,
                    nextFireAt = 0L,
                    revision = LoopPolicy.nextRevision(loop.revision),
                )
                taskRepository.upsertLoop(inactive)
                return@withLock inactive
            }
            if (loop.maxCycles == null) {
                loop.copy(maxCycles = maxCycles).also { taskRepository.upsertLoop(it) }
            } else {
                loop
            }
        }

        if (snapshot == null) return ExecutionResult.NotFound
        if (!snapshot.active) return ExecutionResult.Inactive
        if (expectedFireAt > 0L && snapshot.nextFireAt != expectedFireAt) {
            return ExecutionResult.Superseded(snapshot)
        }

        val now = clock()
        if (snapshot.nextFireAt > now) {
            return ExecutionResult.NotDue(snapshot.nextFireAt)
        }

        val conversation = conversationRepository.getConversation(conversationId)
        if (conversation == null) {
            stateMutex.withLock { taskRepository.deleteLoop(conversationId) }
            return ExecutionResult.NotFound
        }

        // Persistently claim this cycle *before* any model/tool side effect. If the process
        // dies after this write, a WorkManager retry sees a different nextFireAt and cannot
        // replay the same turn. The next alarm is provisionally scheduled now; successful
        // completion below moves it to one full interval after completion and bumps the cycle
        // count. cycleCount is intentionally NOT bumped here: an infrastructure failure that
        // triggers a worker retry would otherwise consume a cycle for a turn that never ran,
        // so a maxCycles=1 loop could die before its first real generation (A2).
        val claimed = stateMutex.withLock {
            val latest = taskRepository.getLoop(conversationId).first()
            if (
                latest == null || !latest.active || latest.revision != snapshot.revision ||
                latest.cycleCount != snapshot.cycleCount || latest.nextFireAt != snapshot.nextFireAt
            ) {
                return@withLock null
            }
            val maxCycles = latest.maxCycles ?: LoopPolicy.DEFAULT_MAX_CYCLES
            latest.copy(
                maxCycles = maxCycles,
                nextFireAt = LoopPolicy.nextFireAt(clock(), latest.intervalMs),
            ).also { taskRepository.upsertLoop(it) }
        } ?: return ExecutionResult.Superseded(
            taskRepository.getLoop(conversationId).first()
        )

        _runningConversationIds.update { it + conversationId }
        val generationResult = try {
            engine.runOnceWithAutomationGuardsHeld(
                conversationId = conversationId,
                userText = LoopPolicy.promptForExecution(claimed.prompt),
                modelId = conversation.modelId,
                systemPromptOverride = null,
                foregroundServiceManagedExternally = true,
                precondition = {
                    val latest = taskRepository.getLoop(conversationId).first()
                    latest != null && latest.revision == claimed.revision &&
                        latest.cycleCount == claimed.cycleCount
                },
            )
        } catch (e: CancellationException) {
            throw e
        } finally {
            _runningConversationIds.update { it - conversationId }
        }

        val updated = stateMutex.withLock {
            val latest = taskRepository.getLoop(conversationId).first()
            if (
                latest == null || latest.revision != claimed.revision ||
                latest.cycleCount != claimed.cycleCount
            ) {
                return@withLock null
            }

            // Completion counts the cycle (a model failure that returned as Finished also
            // counts, matching the documented semantics). The final cycle deactivates the
            // loop here, after the generation ran, instead of at claim time.
            val maxCycles = claimed.maxCycles ?: LoopPolicy.DEFAULT_MAX_CYCLES
            val nextCount = latest.cycleCount + 1
            val remainsActive = latest.active && nextCount < maxCycles
            val next = if (latest.active) {
                latest.copy(
                    cycleCount = nextCount,
                    active = remainsActive,
                    nextFireAt = if (remainsActive) {
                        LoopPolicy.nextFireAt(clock(), latest.intervalMs)
                    } else {
                        0L
                    },
                )
            } else {
                latest
            }
            if (next != latest) taskRepository.upsertLoop(next)
            next
        }

        return if (updated == null) {
            ExecutionResult.Superseded(taskRepository.getLoop(conversationId).first())
        } else {
            ExecutionResult.Finished(generationResult, updated)
        }
    }

    /**
     * Ensures a one-shot Loop alarm is never lost when infrastructure fails before a cycle can be
     * claimed. Called only after WorkManager exhausts its bounded retry budget.
     */
    suspend fun deferAfterInfrastructureFailure(conversationId: String): Boolean =
        stateMutex.withLock {
            val latest = taskRepository.getLoop(conversationId).first()
                ?: return@withLock false
            if (!latest.active) return@withLock false
            val maxCycles = latest.maxCycles ?: LoopPolicy.DEFAULT_MAX_CYCLES
            if (LoopPolicy.validate(latest.intervalMs, maxCycles) != null) {
                taskRepository.upsertLoop(
                    latest.copy(
                        active = false,
                        maxCycles = maxCycles,
                        nextFireAt = 0L,
                        revision = LoopPolicy.nextRevision(latest.revision),
                    )
                )
                return@withLock false
            }
            taskRepository.upsertLoop(
                latest.copy(
                    maxCycles = maxCycles,
                    nextFireAt = LoopPolicy.nextFireAt(clock(), latest.intervalMs),
                )
            )
            true
        }
}
