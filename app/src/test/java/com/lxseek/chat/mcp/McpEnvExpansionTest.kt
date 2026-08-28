package com.lxseek.chat.mcp

import com.lxseek.chat.data.McpServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpEnvExpansionTest {

    @Test
    fun expandsSimpleAndDefaultedVars() {
        val env = mapOf("MCP_TEST_TOKEN" to "secret-value")

        val simple = McpEnvExpansion.expandInString(
            "https://mcp.example/${'$'}{MCP_TEST_TOKEN}/path",
            env,
        )
        assertEquals("https://mcp.example/secret-value/path", simple.expanded)
        assertTrue(simple.missingVars.isEmpty())

        val withDefault = McpEnvExpansion.expandInString(
            "https://mcp.example/api?key=${'$'}{MCP_TEST_MISSING:-fallback}",
            env,
        )
        assertEquals("https://mcp.example/api?key=fallback", withDefault.expanded)
        assertTrue(withDefault.missingVars.isEmpty())
    }

    @Test
    fun reportsMissingVarsAndKeepsPlaceholder() {
        val result = McpEnvExpansion.expandInString(
            "https://mcp.example/${'$'}{MCP_TEST_ABSENT}",
            emptyMap(),
        )
        assertEquals("https://mcp.example/${'$'}{MCP_TEST_ABSENT}", result.expanded)
        assertEquals(listOf("MCP_TEST_ABSENT"), result.missingVars)
    }

    @Test
    fun expandsUrlAndHeadersFromConfigEnv() {
        val config = McpServerConfig(
            name = "mcp",
            url = "https://mcp.example/${'$'}{MCP_TEST_TOKEN}",
            headers = mapOf("Authorization" to "Bearer ${'$'}{MCP_TEST_TOKEN}"),
            env = mapOf("MCP_TEST_TOKEN" to "abc"),
        )

        val expanded = McpEnvExpansion.expand(config)

        assertTrue(expanded.missingVars.isEmpty())
        assertEquals("https://mcp.example/abc", expanded.config.url)
        assertEquals(mapOf("Authorization" to "Bearer abc"), expanded.config.headers)
        // 原配置不被修改，env 仍保留原始值
        assertEquals("https://mcp.example/${'$'}{MCP_TEST_TOKEN}", config.url)
    }

    @Test
    fun collectsMissingVarsAcrossUrlAndHeaders() {
        val config = McpServerConfig(
            name = "mcp",
            url = "https://mcp.example/${'$'}{MCP_TEST_MISSING}",
            headers = mapOf("X-Key" to "${'$'}{MCP_TEST_MISSING_TOO}"),
            env = emptyMap(),
        )

        val expanded = McpEnvExpansion.expand(config)

        assertEquals(listOf("MCP_TEST_MISSING", "MCP_TEST_MISSING_TOO"), expanded.missingVars)
    }
}
