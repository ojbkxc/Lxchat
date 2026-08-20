package com.lxseek.chat.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.lxseek.chat.util.Constants
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class SettingsModelPreferenceStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    val selectedModel: Flow<String> = dataStore.data.map { it[SELECTED_MODEL] ?: Constants.EXAMPLE_MODEL_ID }

    val providerBaseUrls: Flow<Map<String, String>> = dataStore.data.map { pref ->
        val jsonStr = pref[PROVIDER_BASE_URLS] ?: "{}"
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { DebugLog.e("SettingsManager", "Failed to decode providerBaseUrls", e); emptyMap() }
    }

    val customEndpointResolutions: Flow<Map<String, CustomEndpointResolution>> = dataStore.data.map { pref ->
        val jsonStr = pref[CUSTOM_ENDPOINT_RESOLUTIONS_JSON] ?: "{}"
        try {
            json.decodeFromString<Map<String, CustomEndpointResolution>>(jsonStr)
        } catch (e: Exception) {
            DebugLog.e("SettingsManager", "Failed to decode customEndpointResolutions", e)
            emptyMap()
        }
    }

    val availableModels: Flow<Map<String, List<String>>> = dataStore.data.map { pref ->
        val jsonStr = pref[AVAILABLE_MODELS_JSON] ?: "{}"
        try { json.decodeFromString<Map<String, List<String>>>(jsonStr) } catch (e: Exception) { DebugLog.e("SettingsManager", "Failed to decode availableModels", e); emptyMap() }
    }

    val customModels: Flow<Set<String>> =
        dataStore.data.map { it[CUSTOM_MODELS] ?: emptySet() }

    val enabledModels: Flow<Set<String>> = dataStore.data.map { it[ENABLED_MODELS] ?: emptySet() }

    val modelAliases: Flow<Map<String, String>> = dataStore.data.map { pref ->
        val jsonStr = pref[MODEL_ALIASES_JSON] ?: "{}"
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }

    val apiKeys: Flow<List<ApiKeyEntry>> = dataStore.data.map { pref ->
        val jsonStr = com.lxseek.chat.util.SecretCrypto.decrypt(pref[API_KEYS_JSON] ?: "[]")
        try { json.decodeFromString<List<ApiKeyEntry>>(jsonStr) } catch (e: Exception) { emptyList() }
    }

    val activeApiKeyIds: Flow<Map<String, String>> = dataStore.data.map { pref ->
        val jsonStr = pref[ACTIVE_API_KEY_IDS_JSON] ?: "{}"
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }

    val localChatModels: Flow<List<LocalChatModelConfig>> = dataStore.data.map { pref ->
        val jsonStr = pref[LOCAL_CHAT_MODELS_JSON] ?: "[]"
        try { json.decodeFromString<List<LocalChatModelConfig>>(jsonStr) } catch (e: Exception) { emptyList() }
    }
    val customProviders: Flow<List<CustomProviderConfig>> = dataStore.data.map { pref ->
        val jsonStr = pref[CUSTOM_PROVIDERS_JSON] ?: "[]"
        try {
            val decoded = json.decodeFromString<List<CustomProviderConfig>>(jsonStr)
            val sanitized = CustomProviderNamePolicy.sanitize(decoded)
            if (sanitized.rejected.isNotEmpty()) {
                DebugLog.w(
                    "SettingsManager",
                    "Quarantined invalid custom provider names: " +
                        sanitized.rejected.joinToString { it.name },
                )
            }
            sanitized.accepted
        } catch (e: Exception) {
            DebugLog.e("SettingsManager", "Failed to decode customProviders", e)
            emptyList()
        }
    }

    val lastModelsFetchFingerprint: Flow<String> = dataStore.data.map { it[LAST_MODELS_FETCH_FINGERPRINT] ?: "" }

    suspend fun saveProviderBaseUrl(provider: String, url: String) {
        // Blank = "use the provider's default base URL". Persisting "" would poison the map
        // (callers that resolve an effective URL treat "" as a real override, not as absent),
        // so a blank value removes the key entirely — "absent" is the canonical "default" state.
        // rename/delete pass "" to clear an entry, which is exactly this semantics.
        if (url.isBlank()) {
            dataStore.edit { prefs ->
                val current = prefs[PROVIDER_BASE_URLS] ?: return@edit
                val map = try { json.decodeFromString<MutableMap<String, String>>(current) } catch (e: Exception) { return@edit }
                if (map.remove(provider) != null) prefs[PROVIDER_BASE_URLS] = json.encodeToString(map)
            }
            return
        }
        dataStore.edit { prefs ->
            val current = prefs[PROVIDER_BASE_URLS] ?: "{}"
            val map = try { json.decodeFromString<MutableMap<String, String>>(current) } catch (e: Exception) { mutableMapOf() }
            map[provider] = url
            prefs[PROVIDER_BASE_URLS] = json.encodeToString(map)
        }
    }

    suspend fun saveProviderBaseUrls(urls: Map<String, String>) {
        val normalized = urls
            .mapValues { (_, value) -> value.trim() }
            .filterValues { it.isNotBlank() }
        dataStore.edit { prefs ->
            if (normalized.isEmpty()) {
                prefs.remove(PROVIDER_BASE_URLS)
            } else {
                prefs[PROVIDER_BASE_URLS] = json.encodeToString(normalized)
            }
        }
    }

    suspend fun saveCustomEndpointResolution(
        provider: String,
        resolution: CustomEndpointResolution?,
    ) {
        dataStore.edit { prefs ->
            val current = prefs[CUSTOM_ENDPOINT_RESOLUTIONS_JSON] ?: "{}"
            val map = try {
                json.decodeFromString<MutableMap<String, CustomEndpointResolution>>(current)
            } catch (e: Exception) {
                mutableMapOf()
            }
            if (resolution == null) {
                map.remove(provider)
            } else {
                map[provider] = resolution
            }
            if (map.isEmpty()) {
                prefs.remove(CUSTOM_ENDPOINT_RESOLUTIONS_JSON)
            } else {
                prefs[CUSTOM_ENDPOINT_RESOLUTIONS_JSON] = json.encodeToString(map)
            }
        }
    }

    suspend fun renameCustomEndpointResolution(oldName: String, newName: String) {
        dataStore.edit { prefs ->
            val current = prefs[CUSTOM_ENDPOINT_RESOLUTIONS_JSON] ?: return@edit
            val map = try {
                json.decodeFromString<MutableMap<String, CustomEndpointResolution>>(current)
            } catch (e: Exception) {
                return@edit
            }
            val resolution = map.remove(oldName) ?: return@edit
            map[newName] = resolution
            prefs[CUSTOM_ENDPOINT_RESOLUTIONS_JSON] = json.encodeToString(map)
        }
    }

    suspend fun saveSelectedModel(model: String) {
        dataStore.edit { it[SELECTED_MODEL] = model }
    }

    suspend fun saveAvailableModels(provider: String, models: List<String>) {
        dataStore.edit { prefs ->
            val current = prefs[AVAILABLE_MODELS_JSON] ?: "{}"
            val map = try { json.decodeFromString<MutableMap<String, List<String>>>(current) } catch (e: Exception) { mutableMapOf() }
            map[provider] = models
            prefs[AVAILABLE_MODELS_JSON] = json.encodeToString(map)
        }
    }

    suspend fun saveCustomModels(models: Set<String>) {
        dataStore.edit { it[CUSTOM_MODELS] = models }
    }

    suspend fun addCustomModel(modelId: String, alias: String) {
        dataStore.edit { prefs ->
            prefs[CUSTOM_MODELS] = (prefs[CUSTOM_MODELS] ?: emptySet()) + modelId
            val enabledModels = (prefs[ENABLED_MODELS] ?: emptySet()) + modelId
            prefs[ENABLED_MODELS] = enabledModels
            if (prefs[SELECTED_MODEL].isNullOrBlank()) {
                prefs[SELECTED_MODEL] = modelId
            }

            val aliases = try {
                json.decodeFromString<Map<String, String>>(
                    prefs[MODEL_ALIASES_JSON] ?: "{}",
                )
            } catch (_: Exception) {
                emptyMap()
            }.toMutableMap()
            if (alias.isBlank()) {
                aliases.remove(modelId)
            } else {
                aliases[modelId] = alias.trim()
            }
            prefs[MODEL_ALIASES_JSON] = json.encodeToString(aliases)
        }
    }

    suspend fun replaceCustomModel(
        oldModelId: String,
        newModelId: String?,
        alias: String,
    ) {
        if (oldModelId == newModelId) {
            dataStore.edit { prefs ->
                val aliases = try {
                    json.decodeFromString<Map<String, String>>(
                        prefs[MODEL_ALIASES_JSON] ?: "{}",
                    )
                } catch (_: Exception) {
                    emptyMap()
                }.replaceCustomModelAlias(oldModelId, newModelId, alias)
                prefs[MODEL_ALIASES_JSON] = json.encodeToString(aliases)
            }
            return
        }

        dataStore.edit { prefs ->
            val customModels = prefs[CUSTOM_MODELS] ?: emptySet()
            if (oldModelId !in customModels) return@edit

            prefs[CUSTOM_MODELS] =
                customModels.replaceModelReference(oldModelId, newModelId)

            val updatedEnabled =
                (prefs[ENABLED_MODELS] ?: emptySet())
                    .replaceModelReference(oldModelId, newModelId)
            prefs[ENABLED_MODELS] = updatedEnabled

            val aliases = try {
                json.decodeFromString<Map<String, String>>(
                    prefs[MODEL_ALIASES_JSON] ?: "{}",
                )
            } catch (_: Exception) {
                emptyMap()
            }.replaceCustomModelAlias(oldModelId, newModelId, alias)
            prefs[MODEL_ALIASES_JSON] = json.encodeToString(aliases)

            if (prefs[SELECTED_MODEL] == oldModelId) {
                prefs[SELECTED_MODEL] =
                    newModelId ?: updatedEnabled.firstOrNull().orEmpty()
            }

            val updatedTranscriptionTargets =
                (prefs[IMAGE_TRANSCRIPTION_ENABLED_MODELS] ?: emptySet())
                    .replaceModelReference(oldModelId, newModelId)
            prefs[IMAGE_TRANSCRIPTION_ENABLED_MODELS] = updatedTranscriptionTargets

            fun replaceNullableReference(key: androidx.datastore.preferences.core.Preferences.Key<String>) {
                when (
                    val updated = prefs[key].replaceModelReference(
                        oldModelId,
                        newModelId,
                    )
                ) {
                    null -> prefs.remove(key)
                    else -> prefs[key] = updated
                }
            }

            replaceNullableReference(TITLE_GENERATION_MODEL)
            replaceNullableReference(IMAGE_TRANSCRIPTION_MODEL)
            replaceNullableReference(IMAGE_GEN_MODEL)
            replaceNullableReference(CONTEXT_COMPACT_MODEL)
        }
    }

    suspend fun saveEnabledModels(models: Set<String>) {
        dataStore.edit { it[ENABLED_MODELS] = models }
    }

    suspend fun saveModelAliases(aliases: Map<String, String>) {
        dataStore.edit { it[MODEL_ALIASES_JSON] = json.encodeToString(aliases) }
    }

    suspend fun saveApiKeys(keys: List<ApiKeyEntry>) {
        dataStore.edit { it[API_KEYS_JSON] = com.lxseek.chat.util.SecretCrypto.encrypt(json.encodeToString(keys)) }
    }

    suspend fun saveActiveApiKeyIds(ids: Map<String, String>) {
        dataStore.edit { prefs ->
            if (ids.isEmpty()) {
                prefs.remove(ACTIVE_API_KEY_IDS_JSON)
            } else {
                prefs[ACTIVE_API_KEY_IDS_JSON] = json.encodeToString(ids)
            }
        }
    }

    suspend fun setActiveApiKeyId(provider: String, id: String?) {
        dataStore.edit { prefs ->
            val current = prefs[ACTIVE_API_KEY_IDS_JSON] ?: "{}"
            val map = try { json.decodeFromString<MutableMap<String, String>>(current) } catch (e: Exception) { mutableMapOf() }
            if (id == null) map.remove(provider) else map[provider] = id
            prefs[ACTIVE_API_KEY_IDS_JSON] = json.encodeToString(map)
        }
    }

    /**
     * Atomically rename the provider field on every API key entry for [oldProvider] to
     * [newProvider] and remap the active-key-id in the same DataStore edit. Decryption or
     * parse failures leave the raw encrypted blobs completely untouched (fail-preserving),
     * so a rename can never wipe keys due to a transient Keystore error.
     */
    suspend fun renameApiKeyProvider(oldProvider: String, newProvider: String) {
        dataStore.edit { prefs ->
            val rawKeys = prefs[API_KEYS_JSON] ?: return@edit
            val decrypted = runCatching {
                com.lxseek.chat.util.SecretCrypto.decrypt(rawKeys)
            }.getOrDefault(rawKeys)
            val keys = runCatching {
                json.decodeFromString<List<ApiKeyEntry>>(decrypted)
            }.getOrNull() ?: return@edit
            val renamed = keys.map { entry ->
                if (entry.provider == oldProvider) entry.copy(provider = newProvider) else entry
            }
            if (renamed != keys) {
                prefs[API_KEYS_JSON] = com.lxseek.chat.util.SecretCrypto.encrypt(
                    json.encodeToString(renamed)
                )
            }
            // Remap active-key-id in the same edit so the active key follows the rename.
            val rawIds = prefs[ACTIVE_API_KEY_IDS_JSON] ?: "{}"
            val ids = runCatching {
                json.decodeFromString<MutableMap<String, String>>(rawIds)
            }.getOrNull()
            if (ids != null && ids.containsKey(oldProvider)) {
                ids[newProvider] = ids.remove(oldProvider)!!
                prefs[ACTIVE_API_KEY_IDS_JSON] = json.encodeToString(ids)
            }
        }
    }

    /** Remaps every configured model reference whose provider component was renamed. */
    suspend fun renameProviderModelReferences(oldProvider: String, newProvider: String) {
        val oldPrefix = "$oldProvider:"
        val newPrefix = "$newProvider:"
        fun String.remapProvider(): String =
            if (startsWith(oldPrefix)) newPrefix + removePrefix(oldPrefix) else this

        dataStore.edit { prefs ->
            prefs[CUSTOM_MODELS] = (prefs[CUSTOM_MODELS] ?: emptySet()).mapTo(linkedSetOf()) {
                it.remapProvider()
            }
            prefs[ENABLED_MODELS] = (prefs[ENABLED_MODELS] ?: emptySet()).mapTo(linkedSetOf()) {
                it.remapProvider()
            }
            prefs[IMAGE_TRANSCRIPTION_ENABLED_MODELS] =
                (prefs[IMAGE_TRANSCRIPTION_ENABLED_MODELS] ?: emptySet()).mapTo(linkedSetOf()) {
                    it.remapProvider()
                }
            val aliases = runCatching {
                json.decodeFromString<Map<String, String>>(prefs[MODEL_ALIASES_JSON] ?: "{}")
            }.getOrDefault(emptyMap())
            prefs[MODEL_ALIASES_JSON] = json.encodeToString(
                aliases.mapKeys { (modelId, _) -> modelId.remapProvider() }
            )
            listOf(
                SELECTED_MODEL,
                TITLE_GENERATION_MODEL,
                IMAGE_TRANSCRIPTION_MODEL,
                IMAGE_GEN_MODEL,
                CONTEXT_COMPACT_MODEL,
            ).forEach { key ->
                prefs[key]?.let { modelId -> prefs[key] = modelId.remapProvider() }
            }
        }
    }

    suspend fun saveLocalChatModels(models: List<LocalChatModelConfig>) {
        dataStore.edit { it[LOCAL_CHAT_MODELS_JSON] = json.encodeToString(models) }
    }
    suspend fun saveCustomProviders(providers: List<CustomProviderConfig>) {
        val sanitized = CustomProviderNamePolicy.sanitize(providers)
        dataStore.edit {
            it[CUSTOM_PROVIDERS_JSON] = json.encodeToString(sanitized.accepted)
        }
    }

    suspend fun saveLastModelsFetchFingerprint(fingerprint: String) {
        dataStore.edit { it[LAST_MODELS_FETCH_FINGERPRINT] = fingerprint }
    }

    suspend fun invalidatePortableModelCaches() {
        dataStore.edit { prefs ->
            prefs.remove(AVAILABLE_MODELS_JSON)
            prefs.remove(CUSTOM_ENDPOINT_RESOLUTIONS_JSON)
            prefs.remove(LAST_MODELS_FETCH_FINGERPRINT)
        }
    }
}
