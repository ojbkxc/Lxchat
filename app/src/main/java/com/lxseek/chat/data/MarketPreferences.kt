package com.lxseek.chat.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Offline persistence for the plugin market.
 *
 * Raw JSON is stored as-is and parsed by the market service; the data module
 * deliberately stays unaware of market model shapes.
 */
class MarketPreferences(private val store: DataStore<Preferences>) {
    val sourcesJson: Flow<String> = store.data.map { it[MARKET_SOURCES_JSON] ?: "" }
    val installedJson: Flow<String> = store.data.map { it[MARKET_INSTALLED_JSON] ?: "" }

    /** Persist the whole market-sources JSON array. */
    suspend fun saveSources(json: String) {
        store.edit { it[MARKET_SOURCES_JSON] = json }
    }

    /** Persist the whole installed-plugins JSON array. */
    suspend fun saveInstalled(json: String) {
        store.edit { it[MARKET_INSTALLED_JSON] = json }
    }
}