package com.lxseek.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenUsageTest {
    @Test
    fun cumulativeSnapshotsReplaceWithinRequestAndRequestsAddAcrossToolRounds() {
        val accumulator = RequestTokenUsageAccumulator()

        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(usage(total = 12, input = 8, output = 4))
        accumulator.observeRequestSnapshot(usage(total = 20, input = 13, output = 7))
        assertEquals(20, accumulator.snapshot()?.totalTokenCount)
        accumulator.finishRequest()

        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(usage(total = 9, input = 6, output = 3))
        accumulator.finishRequest()

        val total = accumulator.snapshot()
        assertEquals(29, total?.totalTokenCount)
        assertEquals(19, total?.inputTokenCount)
        assertEquals(10, total?.outputTokenCount)
    }

    @Test
    fun missingBreakdownInAnyRequestKeepsAggregateBreakdownUnknown() {
        val accumulator = RequestTokenUsageAccumulator()

        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(usage(total = 10, input = 7, output = 3))
        accumulator.finishRequest()
        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(TokenUsage(totalTokenCount = 5))
        accumulator.finishRequest()

        val total = accumulator.snapshot()
        assertEquals(15, total?.totalTokenCount)
        assertNull(total?.inputTokenCount)
        assertNull(total?.outputTokenCount)
    }

    private fun usage(total: Int, input: Int, output: Int) = TokenUsage(
        totalTokenCount = total,
        inputTokenCount = input,
        outputTokenCount = output,
    )
}
