package com.lxseek.chat.im.qq

import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImMessageDirection
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.PushMessageChannel
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * QQ Bot push channel: turns an AppID + AppSecret (from the QQ Bot Open Platform) into a
 * [PushMessageChannel] that Lxchat's [com.lxseek.chat.im.ImPollingReceiver] binds to as a
 * long-lived listener.
 *
 * **Push model** — QQ offers no REST history fetch for bots, so the platform WebSocket is
 * the only inbound surface. [startListening] opens the WebSocket via [QqBotWebSocketClient],
 * runs the HELLO/IDENTIFY/READY handshake, and invokes [onMessage] for every
 * `C2C_MESSAGE_CREATE` / `GROUP_AT_MESSAGE_CREATE` event that is not from our own bot.
 * [listConversations] and [fetchMessages] return empty lists — the receiver's push path
 * never calls them, and de-duplication / session binding are handled by
 * [com.lxseek.chat.im.ImPollingReceiver] exactly as for the polling channels.
 *
 * **Outbound** messages go through [QqRestApi.sendC2cMessage] / [sendGroupMessage]. The
 * [conversationId] passed to [sendMessage] is the QQ conversation key
 * (`"c2c:<user_openid>"` or `"group:<group_openid>"`) the receiver bound the Lxchat session
 * to. When a recent inbound message id is known for that conversation (within QQ's 5-minute
 * passive-reply window), it is attached as `msg_id` so the send is a passive reply; otherwise
 * the send is a proactive message (subject to QQ's proactive quotas).
 *
 * **Configuration** is reused from [ImGatewayConfig]: `token` carries the AppID, `baseUrl`
 * carries the AppSecret (field reuse documented in the channel task — QQ has no REST base
 * URL to override, so the secret field is borrowed), `platform` must be `"qq"`.
 *
 * Mirrors `dsh-im/src/channels/qq/qq-runtime.mjs` + `qq-bridge.mjs` and the Discord channel's
 * structure (`DiscordChannel` + `DiscordGatewayApi`), adapted to the push contract.
 */
class QqChannel(
    private val config: ImGatewayConfig,
) : PushMessageChannel {

    override val channelId: String get() = CHANNEL_ID

    override val displayName: String
        get() {
            // Snapshot the @Volatile field once so the null-check and the interpolation agree.
            val name = gateway?.botDisplayName
            return if (name != null) "QQ · $name" else "QQ"
        }

    override val isConfigured: Boolean
        get() = config.enabled && config.token.isNotBlank() && config.baseUrl.isNotBlank()

    /**
     * Lazily built; null when credentials are missing so [isConfigured] stays false.
     * `baseUrl` carries the AppSecret per the channel task's field-reuse convention.
     */
    private val rest: QqRestApi? =
        if (config.token.isNotBlank() && config.baseUrl.isNotBlank()) {
            runCatching {
                QqRestApi(
                    appId = config.token.trim(),
                    appSecret = config.baseUrl.trim(),
                )
            }.getOrElse {
                DebugLog.e("QqChannel", "QqRestApi construction failed: ${it.message}", it)
                null
            }
        } else null

    /** Active gateway client while [startListening] is running; null otherwise. */
    @Volatile private var gateway: QqBotWebSocketClient? = null

    /**
     * Most recent inbound message id per conversation key, used to attach `msg_id` to
     * passive replies within QQ's 5-minute window. ConcurrentHashMap because the listening
     * coroutine writes and sendMessage coroutines read concurrently. Entries expire lazily
     * on read; the map is bounded indirectly by the number of active conversations.
     */
    private val recentInboundMsgIds = ConcurrentHashMap<String, MsgIdEntry>()

    private data class MsgIdEntry(val msgId: String, val receivedAtMs: Long)

    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        val api = rest ?: return ImSendResult.NotConfigured
        if (!isConfigured) return ImSendResult.NotConfigured
        // Parse "c2c:<openid>" / "group:<group_openid>".
        val parsed = parseConversationId(conversationId)
            ?: return ImSendResult.Failure("QQ conversation id must be 'c2c:<openid>' or 'group:<group_openid>': $conversationId")
        return try {
            val msgId = consumeRecentMsgId(conversationId)
            val result = when (parsed.scope) {
                "c2c" -> api.sendC2cMessage(parsed.targetId, text, msgId)
                "group" -> api.sendGroupMessage(parsed.targetId, text, msgId)
                else -> return ImSendResult.Failure("Unknown QQ reply scope: ${parsed.scope}")
            }
            val sentId = result["id"]?.let {
                runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
            } ?: result["msg_id"]?.let {
                runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
            } ?: "unknown"
            ImSendResult.Success(sentId)
        } catch (e: QqApiException) {
            DebugLog.e("QqChannel", "sendMessage failed: ${e.message} (code=${e.errorCode})")
            ImSendResult.Failure(e.message ?: "qq send failed")
        } catch (e: Exception) {
            DebugLog.e("QqChannel", "sendMessage failed", e)
            ImSendResult.Failure(e.message ?: "qq send failed")
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
        val client = QqBotWebSocketClient(
            appId = config.token.trim(),
            appSecret = config.baseUrl.trim(),
            restApi = api,
            gatewayUrl = QqBotWebSocketClient.DEFAULT_GATEWAY_URL,
            onMessage = { event ->
                // Track the inbound msg_id so a near-term reply can be sent as a passive reply.
                recentInboundMsgIds[event.conversationId] =
                    MsgIdEntry(event.messageId, System.currentTimeMillis())
                onMessage(
                    ImMessage(
                        id = event.messageId,
                        conversationId = event.conversationId,
                        direction = ImMessageDirection.INCOMING,
                        text = event.content,
                        sender = event.authorName.ifBlank { event.authorId },
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Parse `"c2c:<openid>"` / `"group:<group_openid>"` into scope + target id. */
    private fun parseConversationId(conversationId: String): ParsedConversation? {
        val idx = conversationId.indexOf(':')
        if (idx <= 0 || idx == conversationId.length - 1) return null
        val scope = conversationId.substring(0, idx)
        val targetId = conversationId.substring(idx + 1)
        if (scope !in REPLY_SCOPES || targetId.isBlank()) return null
        return ParsedConversation(scope, targetId)
    }

    /**
     * Return the most recent inbound msg_id for [conversationId] if it is still within QQ's
     * passive-reply window, then remove it so a second reply in the same window becomes a
     * proactive send. Returns null when no recent inbound message is known or it has expired.
     */
    private fun consumeRecentMsgId(conversationId: String): String? {
        val entry = recentInboundMsgIds[conversationId] ?: return null
        val ageMs = System.currentTimeMillis() - entry.receivedAtMs
        if (ageMs > PASSIVE_REPLY_WINDOW_MS) {
            recentInboundMsgIds.remove(conversationId, entry)
            return null
        }
        return entry.msgId
    }

    private data class ParsedConversation(val scope: String, val targetId: String)

    companion object {
        /** Platform identifier stored in [ImGatewayConfig.platform] for this channel. */
        const val PLATFORM = "qq"
        private const val CHANNEL_ID = "qq"
        private val REPLY_SCOPES = setOf("c2c", "group")
        /** QQ requires passive replies within 5 minutes of the inbound message. */
        private const val PASSIVE_REPLY_WINDOW_MS = 5 * 60 * 1000L
    }
}