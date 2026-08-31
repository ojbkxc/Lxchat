package com.lxseek.chat.im.qq

import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImMessageDirection
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.PushMessageChannel
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * **Inbound images** — QQ delivers image messages as `attachments` with `content_type` of an image MIME type (e.g. `image/png`).
 * [QqBotWebSocketClient] extracts the CDN URLs into [QqMessageEvent.images]; this channel
 * forwards them to [ImMessage.images] so [com.lxseek.chat.im.ImPollingReceiver.buildPromptText]
 * surfaces them to the agent as Markdown links. Mirrors `dsh-im/qq-bridge.mjs`
 * `qqInboundMessage`.
 *
 * **Outbound** messages go through [QqRestApi.sendC2cMessage] / [sendGroupMessage]. The
 * [conversationId] passed to [sendMessage] is the QQ conversation key
 * (`"c2c:<user_openid>"` or `"group:<group_openid>"`) the receiver bound the Lxchat session
 * to. When a recent inbound message id is known for that conversation (within QQ's 5-minute
 * passive-reply window), it is attached as `msg_id` so the send is a passive reply; otherwise
 * the send is a proactive message (subject to QQ's proactive quotas). Replies that exceed
 * QQ's single-message length limit are split into multiple segments before sending — a
 * defensive fallback that mirrors `dsh-im`'s stream-then-fall-back-to-text path, and
 * composes cleanly with [com.lxseek.chat.im.MultiSegmentMessageSender] which already segments
 * upstream.
 *
 * **Owner filtering** — only the configured owner may issue bot control commands (`/reload`,
 * `/stop`, …). The owner's `user_openid` is read from [ImGatewayConfig.botId] (field reuse:
 * QQ derives its internal bot id from the AppID, so the `botId` config field is repurposed
 * to carry the owner identity — blank or `"*"` admits everyone, matching `dsh-im`'s
 * `ownerUserOpenid === '*'` semantics). A non-owner `/command` is stripped of its leading
 * `/` and forwarded as an ordinary user message so the contact still gets an AI reply
 * instead of silently swallowing the input.
 *
 * **Control commands** — `/reload` is intercepted here and triggers a WebSocket reconnect
 * (tear down the current [QqBotWebSocketClient] and re-enter the connect loop) so config
 * changes pick up without an app restart. Other `/commands` (`/stop`, `/new`, `/help`, …)
 * are left to [com.lxseek.chat.im.ImCommandProcessor] upstream, exactly as for every other
 * push channel.
 *
 * **De-duplication** — a bounded in-memory [seenMessageIds] set drops duplicate event ids
 * within one process lifetime (defensive first layer, mirroring `dsh-im`'s
 * `#acceptedMessageIds`). Cross-restart de-duplication is persisted by
 * [com.lxseek.chat.im.ImPollingReceiver] via [com.lxseek.chat.im.ImGatewayStore]'s DataStore
 * (`ImRuntimeState.seenMessageIds`), so a restart never replays a handled message — the
 * Lxchat equivalent of `dsh-im`'s `state-store.mjs` `QqStateStore.markSeen`.
 *
 * **Configuration** is reused from [ImGatewayConfig]: `token` carries the AppID, `baseUrl`
 * carries the AppSecret (field reuse documented in the channel task — QQ has no REST base
 * URL to override, so the secret field is borrowed), `botId` carries the owner `user_openid`
 * (blank or `"*"` = admit everyone), `platform` must be `"qq"`.
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

    /** Scope captured from [startListening] so control-command handlers can launch coroutines. */
    @Volatile private var listenScope: CoroutineScope? = null

    /** Set by the `/reload` control command to request a WebSocket reconnect from [startListening]. */
    @Volatile private var reloadRequested: Boolean = false

    /**
     * Owner `user_openid` admitted to issue control commands. Blank or `"*"` admits everyone,
     * matching `dsh-im`'s `ownerUserOpenid === '*'` semantics. Read from [ImGatewayConfig.botId].
     */
    private val ownerUserOpenid: String = config.botId.trim()

    /**
     * In-memory de-duplication set for inbound message ids (defensive first layer). Bounded
     * to [MAX_SEEN] entries; cross-restart persistence is handled by [ImPollingReceiver] +
     * [ImGatewayStore] (DataStore), mirroring `dsh-im`'s `QqStateStore.markSeen`.
     */
    private val seenMessageIds: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap())

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
        // Split overlong text into QQ-safe segments. MultiSegmentMessageSender already
        // segments upstream at 1800 chars, so this is a defensive fallback for direct
        // callers — it never double-splits because 1800 < MAX_MESSAGE_LENGTH.
        val segments = splitForSend(text)
        var lastResult: ImSendResult = ImSendResult.Failure("empty message")
        for ((index, segment) in segments.withIndex()) {
            if (index > 0) delay(SEGMENT_DELAY_MS)
            lastResult = sendSingle(api, parsed, segment)
            if (lastResult !is ImSendResult.Success) return lastResult
        }
        return lastResult
    }

    /** Send one segment via the appropriate scope (c2c / group) with passive-reply msg_id. */
    private suspend fun sendSingle(
        api: QqRestApi,
        parsed: ParsedConversation,
        segment: String,
    ): ImSendResult = try {
        val msgId = consumeRecentMsgId(parsed.conversationKey)
        val result = when (parsed.scope) {
            "c2c" -> api.sendC2cMessage(parsed.targetId, segment, msgId)
            "group" -> api.sendGroupMessage(parsed.targetId, segment, msgId)
            else -> return ImSendResult.Failure("Unknown QQ reply scope: ${parsed.scope}")
        }
        val sentId = result["id"]?.let {
            runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
        } ?: result["msg_id"]?.let {
            runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
        } ?: "unknown"
        ImSendResult.Success(sentId)
    } catch (e: QqApiException) {
        DebugLog.e("QqChannel", "sendMessage failed (code=${e.errorCode})")
        ImSendResult.Failure(e.message ?: "qq send failed")
    } catch (e: Exception) {
        DebugLog.e("QqChannel", "sendMessage failed", e)
        ImSendResult.Failure(e.message ?: "qq send failed")
    }

    /** Push channel — conversations are not polled; the receiver learns of them from inbound events. */
    override suspend fun listConversations(): List<ImConversation> = emptyList()

    /** Push channel — messages arrive via [startListening]; nothing to fetch on demand. */
    override suspend fun fetchMessages(conversationId: String, afterId: String?): List<ImMessage> =
        emptyList()

    override suspend fun startListening(onMessage: (ImMessage) -> Unit, scope: CoroutineScope) {
        if (!isConfigured) return
        val api = rest ?: return
        listenScope = scope
        try {
            // Reconnect loop: a `/reload` control command sets reloadRequested and stops the
            // current client, which lets connect() return; the loop then rebuilds a fresh
            // client so config/credential changes pick up without an app restart. Normal
            // disconnects are handled inside QqBotWebSocketClient.connect's own backoff loop,
            // so we only re-enter here when an explicit reload is requested.
            while (scope.isActive) {
                val client = QqBotWebSocketClient(
                    appId = config.token.trim(),
                    appSecret = config.baseUrl.trim(),
                    restApi = api,
                    gatewayUrl = QqBotWebSocketClient.DEFAULT_GATEWAY_URL,
                    onMessage = { event -> handleInboundEvent(event, onMessage, scope) },
                )
                gateway = client
                try {
                    client.connect(scope)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.e("QqChannel", "WebSocket connect failed", e)
                } finally {
                    gateway = null
                }
                if (!scope.isActive) break
                if (reloadRequested) {
                    reloadRequested = false
                    DebugLog.i("QqChannel", "/reload: reconnecting QQ WebSocket")
                    continue
                }
                break  // connect returned without a reload request — stop listening
            }
        } finally {
            listenScope = null
        }
    }

    override fun stopListening() {
        gateway?.stop()
    }

    // ── Inbound event handling ───────────────────────────────────────────────

    /**
     * Apply owner filtering, control-command interception, and de-duplication to a raw
     * [QqMessageEvent], then forward the resulting [ImMessage] to [onMessage].
     */
    private fun handleInboundEvent(
        event: QqMessageEvent,
        onMessage: (ImMessage) -> Unit,
        scope: CoroutineScope,
    ) {
        // De-dup: drop events we have already seen in this process lifetime. Cross-restart
        // de-dup is persisted upstream by ImPollingReceiver via ImGatewayStore.
        if (!seenMessageIds.add(event.messageId)) {
            DebugLog.d("QqChannel", "dro duplicate inbound msg=${event.messageId}")
            return
        }
        if (seenMessageIds.size > MAX_SEEN) pruneSeen()

        // Track the inbound msg_id so a near-term reply can be sent as a passive reply.
        recentInboundMsgIds[event.conversationId] =
            MsgIdEntry(event.messageId, System.currentTimeMillis())

        val text = event.content
        // Control-command interception: `/reload` is handled in-channel (reconnect). Other
        // `/commands` are left to ImCommandProcessor upstream — but only when the sender is
        // the owner. A non-owner `/command` is stripped of its leading `/` and forwarded as
        // an ordinary user message so the contact still gets an AI reply.
        if (text.startsWith("/") && text.length > 1) {
            val commandName = text.substring(1).takeWhile { !it.isWhitespace() }.lowercase()
            if (commandName == "reload") {
                if (isOwner(event.authorId)) {
                    // Owner-issued reload: tear down the WebSocket and re-enter the connect loop.
                    reloadRequested = true
                    scope.launch {
                        runCatching { sendControlReply(event, "已重新加载配置，正在重连…") }
                    }
                    gateway?.stop()
                    return
                }
                // Non-owner reload: fall through and treat as ordinary message (strip the `/`).
                forwardAsOrdinaryMessage(event, onMessage)
                return
            }
            if (!isOwner(event.authorId)) {
                // Non-owner command: strip the leading `/` so ImCommandProcessor does not
                // intercept it, and let the agent answer it as a normal user message.
                forwardAsOrdinaryMessage(event, onMessage)
                return
            }
            // Owner-issued non-reload command: forward verbatim for ImCommandProcessor.
        }

        onMessage(
            ImMessage(
                id = event.messageId,
                conversationId = event.conversationId,
                direction = ImMessageDirection.INCOMING,
                text = text,
                sender = event.authorName.ifBlank { event.authorId },
                timestampMs = event.timestampMs,
                images = event.images,
            ),
        )
    }

    /**
     * Forward [event] as an ordinary user message with the leading `/` stripped, so a
     * non-owner `/command` reaches the agent as plain text instead of being intercepted
     * by [ImCommandProcessor].
     */
    private fun forwardAsOrdinaryMessage(event: QqMessageEvent, onMessage: (ImMessage) -> Unit) {
        val stripped = event.content.trimStart('/').trim()
        onMessage(
            ImMessage(
                id = event.messageId,
                conversationId = event.conversationId,
                direction = ImMessageDirection.INCOMING,
                text = stripped,
                sender = event.authorName.ifBlank { event.authorId },
                timestampMs = event.timestampMs,
                images = event.images,
            ),
        )
    }

    /** True when [senderId] is the configured owner, or when no owner restriction is in place. */
    private fun isOwner(senderId: String): Boolean =
        ownerUserOpenid.isEmpty() || ownerUserOpenid == "*" || senderId == ownerUserOpenid

    /** Send a short control-command acknowledgement back to the originating conversation. */
    private suspend fun sendControlReply(event: QqMessageEvent, text: String) {
        val api = rest ?: return
        try {
            when (event.replyScope) {
                "c2c" -> api.sendC2cMessage(event.replyTargetId, text, event.messageId)
                "group" -> api.sendGroupMessage(event.replyTargetId, text, event.messageId)
            }
        } catch (e: Exception) {
            DebugLog.w("QqChannel", "control reply failed")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Parse `"c2c:<openid>"` / `"group:<group_openid>"` into scope + target id. */
    private fun parseConversationId(conversationId: String): ParsedConversation? {
        val idx = conversationId.indexOf(':')
        if (idx <= 0 || idx == conversationId.length - 1) return null
        val scope = conversationId.substring(0, idx)
        val targetId = conversationId.substring(idx + 1)
        if (scope !in REPLY_SCOPES || targetId.isBlank()) return null
        return ParsedConversation(scope, targetId, conversationId)
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

    /**
     * Split [text] into segments of at most [MAX_MESSAGE_LENGTH] characters, preferring to
     * break at newlines so segments are not cut mid-line. A single segment is returned as-is
     * (no allocation) when the text already fits.
     */
    private fun splitForSend(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= MAX_MESSAGE_LENGTH) return listOf(trimmed)
        val segments = ArrayList<String>()
        var start = 0
        while (start < trimmed.length) {
            val end = minOf(start + MAX_MESSAGE_LENGTH, trimmed.length)
            if (end == trimmed.length) {
                segments.add(trimmed.substring(start))
                break
            }
            // Prefer to break at the last newline within the window to avoid mid-line cuts.
            val window = trimmed.substring(start, end)
            val newline = window.lastIndexOf('\n')
            val cut = if (newline > 0) newline + 1 else MAX_MESSAGE_LENGTH
            segments.add(window.substring(0, cut).trimEnd())
            start += cut
        }
        return segments
    }

    /** Evict the oldest entries when [seenMessageIds] exceeds [MAX_SEEN]. */
    private fun pruneSeen() {
        // ConcurrentHashMap iteration is weakly consistent; remove a small batch to amortize.
        val it = seenMessageIds.iterator()
        var removed = 0
        while (it.hasNext() && removed < SEEN_PRUNE_BATCH) {
            it.next()
            it.remove()
            removed++
        }
    }

    private data class ParsedConversation(val scope: String, val targetId: String, val conversationKey: String)

    companion object {
        /** Platform identifier stored in [ImGatewayConfig.platform] for this channel. */
        const val PLATFORM = "qq"
        private const val CHANNEL_ID = "qq"
        private val REPLY_SCOPES = setOf("c2c", "group")
        /** QQ requires passive replies within 5 minutes of the inbound message. */
        private const val PASSIVE_REPLY_WINDOW_MS = 5 * 60 * 1000L
        /**
         * QQ single-message content ceiling. The platform rejects `content` beyond ~2000
         * chars; we split defensively at [MAX_MESSAGE_LENGTH] so a direct caller cannot
         * trigger a gateway error. Upstream [MultiSegmentMessageSender] already segments at
         * 1800, so this only engages for unsliced sends.
         */
        private const val MAX_MESSAGE_LENGTH = 2000
        /** Delay between consecutive segments of a split outbound message, to ease rate limits. */
        private const val SEGMENT_DELAY_MS = 300L
        /** Bound on the in-memory seen-set; cross-restart persistence is upstream. */
        private const val MAX_SEEN = 2_000
        /** Number of entries evicted per prune pass, to amortize the cost. */
        private const val SEEN_PRUNE_BATCH = 200
    }
}
