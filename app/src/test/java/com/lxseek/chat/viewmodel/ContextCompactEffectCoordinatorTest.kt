package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.CompactMode
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContextCompactEffectCoordinatorTest {
    @Test
    fun manualExecutionRunsOnlyTheClaimedEffectAndSettlesIdle() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val coordinator = ContextCompactEffectCoordinator { "manual" }
        var received: RunEffect.RunCompact? = null

        val execution = coordinator.executeManual(state) { effect ->
            received = effect
            assertTrue(state.compacting.value)
            assertFalse(state.generating.value)
            assertEquals("", state.compactPreview.value)
            assertTrue(state.appendCompactPreview(effect.identity, "first"))
            assertTrue(state.appendCompactPreview(effect.identity, " second"))
            assertEquals("first second", state.compactPreview.value)
            assertFalse(
                state.appendCompactPreview(
                    effect.identity.copy(effectId = "stale-compact"),
                    " stale",
                )
            )
            assertEquals("first second", state.compactPreview.value)
            CompactResult.Created("compact-message")
        }

        assertEquals(
            RunEffect.RunCompact(
                identity = requireNotNull(received).identity,
                compactRunId = "compact_run_manual",
                mode = CompactMode.MANUAL,
            ),
            received,
        )
        assertEquals(
            ContextCompactEffectCoordinator.Execution.Settled(
                CompactResult.Created("compact-message"),
            ),
            execution,
        )
        assertFalse(state.compacting.value)
        assertEquals("", state.compactPreview.value)
        assertFalse(state.generating.value)
    }

    @Test
    fun automaticExecutionReturnsOnlyAfterContinuationIsAuthorized() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run", pass = 3)
        val coordinator = ContextCompactEffectCoordinator { "automatic" }

        val execution = coordinator.executeAutomatic(state) { effect ->
            assertEquals("run", effect.identity.runId)
            assertEquals(3, effect.identity.pass)
            assertEquals("compact_run_automatic", effect.compactRunId)
            assertTrue(state.compacting.value)
            CompactResult.NotNeeded
        }

        assertEquals(
            ContextCompactEffectCoordinator.Execution.Settled(CompactResult.NotNeeded),
            execution,
        )
        assertFalse(state.compacting.value)
        assertTrue(state.generating.value)
        assertEquals(
            listOf("RunCompact", "ResumeAfterCompact"),
            state.runtimeTraceSnapshot().takeLast(2).flatMap { it.effectTypes },
        )
        val finalizationIdentity = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = token,
            runId = "run",
            pass = 3,
            effectId = "finalize-run-3",
        )
        state.commands.requestRunFinalization(
            finalizationIdentity,
            RunStatus.COMPLETED,
            RunEndReason.MODEL_COMPLETED,
            markConversationUnread = true,
        )
        state.finishRunFinalization(finalizationIdentity, success = true)
        assertTrue(state.endGeneration(token))
    }

    @Test
    fun thrownEffectFailureSettlesRuntimeBeforePropagating() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val coordinator = ContextCompactEffectCoordinator { "failure" }

        try {
            coordinator.executeManual(state) {
                throw IllegalStateException("effect failed")
            }
            fail("Expected effect failure")
        } catch (error: IllegalStateException) {
            assertEquals("effect failed", error.message)
        }

        assertFalse(state.compacting.value)
        assertFalse(state.generating.value)
        assertEquals(
            listOf("CompactRequested", "CompactCompleted"),
            state.runtimeTraceSnapshot().map { it.commandType },
        )
        assertEquals(
            listOf("CompactFailed"),
            state.runtimeTraceSnapshot().last().effectTypes,
        )
    }

    @Test
    fun cancellationCannotStrandAManualCompactClaim() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val coordinator = ContextCompactEffectCoordinator { "cancel" }
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            coordinator.executeManual(state) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }

        entered.await()
        assertTrue(state.compacting.value)
        job.cancelAndJoin()

        assertFalse(state.compacting.value)
        assertFalse(state.generating.value)
        val next = state.commands.requestManualCompact("compact-run-next", "compact-effect-next")
        assertTrue(next != null)
        state.commands.finishCompact(
            requireNotNull(next).identity,
            com.lxseek.chat.model.CompactOutcome.NOT_NEEDED,
        )
        Unit
    }

    @Test
    fun automaticExecutionIsBusyWithoutAnActiveRunAndDoesNotInvokeEffect() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val coordinator = ContextCompactEffectCoordinator { "idle" }
        var invoked = false

        val execution = coordinator.executeAutomatic(state) {
            invoked = true
            CompactResult.NotNeeded
        }

        assertEquals(ContextCompactEffectCoordinator.Execution.Busy, execution)
        assertFalse(invoked)
        assertFalse(state.compacting.value)
    }

    @Test
    fun manualExecutionDoesNotOvertakePendingGuidance() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val queued = QueuedSend(
            id = "guidance",
            text = "next",
            modelId = "model",
            attachments = emptyList(),
            runId = "origin-run",
        )
        state.enqueueSend(queued)
        val coordinator = ContextCompactEffectCoordinator { "queued" }
        var invoked = false

        val execution = coordinator.executeManual(state) {
            invoked = true
            CompactResult.NotNeeded
        }

        assertEquals(ContextCompactEffectCoordinator.Execution.Busy, execution)
        assertFalse(invoked)
        assertEquals(listOf(queued), state.queuedSends.value)
        assertFalse(state.compacting.value)
    }
}
