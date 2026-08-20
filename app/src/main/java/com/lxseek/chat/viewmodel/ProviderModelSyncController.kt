package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ProviderModelSyncRequest(
    val failureLabels: ModelSyncFailureLabels,
    val globalProviderName: String,
)

internal data class ProviderModelSyncOutcome(
    val failures: List<ProviderModelSyncFailure>,
    val successfulProviderCount: Int,
    val skippedProviderCount: Int,
)

/**
 * Owns one full-provider model-sync lifecycle and its in-flight admission flag.
 *
 * Single-provider onboarding fetches deliberately bypass the full-sync flag and presentation
 * result. They retain caller-owned cancellation while sharing the same ProviderRegistry boundary.
 */
internal class ProviderModelSyncController(
    private val providers: ProviderRegistry,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    suspend fun fetchModelsForProvider(name: String): List<String> =
        providers.fetchModelsForProvider(name)

    fun computeFingerprint(): String = providers.computeFingerprint()

    fun start(
        request: ProviderModelSyncRequest,
        onCompleted: suspend (ProviderModelSyncOutcome) -> Unit,
    ) {
        scope.launch {
            if (_isSyncing.value) return@launch
            _isSyncing.value = true
            val outcome = try {
                synchronize(request)
            } finally {
                _isSyncing.value = false
            }
            onCompleted(outcome)
        }
    }

    private suspend fun synchronize(request: ProviderModelSyncRequest): ProviderModelSyncOutcome {
        val failures = mutableListOf<ProviderModelSyncFailure>()
        var successfulProviderCount = 0
        var skippedProviderCount = 0

        try {
            providers.ensureCustomProvidersRegistered()
            providers.all.forEach { (name, _) ->
                if (name == Constants.PROVIDER_LOCAL) return@forEach

                try {
                    if (!providers.isConfigured(name, settings.resolveActiveKey(name) ?: "")) {
                        skippedProviderCount++
                        settings.saveAvailableModels(name, emptyList())
                        return@forEach
                    }

                    providers.fetchModelsForProvider(name)
                    successfulProviderCount++
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failures += ProviderModelSyncFailure(
                        providerName = name,
                        reason = modelSyncFailureReason(error, request.failureLabels),
                    )
                }
            }

            val allKnownModels =
                settings.getAvailableModels().values.flatten().toSet() + settings.customModels.value
            settings.setEnabledModels(settings.enabledModels.value.intersect(allKnownModels))

            if (failures.isEmpty()) {
                settings.saveLastModelsFetchFingerprint(computeFingerprint())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failures += ProviderModelSyncFailure(
                providerName = request.globalProviderName,
                reason = modelSyncFailureReason(error, request.failureLabels),
            )
        }

        return ProviderModelSyncOutcome(
            failures = failures,
            successfulProviderCount = successfulProviderCount,
            skippedProviderCount = skippedProviderCount,
        )
    }
}
