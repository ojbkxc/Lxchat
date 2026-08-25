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
 * Built-in IM platforms supported by LxChat. Each entry carries the wire identifier
 * ([id]) used in configs and tool payloads, plus whether the platform delivers messages
 * via a long-lived push connection ([push]) or must be polled.
 *
 * Ten platforms are enumerated; concrete channel implementations are plugged in by
 * [ImChannelFactory]. Polling platforms reuse the legacy [GatewayChannel] HTTP bridge
 * until a native SDK channel is provided; push platforms return null from the factory
 * until their long-connection channel is implemented by a downstream task.
 */
enum class ImPlatform(
    /** Wire identifier persisted in [ImGatewayConfig.platform] and sent to gateways. */
    val id: String,
    /** Human-readable label shown in UI and logs. */
    val label: String,
    /** True when the platform pushes messages to LxChat over a long-lived connection. */
    val push: Boolean,
) {
    WECHAT("wechat", "WeChat", false),
    TELEGRAM("telegram", "Telegram", false),
    LARK("lark", "Lark/Feishu", true),
    DINGTALK("dingtalk", "DingTalk", true),
    WECOM("wecom", "WeCom", true),
    QQ("qq", "QQ", true),
    DISCORD("discord", "Discord", true),
    SLACK("slack", "Slack", true),
    WHATSAPP("whatsapp", "WhatsApp", false),
    SMS("sms", "SMS", false);

    companion object {
        /** All wire identifiers, for validation / factory fallback. */
        val IDS: Set<String> = entries.map { it.id }.toSet()

        /** Resolve a wire id to its [ImPlatform], or null when unknown. */
        fun of(id: String): ImPlatform? = entries.firstOrNull { it.id == id }

        /** True when [id] denotes a push (long-connection) platform. */
        fun isPush(id: String): Boolean = of(id)?.push == true
    }
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
    /** 图片URL列表（JPEG/PNG/WebP/GIF），单张上限5MB，总计上限20MB */
    val images: List<String> = emptyList(),
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
 *
 * [channelId] identifies this specific bridge instance locally (e.g. "wechat:bot1") so
 * multiple bots on the same platform can coexist; blank falls back to [platform] for
 * legacy single-bot configs (see [effectiveChannelId]).
 * [botId] identifies the remote bot/account (corp+agent, app id, bot handle...) when the
 * platform supports multiple bots per credential.
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
    /** Local bridge instance id; blank falls back to [platform] (single-bot legacy mode). */
    val channelId: String = "",
    /** Remote bot/account id on platforms that support multiple bots per credential. */
    val botId: String = "",
    /** Agent Preset ID，空白跟随默认 */
    val agentPreset: String = "",
) {
    /** Effective local channel id, falling back to [platform] for legacy single-bot configs. */
    val effectiveChannelId: String get() = channelId.ifBlank { platform }
    val isConfigured: Boolean get() = enabled && baseUrl.isNotBlank()
    val name: String get() = "Gateway · $platform"
}

/**
 * Multi-channel, multi-bot configuration: one ordered list of [ImGatewayConfig] per
 * platform id. Serialized as a JSON object `{ "wechat": [...], "telegram": [...] }`.
 *
 * This is the source of truth for [ImBridgeService] and [ImPollingReceiver]; the legacy
 * single-config [ImGatewayStore.config] flow is derived from it for backward compatibility
 * (when non-empty, otherwise the legacy single-config flow is used as a fallback).
 */
@Serializable
data class ImMultiGatewayConfig(
    val channels: Map<String, List<ImGatewayConfig>> = emptyMap(),
) {
    /** All configured bots across every platform, flattened in declaration order. */
    val all: List<ImGatewayConfig> get() = channels.values.flatten()

    /** The first enabled, configured bot across every platform, for legacy single-config consumers. */
    val primary: ImGatewayConfig? get() = all.firstOrNull { it.isConfigured }

    /** Bots for a given platform id (empty when none configured). */
    fun botsFor(platform: String): List<ImGatewayConfig> = channels[platform].orEmpty()

    /** Add or replace the bot list for [platform]. */
    fun withBots(platform: String, bots: List<ImGatewayConfig>): ImMultiGatewayConfig =
        copy(channels = channels + (platform to bots))

    /** Upsert a single bot by its [ImGatewayConfig.effectiveChannelId]. */
    fun upsert(config: ImGatewayConfig): ImMultiGatewayConfig {
        val platform = config.platform
        val current = channels[platform].orEmpty().toMutableList()
        val idx = current.indexOfFirst { it.effectiveChannelId == config.effectiveChannelId }
        if (idx >= 0) current[idx] = config else current += config
        return withBots(platform, current)
    }

    /** Remove the bot identified by [channelId] from its platform list. */
    fun remove(platform: String, channelId: String): ImMultiGatewayConfig {
        val current = channels[platform].orEmpty().filterNot { it.effectiveChannelId == channelId }
        return withBots(platform, current)
    }

    companion object {
        /** Build a multi-config from a single legacy [config] (one bot on its platform). */
        fun fromSingle(config: ImGatewayConfig): ImMultiGatewayConfig =
            if (config.isConfigured || config.enabled) {
                ImMultiGatewayConfig(channels = mapOf(config.platform to listOf(config)))
            } else {
                ImMultiGatewayConfig()
            }
    }
}

/**
 * Runtime state that lets the background receiver resume exactly where it left off across restarts.
 *
 * - [conversationBindings]: IM conversation id -> Lxchat conversation id. One Lxchat session
 *   (and its generation history) is bound to each remote IM thread, mirroring the per-channel
 *   session binding in `conversation-state-store`.
 * - [seenMessageIds]: IM message ids already handed to the agent, used as a de-duplication set so
 *   a re-poll never replays a handled message. Only the last [MAX_SEEN] ids are kept.
 *
 * [channelId] ties this state to a specific bridge instance so multiple bots can keep
 * independent seen-sets; blank denotes legacy single-channel state.
 */
@Serializable
data class ImRuntimeState(
    val conversationBindings: Map<String, String> = emptyMap(),
    val seenMessageIds: List<String> = emptyList(),
    /**
     * WeChat iLink per-会话 context_token（conversationId/userId → token）。
     * 持久化以便 App 重启后 Proactive/离体发送仍能带回 context_token，避免回复被服务端静默丢弃。
     */
    val contextTokens: Map<String, String> = emptyMap(),
    val platform: String = "wechat",
    /** Local channel instance id this state belongs to; blank = legacy single-channel state. */
    val channelId: String = "",
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
