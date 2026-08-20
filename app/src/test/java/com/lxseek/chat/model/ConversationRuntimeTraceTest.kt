package com.lxseek.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRuntimeTraceTest {
    @Test
    fun `trace is bounded ordered and contains only structured metadata`() {
        var now = 100L
        val trace = ConversationRuntimeTrace(capacity = 2, clock = { now++ })
        var state: RunState = RunState.Idle(CONVERSATION_ID)

        fun apply(command: ConversationCommand) {
            val transition = ConversationRuntimeReducer.reduce(state, command)
            trace.record(state, command, transition)
            state = transition.newState
        }

        apply(ConversationCommand.AcquireSlot(identity(runId = null)))
        apply(ConversationCommand.BindRun(identity(runId = "run", pass = 3)))
        apply(
            ConversationCommand.StopRequested(
                identity = identity(runId = "run", pass = 3),
                coroutineAlreadySettled = false,
                requiresPersistence = true,
                effectId = "effect",
            ),
        )

        val entries = trace.snapshot()
        assertEquals(listOf(2L, 3L), entries.map { it.sequence })
        assertEquals(listOf(101L, 102L), entries.map { it.timestamp })
        assertEquals(listOf("BindRun", "StopRequested"), entries.map { it.commandType })
        assertEquals("run", entries.last().runId)
        assertEquals(3, entries.last().pass)
        assertEquals("effect", entries.last().effectId)
        assertEquals(
            listOf("CancelProviderPass", "FinalizeStop"),
            entries.last().effectTypes,
        )
        assertTrue(entries.all { it.conversationIdHash.length == 24 })
        assertTrue(entries.all { it.conversationIdHash == entries.first().conversationIdHash })
        assertFalse(entries.any { it.conversationIdHash.contains(CONVERSATION_ID) })
    }

    @Test
    fun `conversation digest is deterministic without retaining the raw id`() {
        val first = ConversationRuntimeTrace.hashConversationId(CONVERSATION_ID)
        val second = ConversationRuntimeTrace.hashConversationId(CONVERSATION_ID)
        val different = ConversationRuntimeTrace.hashConversationId("another-id")

        assertEquals(first, second)
        assertFalse(first == different)
        assertFalse(first.contains(CONVERSATION_ID))
    }

    @Test
    fun `recovery trace records only its identified metadata`() {
        val trace = ConversationRuntimeTrace(capacity = 4, clock = { 123L })
        val idle = RunState.Idle(CONVERSATION_ID)
        val recover = ConversationCommand.Recover(
            RunRecoverySnapshot(
                conversationId = CONVERSATION_ID,
                runId = "orphaned-run",
                pass = 2,
                status = RunStatus.STOPPING,
            ),
        )
        val requested = ConversationRuntimeReducer.reduce(idle, recover)

        trace.record(idle, recover, requested)

        val entry = trace.snapshot().single()
        assertEquals("Recover", entry.commandType)
        assertEquals("Recovering", entry.newState)
        assertEquals("orphaned-run", entry.runId)
        assertEquals(2, entry.pass)
        assertEquals("recover-orphaned-run-2", entry.effectId)
        assertEquals(listOf("RecoverDurableRun"), entry.effectTypes)
        assertFalse(entry.conversationIdHash.contains(CONVERSATION_ID))
    }

    private fun identity(runId: String?, pass: Int = 0) = RuntimeRunIdentity(
        conversationId = CONVERSATION_ID,
        ownerToken = 1,
        runId = runId,
        pass = pass,
    )

    private companion object {
        const val CONVERSATION_ID = "conversation-id-sentinel"
    }
}
