package com.lxseek.chat.util

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacySafeLoggingTest {
    @Test
    fun `throwable summary keeps diagnostic type and frames without uncontrolled messages`() {
        val failure = IOException("TOP_SECRET_THROWABLE_MESSAGE").apply {
            initCause(IllegalStateException("TOP_SECRET_CAUSE_MESSAGE"))
            stackTrace = arrayOf(
                StackTraceElement("com.lxseek.chat.SafeComponent", "run", "SafeComponent.kt", 42),
            )
        }

        val summary = DebugLog.safeThrowableSummary(failure)

        assertTrue(summary.contains("exception=java.io.IOException"))
        assertTrue(summary.contains("SafeComponent.kt:42"))
        assertFalse(summary.contains("TOP_SECRET_THROWABLE_MESSAGE"))
        assertFalse(summary.contains("TOP_SECRET_CAUSE_MESSAGE"))
    }

    @Test
    fun `throwable summary has a bounded stack frame count`() {
        val failure = IOException("omitted").apply {
            stackTrace = Array(100) { index ->
                StackTraceElement("SafeComponent$index", "run", "SafeComponent.kt", index)
            }
        }

        val summary = DebugLog.safeThrowableSummary(failure)

        assertTrue(summary.lineSequence().count { it.startsWith("\tat ") } <= 24)
        assertFalse(summary.contains("SafeComponent99"))
    }
}
