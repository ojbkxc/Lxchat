package com.lxseek.chat.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LoopPolicyTest {
    @Test
    fun acceptsInclusiveSafetyBoundaries() {
        assertNull(LoopPolicy.validate(LoopPolicy.MIN_INTERVAL_MS, LoopPolicy.MIN_MAX_CYCLES))
        assertNull(LoopPolicy.validate(LoopPolicy.MAX_INTERVAL_MS, LoopPolicy.MAX_MAX_CYCLES))
    }

    @Test
    fun rejectsUnsafeCadenceAndCycleCounts() {
        assertNotNull(LoopPolicy.validate(LoopPolicy.MIN_INTERVAL_MS - 1, 10))
        assertNotNull(LoopPolicy.validate(LoopPolicy.MAX_INTERVAL_MS + 1, 10))
        assertNotNull(LoopPolicy.validate(LoopPolicy.MIN_INTERVAL_MS, 0))
        assertNotNull(LoopPolicy.validate(LoopPolicy.MIN_INTERVAL_MS, 101))
    }

    @Test
    fun blankPromptFallsBackToBoundedDefault() {
        assertEquals(LoopPolicy.DEFAULT_PROMPT, LoopPolicy.promptForExecution(null))
        assertEquals(LoopPolicy.DEFAULT_PROMPT, LoopPolicy.promptForExecution("   "))
        assertEquals("check again", LoopPolicy.promptForExecution("  check again  "))
    }

    @Test
    fun timeAndRevisionArithmeticDoNotOverflow() {
        assertEquals(Long.MAX_VALUE, LoopPolicy.nextFireAt(Long.MAX_VALUE - 2, 3))
        assertEquals(0L, LoopPolicy.nextRevision(Long.MAX_VALUE))
    }
}
