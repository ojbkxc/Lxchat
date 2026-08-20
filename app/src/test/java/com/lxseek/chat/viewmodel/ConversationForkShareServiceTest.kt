package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationForkShareServiceTest {
    @Test
    fun shareTextIncludesTheGenerationInputThinkingAndAnswerButNotSyntheticRows() {
        val messages = listOf(
            message("user", "Tell me why", Participant.USER, 0),
            message("model", "Because.", Participant.MODEL, 1, thoughts = "Reasoning"),
            message("tool_hidden", "protocol", Participant.MODEL, 2),
        )

        val text = formatShareText("Explanation", messages)

        assertTrue(text.contains("## User\n\nTell me why"))
        assertTrue(text.contains("## Thinking\n\nReasoning"))
        assertTrue(text.contains("## Assistant\n\nBecause."))
        assertFalse(text.contains("protocol"))
    }

    private fun message(
        id: String,
        text: String,
        participant: Participant,
        sequence: Long,
        thoughts: String? = null,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        text = text,
        thoughts = thoughts,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = sequence,
        runId = "run",
        runSequence = sequence,
    )
}
