package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.CompactOutcome
import com.lxseek.chat.model.ConversationRuntimeReducer
import com.lxseek.chat.model.ProviderPassResult
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.model.RunState
import com.lxseek.chat.model.RuntimeRunIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRuntimeCommandPortTest {
    @Test
    fun sendAndProviderResultsRemainIdentifiedAndMailboxApplied() = runBlocking {
        val harness = Harness()
        try {
            val requested = harness.port.requestSend(
                proposedRunId = "run",
                effectId = "input",
                directOnly = true,
                hasPendingGuidance = false,
            )
            val input = requested.effects.filterIsInstance<RunEffect.PersistAcceptedInput>().single()
            assertEquals(effectIdentity("input"), input.identity)

            assertTrue(harness.port.finishInputPersistence(input.identity).newState is RunState.Active)

            val providerIdentity = effectIdentity("provider")
            assertEquals(
                providerIdentity,
                harness.port.requestProviderPass(providerIdentity)?.identity,
            )
            assertEquals(
                providerIdentity,
                harness.port.finishProviderPass(
                    providerIdentity,
                    ProviderPassResult.COMPLETED_TEXT,
                )?.identity,
            )
            assertNull(
                harness.port.finishProviderPass(
                    providerIdentity,
                    ProviderPassResult.COMPLETED_TEXT,
                ),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun automaticCompactUsesTheCurrentRunIdentityAndReturnsThroughTheMailbox() = runBlocking {
        val harness = Harness()
        try {
            val send = harness.port.requestSend("run", "input", true, false)
            val input = send.effects.filterIsInstance<RunEffect.PersistAcceptedInput>().single()
            harness.port.finishInputPersistence(input.identity)

            val compact = harness.port.requestAutomaticCompact("compact-run", "compact")!!

            assertEquals(effectIdentity("compact"), compact.identity)
            assertTrue(
                harness.port.finishCompact(
                    compact.identity,
                    CompactOutcome.NOT_NEEDED,
                ).accepted,
            )
            assertTrue(harness.state is RunState.Active)
        } finally {
            harness.close()
        }
    }

    private class Harness {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var state: RunState = RunState.Idle(CONVERSATION_ID)
            private set

        private val mailbox = ConversationCommandMailbox(scope) { factory ->
            ConversationRuntimeReducer.reduce(state, factory.create()).also { transition ->
                if (transition.accepted) state = transition.newState
            }
        }

        val port = ConversationRuntimeCommandPort(
            conversationId = CONVERSATION_ID,
            mailbox = mailbox,
            nextOwnerToken = { OWNER_TOKEN },
            currentRunIdentity = { state.identityOrNull() },
        )

        fun close() {
            scope.cancel()
        }

        private fun RunState.identityOrNull(): RuntimeRunIdentity? = when (this) {
            is RunState.Idle,
            is RunState.Recovering,
            -> null
            is RunState.Preparing -> ownerIdentity
            is RunState.Active -> identity
            is RunState.Compacting -> resumeIdentity
            is RunState.Finalizing -> identity
            is RunState.Stopping -> identity
        }
    }

    private fun effectIdentity(effectId: String) = RunEffectIdentity(
        conversationId = CONVERSATION_ID,
        ownerToken = OWNER_TOKEN,
        runId = "run",
        pass = 0,
        effectId = effectId,
    )

    private companion object {
        const val CONVERSATION_ID = "conversation"
        const val OWNER_TOKEN = 1L
    }
}
