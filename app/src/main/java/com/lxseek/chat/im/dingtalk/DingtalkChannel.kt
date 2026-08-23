package com.lxseek.chat.im.dingtalk

import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImMessageDirection
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.PushMessageChannel
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * DingTalk channel for Lxchat: a [PushMessageChannel] backed by DingTalk's Stream Mode
 * (WebSocket long connection) for inbound delivery and the robot REST API for replies.
 *
 * Pure Kotlin over the shared OkHttp [com.lxseek.chat.api.HttpClient] — no SDK, no extra
 * dependencies. Mirrors `dsh-im/src/channels/dingtalk/dingtalk-runtime.mjs` in behavior:
 *  - Inbound: open `wss://` via `gateway/connections/open`, receive robot messages, ACK each
 *    frame, and feed an [ImMessage] to [onMessage]. Reconnect with backoff is handled by
 *    [DingtalkStreamConnection].
 *  - Outbound: reply via the per-message `sessionWebhook` when fresh (cheapest path), falling
 *    back to `robot/oToMessages/batchSend` (single-chat) or `robot/groupMessages/send` (group)
 *    when the webhook has expired or is missing.
 *
 * Configuration mapping (per task spec — reuses [ImGatewayConfig] fields):
 *  - [ImGatewayConfig.token] → DingTalk **Client ID** (appKey).
 *  - [ImGatewayConfig.baseUrl] → DingTalk **Client Secret** (appSecret). This field is
 *    repurposed because DingTalk credentials are a single (id, secret) pair and Lxchat has no
 *    dedicated secret field; the value is never logged or surfaced in tool output.
 *  - [ImGatewayConfig.enabled] gates the channel; [isConfigured] requires enabled + non-blank
 *    id + non-blank secret.
 *
 * Only text messages are supported today; images/voice/cards are future work (the dsh-im
 * card-stream path is intentionally omitted to keep this first cut dependency-free).
 *
 * [listConversations] and [fetchMessages] return empty: this is a push channel, so the
 * [com.lxseek.chat.im.ImPollingReceiver] never polls it — inbound messages arrive exclusively
 * through [startListening].
 */
