package com.lxseek.chat.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellDurableJobExecutorTest {
    private val executor = ShellDurableJobExecutor()

    @Test
    fun onlyConchTerminalStatesFinishPolling() {
        listOf("succeeded", "failed", "stopped", "interrupted").forEach { state ->
            assertTrue(executor.isTerminalJobPayload("""{"state":"$state"}"""))
        }
        assertFalse(executor.isTerminalJobPayload("""{"state":"running"}"""))
        assertFalse(executor.isTerminalJobPayload("""{"state":"stopping"}"""))
    }

    @Test
    fun explicitErrorFinishesButMalformedPayloadDoesNot() {
        assertTrue(executor.isTerminalJobPayload("""{"error":"job not found"}"""))
        assertFalse(executor.isTerminalJobPayload(""))
        assertFalse(executor.isTerminalJobPayload("not-json"))
        assertFalse(executor.isTerminalJobPayload("{}"))
    }
}
