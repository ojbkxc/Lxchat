package com.lxseek.chat.ui.chat.message

import com.lxseek.chat.model.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenUsagePresentationTest {
    @Test
    fun completeBreakdown_presentsInputCachedSubsetAndOutput() {
        val presentation = tokenUsagePresentation(
            TokenUsage(
                totalTokenCount = 140,
                inputTokenCount = 100,
                cachedInputTokenCount = 25,
                uncachedInputTokenCount = 75,
                outputTokenCount = 40,
            )
        )

        assertEquals(
            TokenUsagePresentation(
                input = 100,
                cachedInput = 25,
                output = 40,
            ),
            presentation,
        )
    }

    @Test
    fun unknownCacheSplit_remainsUnknownInsteadOfBecomingZero() {
        val presentation = tokenUsagePresentation(
            TokenUsage(
                totalTokenCount = 140,
                inputTokenCount = 100,
                outputTokenCount = 40,
            )
        )

        assertEquals(
            TokenUsagePresentation(
                input = 100,
                cachedInput = null,
                output = 40,
            ),
            presentation,
        )
    }

    @Test
    fun missingInputOrOutput_isDerivedFromReportedTotal() {
        assertEquals(
            TokenUsagePresentation(input = 100, cachedInput = null, output = 40),
            tokenUsagePresentation(
                TokenUsage(totalTokenCount = 140, inputTokenCount = 100),
            ),
        )
        assertEquals(
            TokenUsagePresentation(input = 100, cachedInput = null, output = 40),
            tokenUsagePresentation(
                TokenUsage(totalTokenCount = 140, outputTokenCount = 40),
            ),
        )
    }

    @Test
    fun legacyTotalOnlyUsage_doesNotInventInputOutputSplit() {
        val unknown = TokenUsagePresentation(input = null, cachedInput = null, output = null)
        assertEquals(unknown, tokenUsagePresentation(TokenUsage(totalTokenCount = 140)))
        assertEquals(unknown, tokenUsagePresentation(null))
    }
}
