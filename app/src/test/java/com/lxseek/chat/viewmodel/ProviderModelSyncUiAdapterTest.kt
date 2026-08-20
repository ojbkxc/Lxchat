package com.lxseek.chat.viewmodel

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderModelSyncUiAdapterTest {
    @Test
    fun directMethodsForwardWithoutPresentationSideEffects() = runTest {
        val controller = mockk<ProviderModelSyncController>()
        every { controller.isSyncing } returns MutableStateFlow(true)
        coEvery { controller.fetchModelsForProvider("Provider") } returns listOf("Provider:model")
        every { controller.computeFingerprint() } returns "fingerprint"
        val messages = mutableListOf<String>()
        val adapter = ProviderModelSyncUiAdapter(controller, TEXT, messages::add)

        assertEquals(listOf("Provider:model"), adapter.fetchModelsForProvider("Provider"))
        assertEquals("fingerprint", adapter.computeFingerprint())
        assertEquals(true, adapter.isSyncing.value)
        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun fullSyncForwardsRequestAndPublishesTypedOutcomeMessage() {
        val controller = mockk<ProviderModelSyncController>()
        every { controller.isSyncing } returns MutableStateFlow(false)
        val request = slot<ProviderModelSyncRequest>()
        every { controller.start(capture(request), any()) } answers {
            val callback = secondArg<suspend (ProviderModelSyncOutcome) -> Unit>()
            runBlocking {
                callback(ProviderModelSyncOutcome(emptyList(), 2, 0))
            }
        }
        val messages = mutableListOf<String>()
        val adapter = ProviderModelSyncUiAdapter(controller, TEXT, messages::add)

        adapter.fetchAvailableModels()

        assertEquals(TEXT.failureLabels, request.captured.failureLabels)
        assertEquals(TEXT.globalProviderName, request.captured.globalProviderName)
        assertEquals(listOf("success:2"), messages)
    }

    @Test
    fun outcomeMessageUsesFailureThenSuccessThenSkippedThenCompletedPrecedence() {
        assertEquals(
            "Provider: broken",
            providerModelSyncUiMessage(
                ProviderModelSyncOutcome(
                    failures = listOf(ProviderModelSyncFailure("Provider", "broken")),
                    successfulProviderCount = 3,
                    skippedProviderCount = 4,
                ),
                TEXT,
            ),
        )
        assertEquals(
            "success:3",
            providerModelSyncUiMessage(ProviderModelSyncOutcome(emptyList(), 3, 4), TEXT),
        )
        assertEquals(
            "none",
            providerModelSyncUiMessage(ProviderModelSyncOutcome(emptyList(), 0, 4), TEXT),
        )
        assertEquals(
            "complete",
            providerModelSyncUiMessage(ProviderModelSyncOutcome(emptyList(), 0, 0), TEXT),
        )
    }

    private companion object {
        val TEXT = ProviderModelSyncUiText(
            failureLabels = ModelSyncFailureLabels(
                noModels = "no models",
                timeout = "timeout",
                invalidResponse = "invalid",
                unknown = "unknown",
            ),
            globalProviderName = "Models",
            successfulProviders = { "success:$it" },
            noProviders = "none",
            completed = "complete",
        )
    }
}
