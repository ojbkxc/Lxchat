package com.lxseek.chat.api.util

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.api.OpenAiChatRequest
import com.lxseek.chat.api.openai.requireValidWireFormat
import com.lxseek.chat.viewmodel.buildCompactSummaryInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactTest {
    private fun message(id: String, text: String, participant: Participant) = ChatMessage(
        id = id,
        text = text,
        participant = participant,
        status = MessageStatus.SUCCESS,
    )

    @Test
    fun nearestCompactIsTheOnlyBoundary() {
        val projected = applyNearestContextCompact(
            listOf(
                message("u0", "old", Participant.USER),
                message("compact_first", "summary one", Participant.MODEL),
                message("u1", "middle", Participant.USER),
                message("compact_second", "summary two", Participant.MODEL),
                message("u2", "new", Participant.USER),
            )
        )
        assertEquals(listOf("summary two", "new"), projected.map { it.text })
        assertEquals(Participant.USER, projected.first().participant)
    }

    @Test
    fun deletingNewestCompactNaturallyRevealsPreviousBoundary() {
        val withoutNewest = listOf(
            message("u0", "old", Participant.USER),
            message("compact_first", "summary one", Participant.MODEL),
            message("u1", "middle", Participant.USER),
            message("u2", "new", Participant.USER),
        )
        assertEquals(
            listOf("summary one", "middle", "new"),
            applyNearestContextCompact(withoutNewest).map { it.text },
        )
    }

    @Test
    fun logicalSplitMergesSameRolesAndKeepsToolRoundAtomic() {
        val history = listOf(
            message("u0", "one", Participant.USER),
            message("u1", "two", Participant.USER),
            message("a0", "answer", Participant.MODEL),
            message("tool_call", "", Participant.MODEL),
            message("result_call", "result", Participant.USER),
            message("a1", "continuation", Participant.MODEL),
        )
        val split = splitLogicalContext(history, 1)
        assertEquals(2, split.logicalMessageCount)
        assertEquals(listOf("a0", "tool_call", "result_call", "a1"), split.suffix.map { it.id })
        assertEquals(listOf("u0", "u1"), split.prefix.map { it.id })
    }

    @Test
    fun logicalSplitWithLargeRetentionLeavesNoPrefix() {
        val history = listOf(
            message("u0", "one", Participant.USER),
            message("a0", "answer", Participant.MODEL),
        )
        val split = splitLogicalContext(history, 2)
        assertTrue(split.prefix.isEmpty())
        assertEquals(history, split.suffix)
    }

    @Test
    fun noCompactLeavesHistoryUntouched() {
        val history = listOf(message("u0", "old", Participant.USER))
        assertTrue(applyNearestContextCompact(history) === history)
    }

    @Test
    fun contextUsageSharesCanonicalRoleAndToolRoundAccounting() {
        val history = listOf(
            message("u0", "one", Participant.USER),
            message("u1", "two", Participant.USER),
            message("a0", "answer", Participant.MODEL),
            message("tool_call", "", Participant.MODEL).copy(
                segments = listOf(
                    com.lxseek.chat.model.MessageSegment(
                        type = "tool",
                        toolName = "test_tool",
                        toolArgs = "{}",
                        toolCallId = "call-1",
                    )
                )
            ),
            message("result_call", "result", Participant.USER).copy(
                segments = listOf(
                    com.lxseek.chat.model.MessageSegment(
                        type = "tool",
                        toolName = "test_tool",
                        toolArgs = "{}",
                        toolResult = "result",
                        toolCallId = "call-1",
                    )
                )
            ),
            message("a1", "continuation", Participant.MODEL),
        )

        val usage = contextWindowUsage(history, tokenBudget = 4_096)

        assertEquals(2, usage.logicalMessageCount)
        assertEquals(4_096, usage.tokenBudget)
        assertTrue(usage.estimatedTokenCount > 0)
        assertEquals(
            usage.estimatedTokenCount.toFloat() / usage.tokenBudget,
            usage.progress,
        )
        assertTrue(!usage.hasCompactBoundary)
    }

    @Test
    fun retainedContextIdsMatchProviderUserLedSuffix() {
        val history = listOf(
            message("u0", "one", Participant.USER),
            message("u1", "two", Participant.USER),
            message("a0", "answer", Participant.MODEL),
            message("u2", "latest", Participant.USER),
        )

        val retained = contextWindowRetainedMessageIds(history, tokenBudget = 20)

        assertEquals(linkedSetOf("u2"), retained)
    }

    @Test
    fun contextUsageStartsAtNearestCompactBoundary() {
        val history = listOf(
            message("u0", "old", Participant.USER),
            message("compact_boundary", "summary", Participant.MODEL),
            message("u1", "new", Participant.USER),
        )

        val usage = contextWindowUsage(history, tokenBudget = 4_096)

        assertEquals(1, usage.logicalMessageCount)
        assertTrue(usage.hasCompactBoundary)
    }

    @Test
    fun retainedContextIdsKeepCompactBoundaryAndVerbatimSuffixVisible() {
        val history = listOf(
            message("u0", "old", Participant.USER),
            message("compact_boundary", "summary", Participant.MODEL),
            message("u1", "new", Participant.USER),
            message("a1", "answer", Participant.MODEL),
        )

        val retained = contextWindowRetainedMessageIds(history, tokenBudget = 4_096)

        assertEquals(
            linkedSetOf("compact_boundary", "u1", "a1"),
            retained,
        )
    }

    @Test
    fun compactSummaryRequestEndsWithEphemeralUserInputForStrictProviders() {
        val prefixEndingInAssistant = listOf(
            message("u0", "question", Participant.USER),
            message("a0", "answer", Participant.MODEL),
        )

        val compactInput = buildCompactSummaryInput(prefixEndingInAssistant)
        val prepared = prepareMessages(compactInput, contextTokenBudget = Int.MAX_VALUE)
        val request = OpenAiChatRequest(
            model = "deepseek-test",
            messages = convertToOpenAiMessages(prepared),
        )

        request.requireValidWireFormat("DeepSeek")
        assertEquals(Participant.USER, compactInput.last().participant)
        assertTrue(compactInput.last().id.startsWith("compact_summary_request_"))
        assertEquals(prefixEndingInAssistant, compactInput.dropLast(1))
    }

    @Test
    fun selectedPathExpansionIncludesEveryParallelToolResultExactlyOnce() {
        val user = message("u", "start", Participant.USER).copy(runId = "run", runSequence = 0)
        val model = message("m", "", Participant.MODEL).copy(
            parentId = "u",
            runId = "run",
            runSequence = 1,
        )
        val tool = message("tool_round", "", Participant.MODEL).copy(
            parentId = "m",
            runId = "run",
            runSequence = 2,
        )
        val resultOne = message("result_one", "one", Participant.USER).copy(
            parentId = "tool_round",
            runId = "run",
            runSequence = 3,
        )
        val resultTwo = message("result_two", "two", Participant.USER).copy(
            parentId = "tool_round",
            runId = "run",
            runSequence = 4,
        )

        assertEquals(
            listOf("u", "tool_round", "result_one", "result_two", "m"),
            expandSelectedToolProtocolRows(
                selectedPath = listOf(user, model, tool, resultTwo),
                allMessages = listOf(user, model, tool, resultOne, resultTwo),
            ).map { it.id },
        )
    }
}
