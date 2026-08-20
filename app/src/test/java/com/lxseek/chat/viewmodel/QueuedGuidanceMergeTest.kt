package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.AttachmentItem
import com.lxseek.chat.model.AttachmentMeta
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedGuidanceMergeTest {
    @Test
    fun drainMergesFifoTextAndAttachmentOwnershipIntoOneBubble() {
        val firstMeta = Json.encodeToString(
            AttachmentMeta(listOf(AttachmentItem(type = "image", fileName = "one.png")))
        )
        val secondMeta = Json.encodeToString(
            AttachmentMeta(listOf(AttachmentItem(type = "file", fileName = "two.txt")))
        )
        val merged = mergeQueuedGuidance(
            listOf(
                queued("one", "first").copy(
                    modelId = "older-model",
                    preparedImages = listOf("one.png"),
                    preparedAttachmentMetaJson = firstMeta,
                    preparedOwnedPaths = listOf("one-owned"),
                ),
                queued("two", "second").copy(
                    modelId = "latest-model",
                    preparedImages = listOf("two.png"),
                    preparedAttachmentMetaJson = secondMeta,
                    preparedOwnedPaths = listOf("two-owned"),
                ),
            )
        )

        assertEquals("one", merged.id)
        assertEquals("first\n\nsecond", merged.text)
        assertEquals("latest-model", merged.modelId)
        assertEquals(listOf("one.png", "two.png"), merged.preparedImages)
        assertEquals(listOf("one-owned", "two-owned"), merged.preparedOwnedPaths)
        assertEquals(
            listOf("one.png", "two.txt"),
            Json.decodeFromString<AttachmentMeta>(
                checkNotNull(merged.preparedAttachmentMetaJson)
            ).items.map(AttachmentItem::fileName),
        )
    }

    @Test
    fun mergeFailureRestoresTheExactOriginalLeaseBatch() {
        val store = GuidanceLeaseStore { "lease" }
        val first = queued("one", "first").copy(preparedAttachmentMetaJson = "{")
        val second = queued("two", "second")
        store.enqueue(first)
        store.enqueue(second)
        val lease = checkNotNull(store.claim())

        assertThrows(SerializationException::class.java) {
            mergeQueuedGuidance(lease.batch)
        }
        assertTrue(store.settle(lease.id, durable = false))
        assertEquals(listOf(first, second), store.queuedSends.value)
    }

    private fun queued(id: String, text: String) = QueuedSend(
        id = id,
        text = text,
        modelId = "model",
        attachments = emptyList(),
        runId = "old-run",
    )
}
