package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RuntimeRunIdentity
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRuntimeResourceOwnershipTest {
    @Test
    fun streamClear_commitsFinalMessageBeforeRemovingOverlay() {
        val resources = activeResources()
        val finalMessage = message(MessageStatus.SUCCESS)
        val events = mutableListOf<String>()
        resources.streamUpdate(OWNER_TOKEN, finalMessage)

        val committed = resources.streamMessageForClear(OWNER_TOKEN)!!
        assertEquals(finalMessage, resources.streamingMessage.value)
        assertEquals(finalMessage, committed)
        events += "commit"
        resources.clearStreamingMessage()

        events += "cleared"
        assertEquals(listOf("commit", "cleared"), events)
        assertNull(resources.streamingMessage.value)
    }

    @Test
    fun terminalStreamUpdate_replacesStaleAnsweringSnapshotBeforeClearCommit() {
        val resources = activeResources()
        val answering = message(MessageStatus.SENDING)
        val terminal = answering.copy(status = MessageStatus.SUCCESS)
        resources.streamUpdate(OWNER_TOKEN, answering)

        resources.streamUpdate(OWNER_TOKEN, terminal)
        val committed = resources.streamMessageForClear(OWNER_TOKEN)!!
        resources.clearStreamingMessage()

        assertEquals(MessageStatus.SUCCESS, committed.status)
        assertEquals(terminal, committed)
        assertNull(resources.streamingMessage.value)
    }

    @Test
    fun streamClear_keepsStoppedOverlay() {
        val resources = activeResources()
        val stopped = message(MessageStatus.STOPPED)
        resources.streamUpdate(OWNER_TOKEN, stopped)

        assertNull(resources.streamMessageForClear(OWNER_TOKEN))

        assertEquals(stopped, resources.streamingMessage.value)
    }

    @Test
    fun generationJobAndStreamsHaveOneCancellableResourceOwner() {
        val resources = ConversationRuntimeResources()
        val installed = Job()
        val rejected = Job()
        var streamCancellations = 0
        resources.streamScope.register(GenerationCancelHandle { streamCancellations += 1 })

        assertTrue(resources.installGenerationJob(installed))
        assertFalse(resources.installGenerationJob(rejected))

        resources.cancelStreamsAnd(resources.currentGenerationJob())

        assertTrue(installed.isCancelled)
        assertFalse(rejected.isCancelled)
        assertEquals(1, streamCancellations)
        rejected.cancel()
    }

    @Test
    fun queuedGuidanceRemainsMemoryOnlyAndPreservesOrder() {
        val store = GuidanceLeaseStore { "lease" }
        val first = queued("one", "first")
        val second = queued("two", "second")

        store.enqueue(first)
        store.enqueue(second)

        assertEquals(listOf("one", "two"), store.queuedSends.value.map { it.id })
        val lease = store.claim()!!
        assertEquals(listOf(first, second), lease.batch)
        assertTrue(store.queuedSends.value.isEmpty())
        assertTrue(store.settle(lease.id, durable = true))
    }

    @Test
    fun failedGuidanceLeaseReturnsTheExactBatchToTheFront() {
        val store = GuidanceLeaseStore { "lease" }
        val first = queued("one", "first")
        val second = queued("two", "second")
        store.enqueue(first)
        store.enqueue(second)

        val lease = store.claim()!!
        val newer = queued("three", "third")
        store.enqueue(newer)

        assertTrue(store.settle(lease.id, durable = false))
        assertEquals(listOf(first, second, newer), store.queuedSends.value)
        assertFalse(store.settle(lease.id, durable = false))
    }

    @Test
    fun disposalCleansPendingAndFailedInflightGuidanceOwnership() {
        val store = GuidanceLeaseStore { "lease" }
        val pendingFile = java.nio.file.Files.createTempFile("lxchat-pending", ".tmp").toFile()
        val claimedFile = java.nio.file.Files.createTempFile("lxchat-claimed", ".tmp").toFile()
        try {
            val pending = queued("pending", "pending", pendingFile.absolutePath)
            val claimed = queued("claimed", "claimed", claimedFile.absolutePath)
            store.enqueue(claimed)
            val lease = store.claim()!!
            store.enqueue(pending)

            store.disposePending().forEach(QueuedSend::deleteOwnedFiles)

            assertFalse(pendingFile.exists())
            assertTrue(claimedFile.exists())
            assertTrue(store.settle(lease.id, durable = false))
            assertFalse(claimedFile.exists())
        } finally {
            pendingFile.delete()
            claimedFile.delete()
        }
    }

    @Test
    fun durableGuidanceLeaseTransfersFilesToRoomEvenAfterDisposal() {
        val store = GuidanceLeaseStore { "lease" }
        val durableFile = java.nio.file.Files.createTempFile("lxchat-durable", ".tmp").toFile()
        store.enqueue(queued("durable", "durable", durableFile.absolutePath))
        val lease = store.claim()!!
        store.disposePending()

        try {
            assertTrue(store.settle(lease.id, durable = true))
            assertTrue(durableFile.exists())
        } finally {
            durableFile.delete()
        }
    }

    private fun activeResources(): ConversationRuntimeResources =
        ConversationRuntimeResources().also { resources ->
            resources.activate(
                RuntimeRunIdentity("conversation", OWNER_TOKEN),
                loading = false,
            )
        }

    private fun message(status: MessageStatus) = ChatMessage(
        id = "model",
        text = "complete",
        participant = Participant.MODEL,
        status = status,
    )

    private fun queued(id: String, text: String, ownedPath: String? = null) = QueuedSend(
        id = id,
        text = text,
        modelId = "model",
        attachments = emptyList(),
        runId = "old-run",
        preparedOwnedPaths = listOfNotNull(ownedPath),
    )

    private companion object {
        const val OWNER_TOKEN = 1L
    }
}
