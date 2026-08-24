package com.lxseek.chat.im.discord

import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImMessageDirection
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.PushMessageChannel
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Discord push channel: turns a bot token (from the Discord Developer Portal) into a
 * [PushMessageChannel] that Lxchat's [com.lxseek.chat.im.ImPollingReceiver] binds to as a
 * long-lived listener.
 *
 * **Push model** — Discord offers no REST history fetch for bots, so the Gateway v10 WebSocket
 * is the only inbound surface. [startListening] opens the Gateway via [DiscordGatewayClient],
 * runs the IDENTIFY/HELLO/READY handshake, and invokes [onMessage] for every MESSAGE_CREATE
 * that is not from our own bot. [listConversations] and [fetchMessages] return empty lists —
 * the receiver's push path never calls them, and de-duplication / session binding are handled
 * by [com.lxseek.chat.im.ImPollingReceiver] exactly as for the polling channels.
 *
 * **Outbound** messages go through [DiscordRestApi.createMessage]
 * (POST /channels/{id}/messages). The [conversationId] passed to [sendMessage] is the Discord
 * channel snowflake; the receiver binds each Lxchat session to a Discord channel id and passes
 * it back on reply.
 *
 * **Configuration** is reused from [ImGatewayConfig]: `token` carries the bot token (with or
 * without the `Bot ` prefix), `baseUrl` may override the REST host (blank = official host),
 * `platform` must be `"discord"`. The MESSAGE_CONTENT privileged intent must be enabled in the
 * Developer Portal for message text to arrive — without it, content comes through empty and
 * is skipped.
 *
 * Mirrors `dsh-im/src/channels/discord/discord-api.mjs` and the Telegram channel's structure
 * (`TelegramChannel` + `TelegramBotApi`), adapted to the push contract.
 */
class DiscordChannel(
    private val config: ImGatewayConfig,
) : PushMessageChannel {

    override val channelId: String get() = CHANNEL_ID

    override val displayName: String
        get() {
            // Snapshot the @Volatile field once so the null-check and the interpolation agree.
            val name = gateway?.botDisplayName
            return if (name != null) "Discord · $name" else "Discord"
        }

    override val isConfigured: Boolean
        get() = config.enabled && DiscordRestApi.isValidDiscordToken(config.token)

    /** Lazily built; null when the token is malformed so [isConfigured] stays false. */
    private val rest: DiscordRestApi? =
        if (DiscordRestApi.isValidDiscordToken(config.token)) {
            DiscordRestApi(
                token = DiscordRestApi.normalizeToken(config.token),
                baseUrl = config.baseUrl.takeIf { it.isNotBlank() }
                    ?: DiscordRestApi.DEFAULT_BASE_URL,
            )
        } else null

    /** Active gateway client while [startListening] is running; null otherwise. */
    @Volatile private var gateway: DiscordGatewayClient? = null

    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        val api = rest ?: return ImSendResult.NotConfigured
        if (!isConfigured) return ImSendResult.NotConfigured
        // conversationId is the Discord channel snowflake the receiver bound this session to.
        if (!SNOWFLAKE_REGEX.matches(conversationId)) {
            return ImSendResult.Failure("Discord channel id must be a snowflake: $conversationId")
        }
        return try {
            val result = api.createMessage(channelId = conversationId, content = text)
            val messageId = result["id"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            ImSendResult.Success(messageId)
        } catch (e: DiscordApiException) {
            DebugLog.e("DiscordChannel", "sendMessage failed: ${e.message} (code=${e.errorCode})")
            ImSendResult.Failure(e.message ?: "discord send failed")
        } catch (e: Exception) {
            DebugLog.e("DiscordChannel", "sendMessage failed", e)
            ImSendResult.Failure(e.message ?: "discord send failed")
        }
    }

    /** Push channel — conversations are not polled; the receiver learns of them from inbound events. */
    override suspend fun listConversations(): List<ImConversation> = emptyList()

    /** Push channel — messages arrive via [startListening]; nothing to fetch on demand. */
    override suspend fun fetchMessages(conversationId: String, afterId: String?): List<ImMessage> =
        emptyList()

    override suspend fun startListening(onMessage: (ImMessage) -> Unit, scope: CoroutineScope) {
        if (!isConfigured) return
        val api = rest ?: return
        val token = DiscordRestApi.normalizeToken(config.token)
        // Fetch the recommended Gateway URL dynamically via GET /gateway/bot — mirrors
        // `dsh-im/discord-runtime.mjs` which calls `api.getGatewayBot()` instead of hard-coding
        // `wss://gateway.discord.gg`. Falls back to the default URL on failure so a transient
        // REST hiccup never blocks the bot from coming online.
        val gatewayBaseUrl = try {
            api.getGatewayBot()["url"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            DebugLog.w("DiscordChannel", "getGatewayBot failed, falling back to default URL: ${e.message}")
            null
        }
        val client = DiscordGatewayClient(
            token = token,
            gatewayUrl = if (!gatewayBaseUrl.isNullOrBlank()) {
                DiscordGatewayClient.normalizeGatewayUrl(gatewayBaseUrl)
            } else {
                DiscordGatewayClient.DEFAULT_GATEWAY_URL
            },
            onMessage = { event ->
                onMessage(
                    ImMessage(
                        id = event.messageId,
                        conversationId = event.channelId,
                        direction = ImMessageDirection.INCOMING,
                        text = event.content,
                        sender = event.authorName,
                        timestampMs = event.timestampMs,
                    ),
                )
            },
        )
        gateway = client
        try {
            client.connect(scope)
        } finally {
            gateway = null
        }
    }

    override fun stopListening() {
        gateway?.stop()
    }

    companion object {
        /** Platform identifier stored in [ImGatewayConfig.platform] for this channel. */
        const val PLATFORM = "discord"
        private const val CHANNEL_ID = "discord"
        private val SNOWFLAKE_REGEX = Regex("""^\d{5,30}$""")
    }
}