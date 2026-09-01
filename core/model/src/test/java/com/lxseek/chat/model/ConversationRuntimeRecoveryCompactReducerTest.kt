package com.lxseek.chat.model

import com.lxseek.chat.model.ConversationRuntimeReducerTestFixture.CONVERSATION_ID
import com.lxseek.chat.model.ConversationRuntimeReducerTestFixture.active
import com.lxseek.chat.model.ConversationRuntimeReducerTestFixture.effectIdentity
import com.lxseek.chat.model.ConversationRuntimeReducerTestFixture.identity
import com.lxseek.chat.model.ConversationRuntimeReducerTestFixture.sendCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRuntimeRecoveryCompactReducerTest {
    @Test
    fun `Room live Run snapshot produces one deterministic recovery effect`() {
        listOf(RunStatus.ACTIVE, RunStatus.STOPPING).forEach { priorStatus ->
            val snapshot = RunRecoverySnapshot(
                conversationId = CONVERSATION_ID,
                runId = "orphaned-run",
                pass = 4,
                status = priorStatus,
            )
            val command = ConversationCommand.Recover(snapshot)

            val first = ConversationRuntimeReducer.reduce(
                RunState.Idle(CONVERSATION_ID),
                command,
            )
            val replay = ConversationRuntimeReducer.reduce(
                RunState.Idle(CONVERSATION_ID),
                command,
            )

            assertEquals(first, replay)
            val effect = first.effects.filterIsInstance<RunEffect.RecoverDurableRun>().single()
            assertEquals("orphaned-run", effect.identity.runId)
            assertEquals(4, effect.identity.pass)
            assertEquals("recover-orphaned-run-4", effect.identity.effectId)
            assertEquals(priorStatus, effect.priorStatus)
            assertTrue(first.newState is RunState.Recovering)
            assertFalse(first.effects.any { it is RunEffect.StartProviderPass })
        }
    }

    @Test
    fun `recovery rejects stale and duplicate results and becomes Idle only on durable success`() {
        val snapshot = RunRecoverySnapshot(
            conversationId = CONVERSATION_ID,
            runId = "orphaned-run",
            pass = 2,
            status = RunStatus.ACTIVE,
        )
        val requested = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            ConversationCommand.Recover(snapshot),
        )
        val effect = requested.effects.filterIsInstance<RunEffect.RecoverDurableRun>().single()
        val duplicateRequest = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.Recover(snapshot),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicateRequest.rejection)

        val stale = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.RecoveryCompleted(
                effect.identity.copy(pass = 1),
                success = true,
            ),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, stale.rejection)

        val failed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.RecoveryCompleted(effect.identity, success = false),
        )
        assertEquals(listOf(RunEffect.RunRecoveryFailed(effect.identity)), failed.effects)
        assertTrue((failed.newState as RunState.Recovering).failureReported)
        val duplicateFailure = ConversationRuntimeReducer.reduce(
            failed.newState,
            ConversationCommand.RecoveryCompleted(effect.identity, success = false),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicateFailure.rejection)

        val recovered = ConversationRuntimeReducer.reduce(
            failed.newState,
            ConversationCommand.RecoveryCompleted(effect.identity, success = true),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), recovered.newState)
        assertTrue(recovered.effects.isEmpty())
    }

    @Test
    fun `manual Compact owns Idle without becoming a generation and serializes Send`() {
        val identity = RunEffectIdentity(
            conversationId = CONVERSATION_ID,
            ownerToken = 4,
            runId = "compact-run",
            pass = 0,
            effectId = "compact-effect",
        )
        val request = ConversationCommand.CompactRequested(
            identity = identity,
            compactRunId = "compact-run",
            mode = CompactMode.MANUAL,
        )

        val started = ConversationRuntimeReducer.reduce(RunState.Idle(CONVERSATION_ID), request)

        assertEquals(
            RunState.Compacting(identity, "compact-run", CompactMode.MANUAL, null),
            started.newState,
        )
        assertEquals(
            listOf(RunEffect.RunCompact(identity, "compact-run", CompactMode.MANUAL)),
            started.effects,
        )
        val waitingSend = ConversationRuntimeReducer.reduce(
            started.newState,
            sendCommand(ownerToken = 5, runId = "send-run", effectId = "send"),
        )
        assertEquals(
            RunEffect.AwaitCompactSettlement(sendCommand(5, "send-run", "send").identity),
            waitingSend.effects.single(),
        )
        val directSend = ConversationRuntimeReducer.reduce(
            started.newState,
            sendCommand(
                ownerToken = 5,
                runId = "send-run",
                effectId = "direct",
                directOnly = true,
            ),
        )
        assertTrue(directSend.effects.single() is RunEffect.RejectSendBusy)

        val stopped = ConversationRuntimeReducer.reduce(
            started.newState,
            ConversationCommand.StopRequested(
                identity = RuntimeRunIdentity(CONVERSATION_ID, ownerToken = 4),
                coroutineAlreadySettled = true,
                requiresPersistence = false,
                effectId = null,
            ),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, stopped.rejection)
        assertSame(started.newState, stopped.newState)

        val completed = ConversationRuntimeReducer.reduce(
            started.newState,
            ConversationCommand.CompactCompleted(identity, CompactOutcome.CREATED),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), completed.newState)
        assertTrue(completed.effects.isEmpty())
    }

    @Test
    fun `automatic Compact resumes only after its exact successful result`() {
        val active = active(ownerToken = 7, runId = "run", pass = 3)
        val identity = effectIdentity(active.identity, "compact-effect")
        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.CompactRequested(
                identity = identity,
                compactRunId = "compact-run",
                mode = CompactMode.AUTOMATIC,
            ),
        )

        assertEquals(
            RunState.Compacting(
                effectIdentity = identity,
                compactRunId = "compact-run",
                mode = CompactMode.AUTOMATIC,
                resumeIdentity = active.identity,
            ),
            requested.newState,
        )
        assertEquals(
            RunEffect.RunCompact(identity, "compact-run", CompactMode.AUTOMATIC),
            requested.effects.single(),
        )

        val stale = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.CompactCompleted(
                identity.copy(effectId = "old-effect"),
                CompactOutcome.CREATED,
            ),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, stale.rejection)
        assertSame(requested.newState, stale.newState)

        val completed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.CompactCompleted(identity, CompactOutcome.NOT_NEEDED),
        )
        assertEquals(active, completed.newState)
        assertEquals(
            listOf(RunEffect.ResumeAfterCompact(identity, CompactOutcome.NOT_NEEDED)),
            completed.effects,
        )
        val duplicate = ConversationRuntimeReducer.reduce(
            completed.newState,
            ConversationCommand.CompactCompleted(identity, CompactOutcome.NOT_NEEDED),
        )
        assertFalse(duplicate.accepted)
        assertTrue(duplicate.effects.isEmpty())
    }

    @Test
    fun `failed automatic Compact returns to Active without continuation`() {
        val active = active(ownerToken = 9, runId = "run", pass = 1)
        val identity = effectIdentity(active.identity, "compact-effect")
        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.CompactRequested(
                identity,
                compactRunId = "compact-run",
                mode = CompactMode.AUTOMATIC,
            ),
        )

        val failed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.CompactCompleted(identity, CompactOutcome.FAILED),
        )

        assertEquals(active, failed.newState)
        assertEquals(
            listOf(RunEffect.CompactFailed(identity, CompactMode.AUTOMATIC)),
            failed.effects,
        )
        assertFalse(failed.effects.any { it is RunEffect.ResumeAfterCompact })
    }

    @Test
    fun `automatic Compact cannot overlap an executing tool batch`() {
        val active = active(ownerToken = 10, runId = "run", pass = 1)
        val toolRequested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.ToolBatchRequested(
                effectIdentity(active.identity, "provider-effect"),
            ),
        )
        val compactIdentity = effectIdentity(active.identity, "compact-effect")

        val compact = ConversationRuntimeReducer.reduce(
            toolRequested.newState,
            ConversationCommand.CompactRequested(
                compactIdentity,
                compactRunId = "compact-run",
                mode = CompactMode.AUTOMATIC,
            ),
        )

        assertEquals(CommandRejection.ILLEGAL_STATE, compact.rejection)
        assertSame(toolRequested.newState, compact.newState)
        assertTrue(compact.effects.isEmpty())
    }

    @Test
    fun `Stop wins over automatic Compact and rejects its late result`() {
        val active = active(ownerToken = 11, runId = "run", pass = 2)
        val identity = effectIdentity(active.identity, "compact-effect")
        val compacting = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.CompactRequested(
                identity,
                compactRunId = "compact-run",
                mode = CompactMode.AUTOMATIC,
            ),
        ).newState

        val stopping = ConversationRuntimeReducer.reduce(
            compacting,
            ConversationCommand.StopRequested(
                identity = active.identity,
                coroutineAlreadySettled = false,
                requiresPersistence = true,
                effectId = "stop",
            ),
        )
        assertTrue(stopping.newState is RunState.Stopping)
        assertEquals(
            listOf(
                RunEffect.CancelProviderPass(active.identity),
                RunEffect.FinalizeStop(effectIdentity(active.identity, "stop")),
            ),
            stopping.effects,
        )

        val late = ConversationRuntimeReducer.reduce(
            stopping.newState,
            ConversationCommand.CompactCompleted(identity, CompactOutcome.CREATED),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, late.rejection)
        assertSame(stopping.newState, late.newState)
    }

}