class DingtalkChannel(
    private val config: ImGatewayConfig,
) : PushMessageChannel {

    override val channelId: String get() = CHANNEL_ID
    override val displayName: String
        get() = if (config.token.length >= 4) "DingTalk · ${config.token.take(4)}⋯" else "DingTalk"
    override val isConfigured: Boolean
        get() = config.enabled && config.token.isNotBlank() && config.baseUrl.isNotBlank()

    /**
     * Lazily built; null when credentials are missing so [isConfigured] stays false and
     * [startListening] returns immediately without touching the network.
     */
    private val api: DingtalkStreamApi? = if (config.token.isNotBlank() && config.baseUrl.isNotBlank()) {
        runCatching {
            DingtalkStreamApi(
                clientId = config.token.trim(),
                clientSecret = config.baseUrl.trim(),
            )
        }.getOrElse {
            DebugLog.e(TAG, "failed to build DingTalk api: ${it.message}", it)
            null
        }
    } else null

    /** Live stream handle, non-null only while listening. Volatile — set/cleared on one job. */
    @Volatile private var connection: DingtalkStreamConnection? = null

    /**
     * Per-conversation reply context, refreshed on every inbound message.
     *
     * Keyed by DingTalk's `conversationId` (the value we surface as [ImMessage.conversationId]).
     * Each entry remembers the most recent `sessionWebhook` (cheap, short-lived reply path) and
     * the `senderStaffId` / `conversationType` needed to fall back to the REST send APIs when
     * the webhook has expired. Bounded by the number of distinct conversations the bot is in;
     * for a personal assistant that is typically a few dozen.
     */
    private val replyContexts = ConcurrentHashMap<String, ReplyContext>()

    // ── PushMessageChannel ──────────────────────────────────────────────────

    /**
     * Open the Stream Mode WebSocket and deliver inbound messages to [onMessage].
     *
     * Suspends until the connection is closed (by [stopListening] or [scope] cancellation);
     * the reconnect loop runs on [scope] so cancelling it tears everything down. Each inbound
     * DingTalk message is mapped to an [ImMessage] (INCOMING, text-only) before the callback,
     * and its reply context is cached for the next [sendMessage].
     */
    override suspend fun startListening(onMessage: (ImMessage) -> Unit, scope: CoroutineScope) {
        val api = api ?: return
        if (!isConfigured) return
        if (connection != null) return // already listening; avoid a second socket

        val listener = object : DingtalkStreamListener {
            override fun onOpen() {
                DebugLog.d(TAG, "stream connected for client ${maskClientId(api.clientId)}")
            }

            override fun onMessage(message: DingtalkInbound) {
                // Cache reply context for the next sendMessage call on this conversation.
                replyContexts[message.conversationId] = ReplyContext(
                    sessionWebhook = message.sessionWebhook,
                    webhookExpiresAtMs = System.currentTimeMillis() + WEBHOOK_TTL_MS,
                    senderStaffId = message.senderStaffId,
                    conversationType = message.conversationType,
                    robotCode = message.robotCode,
                )
                val im = ImMessage(
                    id = message.msgId.ifBlank { message.messageId },
                    conversationId = message.conversationId,
                    direction = ImMessageDirection.INCOMING,
                    text = message.text,
                    sender = message.senderNick.ifBlank { message.senderStaffId },
                    timestampMs = message.createAt,
                )
                runCatching { onMessage(im) }.onFailure {
                    DebugLog.e(TAG, "onMessage callback threw: ${it.message}", it)
                }
            }

            override fun onClosed(code: Int, reason: String) {
                DebugLog.d(TAG, "stream closed: $code $reason")
            }

            override fun onError(error: Throwable, fatal: Boolean) {
                if (fatal) DebugLog.e(TAG, "stream fatal error: ${error.message}", error)
                else DebugLog.w(TAG, "stream error (will retry): ${error.message}")
            }
        }

        val conn = api.openStream(scope, listener)
        connection = conn
        // Suspend until the calling scope is cancelled (ImPollingReceiver cancels the launch
        // job on stop). The reconnect loop runs on `scope` inside the connection; we just need
        // to stay alive here so the launch job doesn't complete while the socket is open.
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            conn.close()
            connection = null
        }
    }

    /** Close the WebSocket and stop the reconnect loop. Safe to call when not listening. */
    override fun stopListening() {
        connection?.close()
        connection = null
    }

    // ── MessageChannel ──────────────────────────────────────────────────────

    /**
     * Send [text] into [conversationId]. Tries the cached `sessionWebhook` first (cheap, no
     * token round-trip); on failure or expiry falls back to the proactive REST API
     * ([DingtalkStreamApi.sendOtoMessage] for single-chat, [DingtalkStreamApi.sendGroupMessage]
     * for group). Returns [ImSendResult.NotConfigured] when the channel is disabled or has no
     * credentials, [ImSendResult.Failure] on transport/rejection, [ImSendResult.Success] with
     * DingTalk's `processQueryKey` (or a synthetic id for webhook replies) on success.
     */
    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        val api = api ?: return ImSendResult.NotConfigured
        if (!isConfigured) return ImSendResult.NotConfigured
        if (text.isBlank()) return ImSendResult.Failure("text is empty")

        return withContext(Dispatchers.IO) {
            val ctx = replyContexts[conversationId]
            // 1) Cheapest path: sessionWebhook, if present and not stale.
            if (ctx != null && ctx.sessionWebhook.isNotBlank() &&
                System.currentTimeMillis() < ctx.webhookExpiresAtMs
            ) {
                try {
                    if (api.replyBySessionWebhook(ctx.sessionWebhook, text)) {
                        return@withContext ImSendResult.Success("webhook:${System.currentTimeMillis()}")
                    }
                } catch (e: Exception) {
                    DebugLog.w(TAG, "sessionWebhook reply failed, falling back to REST: ${e.message}")
                }
            }
            // 2) REST fallback — needs senderStaffId for single-chat, conversationId for group.
            try {
                val isGroup = ctx?.conversationType == "2"
                val id = if (isGroup) {
                    api.sendGroupMessage(conversationId, text)
                } else {
                    val userId = ctx?.senderStaffId
                        ?: return@withContext ImSendResult.Failure(
                            "no senderStaffId cached for $conversationId; cannot send proactive single-chat",
                        )
                    api.sendOtoMessage(userId, text)
                }
                ImSendResult.Success(id.ifBlank { "rest:${System.currentTimeMillis()}" })
            } catch (e: DingtalkApiException) {
                DebugLog.e(TAG, "sendMessage REST failed: ${e.message} (code=${e.code})")
                ImSendResult.Failure(e.message ?: "dingtalk send failed")
            } catch (e: Exception) {
                DebugLog.e(TAG, "sendMessage failed", e)
                ImSendResult.Failure(e.message ?: "dingtalk send failed")
            }
        }
    }

    /** Push channel — never polled. Returns empty so any stray poll is a no-op. */
    override suspend fun listConversations(): List<ImConversation> = emptyList()

    /** Push channel — messages arrive via [startListening]. Returns empty. */
    override suspend fun fetchMessages(conversationId: String, afterId: String?): List<ImMessage> = emptyList()

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Mask a Client ID for logs: keep the first 4 chars, elide the rest. */
    private fun maskClientId(id: String): String =
        if (id.length <= 4) id else id.take(4) + "⋯"

    /** Per-conversation reply state, refreshed on every inbound message. */
    private data class ReplyContext(
        val sessionWebhook: String,
        val webhookExpiresAtMs: Long,
        val senderStaffId: String,
        val conversationType: String,
        val robotCode: String,
    )

    companion object {
        /** Platform identifier stored in [ImGatewayConfig.platform] for this channel. */
        const val PLATFORM = "dingtalk"
        private const val CHANNEL_ID = "dingtalk"
        private const val TAG = "DingtalkChannel"
        /** DingTalk session webhooks are valid ~1h; we treat 50min as the safe TTL. */
        private const val WEBHOOK_TTL_MS = 50L * 60 * 1000
    }
}