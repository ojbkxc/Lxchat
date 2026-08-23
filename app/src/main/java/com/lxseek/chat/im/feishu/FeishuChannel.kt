package com.lxseek.chat.im.feishu

import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImMessageDirection
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.PushMessageChannel
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Feishu/Lark channel for Lxchat: a [PushMessageChannel] backed by Feishu's long connection
 * (WebSocket) for inbound delivery and the IM REST API for replies.
 *
 * Pure Kotlin over the shared OkHttp [com.lxseek.chat.api.HttpClient] — no SDK, no extra
 * dependencies. Mirrors `dsh-im/src/channels/feishu/` in behavior:
 *  - Inbound: `event/v1/establish` to obtain a `wss://` endpoint, then receive
 *    `im.message.receive_v1` events, ACK each frame, and feed an [ImMessage] to [onMessage].
 *    Reconnect with exponential backoff is handled by [FeishuLarkConnection].
 *  - Outbound: `im/v1/messages` with `receive_id_type=chat_id` (the path used by
 *    `feishu-channel.mjs`'s `#sendCard` for non-card text replies).
 *
 * This is the most complex IM channel in dsh-im (14 files: cards, runtime, bridge, repair,
 * group-response-mode...). Lxchat's first cut intentionally implements only the core
 * long-connection + text-reply surface — card streaming, reactions, repair flow, and rich
 * post/image messages are out of scope (they are enhancement features, not the binding core).
 *
 * Configuration mapping (per task spec — reuses [ImGatewayConfig] fields):
 *  - [ImGatewayConfig.token] → Feishu **App ID** (from the developer console).
 *  - [ImGatewayConfig.baseUrl] → Feishu **App Secret**. This field is repurposed because Feishu
 *    credentials are a single (id, secret) pair and Lxchat has no dedicated secret field; the
 *    value is never logged or surfaced in tool output.
 *  - [ImGatewayConfig.botId] → optional domain selector: `"lark"` switches to the international
 *    `open.larksuite.com` host; any other value (including blank) uses `open.feishu.cn`.
 *  - [ImGatewayConfig.enabled] gates the channel; [isConfigured] requires enabled + non-blank
 *    App ID + non-blank App Secret.
 *
 * Only text messages are supported today; images/voice/post/cards are future work (the dsh-im
 * card-stream path in `feishu-channel.mjs` is intentionally omitted to keep this first cut
 * dependency-free).
 *
 * [listConversations] and [fetchMessages] return empty: this is a push channel, so the
 * [com.lxseek.chat.im.ImPollingReceiver] never polls it — inbound messages arrive exclusively
 * through [startListening].
 */
class FeishuChannel(
    private val config: ImGatewayConfig,
) : PushMessageChannel {

    override val channelId: String get() = CHANNEL_ID
    override val displayName: String get() = "Lark/Feishu · ${maskAppId(config.token)}"
    override val isConfigured: Boolean
        get() = config.enabled && config.token.isNotBlank() && config.baseUrl.isNotBlank()

    /**
     * Lazily built; null when credentials are missing so [isConfigured] stays false and
     * [startListening] returns immediately without touching the network.
     */
    private val api: FeishuLarkApi? = if (config.token.isNotBlank() && config.baseUrl.isNotBlank()) {
        runCatching {
            FeishuLarkApi(
                appId = config.token.trim(),
                appSecret = config.baseUrl.trim(),
                domain = FeishuDomain.of(config.botId),
            )
        }.getOrElse {
            DebugLog.e(TAG, "failed to build Feishu api: ${it.message}", it)
            null
        }
    } else null

    /** Live long-connection handle, non-null only while listening. Volatile — set/cleared on one job. */
    @Volatile private var connection: FeishuLarkConnection? = null

    /**
     * Bot open_id learned from `bot/v3/info/` at connection start. Used to:
     *  - drop echo loops (messages where `senderType == "bot"`),
     *  - detect @-mentions in group chats (mention.id.open_id == botOpenId).
     *
     * Volatile because it is written by the onOpen callback and read by onMessage callbacks.
     */
    @Volatile private var botOpenId: String? = null

    /**
     * Per-conversation last message id, refreshed on every inbound message. Used by [sendMessage]
     * to thread the agent's reply under the user's most recent message via `im/v1/messages/reply`
     * when possible; falls back to a plain `im/v1/messages` send when no recent id is cached.
     */
    private val lastMessageIdByChat = ConcurrentHashMap<String, String>()

    // ── PushMessageChannel ──────────────────────────────────────────────────

    /**
     * Open the long-connection WebSocket and deliver inbound messages to [onMessage].
     *
     * Suspends until the connection is closed (by [stopListening] or [scope] cancellation);
     * the reconnect loop runs on [scope] so cancelling it tears everything down. Each inbound
     * Feishu message is mapped to an [ImMessage] (INCOMING, text-only) before the callback,
     * and its message id is cached for the next [sendMessage].
     *
     * Filtering (mirrors `bridge.mjs`'s `accept` gate, minus the allowlist/repair paths):
     *  - Bot-sent messages (`senderType == "bot"`) are dropped to avoid echo loops.
     *  - Non-text messages (post/image/voice...) arrive with a blank text and are dropped.
     *  - Group messages are dropped unless the bot is explicitly @-mentioned (the
     *    `groupResponseMode == mention` default in dsh-im); p2p messages always pass.
     */
    override suspend fun startListening(onMessage: (ImMessage) -> Unit, scope: CoroutineScope) {
        val api = api ?: return
        if (!isConfigured) return
        if (connection != null) return // already listening; avoid a second socket

        val listener = object : FeishuLarkListener {
            override fun onOpen() {
                DebugLog.d(TAG, "long connection established for app ${maskAppId(api.appId)}")
                // Learn the bot open_id asynchronously so the @-mention gate works in groups.
                // Failure is non-fatal: group messages will fall back to the "any mention"
                // heuristic (mentions non-empty → addressed).
                scope.launch(Dispatchers.IO) {
                    try {
                        botOpenId = api.getBotInfo().openId
                    } catch (e: Exception) {
                        DebugLog.w(TAG, "getBotInfo failed: ${e.message}")
                    }
                }
            }

            override fun onMessage(message: FeishuInboundMessage) {
                // Drop bot-sent messages to avoid echo loops (mirrors isBotSender in dsh-im).
                if (message.senderType == "bot") return
                // Text-only for now; non-text messages have a blank text.
                if (message.text.isBlank()) return
                // Group gate: only react when explicitly @-mentioned. p2p chats always pass.
                if (message.chatType != "p2p" && !isAddressedToBot(message)) return

                // Cache the message id so the next sendMessage can thread the reply.
                lastMessageIdByChat[message.chatId] = message.messageId

                val im = ImMessage(
                    id = message.messageId,
                    conversationId = message.chatId,
                    direction = ImMessageDirection.INCOMING,
                    text = message.text,
                    sender = message.senderOpenId,
                    timestampMs = message.createTimeMs,
                )
                runCatching { onMessage(im) }.onFailure {
                    DebugLog.e(TAG, "onMessage callback threw: ${it.message}", it)
                }
            }

            override fun onClosed(code: Int, reason: String) {
                DebugLog.d(TAG, "long connection closed: $code $reason")
            }

            override fun onError(error: Throwable, fatal: Boolean) {
                if (fatal) DebugLog.e(TAG, "long connection fatal error: ${error.message}", error)
                else DebugLog.w(TAG, "long connection error (will retry): ${error.message}")
            }
        }

        val conn = api.openLongConnection(scope, listener)
        connection = conn
        // Suspend until the scope is cancelled or stopListening closes the connection. The
        // reconnect loop itself runs on `scope` and is non-blocking; we just need to stay alive
        // here so ImPollingReceiver's launch job doesn't complete while the socket is open.
        withContext(scope.coroutineContext) {
            try {
                kotlinx.coroutines.coroutineScope {
                    kotlinx.coroutines.awaitCancellation()
                }
            } finally {
                connection = null
            }
        }
    }

    /** Close the long connection and stop the reconnect loop. Safe to call when not listening. */
    override fun stopListening() {
        connection?.close()
        connection = null
    }

    // ── MessageChannel ──────────────────────────────────────────────────────

    /**
     * Send [text] into [conversationId] (a Feishu `chat_id`).
     *
     * Tries `im/v1/messages/{message_id}/reply` first when we have a recent inbound message id
     * cached for this chat (threads the reply under the user's message, matching dsh-im's
     * `replyTo` behavior); falls back to a plain `im/v1/messages` send otherwise. Returns
     * [ImSendResult.NotConfigured] when the channel is disabled or has no credentials,
     * [ImSendResult.Failure] on transport/rejection, [ImSendResult.Success] with Feishu's
     * `message_id` on success.
     */
    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        val api = api ?: return ImSendResult.NotConfigured
        if (!isConfigured) return ImSendResult.NotConfigured
        if (text.isBlank()) return ImSendResult.Failure("text is empty")

        return withContext(Dispatchers.IO) {
            try {
                // Prefer replying in-thread when we have a recent inbound message id.
                // Fall back to a plain send if the reply fails (e.g. the parent message was
                // recalled or the reply window expired).
                val replyTo = lastMessageIdByChat[conversationId]
                val sentId = if (replyTo != null) {
                    try {
                        api.replyText(replyTo, text)
                    } catch (e: Exception) {
                        DebugLog.w(TAG, "replyText failed, falling back to sendText: ${e.message}")
                        api.sendText(conversationId, text)
                    }
                } else {
                    api.sendText(conversationId, text)
                }
                ImSendResult.Success(sentId)
            } catch (e: FeishuApiException) {
                DebugLog.e(TAG, "sendMessage failed: ${e.message} (code=${e.code})")
                ImSendResult.Failure(e.message ?: "feishu send failed")
            } catch (e: Exception) {
                DebugLog.e(TAG, "sendMessage failed", e)
                ImSendResult.Failure(e.message ?: "feishu send failed")
            }
        }
    }

    /** Push channel — never polled. Returns empty so any stray poll is a no-op. */
    override suspend fun listConversations(): List<ImConversation> = emptyList()

    /** Push channel — messages arrive via [startListening]. Returns empty. */
    override suspend fun fetchMessages(conversationId: String, afterId: String?): List<ImMessage> = emptyList()

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * True when [message] @-mentions the bot. Mirrors `#isAddressed` in dsh-im bridge.mjs:
     * if we know the bot's open_id, require an exact mention match; otherwise accept any
     * non-empty mention list (the bot was added to the group and someone @-mentioned a user).
     */
    private fun isAddressedToBot(message: FeishuInboundMessage): Boolean {
        val botId = botOpenId
        if (botId.isNullOrBlank()) return message.mentions.isNotEmpty()
        return message.mentions.any { mention ->
            mention["id"]?.let { runCatching { it.jsonObject }.getOrNull() }
                ?.get("open_id")?.jsonPrimitive?.contentOrNull == botId
            || mention["open_id"]?.jsonPrimitive?.contentOrNull == botId
        }
    }

    /** Mask an App ID for logs: keep the first 4 chars, elide the rest. */
    private fun maskAppId(id: String): String =
        if (id.length <= 4) id else id.take(4) + "⋯"

    companion object {
        /**
         * Platform identifier stored in [ImGatewayConfig.platform] for this channel.
         * Note: the [ImPlatform] enum uses `"lark"` (not `"feishu"`) as the wire id, so this
         * channel registers under `"lark"` to match `ImChannelFactory`'s lookup.
         */
        const val PLATFORM = "lark"
        private const val CHANNEL_ID = "lark"
        private const val TAG = "FeishuChannel"
    }
}