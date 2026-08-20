package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Projects configured local chat models into provider model ids and aliases. */
internal class LocalModelCatalogSynchronizer(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            var lastLocalIds: List<String>? = null
            var lastAliases: Map<String, String>? = null
            settings.localChatModels.collect { models ->
                val localIds = models.map { "Local:${it.modelId}" }
                val aliases = settings.getModelAliases().toMutableMap().apply {
                    models.forEach { put("Local:${it.modelId}", it.alias) }
                }
                if (localIds != lastLocalIds) {
                    settings.saveAvailableModels(Constants.PROVIDER_LOCAL, localIds)
                    lastLocalIds = localIds
                }
                if (aliases != lastAliases) {
                    settings.saveModelAliases(aliases)
                    lastAliases = aliases
                }
            }
        }
    }
}
