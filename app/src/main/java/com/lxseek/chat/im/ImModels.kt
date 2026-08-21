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
    /** True when this conversation is a group chat (used to honor proactive ignore-group rails). */
    val isGroup: Boolean = false,
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
    /** Model used for automatic replies; blank falls back to the app default. */
    val autoReplyModel: String = "",
    /** Proactive messages (default OFF per safety red-line). When enabled, the agent may greet
     *  an IM contact that has been idle beyond [proactiveIdleMinutes]. */
    val proactiveEnabled: Boolean = false,
    /** Idle threshold in minutes before a proactive greeting is triggered. */
    val proactiveIdleMinutes: Int = 120,
    /** Quiet window ("HH:MM") during which proactive messages are suppressed. Empty = never quiet. */
    val proactiveSilentStart: String = "",
    /** Quiet window ("HH:MM") during which proactive messages are suppressed. Empty = never quiet. */
    val proactiveSilentEnd: String = "",
    /** Ignore proactive messages in group chats. */
    val proactiveIgnoreGroups: Boolean = true,
    /** Humanize outbound messages with a light randomized typing-style delay (default OFF). */
    val humanizeMessages: Boolean = false,
) {
    val isConfigured: Boolean get() = enabled && baseUrl.isNotBlank()
    val name: String get() = "Gateway · $platform"
}

/**
 * Runtime state that lets the background receiver resume exactly where it left off across restarts.
 *
 * - [conversationBindings]: IM conversation id -> Lxchat conversation id. One Lxchat session
 *   (and its generation history) is bound to each remote IM thread, mirroring the per-channel
 *   session binding in `conversation-state-store`.
 * - [seenMessageIds]: IM message ids already handed to the agent, used as a de-duplication set so
 *   a re-poll never replays a handled message. Only the last [MAX_SEEN] ids are kept.
 */
@Serializable
data class ImRuntimeState(
    val conversationBindings: Map<String, String> = emptyMap(),
    val seenMessageIds: List<String> = emptyList(),
    val platform: String = "wechat",
) {
    fun retainLatest(gateway: ImGatewayConfig): ImRuntimeState {
        val pruned = seenMessageIds.takeLast(MAX_SEEN)
        return if (pruned.size == seenMessageIds.size && platform == gateway.platform) this
        else copy(seenMessageIds = pruned, platform = gateway.platform)
    }

    private companion object {
        const val MAX_SEEN = 2_000
    }
}