package com.lxseek.chat.im.discord

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.receiveCatching
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

// ── REST API ──────────────────────────────────────────────────────────────────

/**
 * Minimal pure-Kotlin client for the Discord REST API (v10). Mirrors
 * `dsh-im/src/channels/discord/discord-api.mjs` (`DiscordApi` class) one-to-one in behavior:
 * token validation, getCurrentUser, getGatewayBot, createMessage. No Discord SDK — just OkHttp
 * via [HttpClient]'s shared client, the same instance every other Lxchat channel uses.
 */
class DiscordRestApi(
    /** Bot token from the Discord Developer Portal. `Bot ` prefix is stripped if present. */
    val token: String,
    /** REST base, overrideable for tests or a proxy. */
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    init {
        require(isValidDiscordToken(token)) { "Invalid Discord bot token" }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val base = baseUrl.trim().trimEnd('/')
    private val authHeaders = mapOf("Authorization" to "Bot ${token.trim()}")

    /** GET /users/@me — resolve the bot's own user object. */
    suspend fun getCurrentUser(): JsonObject = request("users/@me", "GET", null)

    /** GET /gateway/bot — fetch the recommended gateway URL and shard info. */
    suspend fun getGatewayBot(): JsonObject = request("gateway/bot", "GET", null)

    /**
     * POST /channels/{channelId}/messages — send a text message. Returns the created Message
     * object (contains `id`). [replyToMessageId] optionally attaches a message_reference.
     * `allowed_mentions` is locked down (parse: [], replied_user: false) so the bot never pings.
     */
    suspend fun createMessage(
        channelId: String,
        content: String,
        replyToMessageId: String? = null,
    ): JsonObject = request("channels/$channelId/messages", "POST", buildJsonObject {
        put("content", content)
        putJsonObject("allowed_mentions") {
            putJsonArray("parse") {}
            put("replied_user", false)
        }
        if (replyToMessageId != null) {
            putJsonObject("message_reference") {
                put("message_id", replyToMessageId)
                put("channel_id", channelId)
                put("fail_if_not_exists", false)
            }
        }
    })

    private suspend fun request(path: String, method: String, body: JsonObject?): JsonObject =
        withContext(Dispatchers.IO) {
            val url = "$base/$path"
            val response = when (method) {
                "GET" -> HttpClient.getTextResponse(url, authHeaders)
                "POST" -> HttpClient.postTextResponse(url, body?.toString() ?: "{}", authHeaders)
                else -> throw IllegalArgumentException("Unsupported method: $method")
            }
            if (!response.isSuccessful) {
                val apiMsg = runCatching {
                    json.parseToJsonElement(response.body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                throw DiscordApiException(
                    apiMsg ?: "Discord $method failed (HTTP ${response.code})",
                    response.code,
                )
            }
            runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
                ?: throw DiscordApiException("Discord $method returned invalid JSON", response.code)
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://discord.com/api/v10/"

        /** Strip an optional `Bot ` prefix and trim. */
        fun normalizeToken(raw: String): String {
            val t = raw.trim()
            return if (t.startsWith("Bot ", ignoreCase = true)) t.substring(4) else t
        }

        /**
         * Token shape: three dot-separated base64url-ish segments (header.timestamp.hmac),
         * per the Discord Developer Portal. The optional `Bot ` prefix is stripped first.
         */
        fun isValidDiscordToken(value: String): Boolean =
            TOKEN_REGEX.matches(normalizeToken(value))

        private val TOKEN_REGEX =
            Regex("""^[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{20,}$""")
    }
}

/** Raised when the Discord REST API returns a non-2xx or invalid JSON. */
class DiscordApiException(message: String, val errorCode: Int?) : Exception(message)

// ── Gateway WebSocket client ──────────────────────────────────────────────────

/** A single inbound MESSAGE_CREATE event, parsed for [DiscordChannel] to consume. */
data class DiscordMessageEvent(
    val channelId: String,
    val messageId: String,
    val content: String,
    val authorId: String,
    val authorName: String,
    val timestampMs: Long,
    /** True when the message came from a guild channel (guild_id present); false for DMs. */
    val isGroup: Boolean,
)

/**
 * Discord Gateway v10 WebSocket client. Opens `wss://gateway.discord.gg/?v=10&encoding=json`,
 * performs the IDENTIFY → HELLO → READY handshake (or RESUME on reconnect), maintains the
 * heartbeat, and invokes [onMessage] for every inbound MESSAGE_CREATE that is not from our own
 * bot. Reconnects with exponential backoff on close/failure; resumes the session when possible.
 *
 * The caller is expected to run [connect] inside a coroutine on the supplied [CoroutineScope];
 * [stop] closes the socket and lets [connect] return. This is the push surface Lxchat's
 * [DiscordChannel] binds to — no polling, no REST history fetch, just live event delivery.
 */
class DiscordGatewayClient(
    val token: String,
    private val gatewayUrl: String = DEFAULT_GATEWAY_URL,
    private val intents: Int = DEFAULT_INTENTS,
    private val onMessage: (DiscordMessageEvent) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var lastSeq: Long? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var botUserId: String? = null
    @Volatile private var botName: String? = null
    @Volatile private var heartbeatIntervalMs: Long = 0L
    @Volatile private var ready = false
    private var heartbeatJob: Job? = null

    /** Bot display name discovered at READY; null until then. Read by [DiscordChannel.displayName]. */
    val botDisplayName: String? get() = botName

    /** Close the socket and signal [connect] to return. Safe to call when not connected. */
    fun stop() {
        stopped = true
        heartbeatJob?.cancel()
        runCatching { webSocket?.close(1000, "client stop") }
    }

    /**
     * Run the connect → handshake → receive loop, reconnecting with exponential backoff until
     * [stop] is called or [scope] is cancelled. Suspends for the lifetime of the connection.
     */
    suspend fun connect(scope: CoroutineScope) {
        var backoff = INITIAL_BACKOFF_MS
        while (scope.isActive && !stopped) {
            val connected = try {
                runConnection(scope)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e(TAG, "connection attempt failed: ${e.message}", e)
                false
            }
            if (stopped || !scope.isActive) break
            if (connected) {
                backoff = INITIAL_BACKOFF_MS
            } else {
                backoffDelay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /** One full connect → handshake → receive-until-close cycle. Returns true if READY was reached. */
    private suspend fun runConnection(scope: CoroutineScope): Boolean {
        // Per-cycle inbound queue. Closing it (in onClosed/onFailure) unblocks receiveCatching so
        // the receive loop exits promptly without a separate select on a "closed" deferred.
        val incoming = Channel<String>(Channel.UNLIMITED)
        val opened = CompletableDeferred<Unit>()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@DiscordGatewayClient.webSocket = webSocket
                opened.complete(Unit)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.trySend(text)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                DebugLog.w(TAG, "websocket closed: code=$code reason=$reason")
                incoming.close()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                DebugLog.e(TAG, "websocket failure: ${t.message}", t)
                opened.completeExceptionally(t)
                incoming.close()
            }
        }

        val request = Request.Builder().url(gatewayUrl).build()
        HttpClient.client.newWebSocket(request, listener)

        try {
            opened.await()
        } catch (e: Exception) {
            incoming.close()
            return false
        }

        ready = false
        try {
            while (scope.isActive && !stopped) {
                val text = incoming.receiveCatching().getOrNull() ?: break
                handlePayload(text, scope)
            }
        } finally {
            heartbeatJob?.cancel()
            heartbeatJob = null
        }
        return ready
    }

    // ── Payload dispatch ─────────────────────────────────────────────────────

    private fun handlePayload(text: String, scope: CoroutineScope) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val op = root["op"]?.jsonPrimitive?.intOrNull ?: return
        val eventName = root["t"]?.jsonPrimitive?.contentOrNull
        val seq = root["s"]?.jsonPrimitive?.longOrNull
        if (seq != null) lastSeq = seq

        when (op) {
            OP_HELLO -> {
                val d = root["d"]?.jsonObject ?: return
                heartbeatIntervalMs = d["heartbeat_interval"]?.jsonPrimitive?.longOrNull ?: 0L
                sendIdentifyOrResume()
                startHeartbeat(scope)
            }
            OP_HEARTBEAT -> sendHeartbeat()
            OP_HEARTBEAT_ACK -> Unit
            OP_RECONNECT -> {
                DebugLog.w(TAG, "server requested reconnect")
                runCatching { webSocket?.close(4000, "reconnect requested") }
            }
            OP_INVALID_SESSION -> {
                val resumable = root["d"]?.jsonPrimitive?.booleanOrNull == true
                if (!resumable) {
                    sessionId = null
                    lastSeq = null
                }
                DebugLog.w(TAG, "invalid session (resumable=$resumable)")
                runCatching { webSocket?.close(4000, "invalid session") }
            }
            OP_DISPATCH -> handleDispatch(eventName, root["d"]?.jsonObject)
            else -> Unit
        }
    }

    private fun handleDispatch(event: String?, data: JsonObject?) {
        when (event) {
            "READY" -> {
                ready = true
                sessionId = data?.get("session_id")?.jsonPrimitive?.contentOrNull
                val user = data?.get("user")?.jsonObject
                botUserId = user?.get("id")?.jsonPrimitive?.contentOrNull
                botName = user?.get("global_name")?.jsonPrimitive?.contentOrNull
                    ?: user?.get("username")?.jsonPrimitive?.contentOrNull
                DebugLog.w(TAG, "READY: bot id=$botUserId name=$botName session=$sessionId")
            }
            "RESUMED" -> {
                ready = true
                DebugLog.w(TAG, "RESUMED: session=$sessionId seq=$lastSeq")
            }
            "MESSAGE_CREATE" -> handleMessageCreate(data)
            else -> Unit
        }
    }

    private fun handleMessageCreate(data: JsonObject?) {
        if (data == null) return
        val messageId = data["id"]?.jsonPrimitive?.contentOrNull ?: return
        val channelId = data["channel_id"]?.jsonPrimitive?.contentOrNull ?: return
        val content = data["content"]?.jsonPrimitive?.contentOrNull ?: ""
        if (content.isEmpty()) return  // non-text (embed/attachment/sticker-only), skip
        val author = data["author"]?.jsonObject ?: return
        val authorId = author["id"]?.jsonPrimitive?.contentOrNull ?: return
        // Drop our own outbound messages so the bot never echoes itself into a reply loop.
        if (authorId == botUserId) return
        val authorName = author["global_name"]?.jsonPrimitive?.contentOrNull
            ?: author["username"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val isGroup = data["guild_id"]?.jsonPrimitive?.contentOrNull != null
        val timestampMs = snowflakeToTimestampMs(messageId)
        try {
            onMessage(
                DiscordMessageEvent(
                    channelId = channelId,
                    messageId = messageId,
                    content = content,
                    authorId = authorId,
                    authorName = authorName,
                    timestampMs = timestampMs,
                    isGroup = isGroup,
                ),
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "onMessage callback threw: ${e.message}", e)
        }
    }

    // ── Heartbeat & identify ─────────────────────────────────────────────────

    private fun startHeartbeat(scope: CoroutineScope) {
        heartbeatJob?.cancel()
        val interval = heartbeatIntervalMs
        if (interval <= 0) return
        heartbeatJob = scope.launch {
            while (isActive) {
                sendHeartbeat()
                delay(interval)
            }
        }
    }

    private fun sendHeartbeat() {
        val seq = lastSeq
        val payload = if (seq != null) {
            buildJsonObject { put("op", OP_HEARTBEAT); put("d", seq) }.toString()
        } else {
            buildJsonObject { put("op", OP_HEARTBEAT); put("d", JsonNull) }.toString()
        }
        runCatching { webSocket?.send(payload) }
    }

    private fun sendIdentifyOrResume() {
        val sid = sessionId
        val seq = lastSeq
        val ws = webSocket ?: return
        if (sid != null && seq != null) {
            val payload = buildJsonObject {
                put("op", OP_RESUME)
                putJsonObject("d") {
                    put("token", token)
                    put("session_id", sid)
                    put("seq", seq)
                }
            }.toString()
            ws.send(payload)
            DebugLog.d(TAG, "sent RESUME session=$sid seq=$seq")
        } else {
            val payload = buildJsonObject {
                put("op", OP_IDENTIFY)
                putJsonObject("d") {
                    put("token", token)
                    put("intents", intents)
                    putJsonObject("properties") {
                        put("os", "android")
                        put("browser", "Lxchat")
                        put("device", "Lxchat")
                    }
                }
            }.toString()
            ws.send(payload)
            DebugLog.d(TAG, "sent IDENTIFY intents=$intents")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Discord snowflake epoch (2015-01-01T00:00:00Z) in milliseconds. */
    private fun snowflakeToTimestampMs(id: String): Long {
        val raw = id.toLongOrNull() ?: return 0L
        return (raw shr 22) + DISCORD_EPOCH_MS
    }

    /** Delay in small steps so [stop] is noticed within [STEP_MS] rather than the full [ms]. */
    private suspend fun backoffDelay(ms: Long) {
        var remaining = ms
        while (remaining > 0 && !stopped) {
            val step = minOf(remaining, STEP_MS)
            delay(step)
            remaining -= step
        }
    }

    companion object {
        private const val TAG = "DiscordGateway"
        const val DEFAULT_GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"

        // Gateway op codes (https://discord.com/developers/docs/topics/opcodes-and-status-codes).
        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_RESUME = 6
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11

        // Intents: GUILD_MESSAGES (1<<9) | DIRECT_MESSAGES (1<<12) | MESSAGE_CONTENT (1<<15).
        // MESSAGE_CONTENT is a privileged intent — must be enabled in the Developer Portal,
        // otherwise message content arrives empty and is skipped by [handleMessageCreate].
        const val INTENT_GUILD_MESSAGES = 1 shl 9
        const val INTENT_DIRECT_MESSAGES = 1 shl 12
        const val INTENT_MESSAGE_CONTENT = 1 shl 15
        const val DEFAULT_INTENTS =
            INTENT_GUILD_MESSAGES or INTENT_DIRECT_MESSAGES or INTENT_MESSAGE_CONTENT

        private const val DISCORD_EPOCH_MS = 1_420_070_400_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val STEP_MS = 200L
    }
}