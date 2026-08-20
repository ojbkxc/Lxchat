package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.CustomEndpointProtocol
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.ModelId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns custom Provider configuration and serialized custom-model reference migrations. */
internal class CustomModelConfigurationController(
    private val providers: ProviderRegistry,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val onModelReferenceReplaced: (oldModelId: String, newModelId: String?) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutationMutex = Mutex()

    fun addProvider(
        name: String,
        baseUrl: String,
        protocol: CustomEndpointProtocol = CustomEndpointProtocol.OPENAI,
    ) = providers.addCustom(name, baseUrl, protocol)

    fun renameProvider(oldName: String, newName: String) {
        if (!providers.renameCustom(oldName, newName)) return
        scope.launch(ioDispatcher) {
            conversations.renameConfiguredProviderModelReferences(oldName, newName.trim())
        }
    }

    fun updateProviderProtocol(name: String, protocol: CustomEndpointProtocol) =
        providers.updateCustomProtocol(name, protocol)

    fun deleteProvider(name: String) = providers.deleteCustom(name)

    fun updateModel(
        oldModelId: String,
        provider: String,
        modelId: String,
        alias: String,
    ) {
        val normalizedProvider = provider.trim()
        val normalizedModelId = modelId.trim()
        if (normalizedProvider.isEmpty() || normalizedModelId.isEmpty()) return
        val newModelId = ModelId(normalizedProvider, normalizedModelId).prefixed

        scope.launch(ioDispatcher) {
            mutationMutex.withLock {
                val customModels = settings.customModels.value
                if (oldModelId !in customModels) return@withLock
                if (newModelId != oldModelId && newModelId in customModels) return@withLock

                settings.replaceCustomModel(oldModelId, newModelId, alias)
                conversations.replaceConfiguredModelReferences(oldModelId, newModelId)
                onModelReferenceReplaced(oldModelId, newModelId)
            }
        }
    }

    fun deleteModel(modelId: String) {
        scope.launch(ioDispatcher) {
            mutationMutex.withLock {
                if (modelId !in settings.customModels.value) return@withLock

                settings.replaceCustomModel(modelId, null, "")
                conversations.replaceConfiguredModelReferences(modelId, null)
                onModelReferenceReplaced(modelId, null)
            }
        }
    }
}
