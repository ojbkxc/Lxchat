package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.ToolCallData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationToolRoundBuilderTest {
    @Test
    fun `builds matching provider path and Room graph with deterministic hierarchy`() {
        val ids = ArrayDeque(listOf("tool", "result-1", "result-2"))
        val builder = GenerationToolRoundBuilder(
            newId = { ids.removeFirst() },
            nowMs = { 100L },
        )
        val calls = listOf(
            ToolCallData("first", "{}", "raw-one", toolCallId = "call-1"),
            ToolCallData(
                "second",
                "{\"value\":2}",
                "raw-two",
                signature = "signature",
                toolCallId = "call-2",
            ),
        )

        val round = builder.build(
            previousMessageId = "assistant",
            conversationId = "conversation",
            runId = "run",
            modelName = "model",
            providerName = "provider",
            calls = calls,
            completedSegments = emptyList(),
        )

        assertEquals(3, round.pathMessages.size)
        assertEquals(3, round.entities.size)
        assertEquals("tool_tool", round.pathMessages[0].id)
        assertEquals("assistant", round.pathMessages[0].parentId)
        assertEquals("result_result-1", round.pathMessages[1].id)
        assertEquals("tool_tool", round.pathMessages[1].parentId)
        assertEquals("raw-one", round.pathMessages[1].text)
        assertEquals(Participant.USER, round.pathMessages[1].participant)
        assertEquals("result_result-2", round.lastResultId)
        assertEquals(listOf(100L, 101L, 102L), round.entities.map { it.timestamp })
        assertEquals(round.pathMessages.map { it.id }, round.entities.map { it.id })
        assertTrue(round.entities[0].toolCallJson.orEmpty().contains("raw-one"))
        assertTrue(round.entities[2].toolCallJson.orEmpty().contains("signatureProvider"))
        assertTrue(round.entities[2].toolCallJson.orEmpty().contains("provider"))
    }
}
