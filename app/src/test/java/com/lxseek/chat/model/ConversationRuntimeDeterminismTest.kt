package com.lxseek.chat.model

import com.lxseek.chat.model.ConversationRuntimeReducerTestFixture.CONVERSATION_ID
import com.lxseek.chat.model.ConversationRuntimeReducerTestFixture.effectIdentity
import com.lxseek.chat.model.ConversationRuntimeReducerTestFixture.identity
import com.lxseek.chat.model.ConversationRuntimeReducerTestFixture.sendCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConversationRuntimeDeterminismTest {
    @Test
    fun `same command sequence produces equal states and effects`() {
        val send = sendCommand(ownerToken = 1, runId = "run", effectId = "input")
        val commands = listOf(
            send,
            ConversationCommand.InputPersisted(send.identity),
            ConversationCommand.StopRequested(
                identity(ownerToken = 1, runId = "run", pass = 0),
                coroutineAlreadySettled = false,
                requiresPersistence = true,
                effectId = "effect",
            ),
            ConversationCommand.PersistenceSettled(
                effectIdentity(identity(1, "run", 0), "effect"),
                success = true,
            ),
            ConversationCommand.CoroutineSettled(identity(1, "run", 0)),
        )

        fun replay(): List<Transition> {
            var state: RunState = RunState.Idle(CONVERSATION_ID)
            return commands.map { command ->
                ConversationRuntimeReducer.reduce(state, command).also { state = it.newState }
            }
        }

        assertEquals(replay(), replay())
        assertFalse(replay().any { it.rejection != null })
    }
}
