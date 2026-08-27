package com.lxseek.chat.channel

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 多回复渠道配置。
 *
 * 持久化在独立的 DataStore `reply_channel`（与 [com.lxseek.chat.notification.NotificationReplyStore]
 * 分开），由 [ReplyChannelStore] 读写。设置页 [com.lxseek.chat.ui.settings.ReplyChannelSettingsPage]
 * 编辑本结构；[com.lxseek.chat.notification.NotificationAutoReplyService.sendReply] 读取
 * [additionalChannels] 决定除微信外还要把回复推送到哪些渠道。
 *
 * 各渠道的「enabled + 凭据」字段集中在此（而非每渠道独立 DataStore），便于一次性导入/导出与
 * 设置页编辑；字段较多但都是简单标量，序列化体积可忽略。
 */
@Serializable
data class ReplyChannelConfig(
    // ── Telegram ──────────────────────────────────────────────
    /** 是否启用 Telegram 渠道（仅表示配置就绪，是否在回复中真正使用由 [additionalChannels] 决定）。 */
    val telegramEnabled: Boolean = false,
    /** Telegram Bot Token（来自 @BotFather）。 */
    val telegramBotToken: String = "",
    /** Telegram API base url 覆盖（空 = 官方 https://api.telegram.org/）。 */
    val telegramBaseUrl: String = "",

    // ── Bark ──────────────────────────────────────────────────
    /** 是否启用 Bark 渠道。 */
    val barkEnabled: Boolean = false,
    /** Bark 服务器地址（空 = 官方 https://api.day.app/<deviceKey>，自建填 https://your.host）。 */
    val barkServerUrl: String = "",
    /** Bark device key。 */
    val barkDeviceKey: String = "",

    // ── Email（SMTP 直连，手写轻量 SMTP，无 JavaMail 依赖） ─────────
    /** 是否启用邮件渠道。 */
    val emailEnabled: Boolean = false,
    /** 发件邮箱（即 SMTP 登录用户名）。 */
    val emailFrom: String = "",
    /** SMTP 授权码（QQ/163 等在客户端设置里生成，非登录密码）。 */
    val emailPassword: String = "",
    /** SMTP 服务器地址；填发件邮箱后可从预设自动带出。 */
    val emailSmtpHost: String = "",
    /** SMTP 端口。 */
    val emailSmtpPort: Int = 465,
    /** 加密方式：`ssl`（465）/ `starttls`（587）/ `none`。 */
    val emailSmtpSecurity: String = SECURITY_SSL,
    /** 默认收件人（recipient 为空时兜底，避免通知回复里拿不到邮箱地址时无处可发）。 */
    val emailDefaultTo: String = "",

    // ── 路由 ──────────────────────────────────────────────────
    /**
     * 回复时通过哪些渠道发送（除微信外）。元素为 [ReplyChannel.id]，例如
     * `["telegram", "bark", "email"]`。顺序即发送顺序；任一渠道失败不影响后续渠道。
     */
    val additionalChannels: List<String> = emptyList(),
) {
    companion object {
        /** 渠道 id 常量，避免到处硬编码字符串。 */
        const val CHANNEL_TELEGRAM = "telegram"
        const val CHANNEL_BARK = "bark"
        const val CHANNEL_EMAIL = "email"

        /** SMTP 加密方式取值。 */
        const val SECURITY_SSL = "ssl"
        const val SECURITY_STARTTLS = "starttls"
        const val SECURITY_NONE = "none"
        val EMAIL_SECURITY_OPTIONS = listOf(SECURITY_SSL, SECURITY_STARTTLS, SECURITY_NONE)
    }
}

/** 回复渠道配置的 DataStore，独立于 notification_reply。 */
private val Context.replyChannelDataStore by preferencesDataStore(name = "reply_channel")

/**
 * 回复渠道配置持久化读写。模式与 [com.lxseek.chat.notification.NotificationReplyStore] 一致：
 * 单条 JSON 存全部配置，[config] 暴露 Flow，[update] 提供原子改写。
 */
class ReplyChannelStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val KEY_CONFIG = stringPreferencesKey("config_json")

    val config: Flow<ReplyChannelConfig> = context.replyChannelDataStore.data.map { pref ->
        val raw = pref[KEY_CONFIG] ?: return@map ReplyChannelConfig()
        decodeConfig(raw)
    }

    suspend fun currentConfig(): ReplyChannelConfig = config.first()

    suspend fun update(transform: (ReplyChannelConfig) -> ReplyChannelConfig) {
        context.replyChannelDataStore.edit { pref ->
            val current = decodeConfig(pref[KEY_CONFIG] ?: return@edit)
            pref[KEY_CONFIG] = json.encodeToString(transform(current))
        }
    }

    /** 解析配置；非法/旧格式降级为默认值，避免单字段损坏导致整页打不开。 */
    private fun decodeConfig(raw: String): ReplyChannelConfig =
        runCatching { json.decodeFromString<ReplyChannelConfig>(raw) }
            .getOrElse { ReplyChannelConfig() }
}