package com.lxseek.chat.data.repository

import com.lxseek.chat.data.local.ChatDao
import com.lxseek.chat.data.local.MessageStreamCheckpoint
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.TokenUsage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRepositoryCheckpointTest {

    @Test
    fun checkpointUpdatesOnlyMutableStreamingFields() = runTest {
        val dao = mockk<ChatDao>()
        val captured = slot<MessageStreamCheckpoint>()
        coEvery { dao.updateMessageCheckpoint(capture(captured)) } returns 1
        val repository = ConversationRepository(dao)
        val segment = MessageSegment(type = "answer", content = "partial answer")

        val updated = repository.updateStreamingMessageCheckpoint(
            ChatMessage(
                id = "model-message",
                parentId = "user-message",
                text = "partial answer",
                images = listOf("/generated/image.png"),
                thoughts = "partial thought",
                thoughtTitle = "Reasoning",
                tokenCount = 42,
                tokenUsage = TokenUsage(
                    totalTokenCount = 42,
                    inputTokenCount = 30,
                    cachedInputTokenCount = 10,
                    uncachedInputTokenCount = 20,
                    outputTokenCount = 12,
                    reasoningTokenCount = 4,
                ),
                status = MessageStatus.THINKING,
                participant = Participant.MODEL,
                timestamp = 1234,
                thoughtTimeMs = 987,
                modelName = "provider:model",
                segments = listOf(segment),
            )
        )

        assertTrue(updated)
        assertEquals("model-message", captured.captured.id)
        assertEquals("partial answer", captured.captured.text)
        assertEquals(listOf("/generated/image.png"), captured.captured.images)
        assertEquals("partial thought", captured.captured.thoughts)
        assertEquals("Reasoning", captured.captured.thoughtTitle)
        assertEquals(42, captured.captured.tokenCount)
        assertEquals(30, captured.captured.inputTokenCount)
        assertEquals(10, captured.captured.cachedInputTokenCount)
        assertEquals(20, captured.captured.uncachedInputTokenCount)
        assertEquals(12, captured.captured.outputTokenCount)
        assertEquals(4, captured.captured.reasoningTokenCount)
        assertEquals(MessageStatus.THINKING, captured.captured.status)
        assertEquals(987L, captured.captured.thoughtTimeMs)
        assertEquals(
            listOf(segment),
            Json.decodeFromString<List<MessageSegment>>(captured.captured.toolCallJson!!),
        )
        coVerify(exactly = 1) { dao.updateMessageCheckpoint(any()) }
    }

    @Test
    fun missingPlaceholderIsNotRecreated() = runTest {
        val dao = mockk<ChatDao>()
        coEvery { dao.updateMessageCheckpoint(any()) } returns 0
        val repository = ConversationRepository(dao)

        val updated = repository.updateStreamingMessageCheckpoint(
            ChatMessage(
                id = "deleted-message",
                text = "must not be resurrected",
                status = MessageStatus.SENDING,
                participant = Participant.MODEL,
            )
        )

        assertFalse(updated)
        coVerify(exactly = 1) { dao.updateMessageCheckpoint(any()) }
    }
}
