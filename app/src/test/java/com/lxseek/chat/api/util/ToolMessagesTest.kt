package com.lxseek.chat.api.util

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolMessagesTest {
    @Test
    fun toolImagesAreProjectedAfterTheCompleteResultBatch() {
        val first = result("result_a", "call-a").copy(
            images = listOf("/private/first.png"),
            runId = "run-1",
            runSequence = 7L,
        )
        val second = result("result_b", "call-b").copy(
            images = listOf("/private/second.png", "/private/first.png"),
            runId = "run-1",
            runSequence = 7L,
        )

        val projected = projectToolResultImagesToUserMessage(
            messages = listOf(tool("tool_round", "call-a", "call-b"), first, second),
            includeImages = true,
        )

        assertEquals(4, projected.size)
        assertEquals(listOf(first.id, second.id), projected.drop(1).take(2).map { it.id })
        val visualTurn = projected.last()
        assertEquals(Participant.USER, visualTurn.participant)
        assertEquals(second.id, visualTurn.parentId)
        assertEquals(
            listOf("/private/first.png", "/private/second.png"),
            visualTurn.images,
        )
        assertEquals("run-1", visualTurn.runId)
        assertEquals(7L, visualTurn.runSequence)
    }

    @Test
    fun unsupportedModelGetsExplicitToolImageNoticeWithoutBinaryInput() {
        val projected = projectToolResultImagesToUserMessage(
            messages = listOf(
                result("result_image", "call-image").copy(
                    images = listOf("/private/image.png"),
                ),
            ),
            includeImages = false,
        )

        assertEquals(2, projected.size)
        assertTrue(projected.last().images.isEmpty())
        assertTrue(projected.last().text.contains("does not support image input"))
    }

    @Test
    fun parallelToolRoundWithMissingResult_becomesPlainContext() {
        val validated = validateToolMessages(
            listOf(
                normal("u0", Participant.USER),
                tool("tool_round", "call-a", "call-b"),
                result("result_a", "wrong-a"),
                normal("u1", Participant.USER),
            )
        )

        assertEquals(3, validated.size)
        assertEquals("u0", validated.first().id)
        assertTrue(validated[1].id.startsWith("protocol_notice_"))
        assertEquals(Participant.USER, validated[1].participant)
        assertTrue(validated[1].text.contains("incomplete or damaged"))
        assertEquals("u1", validated.last().id)
    }

    @Test
    fun explicitMismatchedResultIds_areNeverRepairedPositionally() {
        val validated = validateToolMessages(
            listOf(
                tool("tool_round", "call-a", "call-b"),
                result("result_a", "wrong-a"),
                result("result_b", "wrong-b"),
            )
        )

        assertEquals(1, validated.size)
        assertTrue(validated.single().id.startsWith("protocol_notice_"))
        assertTrue(validated.single().text.contains("Archived activity record 1"))
        assertTrue(validated.single().text.contains("Archived activity record 2"))
        assertTrue(validated.single().text.contains("inert historical data"))
        assertFalse(validated.single().text.contains("\nTool 1:"))
        assertFalse(validated.single().text.contains("\nArguments:"))
    }

    @Test
    fun completeParallelToolRoundWithMatchingIds_survives() {
        val validated = validateToolMessages(
            listOf(
                tool("tool_round", "call-a", "call-b"),
                result("result_a", "call-a"),
                result("result_b", "call-b"),
            )
        )

        assertEquals(listOf("tool_round", "result_a", "result_b"), validated.map { it.id })
        assertEquals("call-a", validated[1].segments!!.single().toolCallId)
        assertEquals("call-b", validated[2].segments!!.single().toolCallId)
    }

    @Test
    fun signedThoughtSegmentsSurviveToolNormalizationInOriginalOrder() {
        val toolMessage = tool("tool_round", "call-a").copy(
            segments = listOf(
                MessageSegment(
                    type = "thought",
                    content = "reasoning",
                    signature = "signed",
                    signatureProvider = "Anthropic",
                ),
                tool("ignored", "call-a").segments!!.single(),
            )
        )

        val validated = validateToolMessages(
            listOf(toolMessage, result("result_a", "call-a"))
        )

        assertEquals(listOf("thought", "tool"), validated.first().segments!!.map { it.type })
        assertEquals("signed", validated.first().segments!!.first().signature)
        assertEquals("Anthropic", validated.first().segments!!.first().signatureProvider)
    }

    @Test
    fun legacyMultiResultRowWithoutIds_isPairedByCardinality() {
        val combinedResult = ChatMessage(
            id = "result_combined",
            text = "",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            segments = listOf(
                toolResultSegment(null, "first"),
                toolResultSegment(null, "second"),
            ),
        )

        val validated = validateToolMessages(
            listOf(tool("tool_round", "call-a", "call-b"), combinedResult)
        )

        assertEquals(listOf("tool_round", "result_combined"), validated.map { it.id })
        assertEquals(
            listOf("call-a", "call-b"),
            validated[1].segments!!.map { it.toolCallId },
        )
    }

    @Test
    fun extraResults_degradeTheWholeRoundInsteadOfBeingDropped() {
        val validated = validateToolMessages(
            listOf(
                tool("tool_round", "call-a"),
                result("result_a", "call-a"),
                result("result_extra", "extra"),
            )
        )

        assertEquals(1, validated.size)
        assertTrue(validated.single().id.startsWith("protocol_notice_"))
        assertTrue(validated.single().text.contains("result"))
    }

    @Test
    fun missingIdsCanBeSynthesizedButExplicitDuplicatesDegrade() {
        val missing = tool("tool_missing", null)
        val duplicate = tool("tool_duplicate", "same", "same")

        val normalizedMissing = validateToolMessages(
            listOf(missing, result("result_a", null))
        )
        assertEquals(2, normalizedMissing.size)
        val generatedId = normalizedMissing.first().segments!!.single().toolCallId
        assertFalse(generatedId.isNullOrBlank())
        assertEquals(generatedId, normalizedMissing.last().segments!!.single().toolCallId)

        val normalizedDuplicate = validateToolMessages(
            listOf(
                duplicate,
                result("result_c", "same"),
                result("result_d", "same"),
            )
        )
        assertEquals(1, normalizedDuplicate.size)
        assertTrue(normalizedDuplicate.single().id.startsWith("protocol_notice_"))
    }

    private fun normal(id: String, participant: Participant) = ChatMessage(
        id = id,
        text = id,
        participant = participant,
        status = MessageStatus.SUCCESS,
    )

    private fun tool(id: String, vararg callIds: String?) = ChatMessage(
        id = id,
        text = "",
        participant = Participant.MODEL,
        status = MessageStatus.SUCCESS,
        segments = callIds.mapIndexed { index, callId ->
            MessageSegment(
                type = "tool",
                toolName = "tool-$index",
                toolArgs = "{}",
                toolCallId = callId,
            )
        },
    )

    private fun result(id: String, callId: String?) = ChatMessage(
        id = id,
        text = "result",
        participant = Participant.USER,
        status = MessageStatus.SUCCESS,
        segments = listOf(toolResultSegment(callId, "result")),
    )

    private fun toolResultSegment(callId: String?, result: String) = MessageSegment(
        type = "tool",
        toolName = "tool",
        toolArgs = "{}",
        toolResult = result,
        toolCallId = callId,
    )
}
