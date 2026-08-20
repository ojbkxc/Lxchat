package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.GenerationError
import com.lxseek.chat.api.LlmProvider
import com.lxseek.chat.api.ProviderConfig
import com.lxseek.chat.api.StreamEvent
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEffectIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPassRunnerTest {
    @Test
    fun `closed text stream returns identified CompletedText and forwards events`() = runTest {
        val events = listOf<StreamEvent>(
            StreamEvent.TextChunk("answer"),
            StreamEvent.UsageUpdate(12),
        )
        val forwarded = mutableListOf<StreamEvent>()

        val outcome = runner(events).run(IDENTITY, messages(), CONFIG, forwarded::add)

        assertEquals(ProviderPassOutcome.CompletedText(IDENTITY), outcome)
        assertEquals(events, forwarded)
    }

    @Test
    fun `only a complete unique tool batch becomes authoritative`() = runTest {
        val update = StreamEvent.ToolCallUpdate(
            streamKey = "stream_1",
            id = "call_1",
            name = "file_read",
            arguments = "{}",
        )
        val first = StreamEvent.ToolCallRequest(
            id = "call_1",
            name = "file_read",
            arguments = "{}",
            streamKey = "stream_1",
        )
        val second = StreamEvent.ToolCallRequest(
            id = "call_2",
            name = "file_write",
            arguments = "{\"path\":\"a\"}",
            streamKey = "stream_2",
        )

        val outcome = runner(
            listOf(update, StreamEvent.ToolCallsRequest(listOf(first, second))),
        ).run(IDENTITY, messages(), CONFIG) {}

        assertEquals(
            ProviderPassOutcome.CompletedToolCalls(IDENTITY, listOf(first, second)),
            outcome,
        )
    }

    @Test
    fun `malformed duplicate incomplete and empty tool batches fail closed`() = runTest {
        val valid = StreamEvent.ToolCallRequest("call_1", "file_read", "{}", streamKey = "s1")
        val invalidStreams = listOf(
            listOf<StreamEvent>(
                valid,
                valid.copy(streamKey = "s2"),
            ),
            listOf<StreamEvent>(valid.copy(name = "bad name")),
            listOf<StreamEvent>(valid.copy(id = "bad id")),
            listOf<StreamEvent>(valid.copy(streamKey = "")),
            listOf<StreamEvent>(valid.copy(arguments = "{")),
            listOf<StreamEvent>(
                valid,
                valid.copy(id = "call_2"),
            ),
            listOf<StreamEvent>(
                StreamEvent.ToolCallUpdate("open", null, "file_read", "{"),
            ),
            listOf<StreamEvent>(StreamEvent.ToolCallsRequest(emptyList())),
        )

        invalidStreams.forEach { streamEvents ->
            val forwarded = mutableListOf<StreamEvent>()
            val outcome = runner(streamEvents).run(
                IDENTITY,
                messages(),
                CONFIG,
                forwarded::add,
            )

            assertTrue(outcome is ProviderPassOutcome.Failed)
            assertTrue((outcome as ProviderPassOutcome.Failed).error is GenerationError.SseParse)
            assertTrue(forwarded.last() is StreamEvent.Error)
        }
    }

    @Test
    fun `provider error closes as Truncated or Failed without tool authority`() = runTest {
        val truncated = GenerationError.OutputTruncated("provider", "length")
        val apiError = GenerationError.Api("bad", "request", "failure")

        assertEquals(
            ProviderPassOutcome.Truncated(IDENTITY, truncated),
            runner(listOf(StreamEvent.Error(truncated))).run(IDENTITY, messages(), CONFIG) {},
        )
        assertEquals(
            ProviderPassOutcome.Failed(IDENTITY, apiError),
            runner(listOf(StreamEvent.Error(apiError))).run(IDENTITY, messages(), CONFIG) {},
        )
    }

    @Test
    fun `provider exception becomes a forwarded identified failure`() = runTest {
        val failure = IllegalStateException("transport failed")
        val forwarded = mutableListOf<StreamEvent>()
        val provider = fakeProvider(flow {
            throw failure
        })

        val outcome = ProviderPassRunner().run(
            IDENTITY,
            provider,
            messages(),
            CONFIG,
            forwarded::add,
        )

        assertTrue(outcome is ProviderPassOutcome.Failed)
        assertSame(failure, (outcome as ProviderPassOutcome.Failed).error.let {
            (it as GenerationError.Unknown).cause
        })
        assertTrue(forwarded.single() is StreamEvent.Error)
    }

    @Test
    fun `terminal provider error closes the pass and rejects later events`() = runTest {
        val apiError = GenerationError.Api("bad", "request", "failure")
        val forwarded = mutableListOf<StreamEvent>()
        val provider = fakeProvider(flow {
            emit(StreamEvent.Error(apiError))
            emit(StreamEvent.TextChunk("must not be accepted"))
            throw IllegalStateException("must not be reached")
        })

        val outcome = ProviderPassRunner().run(
            IDENTITY,
            provider,
            messages(),
            CONFIG,
            forwarded::add,
        )

        assertEquals(ProviderPassOutcome.Failed(IDENTITY, apiError), outcome)
        assertEquals(listOf(StreamEvent.Error(apiError)), forwarded)
    }

    @Test
    fun `cancellation closes as Cancelled while consumer failures still propagate`() = runTest {
        val cancelledProvider = fakeProvider(flow {
            throw CancellationException("cancel")
        })
        assertEquals(
            ProviderPassOutcome.Cancelled(IDENTITY),
            ProviderPassRunner().run(
                IDENTITY,
                cancelledProvider,
                messages(),
                CONFIG,
            ) {},
        )

        val consumerFailure = IllegalArgumentException("consumer")
        try {
            runner(listOf(StreamEvent.TextChunk("answer"))).run(
                IDENTITY,
                messages(),
                CONFIG,
            ) { throw consumerFailure }
            throw AssertionError("Expected the consumer failure")
        } catch (actual: IllegalArgumentException) {
            assertSame(consumerFailure, actual)
        }
    }

    private fun runner(events: List<StreamEvent>) = ProviderPassRunnerHarness(
        ProviderPassRunner(),
        fakeProvider(flowOf(*events.toTypedArray())),
    )

    private fun fakeProvider(events: Flow<StreamEvent>) = object : LlmProvider {
        override val name: String = "provider"
        override val defaultBaseUrl: String = "https://example.invalid"

        override fun generateResponse(
            messages: List<ChatMessage>,
            config: ProviderConfig,
        ): Flow<StreamEvent> = events

        override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = emptyList()
    }

    private fun messages() = listOf(
        ChatMessage(text = "input", participant = Participant.USER),
    )

    private class ProviderPassRunnerHarness(
        private val runner: ProviderPassRunner,
        private val provider: LlmProvider,
    ) {
        suspend fun run(
            identity: RunEffectIdentity,
            messages: List<ChatMessage>,
            config: ProviderConfig,
            onEvent: suspend (StreamEvent) -> Unit,
        ): ProviderPassOutcome = runner.run(identity, provider, messages, config, onEvent)
    }

    private companion object {
        val IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 7,
            runId = "run",
            pass = 3,
            effectId = "provider-3-0",
        )
        val CONFIG = ProviderConfig(apiKey = "key", modelId = "model")
    }
}
