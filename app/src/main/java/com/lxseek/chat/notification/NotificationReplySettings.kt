package com.lxseek.chat.notification

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 系统通知自动回复配置（独立于 IM 网关，按 App 持久化）。 */
@Serializable
data class NotificationReplyConfig(
    /** 总开关。关闭后即使已授予通知使用权也不再自动回复。 */
    val enabled: Boolean = false,
    /** 监听的 App 包名；空列表表示监听全部（默认只玩微信通知）。 */
    val packages: List<String> = listOf("com.tencent.mm"),
    /** 昵称 → iLink user_id 映射。通知标题（发送者昵称）命中映射时才回复，避免发错对象。 */
    val contacts: Map<String, String> = emptyMap(),
    /** 对话系统提示词（空则用默认）。 */
    val promptHeader: String = "",
    /** 同一好友两次自动回复的最小间隔（毫秒），防连发触发 AI 请求风暴。 */
    val cooldownMs: Long = 30_000L,
)

/** 系统通知自动回复的配置持久化：用独立的 preferencesDataStore("notification_reply")。 */
private val Context.notificationReplyDataStore by preferencesDataStore(name = "notification_reply")

class NotificationReplyStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val KEY_CONFIG = stringPreferencesKey("config_json")
    private val KEY_CONVERSATION_ID = stringPreferencesKey("dedicated_conversation_id")

    val config: Flow<NotificationReplyConfig> = context.notificationReplyDataStore.data.map { pref ->
        val raw = pref[KEY_CONFIG] ?: return@map NotificationReplyConfig()
        runCatching { json.decodeFromString<NotificationReplyConfig>(raw) }
            .getOrDefault(NotificationReplyConfig())
    }

    suspend fun currentConfig(): NotificationReplyConfig = config.first()

    suspend fun update(transform: (NotificationReplyConfig) -> NotificationReplyConfig) {
        context.notificationReplyDataStore.edit { pref ->
            val current = try {
                json.decodeFromString<NotificationReplyConfig>(pref[KEY_CONFIG] ?: return@edit)
            } catch (e: Exception) {
                NotificationReplyConfig()
            }
            pref[KEY_CONFIG] = json.encodeToString(transform(current))
        }
    }

    /** 专用的 Lxchat 会话 id（AI 回复落库于此），用于跨重启记住上下文。 */
    suspend fun conversationId(): String? =
        context.notificationReplyDataStore.data.first()[KEY_CONVERSATION_ID]

    suspend fun setConversationId(id: String) {
        context.notificationReplyDataStore.edit { it[KEY_CONVERSATION_ID] = id }
    }
}