package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.anthropic.AnthropicProvider
import com.lxseek.chat.api.gemini.GeminiProvider
import com.lxseek.chat.api.openai.CustomOpenAiProvider
import com.lxseek.chat.data.CustomEndpointProtocol
import com.lxseek.chat.data.CustomEndpointResolution
import com.lxseek.chat.data.CustomProviderConfig
import com.lxseek.chat.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderFactoryTest {
    @Test
    fun factoryReusesExistingProtocolImplementations() {
        val url = "https://example.test/api"

        val openAi = createCustomProvider(
            CustomProviderConfig("OpenAI proxy", CustomEndpointProtocol.OPENAI),
            url,
        )
        val google = createCustomProvider(
            CustomProviderConfig("Google proxy", CustomEndpointProtocol.GOOGLE),
            url,
        )
        val anthropic = createCustomProvider(
            CustomProviderConfig("Anthropic proxy", CustomEndpointProtocol.ANTHROPIC),
            url,
        )

        assertTrue(openAi is CustomOpenAiProvider)
        assertTrue(google is GeminiProvider)
        assertTrue(anthropic is AnthropicProvider)
        assertEquals("Google proxy", google?.name)
        assertEquals("Anthropic proxy", anthropic?.name)
        assertEquals(url, google?.defaultBaseUrl)
        assertEquals(url, anthropic?.defaultBaseUrl)
    }

    @Test
    fun unknownProtocolIsNotRegistered() {
        assertNull(
            createCustomProvider(
                CustomProviderConfig("Unknown", CustomEndpointProtocol.UNKNOWN),
                "https://example.test",
            ),
        )
    }

    @Test
    fun builtInNameCannotBeRegisteredAsCustomProvider() {
        assertNull(
            createCustomProvider(
                CustomProviderConfig(Constants.PROVIDER_LOCAL),
                "https://example.test",
            ),
        )
        assertNull(
            createCustomProvider(
                CustomProviderConfig(Constants.PROVIDER_LOCAL.lowercase()),
                "https://example.test",
            ),
        )
    }

    @Test
    fun baseUrlCandidatesAreProtocolSpecific() {
        assertEquals(
            listOf("https://example.test/v1", "https://example.test"),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.OPENAI,
                "https://example.test",
            ),
        )
        assertEquals(
            listOf("https://example.test"),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.GOOGLE,
                "https://example.test",
            ),
        )
        assertEquals(
            listOf("https://example.test/v1", "https://example.test"),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.ANTHROPIC,
                "https://example.test",
            ),
        )
        assertEquals(
            emptyList<String?>(),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.UNKNOWN,
                "https://example.test",
            ),
        )
    }

    @Test
    fun explicitVersionedBaseUrlIsTriedBeforeMigrationFallbacks() {
        val url = "https://example.test/v1beta"

        assertEquals(
            listOf(url, "https://example.test/v1", "https://example.test"),
            customEndpointBaseUrlCandidates(CustomEndpointProtocol.OPENAI, url),
        )
        assertEquals(
            listOf(url, "https://example.test/v1", "https://example.test"),
            customEndpointBaseUrlCandidates(CustomEndpointProtocol.ANTHROPIC, url),
        )
        assertEquals(
            listOf(url, "https://example.test"),
            customEndpointBaseUrlCandidates(CustomEndpointProtocol.GOOGLE, url),
        )
    }

    @Test
    fun oldPersistedV1CanRecoverWhenSwitchingToGoogle() {
        assertEquals(
            listOf("https://example.test/v1", "https://example.test"),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.GOOGLE,
                "https://example.test/v1",
            ),
        )
    }

    @Test
    fun resolvedEndpointIsScopedToProtocolAndConfiguredUrl() {
        val resolution = CustomEndpointResolution(
            protocol = CustomEndpointProtocol.OPENAI,
            configuredBaseUrl = "https://example.test/",
            effectiveBaseUrl = "https://example.test/v1",
        )

        assertTrue(resolution.matches(CustomEndpointProtocol.OPENAI, "https://example.test"))
        assertTrue(!resolution.matches(CustomEndpointProtocol.GOOGLE, "https://example.test"))
        assertTrue(!resolution.matches(CustomEndpointProtocol.OPENAI, "https://other.test"))
    }
}
