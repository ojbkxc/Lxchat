package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunEffectIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRoundEffectCoordinatorTest {
    @Test
    fun `effect order is batch then results then durable commit then continuation`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = ToolRoundEffectCoordinator(callbacks(events))

        val batch = coordinator.acceptValidatedBatch(PROVIDER_IDENTITY)
        val commit = coordinator.completeBatch(batch.identity)
        val continuation = coordinator.commitRound { identity ->
            assertEquals(commit.identity, identity)
            events += "persist"
        }

        assertEquals(commit.identity, continuation.identity)
        assertEquals(
            listOf("request", "complete", "persist", "settled:true"),
            events,
        )
    }

    @Test
    fun `durable failure reports failure and never authorizes continuation`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = ToolRoundEffectCoordinator(callbacks(events))
        val batch = coordinator.acceptValidatedBatch(PROVIDER_IDENTITY)
        coordinator.completeBatch(batch.identity)

        val failure = runCatching {
            coordinator.commitRound {
                events += "persist"
                error("database failed")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            listOf("request", "complete", "persist", "settled:false"),
            events,
        )
    }

    @Test
    fun `runtime rejection prevents tool execution or continuation`() = runTest {
        val callbacks = callbacks(mutableListOf()).copy(
            onToolBatchRequested = { null },
        )
        val coordinator = ToolRoundEffectCoordinator(callbacks)

        val failure = runCatching {
            coordinator.acceptValidatedBatch(PROVIDER_IDENTITY)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    @Test
    fun `wrong callback identity fails closed`() = runTest {
        val callbacks = callbacks(mutableListOf()).copy(
            onToolBatchRequested = { identity ->
                RunEffect.ExecuteToolBatch(identity.copy(effectId = "wrong"))
            },
        )
        val coordinator = ToolRoundEffectCoordinator(callbacks)

        val failure = runCatching {
            coordinator.acceptValidatedBatch(PROVIDER_IDENTITY)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    private fun callbacks(events: MutableList<String>) = GenerationCallbacks(
        onStreamUpdate = { _: ChatMessage -> },
        onLoadingChange = {},
        onStreamClear = {},
        isLatestPersist = { true },
        onProviderPassRequested = { RunEffect.StartProviderPass(it) },
        onProviderPassCompleted = { identity, result ->
            RunEffect.ProviderPassAccepted(identity, result)
        },
        onRunFinalizationRequested = { identity, status, reason, markUnread ->
            RunEffect.FinalizeRun(identity, status, reason, markUnread)
        },
        onRunFinalizationCompleted = { _, _ -> true },
        onToolBatchRequested = { identity ->
            events += "request"
            RunEffect.ExecuteToolBatch(
                identity.copy(effectId = "tool-batch-${identity.effectId}"),
            )
        },
        onToolBatchCompleted = { identity ->
            events += "complete"
            RunEffect.CommitToolRound(
                identity.copy(effectId = "tool-round-${identity.effectId}"),
            )
        },
        onToolRoundCommitted = { identity, success ->
            events += "settled:$success"
            if (success) RunEffect.ContinueProviderPass(identity)
            else RunEffect.ToolRoundCommitFailed(identity)
        },
    )

    private companion object {
        val PROVIDER_IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 1,
            runId = "run",
            pass = 2,
            effectId = "provider-2-0",
        )
    }
}
