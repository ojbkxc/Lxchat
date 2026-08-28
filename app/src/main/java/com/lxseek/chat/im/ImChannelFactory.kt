package com.lxseek.chat.im

import com.lxseek.chat.im.dingtalk.DingtalkChannel
import com.lxseek.chat.im.discord.DiscordChannel
import com.lxseek.chat.im.feishu.FeishuChannel
import com.lxseek.chat.im.qq.QqChannel
import com.lxseek.chat.im.slack.SlackChannel
import com.lxseek.chat.im.telegram.TelegramChannel
import com.lxseek.chat.im.wecom.WecomChannel
import com.lxseek.chat.im.weixin.WeixinChannel
import com.lxseek.chat.im.whatsapp.WhatsappChannel
import java.io.File

/**
 * Builds a [MessageChannel] from an [ImGatewayConfig] according to its [ImGatewayConfig.platform].
 *
 * The single seam where platform dispatch happens: adding a new native channel only requires
 * adding a branch here and implementing the channel class. The factory itself is free of any
 * protocol-layer code — no HTTP clients, no SDKs, no websocket stacks — those live in the
 * channel implementations.
 *
 * Current dispatch:
 *  - **telegram** → [TelegramChannel] (Bot API long-poll).
 *  - **wechat** → [WeixinChannel] (iLink long-poll) when token is set, else [GatewayChannel]
 *    HTTP fallback for legacy OneBot/NapCat gateways using baseUrl.
 *  - **lark** → [FeishuChannel] (WebSocket long-connection).
 *  - **dingtalk** → [DingtalkChannel] (Stream WebSocket).
 *  - **wecom** → [WecomChannel] (WebSocket long-connection).
 *  - **qq** → [QqChannel] (WebSocket long-connection).
 *  - **discord** → [DiscordChannel] (Gateway v10 WebSocket).
 *  - **slack** → [SlackChannel] (Socket Mode WebSocket).
 *  - **whatsapp** → [WhatsappChannel] (Meta Cloud API, send-only on mobile).
 *  - **sms** → [GatewayChannel] HTTP fallback.
 *  - unknown platform → [GatewayChannel] HTTP fallback so existing custom gateways still work.
 */
object ImChannelFactory {

    /**
     * Create the [MessageChannel] for [config], or null when the platform is unrecognized and
     * no gateway fallback applies. The returned channel may still report [MessageChannel.isConfigured]
     * false when its credentials are missing/invalid — callers should filter on that.
     */
    fun create(config: ImGatewayConfig, cacheDir: File): MessageChannel? {
        val platform = ImPlatform.of(config.platform)
        return when (platform) {
            // Native Telegram Bot API channel (long-poll).
            ImPlatform.TELEGRAM -> TelegramChannel(config)

            // WeChat: iLink native channel when token is set (扫码绑定),
            // legacy HTTP gateway fallback when only baseUrl is configured.
            ImPlatform.WECHAT -> when {
                config.token.isNotBlank() -> WeixinChannel(config, cacheDir)
                config.baseUrl.isNotBlank() -> GatewayChannel(config)
                else -> null
            }

            // Push channels — native long-connection implementations.
            ImPlatform.LARK -> FeishuChannel(config)
            ImPlatform.DINGTALK -> DingtalkChannel(config)
            ImPlatform.WECOM -> WecomChannel(config)
            ImPlatform.QQ -> QqChannel(config)
            ImPlatform.DISCORD -> DiscordChannel(config)
            ImPlatform.SLACK -> SlackChannel(config)

            // WhatsApp: Meta Cloud API channel (send-only on mobile; webhook-only inbound).
            ImPlatform.WHATSAPP -> WhatsappChannel(config)

            // SMS: HTTP gateway fallback (no native channel).
            ImPlatform.SMS -> if (config.isConfigured) GatewayChannel(config) else null

            // Unknown platform: legacy HTTP fallback so existing custom gateways keep working.
            null -> if (config.isConfigured) GatewayChannel(config) else null
        }
    }

    /** True when [platformId] is a known built-in [ImPlatform]. */
    fun isSupported(platformId: String): Boolean = ImPlatform.of(platformId) != null

    /** True when [platformId] delivers messages via a long-lived push connection. */
    fun isPush(platformId: String): Boolean = ImPlatform.isPush(platformId)
}
