package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ConversationCommand
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStateRegistryTest {
    @Test
    fun stoppingSlot_survivesUiOwnerReplacement() = runBlocking {
        val registry = ConversationStateRegistry()
        val firstOwner = Any()
        registry.attachUiCallbacks(firstOwner) { }
        val state = registry.getOrCreate("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")

        val stopped = state.stop()
        registry.detachUiCallbacks(firstOwner)
        val secondOwner = Any()
        registry.attachUiCallbacks(secondOwner) { }

        assertSame(state, registry.getOrCreate("conversation"))
        assertTrue(state.generating.value)
        assertTrue(state.stopping.value)
        assertTrue("conversation" in registry.activeConversationIds.value)

        assertFalse(state.endGeneration(token))
        val completion = ConversationCommand.PersistenceSettled(
            identity = requireNotNull(stopped.finalizationEffect).identity,
            success = true,
        )
        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.SETTLED,
            state.finishStopFinalization(completion),
        )
        assertFalse(state.generating.value)
        assertFalse(state.stopping.value)
        assertFalse("conversation" in registry.activeConversationIds.value)
    }

    @Test
    fun staleOwnerCannotDetachNewerUiCallbacks() {
        val registry = ConversationStateRegistry()
        val firstOwner = Any()
        val secondOwner = Any()
        var secondOwnerActive = false
        registry.attachUiCallbacks(firstOwner) { state ->
            state.onActive = { }
        }
        registry.attachUiCallbacks(secondOwner) { state ->
            state.onActive = { secondOwnerActive = true }
        }
        registry.detachUiCallbacks(firstOwner)

        registry.getOrCreate("conversation").acquireForSend()

        assertTrue(secondOwnerActive)
    }

    @Test
    fun remove_disposesExternalJobAndStreamsWithoutFabricatingUserStop() {
        val registry = ConversationStateRegistry()
        val state = registry.getOrCreate("conversation")
        val token = state.acquireForSend()!!
        val externalJob = Job()
        var streamCancelCount = 0
        state.streamScope.register(GenerationCancelHandle { streamCancelCount += 1 })
        assertTrue(state.attachGenerationJob(token, externalJob))

        registry.remove("conversation")

        assertTrue(externalJob.isCancelled)
        assertEquals(1, streamCancelCount)
        assertFalse(
            state.runtimeTraceSnapshot().any { it.commandType == "StopRequested" },
        )
    }

    @Test
    fun repeatedUiReplacementKeepsOnlyTheLatestPendingDrainHandoff() = runBlocking {
        val registry = ConversationStateRegistry()
        val firstOwner = Any()
        val secondOwner = Any()
        var firstDrainCount = 0
        var secondDrainCount = 0
        registry.attachUiCallbacks(firstOwner) { state ->
            state.onQueueDrainRequested = { firstDrainCount += 1 }
        }
        val state = registry.getOrCreate("conversation")
        val token = state.acquireForSend()!!
        state.enqueueSend(QueuedSend("guidance", "text", "model", emptyList(), "run"))

        registry.detachUiCallbacks(firstOwner)
        assertEquals(1, registry.pendingDrainHandoffCount())

        registry.attachUiCallbacks(secondOwner) { boundState ->
            boundState.onQueueDrainRequested = { secondDrainCount += 1 }
        }
        assertEquals(0, registry.pendingDrainHandoffCount())
        registry.detachUiCallbacks(secondOwner)
        assertEquals(1, registry.pendingDrainHandoffCount())

        assertTrue(state.endGeneration(token))
        withTimeout(5_000) {
            while (secondDrainCount == 0) yield()
        }

        assertEquals(0, firstDrainCount)
        assertEquals(1, secondDrainCount)
        assertEquals(0, registry.pendingDrainHandoffCount())
        registry.remove("conversation")
    }
}
