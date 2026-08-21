package com.lxseek.chat.im

import kotlinx.serialization.Serializable

/**
 * Direction of an IM message relative to LxChat. [INCOMING] is a message received from a
 * contact; [OUTGOING] is one LxChat (or the user) sent through the gateway.
 */
enum class ImMessageDirection {
    INCOMING,
    OUTGOING,
}

/**
 * A single instant-messaging message exchanged through a [MessageChannel]. Text is the
 * primary payload; future extensions (images, voice) can add fields without breaking the wire.
 */
@Serializable
data class ImMessage(
    val id: String,
    val conversationId: String,
    val direction: ImMessageDirection,
    val text: String,
    val sender: String = "",
    val timestampMs: Long = 0L,
)

/**
 * A logical IM conversation (one chat thread / contact / group). Tied to a [platform]
 * so multiple gateways (WeChat, Telegram, SMS-shortcut...) can coexist.
 */
@Serializable
data class ImConversation(
    val id: String,
    val title: String,
    val platform: String = "wechat",
    val lastMessageAtMs: Long = 0L,
    val unreadCount: Int = 0,
)

/**
 * Persistent, secret-audited configuration for one IM gateway bridge.
 * [baseUrl] is the REST/SSE endpoint of the remote adapter (OneBot/wechaty-style gateway),
 * [token] is the authentication secret sent only over authorized endpoints.
 */
@Serializable
data class ImGatewayConfig(
    val enabled: Boolean = false,
    val platform: String = "wechat",
    val baseUrl: String = "",
    val token: String = "",
    val pollIntervalMs: Long = 5_000L,
) {
    val isConfigured: Boolean get() = enabled && baseUrl.isNotBlank()
    val name: String get() = "Gateway · $platform"
}