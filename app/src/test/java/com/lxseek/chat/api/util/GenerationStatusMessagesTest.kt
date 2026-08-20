package com.lxseek.chat.api.util

import com.lxseek.chat.model.AttachmentMeta
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.ToolCallData
import com.lxseek.chat.model.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationStatusMessagesTest {
    @Test
    fun standaloneError_becomesSanitizedUserStatusEvent() {
        val error = ChatMessage(
            id = "error",
            text = "HTTP 500",
            images = listOf("/private/image.png"),
            thoughts = "private reasoning",
            thoughtTitle = "Thinking",
            tokenCount = 42,
            tokenUsage = TokenUsage(totalTokenCount = 42, outputTokenCount = 42),
            status = MessageStatus.ERROR,
            participant = Participant.MODEL,
            thoughtTimeMs = 100,
            modelName = "model",
            toolCall = ToolCallData("tool", "{}", "result"),
            segments = listOf(MessageSegment(type = "answer", content = "answer")),
            attachmentMeta = AttachmentMeta(),
            retryText = "retry",
        )

        val projected = projectGenerationStatusesForApi(listOf(error)).single()

        assertEquals(Participant.USER, projected.participant)
        assertEquals(MessageStatus.SUCCESS, projected.status)
        assertTrue(projected.text.contains("[Generation status: ERROR]"))
        assertTrue(projected.text.contains("HTTP 500"))
        assertTrue(projected.images.isEmpty())
        assertEquals(null, projected.thoughts)
        assertEquals(null, projected.toolCall)
        assertEquals(null, projected.segments)
        assertEquals(null, projected.attachmentMeta)
        assertEquals(null, projected.tokenUsage)
    }

    @Test
    fun stoppedStatus_isPrependedToFollowingUserMessageBeforeContextLimiting() {
        val stopped = ChatMessage(
            id = "stopped",
            text = "partial answer",
            status = MessageStatus.STOPPED,
            participant = Participant.MODEL,
        )
        val followUp = ChatMessage(
            id = "follow-up",
            text = "continue",
            participant = Participant.USER,
        )

        val projected = projectGenerationStatusesForApi(listOf(stopped, followUp))

        assertEquals(1, projected.size)
        assertEquals("follow-up", projected.single().id)
        assertTrue(projected.single().text.contains("[Generation status: STOPPED]"))
        assertTrue(projected.single().text.contains("partial answer"))
        assertTrue(projected.single().text.endsWith("continue"))
        assertEquals(
            listOf("follow-up"),
            prepareMessages(projected, contextTokenBudget = 1).map { it.id },
        )
    }

    @Test
    fun legacyErrorParticipant_isVisibleToModelWithDetails() {
        val projected = projectGenerationStatusesForApi(
            listOf(
                ChatMessage(
                    id = "legacy-error",
                    text = "legacy failure",
                    status = MessageStatus.SUCCESS,
                    participant = Participant.ERROR,
                ),
            )
        ).single()

        assertEquals(Participant.USER, projected.participant)
        assertTrue(projected.text.contains("[Generation status: ERROR]"))
        assertTrue(projected.text.contains("legacy failure"))
    }

    @Test
    fun toolProtocolStatus_isNeverRewritten() {
        val tool = ChatMessage(
            id = "tool_call",
            text = "",
            status = MessageStatus.ERROR,
            participant = Participant.MODEL,
        )

        val projected = projectGenerationStatusesForApi(listOf(tool))

        assertSame(tool, projected.single())
        assertFalse(projected.single().text.contains("[Generation status:"))
    }

    @Test
    fun successfulMessages_areReturnedUnchanged() {
        val success = ChatMessage(
            id = "success",
            text = "answer",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
        )
        val messages = listOf(success)

        assertSame(messages, projectGenerationStatusesForApi(messages))
    }
}
