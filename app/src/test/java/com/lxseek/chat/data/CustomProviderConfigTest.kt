package com.lxseek.chat.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomProviderConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyConfigWithoutProtocolDefaultsToOpenAi() {
        val config = json.decodeFromString<CustomProviderConfig>("""{"name":"Legacy"}""")

        assertEquals("Legacy", config.name)
        assertEquals(CustomEndpointProtocol.OPENAI, config.protocol)
    }

    @Test
    fun supportedProtocolsRoundTrip() {
        CustomEndpointProtocol.selectable.forEach { protocol ->
            val encoded = json.encodeToString(
                CustomProviderConfig(name = "Endpoint", protocol = protocol),
            )
            val decoded = json.decodeFromString<CustomProviderConfig>(encoded)

            assertEquals(protocol, decoded.protocol)
        }
    }

    @Test
    fun unknownProtocolDecodesFailClosedWithoutDroppingProviderList() {
        val configs = json.decodeFromString<List<CustomProviderConfig>>(
            """[{"name":"Future","protocol":"future-api"},{"name":"Legacy"}]""",
        )

        assertEquals(CustomEndpointProtocol.UNKNOWN, configs[0].protocol)
        assertEquals(CustomEndpointProtocol.OPENAI, configs[1].protocol)
    }
}
