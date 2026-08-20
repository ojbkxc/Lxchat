package com.lxseek.chat.automation

import com.lxseek.chat.data.local.ChatEntity
import com.lxseek.chat.data.local.LoopEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LoopManagerTest {
    private val taskRepository = mockk<TaskRepository>()
    private val conversationRepository = mockk<ConversationRepository>()
    private val engine = mockk<TaskExecutionEngine>()
    private val stored = MutableStateFlow<LoopEntity?>(null)
    private val cancelled = mutableListOf<String>()
    private var now = 1_000_000L

    @Before
    fun setUp() {
        every { taskRepository.getLoop(any()) } returns stored
        coEvery { taskRepository.upsertLoop(any()) } coAnswers {
            stored.value = firstArg()
        }
        coEvery { taskRepository.deleteLoop(any()) } coAnswers {
            stored.value = null
        }
        coEvery { conversationRepository.getConversation("conversation") } returns
            ChatEntity(id = "conversation", title = "Conversation", modelId = "OpenAI:model")
    }

    @Test
    fun startLoop_persistsAndSchedulesThenRejectsAnActiveConflict() = runTest {
        val manager = manager()

        val started = manager.startLoop(
            conversationId = "conversation",
            intervalMs = LoopPolicy.MIN_INTERVAL_MS,
            prompt = "  inspect  ",
        )

        assertTrue(started is LoopManager.StartResult.Started)
        assertEquals("inspect", stored.value?.prompt)
        assertEquals(now + LoopPolicy.MIN_INTERVAL_MS, stored.value?.nextFireAt)
        assertEquals(LoopPolicy.DEFAULT_MAX_CYCLES, stored.value?.maxCycles)
        val conflict = manager.startLoop("conversation", LoopPolicy.MIN_INTERVAL_MS)
        assertTrue(conflict is LoopManager.StartResult.Conflict)
    }

    @Test
    fun stopLoop_incrementsRevisionAndCancelsDurableWork() = runTest {
        stored.value = loop(revision = 7L)
        val manager = manager()

        assertEquals(LoopManager.StopResult.Stopped, manager.stopLoop("conversation"))
        assertFalse(stored.value!!.active)
        assertEquals(8L, stored.value!!.revision)
        assertEquals(listOf("conversation"), cancelled)
        assertEquals(LoopManager.StopResult.AlreadyStopped, manager.stopLoop("conversation"))
        assertEquals(listOf("conversation", "conversation"), cancelled)
    }

    @Test
    fun stopLoop_cancelsWorkerEvenWhenFinalCycleAlreadyMarkedInactive() = runTest {
        stored.value = loop(maxCycles = 1).copy(active = false, cycleCount = 1, nextFireAt = 0L)
        val manager = manager()

        assertEquals(LoopManager.StopResult.AlreadyStopped, manager.stopLoop("conversation"))

        assertEquals(listOf("conversation"), cancelled)
    }

    @Test
    fun successfulCycleAdvancesAndSchedulesFromCompletionTime() = runTest {
        stored.value = loop(maxCycles = 2, revision = 3L)
        coEvery {
            engine.runOnceWithAutomationGuardsHeld("conversation", "Continue.", "OpenAI:model", null, true, any())
        } returns TaskExecutionEngine.Result.Success("model-message", "done")
        val manager = manager()

        val result = manager.executeByConversationId("conversation")

        assertTrue(result is LoopManager.ExecutionResult.Finished)
        assertEquals(1, stored.value!!.cycleCount)
        assertTrue(stored.value!!.active)
        assertEquals(now + LoopPolicy.MIN_INTERVAL_MS, stored.value!!.nextFireAt)
        assertEquals(3L, stored.value!!.revision)
    }

    @Test
    fun modelFailureStillConsumesFinalCycleWithoutImmediateRetry() = runTest {
        stored.value = loop(maxCycles = 1)
        coEvery {
            engine.runOnceWithAutomationGuardsHeld("conversation", "Continue.", "OpenAI:model", null, true, any())
        } returns TaskExecutionEngine.Result.Failure("provider failed")
        val manager = manager()

        val result = manager.executeByConversationId("conversation")

        assertTrue(result is LoopManager.ExecutionResult.Finished)
        assertEquals(1, stored.value!!.cycleCount)
        assertFalse(stored.value!!.active)
        assertEquals(0L, stored.value!!.nextFireAt)
    }

    @Test
    fun busyRuntimeAdmissionIsAnExplicitCompletedCycleOutcome() = runTest {
        stored.value = loop(maxCycles = 2)
        coEvery {
            engine.runOnceWithAutomationGuardsHeld(
                "conversation", "Continue.", "OpenAI:model", null, true, any(),
            )
        } returns TaskExecutionEngine.Result.Busy()
        val manager = manager()

        val result = manager.executeByConversationId("conversation")

        val finished = result as LoopManager.ExecutionResult.Finished
        assertTrue(finished.generationResult is TaskExecutionEngine.Result.Busy)
        assertEquals(1, finished.loop.cycleCount)
    }

    @Test
    fun loopWaitsAtTheExecutionGateBeforeTakingTheConversationLease() = runTest {
        stored.value = loop(maxCycles = 2)
        coEvery {
            engine.runOnceWithAutomationGuardsHeld(
                "conversation", "Continue.", "OpenAI:model", null, true, any(),
            )
        } returns TaskExecutionEngine.Result.Success("model", "done")
        val gate = AutomationExecutionGate()
        val coordinator = ConversationExecutionCoordinator()
        val importEntered = CompletableDeferred<Unit>()
        val releaseImport = CompletableDeferred<Unit>()
        val import = launch {
            gate.withExclusiveImport {
                importEntered.complete(Unit)
                releaseImport.await()
            }
        }
        importEntered.await()
        val cycle = async {
            manager(gate, coordinator).executeByConversationId("conversation")
        }

        runCurrent()
        assertFalse(coordinator.isExecuting("conversation"))
        coVerify(exactly = 0) {
            engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any())
        }

        releaseImport.complete(Unit)
        import.join()
        assertTrue(cycle.await() is LoopManager.ExecutionResult.Finished)
    }

    @Test
    fun stopDuringGenerationCannotBeOverwrittenByStaleCompletion() = runTest {
        stored.value = loop(maxCycles = 5, revision = 10L)
        coEvery {
            engine.runOnceWithAutomationGuardsHeld("conversation", "Continue.", "OpenAI:model", null, true, any())
        } coAnswers {
            stored.value = stored.value!!.copy(active = false, revision = 11L)
            TaskExecutionEngine.Result.Success("model-message", "done")
        }
        val manager = manager()

        val result = manager.executeByConversationId("conversation")

        assertTrue(result is LoopManager.ExecutionResult.Superseded)
        // The pre-generation claim no longer consumes a cycle (A2): a superseded or
        // infrastructure-failed cycle must not burn maxCycles budget, or a maxCycles=1
        // loop could die before its first real generation.
        assertEquals(0, stored.value!!.cycleCount)
        assertFalse(stored.value!!.active)
        assertEquals(11L, stored.value!!.revision)
    }

    @Test
    fun retryOfClaimedOccurrenceNeverReplaysModelSideEffects() = runTest {
        val scheduledAt = now
        stored.value = loop(nextFireAt = scheduledAt, maxCycles = 3)
        coEvery {
            engine.runOnceWithAutomationGuardsHeld("conversation", "Continue.", "OpenAI:model", null, true, any())
        } returns TaskExecutionEngine.Result.Success("model-message", "done")
        val manager = manager()

        val first = manager.executeByConversationId("conversation", scheduledAt)
        val retry = manager.executeByConversationId("conversation", scheduledAt)

        assertTrue(first is LoopManager.ExecutionResult.Finished)
        assertTrue(retry is LoopManager.ExecutionResult.Superseded)
        assertEquals(1, stored.value!!.cycleCount)
        coVerify(exactly = 1) {
            engine.runOnceWithAutomationGuardsHeld("conversation", "Continue.", "OpenAI:model", null, true, any())
        }
    }

    @Test
    fun exhaustedInfrastructureFailureCanDeferAndRearmLoop() = runTest {
        stored.value = loop(nextFireAt = now - 1L, maxCycles = 3)
        val manager = manager()

        assertTrue(manager.deferAfterInfrastructureFailure("conversation"))

        assertEquals(now + LoopPolicy.MIN_INTERVAL_MS, stored.value!!.nextFireAt)
        assertTrue(stored.value!!.active)
    }

    @Test
    fun notDueWorkerNeverCallsTheModelAndRepairsItsSchedule() = runTest {
        stored.value = loop(nextFireAt = now + 5_000L)
        val manager = manager()

        val result = manager.executeByConversationId("conversation")

        assertEquals(LoopManager.ExecutionResult.NotDue(now + 5_000L), result)
        coVerify(exactly = 0) {
            engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any())
        }
    }

    private fun kotlinx.coroutines.test.TestScope.manager(
        executionGate: AutomationExecutionGate = AutomationExecutionGate(),
        executionCoordinator: ConversationExecutionCoordinator? = null,
    ) = LoopManager(
        taskRepository = taskRepository,
        conversationRepository = conversationRepository,
        engine = engine,
        cancelWork = { cancelled += it },
        clock = { now },
        executionCoordinator = executionCoordinator,
        executionGate = executionGate,
    )

    private fun loop(
        nextFireAt: Long = now,
        maxCycles: Int = LoopPolicy.DEFAULT_MAX_CYCLES,
        revision: Long = 0L,
    ) = LoopEntity(
        conversationId = "conversation",
        intervalMs = LoopPolicy.MIN_INTERVAL_MS,
        nextFireAt = nextFireAt,
        maxCycles = maxCycles,
        active = true,
        revision = revision,
    )
}
