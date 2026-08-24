package com.lxseek.chat.im.qq

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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
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
import java.util.concurrent.atomic.AtomicReference

// ── REST API ──────────────────────────────────────────────────────────────────

/**
 * Minimal pure-Kotlin client for the QQ Bot Open Platform REST API.
 *
 * Mirrors `dsh-im/src/channels/qq/qq-runtime.mjs` (which delegates to
 * `@tencent-connect/qqbot-nodejs`) in behavior, but without any SDK — just OkHttp via
 * [HttpClient]'s shared client, the same instance every other Lxchat channel uses.
 *
 * Two responsibilities:
 *  1. Obtain and refresh an `access_token` from `POST /cgi-bin/token` using the AppID +
 *     AppSecret issued by the QQ Bot Platform. The token is cached and refreshed a safety
 *     margin before [expiresIn] elapses, so callers never see a stale token mid-request.
 *  2. Send text messages to a C2C (private) or group conversation via the v2 message APIs.
 *
 * The AppID is stored in [ImGatewayConfig.token] and the AppSecret in
 * [ImGatewayConfig.baseUrl] (field reuse documented in the channel task). Both must be
 * non-blank for [QqChannel.isConfigured] to hold.
 *
 * Reference: https://bot.q.qq.com/wiki/develop/api-v2/
 */
