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
            val currentEnabled = settings.enabledModels.value
            val newEnabled = currentEnabled.intersect(allKnownModels)
            // 首次同步（用户尚未勾选任何模型）且内置默认模型已成功拉取时，
            // 自动启用 lxchat:glm-4.7-flash，新装即可直接对话而无需手动勾选。
            // One-shot latch: auto-enable only runs before the latch is persisted, so a
            // deliberate full deselect by the user is never re-added by later syncs.
            val effectiveEnabled =
                if (currentEnabled.isEmpty() &&
                    !settings.lxChatDefaultAutoEnabled.value &&
                    Constants.EXAMPLE_MODEL_ID in allKnownModels
                ) {
                    settings.markLxChatDefaultAutoEnabled()
                    newEnabled + Constants.EXAMPLE_MODEL_ID
                } else {
                    newEnabled
                }
            settings.setEnabledModels(effectiveEnabled)

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
