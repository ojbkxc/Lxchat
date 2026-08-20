package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiMessageProjectionTest {
    @Test
    fun branchMutationProjectionPreservesPersistedThoughtAndToolSegments() {
        val segments = listOf(
            MessageSegment(
                type = "thought",
                content = "reasoning",
                durationMs = 42L,
            ),
            MessageSegment(
                type = "tool",
                toolName = "shell",
                toolArgs = """{"command":"pwd"}""",
                toolResult = "workspace",
                toolDisplayName = "Run shell",
                toolResultText = "workspace",
                toolStructuredResult = """{"path":"workspace"}""",
            ),
        )
        val entity = messageEntity(
            id = "assistant",
            text = "answer",
            toolCallJson = Json.encodeToString(segments),
        )

        val projected = entity.toUiChatMessage { value -> "formatted:$value" }

        assertEquals("formatted:answer", projected.text)
        assertEquals(segments, projected.segments)
        assertEquals("shell", projected.toolCall?.toolName)
        assertEquals("""{"command":"pwd"}""", projected.toolCall?.arguments)
        assertEquals("formatted:workspace", projected.toolCall?.result)
        assertEquals("Run shell", projected.toolCall?.displayName)
        assertEquals("workspace", projected.toolCall?.resultText)
        assertEquals("""{"path":"workspace"}""", projected.toolCall?.structuredResult)
    }

    @Test
    fun syntheticProtocolRowsRemainStructuralOnly() {
        val entity = messageEntity(
            id = Constants.TOOL_MSG_PREFIX + "call",
            text = "large provider payload",
            toolCallJson = Json.encodeToString(
                listOf(MessageSegment(type = "thought", content = "hidden"))
            ),
        )

        val projected = entity.toUiChatMessage { value -> "formatted:$value" }

        assertEquals("", projected.text)
        assertEquals(emptyList<String>(), projected.images)
        assertNull(projected.segments)
        assertNull(projected.toolCall)
    }

    private fun messageEntity(
        id: String,
        text: String,
        toolCallJson: String?,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = "user",
        text = text,
        images = listOf("image"),
        participant = Participant.MODEL,
        timestamp = 1L,
        toolCallJson = toolCallJson,
        runId = "run",
    )
}