class QqRestApi(
    /** AppID from the QQ Bot Platform, stored in [ImGatewayConfig.token]. */
    val appId: String,
    /** AppSecret from the QQ Bot Platform, stored in [ImGatewayConfig.baseUrl]. */
    val appSecret: String,
    /** REST base, overrideable for tests or a proxy. */
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    init {
        require(appId.isNotBlank()) { "QQ appId must not be blank" }
        require(appSecret.isNotBlank()) { "QQ appSecret must not be blank" }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val base = baseUrl.trim().trimEnd('/')

    // ── Access token cache ───────────────────────────────────────────────────
    // Token is shared across REST and WebSocket paths; refreshed proactively so a send
    // never races with expiry. AtomicReference swap keeps reads lock-free.
    private val tokenHolder = AtomicReference<CachedToken?>(null)

    private data class CachedToken(val accessToken: String, val expiresAtMs: Long)

    /**
     * Return a non-expired access token, fetching or refreshing when needed.
     * Safe to call concurrently — the last writer wins and an in-flight refresh is not
     * duplicated, but a duplicate refresh is harmless (the platform issues a new token).
     */
    suspend fun accessToken(): String {
        val now = System.currentTimeMillis()
        tokenHolder.get()?.let { if (now < it.expiresAtMs) return it.accessToken }
        return refreshAccessToken()
    }

    private suspend fun refreshAccessToken(): String = withContext(Dispatchers.IO) {
        val url = "$base/cgi-bin/token"
        val body = buildJsonObject {
            put("appid", appId)
            put("secret", appSecret)
            put("grant_type", "client_credential")
        }.toString()
        val response = HttpClient.postTextResponse(url, body, emptyMap())
        if (!response.isSuccessful) {
            throw QqApiException("QQ token request failed (HTTP ${response.code})", response.code)
        }
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: throw QqApiException("QQ token response was not valid JSON", response.code)
        val token = root["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw QqApiException("QQ token response missing access_token", response.code)
        val expiresIn = root["expires_in"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TOKEN_TTL_S
        // Refresh 60s before expiry as a safety margin against clock drift / network latency.
        val expiresAt = System.currentTimeMillis() + (expiresIn - TOKEN_REFRESH_MARGIN_S) * 1000L
        tokenHolder.set(CachedToken(token, expiresAt))
        token
    }

    /**
     * Send a text message to a C2C (private) conversation.
     *
     * [msgId] is the inbound message id to passively reply to; QQ requires passive replies
     * within 5 minutes of the original user message. When null, the send is a proactive
     * message (subject to QQ's proactive messaging quotas).
     */
    suspend fun sendC2cMessage(
        openid: String,
        content: String,
        msgId: String? = null,
    ): JsonObject = sendMessage(
        path = "v2/users/$openid/messages",
        content = content,
        msgId = msgId,
        msgType = MSG_TYPE_TEXT,
    )

    /** Send a text message to a group conversation (passive reply when [msgId] is set). */
    suspend fun sendGroupMessage(
        groupOpenid: String,
        content: String,
        msgId: String? = null,
    ): JsonObject = sendMessage(
        path = "v2/groups/$groupOpenid/messages",
        content = content,
        msgId = msgId,
        msgType = MSG_TYPE_TEXT,
    )

    private suspend fun sendMessage(
        path: String,
        content: String,
        msgId: String?,
        msgType: Int,
    ): JsonObject = withContext(Dispatchers.IO) {
        val token = accessToken()
        val url = "$base/$path"
        val body = buildJsonObject {
            put("content", content)
            put("msg_type", msgType)
            if (msgId != null) put("msg_id", msgId)
        }.toString()
        val headers = mapOf("Authorization" to "QQBot $token")
        val response = HttpClient.postTextResponse(url, body, headers)
        if (!response.isSuccessful) {
            val apiMsg = runCatching {
                json.parseToJsonElement(response.body).jsonObject.let {
                    it["message"]?.jsonPrimitive?.contentOrNull
                        ?: it["errcode"]?.jsonPrimitive?.contentOrNull
                }
            }.getOrNull()
            throw QqApiException(
                apiMsg ?: "QQ send failed (HTTP ${response.code})",
                response.code,
            )
        }
        runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: throw QqApiException("QQ send response was not valid JSON", response.code)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.sgroup.qq.com"
        private const val DEFAULT_TOKEN_TTL_S = 7200L
        private const val TOKEN_REFRESH_MARGIN_S = 60L
        private const val MSG_TYPE_TEXT = 0
    }
}

/** Raised when the QQ REST API returns a non-2xx or invalid JSON. */
class QqApiException(message: String, val errorCode: Int?) : Exception(message)

// ── WebSocket client ──────────────────────────────────────────────────────────

/** A single inbound QQ message event, parsed for [QqChannel] to consume. */
data class QqMessageEvent(
    /** Inbound message id (used as the dedup key and as msg_id for passive replies). */
    val messageId: String,
    /**
     * Conversation key the receiver binds a Lxchat session to: `"c2c:<user_openid>"` for
     * private chats, `"group:<group_openid>"` for group chats. Stable across messages in
     * the same thread, unlike [messageId].
     */
    val conversationId: String,
    /**
     * Message text. May be empty when the inbound event only carries image attachments —
     * in that case [images] is non-empty and [QqChannel] surfaces the URLs to the agent
     * via [com.lxseek.chat.im.ImMessage.images].
     */
    val content: String,
    /** Sender's user_openid / member_openid. */
    val authorId: String,
    /** Author display name when the platform supplies one; blank otherwise. */
    val authorName: String,
    /** Event timestamp in milliseconds (server-reported, epoch ms). */
    val timestampMs: Long,
    /** True when the event came from a group (@-message); false for C2C. */
    val isGroup: Boolean,
    /** Raw event type (e.g. `C2C_MESSAGE_CREATE`, `GROUP_AT_MESSAGE_CREATE`). */
    val rawEventType: String,
    /** Reply scope: `"c2c"` or `"group"`. */
    val replyScope: String,
    /** Reply target id: user_openid (c2c) or group_openid (group). */
    val replyTargetId: String,
    /**
     * Image attachment URLs extracted from the event's `attachments` array (JPEG/PNG/WebP/GIF).
     * Mirrors `dsh-im/src/channels/qq/qq-bridge.mjs` `qqInboundMessage` which turns QQ's
     * attachment metadata into image references for the harness. Empty for pure-text messages.
     * The URLs are QQ CDN temporaries; they are passed through to the agent as Markdown links
     * by [com.lxseek.chat.im.ImPollingReceiver.buildPromptText].
     */
    val images: List<String> = emptyList(),
)

/**
 * QQ Bot Open Platform WebSocket client. Opens `wss://api.sgroup.qq.com/websockets`,
 * performs the HELLO → IDENTIFY → READY handshake (or RESUME on reconnect), maintains the
 * heartbeat, and invokes [onMessage] for every inbound `C2C_MESSAGE_CREATE` /
 * `GROUP_AT_MESSAGE_CREATE` event. Reconnects with exponential backoff on close/failure
 * and resumes the session when the server provides a `session_id`.
 *
 * The QQ wire protocol is op-coded and closely mirrors Discord's Gateway v10
 * (HELLO=10, IDENTIFY=2, DISPATCH=0, HEARTBEAT=1/3, HEARTBEAT_ACK=7, RECONNECT=11,
 * INVALID_SESSION=12, RESUME=4), so this class follows the same shape as
 * [com.lxseek.chat.im.discord.DiscordGatewayClient].
 *
 * The caller runs [connect] inside a coroutine on the supplied [CoroutineScope]; [stop]
 * closes the socket and lets [connect] return. This is the push surface [QqChannel] binds
 * to — no polling, no REST history fetch, just live event delivery.
 *
 * Reference: https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/interface-framework/api-1/
 */
class QqBotWebSocketClient(
    val appId: String,
    val appSecret: String,
    private val restApi: QqRestApi,
    private val gatewayUrl: String = DEFAULT_GATEWAY_URL,
    private val intents: Int = DEFAULT_INTENTS,
    private val onMessage: (QqMessageEvent) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var lastSeq: Long? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var botOpenid: String? = null
    @Volatile private var botName: String? = null
    @Volatile private var heartbeatIntervalMs: Long = 0L
    @Volatile private var ready = false
    private var heartbeatJob: Job? = null

    /** Bot display name discovered at READY; null until then. Read by [QqChannel.displayName]. */
    val botDisplayName: String? get() = botName

    /** Close the socket and signal [connect] to return. Safe to call when not connected. */
    fun stop() {
        stopped = true
        heartbeatJob?.cancel()
        runCatching { webSocket?.close(1000, "client stop") }
    }

    /**
     * Run the connect → handshake → receive loop, reconnecting with exponential backoff
     * until [stop] is called or [scope] is cancelled. Suspends for the connection lifetime.
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
        val incoming = Channel<String>(Channel.UNLIMITED)
        val opened = CompletableDeferred<Unit>()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@QqBotWebSocketClient.webSocket = webSocket
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

        // QQ authenticates the WebSocket by appending the access_token as the query string
        // (no `key=` prefix, per the platform spec). We fetch a fresh token per connection
        // attempt so a long-disconnected client does not reconnect with a stale one. The
        // same token is reused for the IDENTIFY payload below.
        val token = try {
            restApi.accessToken()
        } catch (e: Exception) {
            DebugLog.e(TAG, "could not obtain access_token for websocket: ${e.message}", e)
            return false
        }
        val authedUrl = "$gatewayUrl?$token"
        val request = Request.Builder().url(authedUrl).build()
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
                handlePayload(text, token, scope)
            }
        } finally {
            heartbeatJob?.cancel()
            heartbeatJob = null
        }
        return ready
    }

    // ── Payload dispatch ─────────────────────────────────────────────────────

    /** [connectToken] is the access token captured for this connection cycle, used for IDENTIFY/RESUME. */
    private fun handlePayload(text: String, connectToken: String, scope: CoroutineScope) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val op = root["op"]?.jsonPrimitive?.intOrNull ?: return
        val eventName = root["t"]?.jsonPrimitive?.contentOrNull
        val seq = root["s"]?.jsonPrimitive?.longOrNull
        if (seq != null) lastSeq = seq

        when (op) {
            OP_HELLO -> {
                val d = root["d"]?.jsonObject ?: return
                heartbeatIntervalMs = d["heartbeat_interval"]?.jsonPrimitive?.longOrNull ?: 0L
                sendIdentifyOrResume(connectToken)
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
                botOpenid = user?.get("openid")?.jsonPrimitive?.contentOrNull
                botName = user?.get("username")?.jsonPrimitive?.contentOrNull
                DebugLog.w(TAG, "READY: bot openid=$botOpenid name=$botName session=$sessionId")
            }
            "RESUMED" -> {
                ready = true
                DebugLog.w(TAG, "RESUMED: session=$sessionId seq=$lastSeq")
            }
            "C2C_MESSAGE_CREATE" -> handleMessageCreate(data, isGroup = false, event = event!!)
            "GROUP_AT_MESSAGE_CREATE" -> handleMessageCreate(data, isGroup = true, event = event!!)
            else -> Unit
        }
    }

    private fun handleMessageCreate(data: JsonObject?, isGroup: Boolean, event: String) {
        if (data == null) return
        val messageId = data["id"]?.jsonPrimitive?.contentOrNull ?: return
        val content = data["content"]?.jsonPrimitive?.contentOrNull ?: ""
        // Extract image attachment URLs from the event's `attachments` array. QQ delivers
        // image messages as attachments with a `content_type` of `image/*` (and a `filename`
        // ending in a known image extension). Mirrors `dsh-im/qq-bridge.mjs`
        // `qqInboundMessage` / `isQqImageAttachment`. Pure-text messages carry no attachments.
        val images = extractImageUrls(data)
        // Skip only when there is neither text nor any image to act on. Previously any
        // non-text event was dropped here, which silently discarded all image messages.
        if (content.isEmpty() && images.isEmpty()) return
        val author = data["author"]?.jsonObject ?: return
        val authorId = author["member_openid"]?.jsonPrimitive?.contentOrNull
            ?: author["user_openid"]?.jsonPrimitive?.contentOrNull
            ?: return
        // Drop our own outbound messages so the bot never echoes itself into a reply loop.
        if (authorId == botOpenid) return
        val authorName = author["username"]?.jsonPrimitive?.contentOrNull ?: ""

        val replyScope: String
        val replyTargetId: String
        val conversationId: String
        if (isGroup) {
            val groupOpenid = data["group_openid"]?.jsonPrimitive?.contentOrNull ?: return
            replyScope = "group"
            replyTargetId = groupOpenid
            conversationId = "group:$groupOpenid"
        } else {
            replyScope = "c2c"
            replyTargetId = authorId
            conversationId = "c2c:$authorId"
        }
        val timestampMs = data["timestamp"]?.jsonPrimitive?.contentOrNull
            ?.let { parseIsoTimestampToMs(it) } ?: System.currentTimeMillis()
        try {
            onMessage(
                QqMessageEvent(
                    messageId = messageId,
                    conversationId = conversationId,
                    content = content,
                    authorId = authorId,
                    authorName = authorName,
                    timestampMs = timestampMs,
                    isGroup = isGroup,
                    rawEventType = event,
                    replyScope = replyScope,
                    replyTargetId = replyTargetId,
                    images = images,
                ),
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "onMessage callback threw: ${e.message}", e)
        }
    }

    /**
     * Extract image attachment URLs from a `C2C_MESSAGE_CREATE` / `GROUP_AT_MESSAGE_CREATE`
     * event's `attachments` array. An attachment counts as an image when its `content_type`
     * (or `contentType`) starts with `image/`, or its `filename` matches a known image
     * extension. Non-image attachments (files, video, audio) are ignored.
     *
     * Mirrors `dsh-im/src/channels/qq/qq-bridge.mjs` `isQqImageAttachment` +
     * `attachmentMediaType`, including the dual `content_type`/`contentType` key tolerance
     * (QQ's wire format has used both spellings across SDK revisions).
     */
    private fun extractImageUrls(data: JsonObject): List<String> {
        val attachments = data["attachments"] as? JsonArray ?: return emptyList()
        if (attachments.isEmpty()) return emptyList()
        val urls = ArrayList<String>(attachments.size)
        for (element in attachments) {
            val obj = runCatching { element.jsonObject }.getOrNull() ?: continue
            val contentType = (obj["content_type"] ?: obj["contentType"])
                ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            val filename = obj["filename"]
                ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            val url = obj["url"]
                ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                ?: continue  // no download URL — nothing we can surface to the agent
            val isImage = (contentType != null && contentType.startsWith("image/", ignoreCase = true))
                || (filename != null && IMAGE_FILENAME_REGEX.matches(filename))
            if (isImage) urls.add(url)
        }
        return urls
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
            buildJsonObject { put("op", OP_HEARTBEAT); put("d", 0) }.toString()
        }
        runCatching { webSocket?.send(payload) }
    }

    /** [connectToken] is the access token captured for this connection cycle. */
    private fun sendIdentifyOrResume(connectToken: String) {
        val sid = sessionId
        val seq = lastSeq
        val ws = webSocket ?: return
        if (sid != null && seq != null) {
            val payload = buildJsonObject {
                put("op", OP_RESUME)
                putJsonObject("d") {
                    put("token", connectToken)
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
                    put("token", connectToken)
                    put("intents", intents)
                    putJsonArray("shard") { add(JsonPrimitive(0)); add(JsonPrimitive(1)) }
                }
            }.toString()
            ws.send(payload)
            DebugLog.d(TAG, "sent IDENTIFY intents=$intents")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Parse an ISO-8601 timestamp (e.g. `"2024-01-01T00:00:00+08:00"`) to epoch ms. */
    private fun parseIsoTimestampToMs(value: String): Long =
        runCatching {
            java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }.getOrElse {
            runCatching {
                java.time.LocalDateTime.parse(value)
                    .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            }.getOrElse { 0L }
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
        private const val TAG = "QqGateway"
        const val DEFAULT_GATEWAY_URL = "wss://api.sgroup.qq.com/websockets"

        // QQ Bot op codes (https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/interface-framework/api-1/).
        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_RESUME = 4
        private const val OP_HEARTBEAT_ACK = 7
        private const val OP_RECONNECT = 11
        private const val OP_HELLO = 10
        private const val OP_INVALID_SESSION = 12

        // Intents for the QQ group/C2C bot surface.
        // C2C_MESSAGE_CREATE = 1 << 8, GROUP_AT_MESSAGE_CREATE = 1 << 4.
        const val INTENT_C2C_MESSAGE_CREATE = 1 shl 8
        const val INTENT_GROUP_AT_MESSAGE_CREATE = 1 shl 4
        const val DEFAULT_INTENTS = INTENT_C2C_MESSAGE_CREATE or INTENT_GROUP_AT_MESSAGE_CREATE

        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val STEP_MS = 200L

        /**
         * Image filename suffixes QQ delivers as attachments (mirrors `dsh-im/qq-bridge.mjs`
         * `QQ_IMAGE_FILENAME`). Used as a fallback when an attachment carries no `content_type`.
         */
        private val IMAGE_FILENAME_REGEX = Regex("""\.(?:gif|jpe?g|png|webp)$""", RegexOption.IGNORE_CASE)
    }
}
