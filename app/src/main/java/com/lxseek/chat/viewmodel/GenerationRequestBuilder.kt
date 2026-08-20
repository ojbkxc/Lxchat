package com.lxseek.chat.viewmodel

import android.content.Context
import com.lxseek.chat.R
import com.lxseek.chat.data.ConversationSettings
import com.lxseek.chat.data.MemoryManager
import com.lxseek.chat.data.PredefinedVariables
import com.lxseek.chat.data.isOpenAiProtocolProvider
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.model.ContextBudget
import com.lxseek.chat.model.OpenAiServiceTiers
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Stateless builder for the LLM generation request. Extracted from ChatViewModel.
 * Reads configuration singletons only; holds NO mutable UI state.
 */
class GenerationRequestBuilder(
    private val settings: SettingsRepository,
    private val convRepo: ConversationRepository,
    private val memoryManager: MemoryManager,
    private val providerRegistry: ProviderRegistry,
    private val ragManager: RagManager,
    private val appContext: Context,
    // _pendingConversationSettings 也是 StateFlow,buildEffectiveConversationSettings 读它的 .value
    private val pendingConversationSettings: StateFlow<ConversationSettings?>,
    // resolveProviderKey 需要 emit snackbar
    private val onSnackbar: (String) -> Unit,
) {
    data class ProviderKey(val providerName: String, val apiKey: String)

    /** Resolves the active provider+key for [modelId] and verifies configuration.
     *  Emits a snackbar and returns null when the provider is not configured. */
    internal fun resolveProviderKey(modelId: String): ProviderKey? {
        val providerName = providerRegistry.providerForModel(modelId)
        val activeKey = settings.resolveActiveKey(providerName) ?: ""
        if (!providerRegistry.isConfigured(providerName, activeKey)) {
            onSnackbar(appContext.getString(R.string.no_api_key_for_provider, providerName))
            return null
        }
        return ProviderKey(providerName, activeKey)
    }

    private fun resolveTranscriptionProviderName(): String =
        settings.imageTranscriptionModel.value?.let { providerRegistry.providerForModel(it) } ?: ""

    private fun resolveTranscriptionModelId(): String =
        settings.imageTranscriptionModel.value?.let { ModelId.parse(it).modelName } ?: ""

    private fun resolveTranscriptionApiKey(): String {
        val model = settings.imageTranscriptionModel.value ?: return ""
        val providerName = providerRegistry.providerForModel(model)
        if (providerName == Constants.PROVIDER_LOCAL) return ""
        return settings.resolveActiveKey(providerName) ?: ""
    }

    private fun resolveTranscriptionBaseUrl(): String? {
        val model = settings.imageTranscriptionModel.value ?: return null
        return providerRegistry.getEffectiveBaseUrl(providerRegistry.providerForModel(model))
    }

    // Image generation reuses the selected model's provider credentials (mirrors transcription).
    private fun resolveImageGenModelId(): String =
        settings.imageGenModel.value?.let { ModelId.parse(it).apiModelName } ?: ""

    private fun resolveImageGenApiKey(): String {
        val model = settings.imageGenModel.value ?: return ""
        val providerName = providerRegistry.providerForModel(model)
        if (providerName == Constants.PROVIDER_LOCAL) return ""
        return settings.resolveActiveKey(providerName) ?: ""
    }

    private fun resolveImageGenBaseUrl(): String {
        val model = settings.imageGenModel.value ?: return ""
        return providerRegistry.getEffectiveBaseUrl(providerRegistry.providerForModel(model)) ?: ""
    }

    fun buildEffectiveConversationSettings(conversationId: String): ConversationSettings {
        val overrides = settings.conversationSettings.value[conversationId]
            ?: pendingConversationSettings.value  // new chat: may not be saved to map yet
            ?: ConversationSettings()
        return ConversationSettings(
            contextWindow = ContextBudget.normalize(
                overrides.contextWindow ?: settings.maxContextWindow.value
            ),
            temperature = overrides.temperature ?: settings.defaultTemperature.value,
            maxTokens = overrides.maxTokens ?: settings.defaultMaxTokens.value,
            topP = overrides.topP ?: settings.defaultTopP.value,
            frequencyPenalty = overrides.frequencyPenalty ?: settings.defaultFrequencyPenalty.value,
            presencePenalty = overrides.presencePenalty ?: settings.defaultPresencePenalty.value,
            codeExecutionEnabled = overrides.codeExecutionEnabled ?: settings.codeExecutionEnabled.value,
            googleSearchEnabled = overrides.googleSearchEnabled ?: settings.googleSearchEnabled.value,
            thinkingEnabled = overrides.thinkingEnabled ?: settings.thinkingEnabled.value,
            thinkingLevel = overrides.thinkingLevel ?: settings.thinkingLevel.value,
            thinkingBudgetEnabled = overrides.thinkingBudgetEnabled ?: settings.thinkingBudgetEnabled.value,
            thinkingBudgetTokens = overrides.thinkingBudgetTokens ?: settings.thinkingBudgetTokens.value,
            openAiServiceTierEnabled =
                overrides.openAiServiceTierEnabled ?: settings.openAiServiceTierEnabled.value,
            openAiServiceTier = OpenAiServiceTiers.normalize(
                overrides.openAiServiceTier ?: settings.openAiServiceTier.value,
            ),
            webSearchEnabled = if (settings.webSearchEnabled.value) (overrides.webSearchEnabled ?: true) else false,
            shellEnabled = if (settings.shellEnabled.value) (overrides.shellEnabled ?: true) else false
        )
    }

    internal suspend fun buildGenerationPair(
        providerName: String,
        modelId: String,
        activeKey: String,
        resolvedSystemPrompt: String?,
        resolvedUserPrepend: String?,
        resolvedUserPostpend: String?,
        effectiveSettings: ConversationSettings,
        currentId: String
    ): Pair<GenerationConfig, GenerationContext> = withContext(Dispatchers.Default) {
        val config = GenerationConfig(
            providerName = providerName,
            modelId = ModelId.parse(modelId).modelName,
            apiKey = activeKey,
            effectiveSystemPrompt = resolvedSystemPrompt,
            maxContextWindow = ContextBudget.normalize(
                effectiveSettings.contextWindow ?: settings.maxContextWindow.value
            ),
            codeExecutionEnabled = effectiveSettings.codeExecutionEnabled ?: settings.codeExecutionEnabled.value,
            googleSearchEnabled = effectiveSettings.googleSearchEnabled ?: settings.googleSearchEnabled.value,
            thinkingEnabled = effectiveSettings.thinkingEnabled ?: settings.thinkingEnabled.value,
            thinkingLevel = effectiveSettings.thinkingLevel ?: settings.thinkingLevel.value,
            thinkingBudgetEnabled = effectiveSettings.thinkingBudgetEnabled ?: settings.thinkingBudgetEnabled.value,
            thinkingBudgetTokens = effectiveSettings.thinkingBudgetTokens ?: settings.thinkingBudgetTokens.value,
            openAiServiceTier = OpenAiServiceTiers.requestValue(
                enabled = effectiveSettings.openAiServiceTierEnabled == true &&
                    isOpenAiProtocolProvider(providerName, settings.customProviders.value),
                value = effectiveSettings.openAiServiceTier,
            ),
            baseUrl = providerRegistry.getEffectiveBaseUrl(providerName),
            userPrepend = resolvedUserPrepend,
            userPostpend = resolvedUserPostpend,
            temperature = effectiveSettings.temperature,
            maxTokens = effectiveSettings.maxTokens,
            topP = effectiveSettings.topP,
            frequencyPenalty = effectiveSettings.frequencyPenalty,
            presencePenalty = effectiveSettings.presencePenalty
        )
        val genCtx = GenerationContext(
            conversationId = currentId,
            accessSavedMemories = settings.accessSavedMemories.value,
            accessActiveMemory = settings.accessActiveMemory.value,
            accessPastConversations = settings.accessPastConversations.value,
            modelSearchMethod = settings.modelSearchMethod.value,
            activeEmbeddingConfig = ragManager.activeEmbeddingModel.value,
            embeddingApiKey = ragManager.resolveEmbeddingApiKey() ?: "",
            ragThreshold = settings.ragThreshold.value,
            searchMatchLimit = settings.searchMatchLimit.value,
            searchContextWindow = settings.searchContextWindow.value,
            webSearchEnabled = effectiveSettings.webSearchEnabled ?: settings.webSearchEnabled.value,
            webSearchApiKeys = settings.webSearchApiKeys.value,
            webSearchProvider = settings.webSearchProvider.value,
            webSearchNumResults = settings.webSearchNumResults.value,
            webSearchBaseUrl = settings.webSearchBaseUrl.value,
            imageGenEnabled = settings.imageGenEnabled.value && settings.imageGenModel.value?.contains(":") == true,
            imageGenApiKey = resolveImageGenApiKey(),
            imageGenBaseUrl = resolveImageGenBaseUrl(),
            imageGenModel = resolveImageGenModelId(),
            imageGenSize = settings.imageGenSize.value,
            automationToolsEnabled = settings.automationToolsEnabled.value,
            shellEnabled = effectiveSettings.shellEnabled ?: settings.shellEnabled.value,
            shellDevices = settings.shellDevices.value,
            sandboxEnabled = settings.sandboxEnabled.value,
            sandboxSharedStorageEnabled = settings.sandboxSharedStorageEnabled.value,
            // Keyed on THIS generation's model, not the UI's currently-selected one — a queued
            // or parallel-conversation generation must not inherit another conversation's model.
            imageTranscriptionEnabled =
                settings.imageTranscriptionEnabled.value &&
                    settings.imageTranscriptionEnabledModels.value.contains(modelId),
            imageTranscriptionModel = settings.imageTranscriptionModel.value,
            imageTranscriptionBatchSize = settings.imageTranscriptionBatchSize.value,
            imageTranscriptionPrompt = settings.imageTranscriptionPrompt.value,
            transcriptionProviderName = resolveTranscriptionProviderName(),
            transcriptionModelId = resolveTranscriptionModelId(),
            transcriptionApiKey = resolveTranscriptionApiKey(),
            transcriptionBaseUrl = resolveTranscriptionBaseUrl()
        )
        Pair(config, genCtx)
    }

    data class ResolvedPrompt(
        val systemPrompt: String?,
        val userPrepend: String?,
        val userPostpend: String?
    )

    /** [activeModel] is the full "provider:model" string of the generation being built. */
    internal suspend fun buildEffectiveSystemPrompt(
        currentId: String,
        activeModel: String,
    ): ResolvedPrompt = withContext(Dispatchers.Default) {
        coroutineScope {
            val includeActiveMemory = settings.accessActiveMemory.value
            // Room and the optional memory-file read are independent. Running both immediately avoids
            // adding their latencies serially to the visible Sending phase.
            val conversationDeferred = async {
                convRepo.getConversation(currentId)
            }
            val activeMemoryDeferred = async(Dispatchers.IO) {
                if (includeActiveMemory) memoryManager.getActiveMemory() else ""
            }
            val conversation = conversationDeferred.await()
            val targetPromptId = conversation?.systemPromptId ?: settings.activeSystemPromptId.value
            val entry = settings.systemPrompts.value.find { it.id == targetPromptId }
            val activeMemory = activeMemoryDeferred.await()
            val modelId = ModelId.parse(activeModel).modelName

            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            val dateSdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val now = java.util.Date()

            val runtimeValues = mapOf(
                PredefinedVariables.TIME to sdf.format(now),
                PredefinedVariables.DATE to dateSdf.format(now),
                PredefinedVariables.SENT_TIME to sdf.format(now),
                PredefinedVariables.SENT_DATE to dateSdf.format(now),
                PredefinedVariables.MODEL_ID to modelId,
                PredefinedVariables.ACTIVE_MEMORY to if (includeActiveMemory && activeMemory.isNotBlank()) activeMemory else ""
            )

            if (entry != null) {
                val systemItems = entry.resolvedSystemItems
                // Prepend/postpend: {sent_time}/{sent_date} stay as placeholders resolved per-message in applyUserTemplate
                val perMsgValues = runtimeValues.filterKeys { it !in PredefinedVariables.PER_MESSAGE_VARS }
                return@coroutineScope ResolvedPrompt(
                    systemPrompt = PredefinedVariables.compile(systemItems, runtimeValues).ifBlank { null },
                    userPrepend = PredefinedVariables.compile(entry.userPrependItems, perMsgValues, emptyMap()).ifBlank { null },
                    userPostpend = PredefinedVariables.compile(entry.userPostpendItems, perMsgValues, emptyMap()).ifBlank { null }
                )
            }

            ResolvedPrompt(null, null, null)
        }
    }
}
