package com.lxseek.chat.viewmodel

import com.lxseek.chat.util.Constants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderFailClosedPolicyTest {
    @Test
    fun missingCustomProviderIsNeverConsideredConfiguredEvenWithFallbackLikeUrl() {
        assertFalse(
            providerConfigurationIsValid(
                providerName = "Deleted Custom",
                activeKey = "key",
                registered = false,
                builtIn = false,
                effectiveBaseUrl = "https://wrong-fallback.example",
            )
        )
    }

    @Test
    fun registeredProvidersStillUseTheirExpectedCredentialPolicy() {
        assertTrue(
            providerConfigurationIsValid(
                providerName = Constants.PROVIDER_OPENAI,
                activeKey = "key",
                registered = true,
                builtIn = true,
                effectiveBaseUrl = null,
            )
        )
        assertTrue(
            providerConfigurationIsValid(
                providerName = "Custom",
                activeKey = "",
                registered = true,
                builtIn = false,
                effectiveBaseUrl = "https://custom.example/v1",
            )
        )
    }

    @Test
    fun generationLookupThrowsInsteadOfSelectingFirstProvider() {
        val registered = Any()
        val providers = linkedMapOf("Registered" to registered)

        assertSame(registered, requireRegisteredProvider(providers, "Registered"))
        try {
            requireRegisteredProvider(providers, "Missing")
            fail("Missing provider must fail closed")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Missing"))
        }
    }
}
