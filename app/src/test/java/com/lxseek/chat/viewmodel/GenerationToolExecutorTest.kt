package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.tool.ToolExecutionEvent
import com.lxseek.chat.tool.ToolProvider
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationToolExecutorTest {
    @Test
    fun `completed result retains the authorized batch and call identities`() = runTest {
        val provider = FakeToolProvider()
        val executor = GenerationToolExecutor.forTest(listOf(provider))
        val events = mutableListOf<ToolExecutionEvent>()

        val executed = executor.execute(
            call = AuthorizedToolCall(
                batchIdentity = BATCH_IDENTITY,
                callId = "call-1",
                name = "known_tool",
                arguments = "{}",
                context = GenerationContext(),
            ),
            onEvent = events::add,
        )

        assertEquals(BATCH_IDENTITY, executed.batchIdentity)
        assertEquals("call-1", executed.callId)
        assertEquals("done", executed.result.text)
        assertEquals(1, provider.executionCount)
        assertTrue(events.single() is ToolExecutionEvent.Completed)
    }

    @Test
    fun `incomplete arguments fail before provider execution and retain identity`() = runTest {
        val provider = FakeToolProvider()
        val executor = GenerationToolExecutor.forTest(listOf(provider))

        val executed = executor.execute(
            call = AuthorizedToolCall(
                batchIdentity = BATCH_IDENTITY,
                callId = "call-invalid",
                name = "known_tool",
                arguments = "{",
                context = GenerationContext(),
            ),
            onEvent = {},
        )

        assertEquals(BATCH_IDENTITY, executed.batchIdentity)
        assertEquals("call-invalid", executed.callId)
        assertTrue(executed.result.isError)
        assertTrue(executed.result.text.contains("complete JSON object"))
        assertEquals(0, provider.executionCount)
    }

    @Test
    fun `tool timeout is a recoverable identified result`() = runTest {
        val provider = object : ToolProvider {
            override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

            override suspend fun execute(
                name: String,
                arguments: String,
                ctx: GenerationContext,
            ): String = awaitCancellation()

            override fun handles(name: String): Boolean = true
        }
        val executor = GenerationToolExecutor.forTest(listOf(provider))

        val executed = executor.execute(
            call = AuthorizedToolCall(
                batchIdentity = BATCH_IDENTITY,
                callId = "call-timeout",
                name = "blocking_tool",
                arguments = "{}",
                context = GenerationContext(toolTimeoutMs = 25L),
            ),
            onEvent = {},
        )

        assertEquals(BATCH_IDENTITY, executed.batchIdentity)
        assertEquals("call-timeout", executed.callId)
        assertTrue(executed.result.isError)
        assertTrue(executed.result.text.contains("timed out after 25ms"))
    }

    private class FakeToolProvider : ToolProvider {
        var executionCount = 0

        override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

        override suspend fun execute(
            name: String,
            arguments: String,
            ctx: GenerationContext,
        ): String {
            executionCount++
            return "done"
        }

        override fun handles(name: String): Boolean = name == "known_tool"
    }

    private companion object {
        val BATCH_IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 1L,
            runId = "run",
            pass = 2,
            effectId = "tool-batch",
        )
    }
}
