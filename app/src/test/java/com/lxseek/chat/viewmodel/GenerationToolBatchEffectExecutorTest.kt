package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.StreamEvent
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.model.ToolExecutionStates
import com.lxseek.chat.tool.ToolExecutionEvent
import com.lxseek.chat.tool.ToolExecutionResult
import com.lxseek.chat.tool.ToolPresentationMetadata
import com.lxseek.chat.tool.ToolProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationToolBatchEffectExecutorTest {
    @Test
    fun `overlay uniquely owns stream indices metadata and terminal presentation`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String) =
                    ToolPresentationMetadata(displayName = "Display $name", target = "initial")
            },
            providerName = "provider",
        )

        assertTrue(overlay.upsert("stream", null, "tool", "{", "signature"))
        assertEquals(false, overlay.upsert("stream", "call", "", "{}", null))
        overlay.start(call())
        overlay.applyProgress("call", ToolExecutionEvent.TargetResolved("resolved"))
        overlay.applyProgress("call", ToolExecutionEvent.OutputDelta("partial"))
        val completed = overlay.complete(
            call(),
            ToolExecutionResult(text = "done", displayText = "shown"),
        )

        assertEquals("done", completed.data.result)
        assertEquals("Display tool", completed.data.displayName)
        assertEquals("resolved", completed.segment.toolTarget)
        assertEquals("partial", completed.segment.toolProgress)
        assertEquals(ToolExecutionStates.SUCCEEDED, completed.segment.toolState)
        assertEquals("provider", completed.segment.signatureProvider)

        overlay.replaceAll(emptyList())
        assertNull(overlay.snapshot().singleOrNull())
    }

    @Test
    fun `accepted batch executes in order and returns the same identity without continuation`() = runTest {
        val provider = StreamingToolProvider()
        val tools = GenerationToolExecutor.forTest(listOf(provider))
        var now = 0L
        val executor = GenerationToolBatchEffectExecutor(tools) { now += 100L; now }
        val overlay = GenerationToolOverlay(tools, "provider")
        overlay.upsert("stream", "call", "tool", "{}", null)
        val forces = mutableListOf<Boolean>()
        val publishedAt = mutableListOf<Long>()

        val outcome = executor.execute(
            request = AuthorizedToolBatchRequest(
                effect = RunEffect.ExecuteToolBatch(IDENTITY),
                calls = listOf(call()),
                context = GenerationContext(),
                conversationId = "conversation",
            ),
            overlay = overlay,
            callbacks = ToolBatchProgressCallbacks(
                publish = { forces += it },
                onPublishedAt = publishedAt::add,
            ),
        )

        assertEquals(IDENTITY, outcome.identity)
        assertEquals(listOf("tool"), provider.executedNames)
        assertEquals(listOf(true, false, false, false), forces)
        assertEquals(4, publishedAt.size)
        assertEquals("done", outcome.calls.single().result)
        assertEquals(ToolExecutionStates.SUCCEEDED, outcome.segments.single().toolState)
        assertTrue(outcome.generatedImages.isEmpty())
    }

    private fun call() = StreamEvent.ToolCallRequest(
        id = "call",
        name = "tool",
        arguments = "{}",
        streamKey = "stream",
        signature = "signature",
    )

    private class StreamingToolProvider : ToolProvider {
        val executedNames = mutableListOf<String>()

        override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

        override fun handles(name: String): Boolean = name == "tool"

        override fun presentationMetadata(name: String) = ToolPresentationMetadata("Display")

        override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
            error("Streaming adapter expected")

        override fun executeEvents(
            name: String,
            arguments: String,
            ctx: GenerationContext,
        ): Flow<ToolExecutionEvent> {
            executedNames += name
            return flowOf(
                ToolExecutionEvent.TargetResolved("target"),
                ToolExecutionEvent.OutputDelta("partial"),
                ToolExecutionEvent.Completed(ToolExecutionResult(text = "done")),
            )
        }
    }

    private companion object {
        val IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 7,
            runId = "run",
            pass = 2,
            effectId = "tool-batch-provider-2-0",
        )
    }
}
