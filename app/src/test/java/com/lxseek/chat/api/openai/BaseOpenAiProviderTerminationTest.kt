package com.lxseek.chat.api.openai

import android.content.Context
import android.content.pm.ApplicationInfo
import com.lxseek.chat.api.ProviderConfig
import com.lxseek.chat.api.StreamEvent
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.DebugLog
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class BaseOpenAiProviderTerminationTest {

    @Before
    fun disableAndroidLoggingForJvmNetworkTests() {
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        DebugLog.forceEnabled = false
        DebugLog.init(context)
    }

    @Test
    fun finishReasonWithoutDone_completesWithinGraceWindow() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"content":"complete"},"finish_reason":"stop"}]}"""
            )
            release.await()
        },
    ) { provider, config ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        assertEquals(
            "complete",
            events.filterIsInstance<StreamEvent.TextChunk>().joinToString("") { it.text },
        )
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun finishReason_stillAcceptsTrailingUsageAndDone() = withServer(
        terminalGraceMillis = 500L,
        response = { socket, _ ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"content":"complete"},"finish_reason":"stop"}]}"""
            )
            Thread.sleep(25L)
            socket.writeSse(
                """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":7,"total_tokens":17}}"""
            )
            socket.writeSse("[DONE]")
        },
    ) { provider, config ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        val usage = events.filterIsInstance<StreamEvent.UsageUpdate>().single().usage
        assertEquals(17, usage.totalTokenCount)
        assertEquals(10, usage.inputTokenCount)
        assertEquals(7, usage.outputTokenCount)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun toolCallFinishReason_emitsCallAndDoesNotRequireDone() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
            )
            release.await()
        },
    ) { provider, config ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        val call = events.filterIsInstance<StreamEvent.ToolCallRequest>().single()
        assertEquals("call_1", call.id)
        assertEquals("lookup", call.name)
        assertEquals("{}", call.arguments)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun structuredToolCall_streamsSnapshotsAndStopStillCompletesCall() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"file_edit","arguments":"{"}}]},"finish_reason":null}]}"""
            )
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]},"finish_reason":null}]}"""
            )
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
            )
            release.await()
        },
    ) { provider, config ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        val updates = events.filterIsInstance<StreamEvent.ToolCallUpdate>()
        assertEquals(listOf("{", "{}"), updates.map { it.arguments })
        assertEquals(1, updates.map { it.streamKey }.distinct().size)
        val call = events.filterIsInstance<StreamEvent.ToolCallRequest>().single()
        assertEquals("call_1", call.id)
        assertEquals("file_edit", call.name)
        assertEquals("{}", call.arguments)
        assertEquals(updates.first().streamKey, call.streamKey)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun structuredToolCall_doneWithoutFinishReasonStillCompletesCall() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, _ ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_done","type":"function","function":{"name":"file_read","arguments":"{}"}}]},"finish_reason":null}]}"""
            )
            socket.writeSse("[DONE]")
        },
    ) { provider, config ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        assertEquals(1, events.filterIsInstance<StreamEvent.ToolCallUpdate>().size)
        val call = events.filterIsInstance<StreamEvent.ToolCallRequest>().single()
        assertEquals("call_done", call.id)
        assertEquals("file_read", call.name)
        assertEquals("{}", call.arguments)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun invalidStructuredToolMetadataProducesOneErrorAndNoExecutableCall() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"bad name","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
            )
            release.await()
        },
    ) { provider, config ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        assertTrue(events.none { it is StreamEvent.ToolCallRequest })
        assertEquals(1, events.filterIsInstance<StreamEvent.Error>().size)
    }

    @Test
    fun duplicateStructuredToolIdsRejectTheWholeBatch() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_same","type":"function","function":{"name":"file_read","arguments":"{}"}},{"index":1,"id":"call_same","type":"function","function":{"name":"file_write","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
            )
            release.await()
        },
    ) { provider, config ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        assertTrue(events.none { it is StreamEvent.ToolCallRequest || it is StreamEvent.ToolCallsRequest })
        assertEquals(1, events.filterIsInstance<StreamEvent.Error>().size)
    }

    @Test
    fun taggedTextToolCall_streamsIntoOneSegmentWithoutFlashingMarkupAsAnswer() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeContentSse("prefix <tool_")
            socket.writeContentSse("call>")
            socket.writeContentSse("{\"name\":\"file_edit\",\"arguments\":{\"path\":\"")
            socket.writeContentSse("a.txt\"}}</tool_call>", finishReason = "stop")
            release.await()
        },
    ) { provider, config ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config.withTools()).toList()
            }
        }

        assertEquals(
            "prefix ",
            events.filterIsInstance<StreamEvent.TextChunk>().joinToString("") { it.text },
        )
        val updates = events.filterIsInstance<StreamEvent.ToolCallUpdate>()
        assertTrue(updates.size >= 2)
        assertEquals(1, updates.map { it.streamKey }.distinct().size)
        assertEquals("", updates.first().name)
        assertEquals("", updates.first().arguments)
        assertEquals("file_edit", updates.last().name)
        val call = events.filterIsInstance<StreamEvent.ToolCallRequest>().single()
        assertEquals("file_edit", call.name)
        assertEquals("""{"path":"a.txt"}""", call.arguments)
        assertEquals(updates.first().streamKey, call.streamKey)
    }

    @Test
    fun incompleteTextToolCall_isDisplayedButNeverExecutedOrLeakedAsAnswer() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeContentSse("<tool_call>")
            socket.writeContentSse("{\"name\":\"file_edit\",\"arguments\":{\"path\":\"unfinished")
            socket.writeContentSse("", finishReason = "stop")
            release.await()
        },
    ) { provider, config ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config.withTools()).toList()
            }
        }

        val updates = events.filterIsInstance<StreamEvent.ToolCallUpdate>()
        assertTrue(updates.isNotEmpty())
        assertEquals("", updates.first().name)
        assertTrue(events.none { it is StreamEvent.ToolCallRequest })
        assertTrue(events.none { it is StreamEvent.TextChunk })
        assertEquals(1, events.filterIsInstance<StreamEvent.Error>().size)
    }

    @Test
    fun bareJsonTextToolCall_streamsArgumentsAndNeverBecomesAnswerText() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeContentSse("{\"name\":\"file_read\",\"arguments\":{\"path\":\"")
            socket.writeContentSse("a.txt\"}}", finishReason = "stop")
            release.await()
        },
    ) { provider, config ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config.withTools()).toList()
            }
        }

        assertTrue(events.none { it is StreamEvent.TextChunk })
        val updates = events.filterIsInstance<StreamEvent.ToolCallUpdate>()
        assertTrue(updates.isNotEmpty())
        val call = events.filterIsInstance<StreamEvent.ToolCallRequest>().single()
        assertEquals("file_read", call.name)
        assertEquals("""{"path":"a.txt"}""", call.arguments)
        assertEquals(updates.first().streamKey, call.streamKey)
    }

    private fun messages() = listOf(
        ChatMessage(
            text = "hello",
            participant = Participant.USER,
        )
    )

    private fun ProviderConfig.withTools() = copy(
        tools = listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "file_edit",
                    description = "Edit a file",
                    parameters = ToolParameters(
                        type = "object",
                        properties = emptyMap(),
                    ),
                )
            )
        )
    )

    private fun withServer(
        terminalGraceMillis: Long,
        response: (Socket, CountDownLatch) -> Unit,
        test: (BaseOpenAiProvider, ProviderConfig) -> Unit,
    ) {
        SseServer(response).use { server ->
            val provider = object : BaseOpenAiProvider() {
                override val name: String = "test"
                override val defaultBaseUrl: String = server.baseUrl
                override val terminalSseGraceMillis: Long = terminalGraceMillis
            }
            try {
                test(
                    provider,
                    ProviderConfig(
                        apiKey = "",
                        modelId = "test-model",
                        baseUrl = server.baseUrl,
                        thinkingEnabled = false,
                    ),
                )
            } finally {
                server.throwIfFailed()
            }
        }
    }

    private class SseServer(
        private val response: (Socket, CountDownLatch) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        private val release = CountDownLatch(1)
        private val accepted = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>(null)
        @Volatile
        private var client: Socket? = null
        private val worker = thread(
            name = "openai-sse-test-server",
            isDaemon = true,
        ) {
            try {
                server.accept().use { socket ->
                    client = socket
                    accepted.countDown()
                    socket.tcpNoDelay = true
                    readRequestHeaders(socket)
                    val headers = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/event-stream\r\n")
                        append("Cache-Control: no-cache\r\n")
                        append("Transfer-Encoding: chunked\r\n")
                        append("Connection: keep-alive\r\n")
                        append("\r\n")
                    }
                    socket.getOutputStream().write(headers.toByteArray(StandardCharsets.US_ASCII))
                    socket.getOutputStream().flush()
                    response(socket, release)
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        val baseUrl: String = "http://127.0.0.1:${server.localPort}/v1"

        fun throwIfFailed() {
            failure.get()?.let { throw AssertionError("SSE test server failed", it) }
            check(accepted.await(1L, TimeUnit.SECONDS)) {
                "SSE test server never received the request"
            }
        }

        override fun close() {
            release.countDown()
            runCatching { client?.close() }
            runCatching { server.close() }
            worker.join(1_000L)
        }

        private fun readRequestHeaders(socket: Socket) {
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)
            )
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) return
            }
        }
    }

    private fun Socket.writeSse(data: String) {
        val payload = "data: $data\n\n".toByteArray(StandardCharsets.UTF_8)
        val output = getOutputStream()
        output.write("${payload.size.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(payload)
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun Socket.writeContentSse(content: String, finishReason: String? = null) {
        writeSse(
            WIRE_JSON.encodeToString(
                com.lxseek.chat.api.OpenAiStreamResponse(
                    choices = listOf(
                        com.lxseek.chat.api.OpenAiChoice(
                            index = 0,
                            delta = com.lxseek.chat.api.OpenAiDelta(content = content),
                            finishReason = finishReason,
                        )
                    )
                )
            )
        )
    }

    private companion object {
        val WIRE_JSON = Json { explicitNulls = false }
    }
}
