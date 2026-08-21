package com.lxseek.chat.im

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Owns the persisted [ImGatewayConfig] in its own encrypted DataStore, independent of the
 * core settings store. Kept separate so the legacy, already-999-line SettingsManager stays
 * untouched by IM. */
internal val Context.imGatewayDataStore by preferencesDataStore(name = "im_gateway")

private val IM_GATEWAY_CONFIG_JSON = stringPreferencesKey("im_gateway_config_json")

/** Persisted runtime state (conversation bindings + seen-message set), encrypted like config. */
private val IM_GATEWAY_STATE_JSON = stringPreferencesKey("im_gateway_state_json")

class ImGatewayStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /** The latest persisted IM gateway configuration. */
    val config: Flow<ImGatewayConfig> = context.imGatewayDataStore.data.map { pref ->
        val jsonStr = com.lxseek.chat.util.SecretCrypto.decrypt(pref[IM_GATEWAY_CONFIG_JSON] ?: "{}")
        try {
            json.decodeFromString<ImGatewayConfig>(jsonStr)
        } catch (e: Exception) {
            DebugLog.e("ImGatewayStore", "Failed to decode IM gateway config", e)
            ImGatewayConfig()
        }
    }

    /** The latest persisted IM runtime state. */
    val runtimeState: Flow<ImRuntimeState> = context.imGatewayDataStore.data.map { pref ->
        val jsonStr = com.lxseek.chat.util.SecretCrypto.decrypt(pref[IM_GATEWAY_STATE_JSON] ?: "{}")
        try {
            json.decodeFromString<ImRuntimeState>(jsonStr)
        } catch (e: Exception) {
            DebugLog.e("ImGatewayStore", "Failed to decode IM runtime state", e)
            ImRuntimeState()
        }
    }

    /** Persist a new IM gateway configuration (encrypted, like MCP servers). */
    suspend fun save(config: ImGatewayConfig) {
        context.imGatewayDataStore.edit {
            it[IM_GATEWAY_CONFIG_JSON] =
                com.lxseek.chat.util.SecretCrypto.encrypt(json.encodeToString(config))
        }
    }

    /** Atomic, read-modify-write of the runtime state. */
    suspend fun updateState(transform: (ImRuntimeState) -> ImRuntimeState) {
        context.imGatewayDataStore.edit { pref ->
            val current = runCatching {
                json.decodeFromString<ImRuntimeState>(
                    com.lxseek.chat.util.SecretCrypto.decrypt(pref[IM_GATEWAY_STATE_JSON] ?: "{}"),
                )
            }.getOrDefault(ImRuntimeState())
            val next = transform(current)
            pref[IM_GATEWAY_STATE_JSON] =
                com.lxseek.chat.util.SecretCrypto.encrypt(json.encodeToString(next))
        }
    }

    /** Clear all bindings and seen-set (e.g. when the gateway is removed or switched). */
    suspend fun clearRuntimeState() = updateState { ImRuntimeState() }
}