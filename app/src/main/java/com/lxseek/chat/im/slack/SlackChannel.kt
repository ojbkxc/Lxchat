package com.lxseek.chat.im.slack

import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImMessageDirection
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.PushMessageChannel
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import kotlin.coroutines.coroutineContext

/**
 * Slack channel for Lxchat: a [PushMessageChannel] that receives inbound messages over
 * Slack's Socket Mode (WebSocket) and sends replies via the Web API (`chat.postMessage`).
 *
 * **Push model** — Slack does not offer a "fetch messages since X" endpoint. Instead the
 * app opens a long-lived WebSocket (Socket Mode) and Slack pushes `events_api` envelopes
 * for every subscribed event. [startListening] opens that socket and invokes [onMessage]
 * for each inbound text message; [listConversations] and [fetchMessages] return empty
 * (the push path bypasses polling).
 *
 * **Socket Mode lifecycle** — [startListening] loops: call `apps.connections.open` → connect
 * WSS → pump envelopes until closed → reconnect with a fresh URL. Slack sends `disconnect`
 * when it wants us to reconnect (URLs are short-lived); network failures trigger the same
 * reconnect loop with exponential backoff. [stopListening] closes the socket and breaks
 * the loop.
 *
 * **Event filtering** — only `message` (DM, `channel_type == "im"`) and `app_mention` (group
 * @-mention) events are handled, matching the manifest's `event_subscriptions`. Messages
 * with a `subtype` other than `file_share` (edits, deletes, bot joins...) and messages from
 * bots (including our own bot) are skipped to avoid loops. `file_share` messages are decoded
 * to the shared file's private URL when no text is present.
 *
 * **Conversation keying** — DMs are keyed by `channel`; group mentions are keyed by
 * `channel:threadTs` so messages in different threads of the same channel don't collide.
 * Replies to group threads carry `thread_ts` so they land in the original thread.
 *
 * **De-duplication** — inbound messages are keyed by Slack's `event_id` (payload-level),
 * not `event.ts`, so retries/edits don't re-trigger the agent. Bot @-mentions are stripped
 * from the text before forwarding. Long replies (>38000 chars) are split into chunks.
 *
 * **Configuration** ([ImGatewayConfig]):
 *  - `token`   → Bot Token (`xoxb-...`), used for `chat.postMessage` and `auth.test`.
 *  - `baseUrl` → App-Level Token (`xapp-...`), used for `apps.connections.open`.
 *  - `enabled` must be true; both tokens must pass format validation in [isConfigured].
 *
 * Mirrors `dsh-im/src/channels/slack/slack-api.mjs` (`SlackApi` + Socket Mode handling).
 * Plugged in by [com.lxseek.chat.im.ImChannelFactory] when the Slack branch is wired.
 */
