package com.lxseek.chat.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOutputGuardTest {

    @Test
    fun leavesSmallOutputUntouched() {
        val text = "hello world"

        val result = McpOutputGuard.guard(text)

        assertEquals(text, result.text)
        assertFalse(result.truncated)
        assertTrue(result.estimatedTokens > 0)
    }

    @Test
    fun leavesEmptyOutputUntouched() {
        val result = McpOutputGuard.guard("")

        assertEquals("", result.text)
        assertFalse(result.truncated)
        assertEquals(0, result.estimatedTokens)
    }

    @Test
    fun truncatesOversizedOutputWithNotice() {
        // 60 'a' chars ~= 15 tokens (4 chars/token) > budget of 10.
        val oversized = "a".repeat(60)

        val result = McpOutputGuard.guard(oversized, maxTokens = 10)

        assertTrue(result.truncated)
        assertTrue(result.text.startsWith("a".repeat(40)))
        assertTrue(result.text.contains("[OUTPUT TRUNCATED - exceeded 10 token limit]"))
        assertEquals(10, result.estimatedTokens)
    }

    @Test
    fun respectsCustomBudget() {
        val text = "abcdefghijklmnopqrstuvwxyz0123456789" // 36 chars ~= 9 tokens

        assertFalse(McpOutputGuard.guard(text, maxTokens = 100).truncated)
        assertTrue(McpOutputGuard.guard(text, maxTokens = 5).truncated)
    }

    @Test
    fun doesNotSplitSurrogatePairAtBoundary() {
        // 19 'a's then an emoji (surrogate pair) then padding. Budget of 5 tokens
        // gives 20 chars, which would otherwise split the emoji at index 19/20.
        val content = "a".repeat(19) + "\uD83D\uDE00" + "b".repeat(5)

        val result = McpOutputGuard.guard(content, maxTokens = 5)

        assertTrue(result.truncated)
        assertTrue(result.text.startsWith("a".repeat(19)))
        // No lone surrogate remains in the truncated prefix.
        val prefix = result.text.substringBefore("[OUTPUT TRUNCATED")
        assertFalse(prefix.any { it.isSurrogate() })
    }
}
