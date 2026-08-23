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
 * with a `subtype` (edits, deletes, bot joins...) and messages from bots (including our own
 * bot) are skipped to avoid loops. Only text is extracted; file/voice/block messages are
 * future work.
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
                val ts = api.postMessage(channel = conversationId, text = text)
                ImSendResult.Success(ts)
            } catch (e: SlackApiException) {
                DebugLog.e("SlackChannel", "sendMessage failed: ${e.message}")
                ImSendResult.Failure(e.message ?: "slack send failed")
            } catch (e: Exception) {
                DebugLog.e("SlackChannel", "sendMessage failed", e)
                ImSendResult.Failure(e.message ?: "slack send failed")
            }
        }
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
                    "socket mode disconnected: ${e.message}; reconnecting in ${backoffMs}ms",
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

        // Skip subtyped messages (bot_message, message_changed, message_deleted, joins...).
        if (event["subtype"]?.jsonPrimitive?.contentOrNull != null) return

        // Skip bot-authored messages (avoids reply loops). bot_id covers most bots; the
        // botUserId check covers our own replies when Slack doesn't set bot_id.
        if (event["bot_id"]?.jsonPrimitive?.contentOrNull != null) return
        val user = event["user"]?.jsonPrimitive?.contentOrNull ?: return
        val myUserId = botUserId
        if (myUserId != null && user == myUserId) return

        val text = event["text"]?.jsonPrimitive?.contentOrNull ?: return
        val channel = event["channel"]?.jsonPrimitive?.contentOrNull ?: return
        val ts = event["ts"]?.jsonPrimitive?.contentOrNull ?: return

        // For `message` events, only respond in DMs (channel_type == "im"). `app_mention`
        // is always a group mention and always passes.
        if (eventType == "message") {
            val channelType = event["channel_type"]?.jsonPrimitive?.contentOrNull
            if (channelType != "im") return
        }

        val message = ImMessage(
            id = ts,
            conversationId = channel,
            direction = ImMessageDirection.INCOMING,
            text = text,
            sender = user,
            timestampMs = parseTsToMillis(ts),
        )
        runCatching { onMessage(message) }
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
    }
}