class SlackChannel(
    private val config: ImGatewayConfig,
) : PushMessageChannel {

    override val channelId: String get() = CHANNEL_ID
    override val displayName: String get() = "Slack"

    override val isConfigured: Boolean
        get() = config.enabled &&
            SlackSocketModeApi.isValidBotToken(config.token) &&
            SlackSocketModeApi.isValidAppToken(config.baseUrl)

    /** Lazily built; null when either token is malformed so [isConfigured] stays false. */
    private val client: SlackSocketModeApi? =
        if (SlackSocketModeApi.isValidBotToken(config.token) &&
            SlackSocketModeApi.isValidAppToken(config.baseUrl)
        ) {
            SlackSocketModeApi(botToken = config.token, appToken = config.baseUrl)
        } else null

    private val json = Json { ignoreUnknownKeys = true }

    // ── Listening state ────────────────────────────────────────────────────
    // `running` is set by startListening/stopListening on the receiver's coroutine; the
    // WebSocket handle is published from the onOpen callback so stopListening can close it.
    @Volatile private var running = false
    @Volatile private var currentWebSocket: WebSocket? = null
    @Volatile private var botUserId: String? = null

    // ── MessageChannel ─────────────────────────────────────────────────────

    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        val api = client ?: return ImSendResult.NotConfigured
        if (!isConfigured) return ImSendResult.NotConfigured
        if (text.isBlank()) return ImSendResult.Failure("Slack message text is required")
        return withContext(Dispatchers.IO) {
            try {
                // Parse conversationId: group threads use "channel:threadTs" (so replies
                // land in the original thread); DMs use plain "channel" (threadTs = null).
                val (channel, threadTs) = parseConversationId(conversationId)
                // Split long messages (Slack text limit is 40000 chars; use 38000 for safety).
                val chunks = splitLongMessage(text, SLACK_MESSAGE_LIMIT)
                var lastTs = ""
                for (chunk in chunks) {
                    lastTs = api.postMessage(channel = channel, text = chunk, threadTs = threadTs)
                }
                ImSendResult.Success(lastTs)
            } catch (e: SlackApiException) {
                DebugLog.e("SlackChannel", "sendMessage failed")
                ImSendResult.Failure(e.message ?: "slack send failed")
            } catch (e: Exception) {
                DebugLog.e("SlackChannel", "sendMessage failed", e)
                ImSendResult.Failure(e.message ?: "slack send failed")
            }
        }
    }

    /**
     * Split [conversationId] into (channelId, threadTs). Group threads encode the thread
     * root timestamp as "channel:threadTs"; DMs use a plain "channel" with no colon, in
     * which case threadTs is null and replies are sent to the channel top-level.
     */
    private fun parseConversationId(conversationId: String): Pair<String, String?> {
        val idx = conversationId.indexOf(':')
        if (idx < 0) return conversationId to null
        val channel = conversationId.substring(0, idx)
        val threadTs = conversationId.substring(idx + 1)
        return channel to threadTs
    }

    /**
     * Split a long message into chunks of at most [limit] chars, preferring newline then
     * space breaks so chunks are not cut mid-sentence. Mirrors `splitMessageText` in
     * `dsh-im/src/channels/shared/editable-message-stream.mjs`.
     */
    private fun splitLongMessage(text: String, limit: Int): List<String> {
        val trimmed = text.trim()
        if (trimmed.length <= limit) return listOf(trimmed)
        val result = ArrayList<String>()
        var remaining = trimmed
        while (remaining.length > limit) {
            var cut = remaining.lastIndexOf('\n', limit)
            if (cut < limit / 2) cut = remaining.lastIndexOf(' ', limit)
            if (cut < limit / 2) cut = limit
            result.add(remaining.substring(0, cut).trim())
            remaining = remaining.substring(cut).trimStart()
        }
        if (remaining.isNotEmpty()) result.add(remaining)
        return result
    }

    /** Push channel — conversations are synthesized from inbound messages, not polled. */
    override suspend fun listConversations(): List<ImConversation> = emptyList()

    /** Push channel — messages arrive via [startListening], not fetch. */
    override suspend fun fetchMessages(conversationId: String, afterId: String?): List<ImMessage> = emptyList()

    // ── PushMessageChannel ─────────────────────────────────────────────────

    override suspend fun startListening(onMessage: (ImMessage) -> Unit, scope: CoroutineScope) {
        val api = client ?: return
        if (!isConfigured) return

        running = true
        // Fetch the bot's own user id once, to filter self-messages and avoid reply loops.
        try {
            val me = api.authTest()
            botUserId = me["user_id"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            DebugLog.e("SlackChannel", "auth.test failed; will rely on bot_id/subtype filtering", e)
        }

        var backoffMs = INITIAL_RECONNECT_DELAY_MS
        while (running) {
            coroutineContext.ensureActive()
            try {
                val wssUrl = api.openConnection()
                DebugLog.d("SlackChannel", "socket mode connecting")
                api.connectSocketMode(
                    wssUrl = wssUrl,
                    onEnvelope = { envelope -> handleEnvelope(envelope, onMessage) },
                    onOpen = { ws ->
                        currentWebSocket = ws
                        // If stopListening was called while we were opening the connection,
                        // close immediately so the loop exits instead of holding a zombie socket.
                        if (!running) runCatching { ws.close(1000, "not running") }
                    },
                )
                // Clean close — reset backoff for the next reconnect.
                backoffMs = INITIAL_RECONNECT_DELAY_MS
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!running) break
                DebugLog.e(
                    "SlackChannel",
                    "socket mode disconnected; reconnecting in ${backoffMs}ms",
                    e,
                )
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            }
        }
    }

    override fun stopListening() {
        running = false
        runCatching { currentWebSocket?.close(1000, "stopListening") }
        currentWebSocket = null
    }

    // ── Envelope / event handling ──────────────────────────────────────────

    /**
     * Route one Socket Mode envelope. Only `events_api` carries user messages; `hello` and
     * other control types are logged at debug level and otherwise ignored. `disconnect` is
     * already handled inside [SlackSocketModeApi.connectSocketMode] (closes the socket).
     */
    private fun handleEnvelope(envelope: JsonObject, onMessage: (ImMessage) -> Unit) {
        val type = envelope["type"]?.jsonPrimitive?.contentOrNull ?: return
        when (type) {
            "hello" -> DebugLog.d("SlackChannel", "socket mode hello")
            "events_api" -> handleEvent(envelope, onMessage)
            else -> Unit
        }
    }

    /**
     * Extract the `payload.event` from an `events_api` envelope, apply the DM/mention filter,
     * and convert it to an [ImMessage] for the agent loop.
     */
    private fun handleEvent(envelope: JsonObject, onMessage: (ImMessage) -> Unit) {
        val payload = envelope["payload"]?.asObject() ?: return
        val event = payload["event"]?.asObject() ?: return
        val eventType = event["type"]?.jsonPrimitive?.contentOrNull ?: return

        // Only handle the two subscribed event types (see manifest: message.im + app_mention).
        if (eventType != "message" && eventType != "app_mention") return

        // event_id lives at the payload level and is the de-duplication key (NOT event.ts,
        // which can repeat across retries/edits). Mirrors `normalizeSlackEvent` in dsh-im.
        val eventId = payload["event_id"]?.jsonPrimitive?.contentOrNull ?: return

        // Skip subtyped messages except file_share (bot_message, message_changed,
        // message_deleted, joins...). file_share is allowed through so we can extract the
        // shared file URL or fallback text.
        val subtype = event["subtype"]?.jsonPrimitive?.contentOrNull
        if (subtype != null && subtype != "file_share") return

        // Skip bot-authored messages (avoids reply loops). bot_id covers most bots; the
        // botUserId check covers our own replies when Slack doesn't set bot_id.
        if (event["bot_id"]?.jsonPrimitive?.contentOrNull != null) return
        val user = event["user"]?.jsonPrimitive?.contentOrNull ?: return
        val myUserId = botUserId
        if (myUserId != null && user == myUserId) return

        val channel = event["channel"]?.jsonPrimitive?.contentOrNull ?: return
        val ts = event["ts"]?.jsonPrimitive?.contentOrNull ?: return

        // For `message` events, only respond in DMs (channel_type == "im"). `app_mention`
        // is always a group mention and always passes.
        val isDirect = eventType == "message"
        if (isDirect) {
            val channelType = event["channel_type"]?.jsonPrimitive?.contentOrNull
            if (channelType != "im") return
        }

        // threadTs: the thread root timestamp for threaded replies. Falls back to the
        // message's own ts when not in a thread (replying starts a new thread on the message).
        val threadTs = event["thread_ts"]?.jsonPrimitive?.contentOrNull ?: ts

        // Group conversations are keyed by "channel:threadTs" so messages in different
        // threads of the same channel don't collide. DMs stay keyed by channel alone.
        // Mirrors dsh-im: `direct ? channel : `${channel}:${threadTs}``.
        val conversationId = if (isDirect) channel else "$channel:$threadTs"

        // Strip the bot @-mention prefix and decode Slack HTML entities. app_mention events
        // include `<@{botId}>` at the start; removing it gives the user's actual prompt.
        val rawText = event["text"]?.jsonPrimitive?.contentOrNull ?: ""
        val stripped = stripBotMention(rawText, myUserId)

        // file_share subtype: when the text is empty (or only contained the mention), fall
        // back to the shared file's private URL so the agent has something to work with.
        val effectiveText = if (stripped.isBlank() && subtype == "file_share") {
            extractFileShareText(event)
        } else {
            stripped
        }
        if (effectiveText.isBlank()) return

        val message = ImMessage(
            id = eventId,
            conversationId = conversationId,
            direction = ImMessageDirection.INCOMING,
            text = effectiveText,
            sender = user,
            timestampMs = parseTsToMillis(ts),
        )
        runCatching { onMessage(message) }
    }

    /**
     * Decode Slack's HTML entities (`&amp;` `&lt;` `&gt;`) and strip `<@{botUserId}>`
     * mentions so the agent receives the user's literal text. Mirrors `stripBotMention`
     * in `dsh-im/src/channels/slack/slack-runtime.mjs`.
     */
    private fun stripBotMention(value: String, botUserId: String?): String {
        val decoded = value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        val withoutMention = if (botUserId != null) {
            decoded.replace(Regex("<@$botUserId>", RegexOption.IGNORE_CASE), "")
        } else {
            decoded
        }
        return withoutMention.trim()
    }

    /**
     * For `file_share` messages whose text is empty, use the private URL of the first
     * attached file (preferring `url_private_download` when present, falling back to
     * `url_private`). Returns empty string when no file/URL is available.
     */
    private fun extractFileShareText(event: JsonObject): String {
        val files = runCatching { event["files"]?.jsonArray }.getOrNull() ?: return ""
        val firstFile = files.firstOrNull()?.asObject() ?: return ""
        return firstFile["url_private_download"]?.jsonPrimitive?.contentOrNull
            ?: firstFile["url_private"]?.jsonPrimitive?.contentOrNull
            ?: ""
    }

    /** Slack `ts` format is `<seconds>.<microseconds>`; we keep second-precision millis. */
    private fun parseTsToMillis(ts: String): Long {
        val seconds = ts.substringBefore('.').toLongOrNull() ?: return 0L
        return seconds * 1_000L
    }

    private fun JsonElement?.asObject(): JsonObject? =
        this?.let { runCatching { it.jsonObject }.getOrNull() }

    companion object {
        /** Platform identifier stored in [ImGatewayConfig.platform] for this channel. */
        const val PLATFORM = "slack"

        private const val CHANNEL_ID = "slack"
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L

        /**
         * Slack `chat.postMessage` rejects text longer than 40000 chars. We split at 38000
         * to leave headroom for any mention/entity expansion Slack does server-side. Mirrors
         * `SLACK_MESSAGE_LIMIT` in `dsh-im/src/channels/slack/slack-runtime.mjs`.
         */
        private const val SLACK_MESSAGE_LIMIT = 38_000
    }
}