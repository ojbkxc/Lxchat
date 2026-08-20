package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.Participant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingCheckpointWriterTest {
    @Test
    fun ordinaryUpdatesConflateWhileFlushWaitsForNewestDurableSnapshot() = runBlocking {
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val persisted = mutableListOf<String>()
        var first = true
        val writer = StreamingCheckpointWriter(
            scope = this,
            persist = { message ->
                if (first) {
                    first = false
                    firstWriteStarted.complete(Unit)
                    releaseFirstWrite.await()
                }
                synchronized(persisted) { persisted += message.text }
                true
            },
            onFailure = { throw AssertionError(it) },
        )

        writer.enqueue(message("one"))
        firstWriteStarted.await()
        // These calls are synchronous even though the Room writer is deliberately blocked.
        writer.enqueue(message("two"))
        writer.enqueue(message("three"))
        val flushed = async { writer.flush(message("four")) }
        releaseFirstWrite.complete(Unit)

        assertTrue(flushed.await())
        writer.cancelAndJoin()
        assertEquals("one", persisted.first())
        assertEquals("four", persisted.last())
        assertTrue(persisted.size <= 3)
    }

    @Test
    fun generationCheckpointOwnerRechecksLatestIdentityAtTheWriterBoundary() = runBlocking {
        var latestChecks = 0
        val persisted = mutableListOf<String>()
        val checkpoints = GenerationStreamingCheckpoints(
            scope = this,
            isLatestPersist = { ++latestChecks == 1 },
            persist = { message ->
                persisted += message.text
                true
            },
            onFailure = { throw AssertionError(it) },
        )

        checkpoints.persist(message("stale"), force = true)
        checkpoints.close()

        assertTrue(latestChecks >= 2)
        assertTrue(persisted.isEmpty())
    }

    private fun message(text: String) = ChatMessage(
        id = "model",
        text = text,
        participant = Participant.MODEL,
    )
}
