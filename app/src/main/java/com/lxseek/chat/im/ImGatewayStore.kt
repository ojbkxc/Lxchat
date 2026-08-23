package com.lxseek.chat.im

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Owns the persisted IM gateway configs in its own encrypted DataStore, independent of the
 * core settings store. Kept separate so the legacy, already-999-line SettingsManager stays
 * untouched by IM. */
internal val Context.imGatewayDataStore by preferencesDataStore(name = "im_gateway")

private val IM_GATEWAY_CONFIG_JSON = stringPreferencesKey("im_gateway_config_json")

/** Persisted runtime state (conversation bindings + seen-message set), encrypted like config. */
private val IM_GATEWAY_STATE_JSON = stringPreferencesKey("im_gateway_state_json")

/** Multi-channel config (Map<platform, List<bots>>), encrypted. Source of truth for the bridge. */
private val IM_MULTI_CONFIG_JSON = stringPreferencesKey("im_multi_config_json")

/** Multi-channel runtime state (Map<channelId, ImRuntimeState>), encrypted. */
private val IM_MULTI_STATE_JSON = stringPreferencesKey("im_multi_state_json")

class ImGatewayStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Legacy single-config API (kept for backward compatibility with SettingsRepository
    //    and the existing SettingsImGatewayPage UI). New multi-bot UI should use the multi-* APIs. ──

    /** The latest persisted IM gateway configuration (legacy single-config view). */
    val config: Flow<ImGatewayConfig> = context.imGatewayDataStore.data.map { pref ->
        val jsonStr = com.lxseek.chat.util.SecretCrypto.decrypt(pref[IM_GATEWAY_CONFIG_JSON] ?: "{}")
        try {
            json.decodeFromString<ImGatewayConfig>(jsonStr)
        } catch (e: Exception) {
            DebugLog.e("ImGatewayStore", "Failed to decode IM gateway config", e)
            ImGatewayConfig()
        }
    }

    /** The latest persisted IM runtime state (legacy single-channel view). */
    val runtimeState: Flow<ImRuntimeState> = context.imGatewayDataStore.data.map { pref ->
        val jsonStr = com.lxseek.chat.util.SecretCrypto.decrypt(pref[IM_GATEWAY_STATE_JSON] ?: "{}")
        try {
            json.decodeFromString<ImRuntimeState>(jsonStr)
        } catch (e: Exception) {
            DebugLog.e("ImGatewayStore", "Failed to decode IM runtime state", e)
            ImRuntimeState()
        }
    }

    /** Persist a new IM gateway configuration (encrypted, like MCP servers). Legacy single-config. */
    suspend fun save(config: ImGatewayConfig) {
        context.imGatewayDataStore.edit {
            it[IM_GATEWAY_CONFIG_JSON] =
                com.lxseek.chat.util.SecretCrypto.encrypt(json.encodeToString(config))
        }
    }

    /** Atomic, read-modify-write of the runtime state (legacy single-channel). */
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

    // ── Multi-channel, multi-bot API (source of truth for ImBridgeService / ImPollingReceiver) ──

    /** The persisted multi-channel, multi-bot configuration. */
    val multiConfig: Flow<ImMultiGatewayConfig> = context.imGatewayDataStore.data.map { pref ->
        val jsonStr = com.lxseek.chat.util.SecretCrypto.decrypt(pref[IM_MULTI_CONFIG_JSON] ?: "{}")
        try {
            json.decodeFromString<ImMultiGatewayConfig>(jsonStr)
        } catch (e: Exception) {
            DebugLog.e("ImGatewayStore", "Failed to decode IM multi config", e)
            ImMultiGatewayConfig()
        }
    }

    /** Per-channel runtime state, keyed by [ImGatewayConfig.effectiveChannelId]. */
    val multiRuntimeState: Flow<Map<String, ImRuntimeState>> = context.imGatewayDataStore.data.map { pref ->
        val jsonStr = com.lxseek.chat.util.SecretCrypto.decrypt(pref[IM_MULTI_STATE_JSON] ?: "{}")
        try {
            json.decodeFromString<Map<String, ImRuntimeState>>(jsonStr)
        } catch (e: Exception) {
            DebugLog.e("ImGatewayStore", "Failed to decode IM multi runtime state", e)
            emptyMap()
        }
    }

    /** Persist the full multi-channel, multi-bot configuration. */
    suspend fun saveMultiConfig(config: ImMultiGatewayConfig) {
        context.imGatewayDataStore.edit {
            it[IM_MULTI_CONFIG_JSON] =
                com.lxseek.chat.util.SecretCrypto.encrypt(json.encodeToString(config))
        }
    }

    /** Upsert a single bot config into the multi-config (keyed by effectiveChannelId). */
    suspend fun upsertBot(config: ImGatewayConfig) {
        context.imGatewayDataStore.edit { pref ->
            val current = decodeMultiConfig(pref)
            val next = current.upsert(config)
            pref[IM_MULTI_CONFIG_JSON] =
                com.lxseek.chat.util.SecretCrypto.encrypt(json.encodeToString(next))
        }
    }

    /** Remove a bot from the multi-config by platform + channelId. */
    suspend fun removeBot(platform: String, channelId: String) {
        context.imGatewayDataStore.edit { pref ->
            val current = decodeMultiConfig(pref)
            val next = current.remove(platform, channelId)
            pref[IM_MULTI_CONFIG_JSON] =
                com.lxseek.chat.util.SecretCrypto.encrypt(json.encodeToString(next))
        }
    }

    /** Atomic, read-modify-write of one channel's runtime state in the multi-channel map. */
    suspend fun updateChannelState(
        channelId: String,
        transform: (ImRuntimeState) -> ImRuntimeState,
    ) {
        context.imGatewayDataStore.edit { pref ->
            val currentMap = decodeMultiRuntimeState(pref)
            val current = currentMap[channelId] ?: ImRuntimeState(channelId = channelId)
            val next = transform(current)
            pref[IM_MULTI_STATE_JSON] =
                com.lxseek.chat.util.SecretCrypto.encrypt(
                    json.encodeToString(currentMap + (channelId to next)),
                )
        }
    }

    /** Clear the runtime state for one channel in the multi-channel map. */
    suspend fun clearChannelRuntimeState(channelId: String) {
        context.imGatewayDataStore.edit { pref ->
            val currentMap = decodeMultiRuntimeState(pref)
            pref[IM_MULTI_STATE_JSON] =
                com.lxseek.chat.util.SecretCrypto.encrypt(
                    json.encodeToString(currentMap - channelId),
                )
        }
    }

    /** Clear every channel's runtime state in the multi-channel map. */
    suspend fun clearAllChannelRuntimeState() {
        context.imGatewayDataStore.edit { pref ->
            pref[IM_MULTI_STATE_JSON] =
                com.lxseek.chat.util.SecretCrypto.encrypt(json.encodeToString(emptyMap<String, ImRuntimeState>()))
        }
    }

    // ── Internal decode helpers ───────────────────────────────

    private fun decodeMultiConfig(pref: androidx.datastore.preferences.core.Preferences): ImMultiGatewayConfig =
        runCatching {
            json.decodeFromString<ImMultiGatewayConfig>(
                com.lxseek.chat.util.SecretCrypto.decrypt(pref[IM_MULTI_CONFIG_JSON] ?: "{}"),
            )
        }.getOrDefault(ImMultiGatewayConfig())

    private fun decodeMultiRuntimeState(pref: androidx.datastore.preferences.core.Preferences): Map<String, ImRuntimeState> =
        runCatching {
            json.decodeFromString<Map<String, ImRuntimeState>>(
                com.lxseek.chat.util.SecretCrypto.decrypt(pref[IM_MULTI_STATE_JSON] ?: "{}"),
            )
        }.getOrDefault(emptyMap())
}
