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

/**
 * 一条「昵称 → iLink 用户」映射。
 *
 * [channelKey] 记录该好友属于哪一个绑定的微信 Bot 渠道（[com.lxseek.chat.im.ImGatewayConfig.effectiveChannelId]），
 * 多 Bot 时系统通知回复必须路由回对应渠道，否则会发到错误的账号。
 * [userId] 是 iLink `from_user_id`，即回复要传的 `to_user_id`；该值只能从 IM 渠道 getupdates
 * 收到好友消息时获得，系统通知里拿不到，因此映射须由「已绑定的 IM 微信」帮助生成。
 */
@Serializable
data class ContactMapping(
    /** 所属微信 Bot 渠道 key（空 = 用任意已配置微信渠道兜底）。 */
    val channelKey: String = "",
    /** iLink user_id（回复时的 to_user_id）。 */
    val userId: String,
)

@Serializable
data class NotificationReplyConfig(
    /** 总开关。关闭后即使已授予通知使用权也不再自动回复。 */
    val enabled: Boolean = false,
    /** 监听的 App 包名；空列表表示监听全部（默认只玩微信通知）。 */
    val packages: List<String> = listOf("com.tencent.mm"),
    /** 昵称 → 联系人映射。通知标题（发送者昵称）命中映射时才回复，避免发错对象。 */
    val contacts: Map<String, ContactMapping> = emptyMap(),
    /** 对话系统提示词（空则用默认）。 */
    val promptHeader: String = "",
    /** 同一好友两次自动回复的最小间隔（毫秒），防连发触发 AI 请求风暴。默认 60 秒。 */
    val cooldownMs: Long = 60_000L,
    /** 指定自动回复使用的模型 id（`provider:model` 格式，空表示跟随默认模型）。 */
    val modelId: String? = null,
    /** 关键词黑名单（一行一个正则表达式）。命中通知标题或内容任意一项则不回复。 */
    val blacklist: String = "",
    /** 仅在锁屏状态时自动回复（使用时不打扰）。 */
    val onlyWhenLocked: Boolean = false,
)

/** 系统通知自动回复的配置持久化：用独立的 preferencesDataStore("notification_reply")。 */
private val Context.notificationReplyDataStore by preferencesDataStore(name = "notification_reply")

class NotificationReplyStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val KEY_CONFIG = stringPreferencesKey("config_json")
    private val KEY_CONVERSATION_ID = stringPreferencesKey("dedicated_conversation_id")

    val config: Flow<NotificationReplyConfig> = context.notificationReplyDataStore.data.map { pref ->
        val raw = pref[KEY_CONFIG] ?: return@map NotificationReplyConfig()
        decodeConfig(raw)
    }

    suspend fun currentConfig(): NotificationReplyConfig = config.first()

    suspend fun update(transform: (NotificationReplyConfig) -> NotificationReplyConfig) {
        context.notificationReplyDataStore.edit { pref ->
            val current = try {
                json.decodeFromString<NotificationReplyConfig>(pref[KEY_CONFIG] ?: return@edit)
            } catch (e: Exception) {
                // 旧格式（contacts 为 Map<String,String>）升级后保存为新的 Map<String,ContactMapping>。
                decodeConfig(pref[KEY_CONFIG] ?: return@edit)
            }
            pref[KEY_CONFIG] = json.encodeToString(transform(current))
        }
    }

    /**
     * 解析配置，兼容旧格式：早期 contacts 是 `昵称 → String(user_id)`，升级为
     * `昵称 → ContactMapping(channelKey, userId)`。旧数据会按空渠道（任意微信兜底）自动迁移。
     */
    private fun decodeConfig(raw: String): NotificationReplyConfig =
        runCatching { json.decodeFromString<NotificationReplyConfig>(raw) }
            .getOrElse {
                runCatching { json.decodeFromString<OldNotificationReplyConfig>(raw).migrate() }
                    .getOrElse { NotificationReplyConfig() }
            }

    /** 专用的 Lxchat 会话 id（AI 回复落库于此），用于跨重启记住上下文。 */
    suspend fun conversationId(): String? =
        context.notificationReplyDataStore.data.first()[KEY_CONVERSATION_ID]

    suspend fun setConversationId(id: String) {
        context.notificationReplyDataStore.edit { it[KEY_CONVERSATION_ID] = id }
    }
}

/** 旧版配置结构（contacts 为 `昵称 → user_id` 字符串）。仅用于一次性迁移到新结构。 */
@Serializable
private data class OldNotificationReplyConfig(
    val enabled: Boolean = false,
    val packages: List<String> = listOf("com.tencent.mm"),
    val contacts: Map<String, String> = emptyMap(),
    val promptHeader: String = "",
    val cooldownMs: Long = 30_000L,
    val modelId: String? = null,
    val blacklist: String = "",
    val onlyWhenLocked: Boolean = false,
) {
    fun migrate(): NotificationReplyConfig = NotificationReplyConfig(
        enabled = enabled,
        packages = packages,
        contacts = contacts.mapValues { (_, userId) -> ContactMapping(channelKey = "", userId = userId) },
        promptHeader = promptHeader,
        cooldownMs = cooldownMs,
        modelId = modelId,
        blacklist = blacklist,
        onlyWhenLocked = onlyWhenLocked,
    )
}