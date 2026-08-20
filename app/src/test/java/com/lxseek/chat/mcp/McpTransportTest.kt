package com.lxseek.chat.mcp

import com.lxseek.chat.data.McpServerConfig
import com.lxseek.chat.data.McpTransportType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTransportTest {
    @Test
    fun existingConfigDefaultsToStreamableHttp() {
        val config = Json.decodeFromString<McpServerConfig>(
            """{"id":"old","name":"Existing","url":"https://example.com/mcp"}""",
        )

        assertEquals(McpTransportType.STREAMABLE_HTTP, config.transport)
        assertEquals("\"sse\"", Json.encodeToString(McpTransportType.SSE))
    }

    @Test
    fun sseParserHandlesEndpointAndMultilineMessageEvents() {
        val parser = McpSseEventParser()

        assertEquals(null, parser.accept("event: endpoint"))
        assertEquals(null, parser.accept("data: /messages?session=abc"))
        assertEquals(
            McpSseEvent("endpoint", "/messages?session=abc"),
            parser.accept(""),
        )

        assertEquals(null, parser.accept("event: message"))
        assertEquals(null, parser.accept("data: {\"jsonrpc\":\"2.0\","))
        assertEquals(null, parser.accept("data: \"id\":1,\"result\":{}}"))
        assertEquals(
            McpSseEvent(
                "message",
                "{\"jsonrpc\":\"2.0\",\n\"id\":1,\"result\":{}}",
            ),
            parser.accept(""),
        )
    }

    @Test
    fun legacyMessageEndpointMustStayOnTheConfiguredOrigin() {
        val stream = "https://example.com/events".toHttpUrl()

        assertEquals(
            "https://example.com/messages?session=abc",
            resolveLegacySseMessageEndpoint(stream, "/messages?session=abc").toString(),
        )
        assertTrue(
            runCatching {
                resolveLegacySseMessageEndpoint(stream, "https://attacker.example/messages")
            }.isFailure,
        )
        assertTrue(
            runCatching {
                resolveLegacySseMessageEndpoint(stream, "https://user@example.com/messages")
            }.isFailure,
        )
    }

    @Test
    fun headerValidationRejectsInjectionReservedNamesAndNonAsciiNames() {
        assertTrue(isValidMcpHeaderName("X-Api-Key"))
        assertTrue(isValidMcpHeaderValue("Bearer abc"))
        assertTrue(isReservedMcpHeaderName("content-type"))
        assertFalse(isValidMcpHeaderName("Bad Header"))
        assertFalse(isValidMcpHeaderName("密钥"))
        assertFalse(isValidMcpHeaderValue("line1\r\nline2"))
    }
}
