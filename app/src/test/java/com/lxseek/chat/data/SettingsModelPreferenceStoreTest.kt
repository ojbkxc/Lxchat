package com.lxseek.chat.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelPreferenceStoreTest {
    @Test
    fun blankProviderBaseUrlRestoresDefaultByRemovingOverride() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)

        store.saveProviderBaseUrl("Anthropic", "https://relay.example/v1")
        assertEquals(
            mapOf("Anthropic" to "https://relay.example/v1"),
            store.providerBaseUrls.first(),
        )

        store.saveProviderBaseUrl("Anthropic", "  ")
        assertTrue(store.providerBaseUrls.first().isEmpty())
        assertEquals("{}", dataStore.data.first()[PROVIDER_BASE_URLS])
    }

    @Test
    fun addingCustomModelCommitsSelectionEnablementAndAliasTogether() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)

        store.addCustomModel("Gateway:model", "  Alias  ")

        assertEquals(setOf("Gateway:model"), store.customModels.first())
        assertEquals(setOf("Gateway:model"), store.enabledModels.first())
        assertEquals("Gateway:model", store.selectedModel.first())
        assertEquals(mapOf("Gateway:model" to "Alias"), store.modelAliases.first())
    }

    @Test
    fun replacingCustomModelRemapsEveryModelReferenceAtomically() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        store.addCustomModel("Gateway:old", "Old alias")
        dataStore.edit { preferences ->
            preferences[TITLE_GENERATION_MODEL] = "Gateway:old"
            preferences[CONTEXT_COMPACT_MODEL] = "Gateway:old"
            preferences[IMAGE_TRANSCRIPTION_ENABLED_MODELS] = setOf("Gateway:old")
        }

        store.replaceCustomModel("Gateway:old", "Gateway:new", "New alias")

        val preferences = dataStore.data.first()
        assertFalse("Gateway:old" in store.customModels.first())
        assertEquals(setOf("Gateway:new"), store.customModels.first())
        assertEquals(setOf("Gateway:new"), store.enabledModels.first())
        assertEquals("Gateway:new", store.selectedModel.first())
        assertEquals(mapOf("Gateway:new" to "New alias"), store.modelAliases.first())
        assertEquals("Gateway:new", preferences[TITLE_GENERATION_MODEL])
        assertEquals("Gateway:new", preferences[CONTEXT_COMPACT_MODEL])
        assertEquals(
            setOf("Gateway:new"),
            preferences[IMAGE_TRANSCRIPTION_ENABLED_MODELS],
        )
    }

    @Test
    fun invalidatingModelCachesClearsDerivedModelsEndpointsAndFingerprint() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        store.saveAvailableModels("Anthropic", listOf("claude"))
        store.saveCustomEndpointResolution(
            provider = "Gateway",
            resolution = CustomEndpointResolution(
                protocol = CustomEndpointProtocol.OPENAI,
                configuredBaseUrl = "https://gateway.example",
                effectiveBaseUrl = "https://gateway.example/v1",
            ),
        )
        store.saveLastModelsFetchFingerprint("fingerprint")

        store.invalidatePortableModelCaches()

        assertTrue(store.availableModels.first().isEmpty())
        assertTrue(store.customEndpointResolutions.first().isEmpty())
        assertEquals("", store.lastModelsFetchFingerprint.first())
    }

    private class InMemoryPreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it }
        }
    }

    private companion object {
        val testJson = Json { ignoreUnknownKeys = true }
    }
}
