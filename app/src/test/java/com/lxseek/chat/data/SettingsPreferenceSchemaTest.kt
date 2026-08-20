package com.lxseek.chat.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPreferenceSchemaTest {
    @Test
    fun durableKeyNamesRemainCompatible() {
        assertEquals("selected_model", SELECTED_MODEL.name)
        assertEquals("api_keys_json", API_KEYS_JSON.name)
        assertEquals("context_token_budget", CONTEXT_TOKEN_BUDGET.name)
        assertEquals("max_context_window", MAX_CONTEXT_WINDOW.name)
        assertEquals("context_compact_retain_count", CONTEXT_COMPACT_RETAIN_COUNT.name)
        assertEquals("mcp_servers_json", MCP_SERVERS_JSON.name)
        assertEquals("last_models_fetch_fingerprint", LAST_MODELS_FETCH_FINGERPRINT.name)
    }

    @Test
    fun publicProxyDefaultsRemainCompatible() {
        assertEquals("127.0.0.1", SettingsManager.DEFAULT_PROXY_HOST)
        assertEquals("7890", SettingsManager.DEFAULT_PROXY_PORT)
        assertEquals(
            "localhost\n127.0.0.1\n10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16\n::1",
            SettingsManager.DEFAULT_PROXY_BYPASS,
        )
    }
}
