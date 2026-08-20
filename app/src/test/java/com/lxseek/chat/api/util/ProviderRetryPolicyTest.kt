package com.lxseek.chat.api.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRetryPolicyTest {

    @Test
    fun allowsFiveRetriesAfterInitialRequest() {
        assertEquals(5, ProviderRetryPolicy.MAX_RETRIES)
        assertEquals(6, ProviderRetryPolicy.MAX_ATTEMPTS)
    }

    @Test
    fun delayScheduleIsThreeFiveSecondThenTwoThirtySecondWaits() {
        assertEquals(
            listOf(5_000L, 5_000L, 5_000L, 30_000L, 30_000L),
            (1..ProviderRetryPolicy.MAX_RETRIES).map(ProviderRetryPolicy::delayMillis),
        )
    }

    @Test
    fun failedToGenerateOutcomeIsRecognizedCaseInsensitively() {
        assertTrue(ProviderRetryPolicy.isFailedToGenerateOutcome("Failed to generate"))
        assertTrue(ProviderRetryPolicy.isFailedToGenerateOutcome("upstream: FAILED TO GENERATE response"))
        assertFalse(ProviderRetryPolicy.isFailedToGenerateOutcome("completed"))
        assertFalse(ProviderRetryPolicy.isFailedToGenerateOutcome(null))
    }

    @Test
    fun failedToGenerateHttpBodyRetriesEvenOnOtherwiseNonRetryableStatus() {
        assertTrue(
            ProviderRetryPolicy.shouldRetryHttp(
                statusCode = 400,
                body = "upstream failed to generate",
                retryableStatusCodes = setOf(429, 502, 503, 504),
            )
        )
        assertFalse(
            ProviderRetryPolicy.shouldRetryHttp(
                statusCode = 400,
                body = "invalid API key",
                retryableStatusCodes = setOf(429, 502, 503, 504),
            )
        )
    }
}
