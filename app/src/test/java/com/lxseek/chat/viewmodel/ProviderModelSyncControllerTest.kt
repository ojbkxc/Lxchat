package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.LlmProvider
import com.lxseek.chat.api.ModelFetchTimeoutException
import com.lxseek.chat.data.SettingsManager
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.util.Constants
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderModelSyncControllerTest {
    @Test
    fun `full sync skips local and unconfigured providers then saves one successful fingerprint`() =
        runTest {
            val providers = mockk<ProviderRegistry>()
            val settings = settings(
                available = mapOf("Cloud" to listOf("Cloud:model")),
                custom = setOf("Custom:model"),
                enabled = setOf("Cloud:model", "Custom:model", "Stale:model"),
            )
            every { providers.all } returns linkedMapOf(
                Constants.PROVIDER_LOCAL to mockk<LlmProvider>(),
                "Cloud" to mockk<LlmProvider>(),
                "Missing" to mockk<LlmProvider>(),
            )
            every { providers.ensureCustomProvidersRegistered() } just Runs
            every { settings.resolveActiveKey("Cloud") } returns "key"
            every { settings.resolveActiveKey("Missing") } returns ""
            every { providers.isConfigured("Cloud", "key") } returns true
            every { providers.isConfigured("Missing", "") } returns false
            coEvery { providers.fetchModelsForProvider("Cloud") } returns listOf("Cloud:model")
            every { providers.computeFingerprint() } returns "fingerprint"
            val completed = CompletableDeferred<ProviderModelSyncOutcome>()
            val controller = ProviderModelSyncController(providers, settings, this)

            controller.start(request()) { completed.complete(it) }
            val outcome = completed.await()

            assertEquals(1, outcome.successfulProviderCount)
            assertEquals(1, outcome.skippedProviderCount)
            assertEquals(emptyList<ProviderModelSyncFailure>(), outcome.failures)
            assertFalse(controller.isSyncing.value)
            coVerify(exactly = 1) { settings.sm.saveAvailableModels("Missing", emptyList()) }
            verify(exactly = 1) {
                settings.setEnabledModels(setOf("Cloud:model", "Custom:model"))
            }
            coVerify(exactly = 1) {
                settings.sm.saveLastModelsFetchFingerprint("fingerprint")
            }
        }

    @Test
    fun `provider failure is typed and keeps fingerprint eligible for retry`() = runTest {
        val providers = mockk<ProviderRegistry>()
        val settings = settings()
        every { providers.all } returns mapOf("Cloud" to mockk<LlmProvider>())
        every { providers.ensureCustomProvidersRegistered() } just Runs
        every { settings.resolveActiveKey("Cloud") } returns "key"
        every { providers.isConfigured("Cloud", "key") } returns true
        coEvery { providers.fetchModelsForProvider("Cloud") } throws ModelFetchTimeoutException()
        val completed = CompletableDeferred<ProviderModelSyncOutcome>()
        val controller = ProviderModelSyncController(providers, settings, this)

        controller.start(request()) { completed.complete(it) }
        val outcome = completed.await()

        assertEquals(0, outcome.successfulProviderCount)
        assertEquals(0, outcome.skippedProviderCount)
        assertEquals(
            listOf(ProviderModelSyncFailure("Cloud", "timeout")),
            outcome.failures,
        )
        assertFalse(controller.isSyncing.value)
        coVerify(exactly = 0) { settings.sm.saveLastModelsFetchFingerprint(any()) }
    }

    @Test
    fun `second request while active is rejected without duplicate provider work`() = runTest {
        val providers = mockk<ProviderRegistry>()
        val settings = settings(available = mapOf("Cloud" to listOf("Cloud:model")))
        every { providers.all } returns mapOf("Cloud" to mockk<LlmProvider>())
        every { providers.ensureCustomProvidersRegistered() } just Runs
        every { settings.resolveActiveKey("Cloud") } returns "key"
        every { providers.isConfigured("Cloud", "key") } returns true
        val releaseProvider = CompletableDeferred<Unit>()
        coEvery { providers.fetchModelsForProvider("Cloud") } coAnswers {
            releaseProvider.await()
            listOf("Cloud:model")
        }
        every { providers.computeFingerprint() } returns "fingerprint"
        val outcomes = mutableListOf<ProviderModelSyncOutcome>()
        val controller = ProviderModelSyncController(providers, settings, this)

        controller.start(request()) { outcomes += it }
        runCurrent()
        assertTrue(controller.isSyncing.value)
        controller.start(request()) { outcomes += it }
        runCurrent()
        releaseProvider.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, outcomes.size)
        assertFalse(controller.isSyncing.value)
        coVerify(exactly = 1) { providers.fetchModelsForProvider("Cloud") }
    }

    @Test
    fun `scope cancellation clears admission and emits no completion`() = runTest {
        val providers = mockk<ProviderRegistry>()
        val settings = settings()
        every { providers.all } returns mapOf("Cloud" to mockk<LlmProvider>())
        every { providers.ensureCustomProvidersRegistered() } just Runs
        every { settings.resolveActiveKey("Cloud") } returns "key"
        every { providers.isConfigured("Cloud", "key") } returns true
        coEvery { providers.fetchModelsForProvider("Cloud") } coAnswers { awaitCancellation() }
        val owner = SupervisorJob()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + owner)
        var completionCount = 0
        val controller = ProviderModelSyncController(providers, settings, scope)

        controller.start(request()) { completionCount++ }
        runCurrent()
        assertTrue(controller.isSyncing.value)
        owner.cancel()
        advanceUntilIdle()

        assertFalse(controller.isSyncing.value)
        assertEquals(0, completionCount)
    }

    @Test
    fun `first sync with fetched default model auto enables it once and latches`() = runTest {
        val providers = mockk<ProviderRegistry>()
        val settings = settings(
            available = mapOf("LxChat" to listOf(Constants.EXAMPLE_MODEL_ID)),
        )
        every { providers.all } returns emptyMap()
        every { providers.ensureCustomProvidersRegistered() } just Runs
        every { providers.computeFingerprint() } returns "fingerprint"
        val completed = CompletableDeferred<ProviderModelSyncOutcome>()
        val controller = ProviderModelSyncController(providers, settings, this)

        controller.start(request()) { completed.complete(it) }
        completed.await()

        verify(exactly = 1) {
            settings.setEnabledModels(setOf(Constants.EXAMPLE_MODEL_ID))
        }
        coVerify(exactly = 1) { settings.markLxChatDefaultAutoEnabled() }
    }

    @Test
    fun `full deselect is never auto re-enabled once the latch is set`() = runTest {
        val providers = mockk<ProviderRegistry>()
        val settings = settings(
            available = mapOf("LxChat" to listOf(Constants.EXAMPLE_MODEL_ID)),
            lxChatDefaultAutoEnabled = true,
        )
        every { providers.all } returns emptyMap()
        every { providers.ensureCustomProvidersRegistered() } just Runs
        every { providers.computeFingerprint() } returns "fingerprint"
        val completed = CompletableDeferred<ProviderModelSyncOutcome>()
        val controller = ProviderModelSyncController(providers, settings, this)

        controller.start(request()) { completed.complete(it) }
        completed.await()

        verify(exactly = 1) { settings.setEnabledModels(emptySet<String>()) }
        coVerify(exactly = 0) { settings.markLxChatDefaultAutoEnabled() }
    }

    private fun settings(
        available: Map<String, List<String>> = emptyMap(),
        custom: Set<String> = emptySet(),
        enabled: Set<String> = emptySet(),
        lxChatDefaultAutoEnabled: Boolean = false,
    ): SettingsRepository = mockk<SettingsRepository>().also { settings ->
        val sm = mockk<SettingsManager>(relaxed = true)
        every { settings.sm } returns sm
        every { settings.customModels } returns MutableStateFlow(custom)
        every { settings.enabledModels } returns MutableStateFlow(enabled)
        every { settings.lxChatDefaultAutoEnabled } returns MutableStateFlow(lxChatDefaultAutoEnabled)
        coEvery { settings.sm.availableModels.first() } returns available
        coEvery { settings.sm.saveAvailableModels(any(), any()) } returns Unit
        coEvery { settings.sm.saveLastModelsFetchFingerprint(any()) } returns Unit
        every { settings.setEnabledModels(any()) } just Runs
        coEvery { settings.markLxChatDefaultAutoEnabled() } returns Unit
    }

    private fun request() = ProviderModelSyncRequest(
        failureLabels = ModelSyncFailureLabels(
            noModels = "empty",
            timeout = "timeout",
            invalidResponse = "invalid",
            unknown = "unknown",
        ),
        globalProviderName = "Models",
    )
}
