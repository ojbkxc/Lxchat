package com.lxseek.chat.api.gemini

import com.lxseek.chat.api.GenerationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiStreamTerminationTest {
    @Test
    fun finishReasonProvesSemanticCompletion() {
        val termination = geminiStreamTermination(
            sawDone = false,
            finishReason = normalizeGeminiFinishReason("STOP"),
            producedContent = true,
        )

        assertTrue(termination.sawTerminalMarker)
        assertEquals("stop", termination.stopReason)
        assertNull(termination.toError("Gemini"))
    }

    @Test
    fun eofWithoutFinishReasonIsIncompleteAndOnlyEmptyAttemptRetries() {
        val empty = geminiStreamTermination(false, null, false)
        val partial = geminiStreamTermination(false, null, true)

        assertTrue(empty.isRetryable)
        assertFalse(partial.isRetryable)
        assertTrue(partial.toError("Gemini") is GenerationError.IncompleteStream)
    }

    @Test
    fun outputCapIsReportedWithoutPointlessReplay() {
        val termination = geminiStreamTermination(
            sawDone = false,
            finishReason = normalizeGeminiFinishReason("MAX_TOKENS"),
            producedContent = true,
        )

        assertFalse(termination.isRetryable)
        assertTrue(termination.toError("Gemini") is GenerationError.OutputTruncated)
    }

    @Test
    fun streamErrorRetriesOnlyBeforeVisibleOutput() {
        val error = GenerationError.Api(null, "failed_to_generate", "failed to generate")

        assertTrue(geminiStreamTermination(false, null, false, error).isRetryable)
        assertFalse(geminiStreamTermination(false, null, true, error).isRetryable)
    }

    @Test
    fun timeoutUsesSharedTerminationPolicy() {
        val termination = geminiStreamTermination(
            sawDone = false,
            finishReason = null,
            producedContent = false,
            timedOut = true,
        )

        assertTrue(termination.isRetryable)
        assertEquals(GenerationError.Timeout, termination.toError("Gemini"))
    }

    @Test
    fun terminalMarkerCannotCompleteAnInvalidOpenFunctionCall() {
        val error = GenerationError.SseParse(
            rawLine = "functionCall",
            cause = "Gemini returned a blank or invalid tool name",
        )
        val termination = geminiStreamTermination(
            sawDone = true,
            finishReason = "stop",
            producedContent = false,
            streamError = error,
            toolCallInFlight = true,
        )

        assertTrue(termination.isRetryable)
        assertEquals(error, termination.toError("Gemini"))
    }
}
