package com.lxseek.chat.viewmodel

import kotlinx.coroutines.flow.StateFlow

internal data class ProviderModelSyncUiText(
    val failureLabels: ModelSyncFailureLabels,
    val globalProviderName: String,
    val successfulProviders: (Int) -> String,
    val noProviders: String,
    val completed: String,
)

/** Adapts provider model-sync intents and typed outcomes for the settings UI. */
internal class ProviderModelSyncUiAdapter(
    private val controller: ProviderModelSyncController,
    private val text: ProviderModelSyncUiText,
    private val publishMessage: suspend (String) -> Unit,
) {
    val isSyncing: StateFlow<Boolean> get() = controller.isSyncing

    /**
     * Onboarding-only fetch with no global sync admission, enabled-set intersection or snackbar.
     * The caller owns cancellation; successful results are persisted by the underlying controller.
     */
    suspend fun fetchModelsForProvider(name: String): List<String> =
        controller.fetchModelsForProvider(name)

    fun computeFingerprint(): String = controller.computeFingerprint()

    fun fetchAvailableModels() {
        controller.start(
            request = ProviderModelSyncRequest(
                failureLabels = text.failureLabels,
                globalProviderName = text.globalProviderName,
            ),
        ) { outcome ->
            publishMessage(providerModelSyncUiMessage(outcome, text))
        }
    }
}

internal fun providerModelSyncUiMessage(
    outcome: ProviderModelSyncOutcome,
    text: ProviderModelSyncUiText,
): String = providerModelSyncFailureMessage(outcome.failures) ?: when {
    outcome.successfulProviderCount > 0 -> text.successfulProviders(outcome.successfulProviderCount)
    outcome.skippedProviderCount > 0 -> text.noProviders
    else -> text.completed
}
