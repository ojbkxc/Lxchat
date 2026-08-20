package com.lxseek.chat.api.util

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTokenEstimatorTest {
    @Test
    fun multilingualTextIsDeterministicAndNonZero() {
        val text = "hello world 你好，世界 👋"
        val first = ContextTokenEstimator.estimateText(text)

        assertTrue(first >= 10)
        assertEquals(first, ContextTokenEstimator.estimateText(text))
    }

    @Test
    fun toolArgumentsAndResultsContributeToCost() {
        val plain = message("u", "start", Participant.USER)
        val tool = message(Constants.TOOL_MSG_PREFIX + "1", "", Participant.MODEL).copy(
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "file_read",
                    toolArgs = """{"path":"/a/very/long/path"}""",
                )
            )
        )
        val result = message(Constants.RESULT_MSG_PREFIX + "1", "", Participant.USER).copy(
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "file_read",
                    toolArgs = "{}",
                    toolResult = "result ".repeat(100),
                )
            )
        )

        assertTrue(
            ContextTokenEstimator.estimate(listOf(plain, tool, result)) >
                ContextTokenEstimator.estimate(listOf(plain))
        )
    }

    @Test
    fun contextLimitNeverSplitsLatestToolRoundAndRetainsUserAnchor() {
        val user = message("u", "start", Participant.USER)
        val tool = message(Constants.TOOL_MSG_PREFIX + "1", "", Participant.MODEL)
        val result = message(Constants.RESULT_MSG_PREFIX + "1", "large ".repeat(100), Participant.USER)

        assertEquals(
            listOf("u", tool.id, result.id),
            limitContext(listOf(user, tool, result), contextTokenBudget = 1).map { it.id },
        )
    }

    private fun message(id: String, text: String, participant: Participant) = ChatMessage(
        id = id,
        text = text,
        participant = participant,
    )
}
