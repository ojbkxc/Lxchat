package com.lxseek.chat.im.feishu

import com.lxseek.chat.im.ImJson
import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Raised when a Feishu/Lark REST or WebSocket call fails. [code] carries a stable diagnostic
 * token (Feishu's business `code`, HTTP status, or a local reason) so callers can branch
 * without parsing messages.
 */
class FeishuApiException(message: String, val code: String? = null) : Exception(message)

/**
 * Long-Connection endpoint metadata returned by `event/v1/establish`.
 *
 * [endpoint] is the fully-formed `wss://` URL the WebSocket client connects to. [keys] carries
 * the optional encryption keys Feishu may provision; kept for diagnostics and future encrypted
 * payload support (today Feishu pushes plaintext JSON over the WSS channel).
 */
data class FeishuLarkEndpoint(
    val endpoint: String,
    val keys: Map<String, String> = emptyMap(),
)

/**
 * Bot identity read from `bot/v3/info/`. Used to detect @-mentions in group chats and to
 * label the channel. Fields are nullable because Feishu may omit them when the bot is not
 * fully activated.
 */
data class FeishuBotInfo(
    val openId: String?,
    val appName: String?,
    val activateStatus: Int?,
)

/**
 * A normalized inbound message extracted from one `im.message.receive_v1` event pushed over the
 * Feishu long connection.
 *
 * - [messageId] is Feishu's stable message id (used for de-dup and as the ImMessage id).
 * - [chatId] is the chat the message was sent in (p2p or group); surfaced as conversationId.
 * - [chatType] is `"p2p"` for direct messages, `"group"` for group chats.
 * - [senderOpenId] / [senderType] identify the sender; `senderType == "bot"` is used to drop
 *   echo loops.
 * - [messageType] is the raw Feishu message type (`"text"`, `"post"`, `"image"`...). Only
 *   `text` is surfaced today; non-text messages arrive with a blank [text] and are skipped by
 *   the channel.
 * - [text] is the message body for text messages, with @-mention placeholders stripped.
 * - [mentions] is the raw mention list (used by the channel to detect whether the bot was
 *   @-addressed in a group).
 * - [createTimeMs] is the message creation timestamp in milliseconds.
 */
data class FeishuInboundMessage(
    val messageId: String,
    val chatId: String,
    val chatType: String,
    val senderOpenId: String,
    val senderType: String,
    val messageType: String,
    val text: String,
    val mentions: List<JsonObject>,
    val createTimeMs: Long,
)

/**
 * Pure-Kotlin Feishu/Lark client: REST API envelope + WebSocket long connection.
 *
 * No SDK, no extra dependencies — only [HttpClient]'s shared OkHttp instance and
 * kotlinx.serialization. Mirrors the wire behavior of `dsh-im/src/channels/feishu/`:
 *  - `feishu-app.mjs` → [tenantAccessToken] / [getBotInfo] credential verification.
 *  - `feishu-runtime.mjs` → [establishLongConnection] + [openLongConnection] (the
 *    `lark.WSClient` handshake, reimplemented over OkHttp's WebSocket).
 *  - `feishu-channel.mjs` → [sendText] / [replyText] (the `im.v1.message` REST surface).
 *
 * Two API domains are supported via [FeishuDomain]:
 *  - [FeishuDomain.FEISHU] → `https://open.feishu.cn` (China tenant).
 *  - [FeishuDomain.LARK]   → `https://open.larksuite.com` (international tenant).
 *
 * REST surface:
 *  - [tenantAccessToken] — `auth/v3/tenant_access_token/internal`, cached with a 60s safety
 *    margin (mirrors feishu-app.mjs).
 *  - [getBotInfo] — `bot/v3/info/`, used to read the bot open_id for the @-mention gate.
 *  - [sendText] / [sendMessage] — `im/v1/messages` with `receive_id_type=chat_id`.
 *  - [replyText] / [replyMessage] — `im/v1/messages/{message_id}/reply`.
 *
 * WebSocket surface:
 *  - [establishLongConnection] — `event/v1/establish`, returns the WSS endpoint.
 *  - [openLongConnection] — opens the WSS, dispatches `im.message.receive_v1` events to
 *    [FeishuLarkListener.onMessage], handles ping/pong and event ACKs, and reconnects with
 *    exponential backoff until [FeishuLarkConnection.close] is called.
 */
class FeishuLarkApi(
    /** Feishu App ID from the developer console. */
    val appId: String,
    /** Feishu App Secret from the developer console. */
    val appSecret: String,
    /** API domain — Feishu (China) or Lark (international). */
    private val domain: FeishuDomain = FeishuDomain.FEISHU,
    /** Shared OkHttp client used for the WebSocket upgrade. */
    private val wsClient: OkHttpClient = HttpClient.client,
) {
    init {
        require(appId.isNotBlank()) { "appId is required" }
        require(appSecret.isNotBlank()) { "appSecret is required" }
    }

    private val base: String = domain.origin

    // ── Access token cache ─────────────────────────────────────────────────
    // Single-credential client, so a pair of volatiles is enough; refresh is rare and
    // idempotent enough that a racing double refresh is harmless (both return valid tokens).
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAtMs: Long = 0L

    /**
     * Fetch (or return cached) a `tenant_access_token`. Refreshed 60s before expiry to avoid
     * a race where a call uses a token that expires mid-flight. Mirrors feishu-app.mjs.
     */
    suspend fun tenantAccessToken(): String {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < tokenExpiresAtMs) return it }
        return withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("app_id", appId)
                put("app_secret", appSecret)
            }.toString()
            val resp = postJson("$base/open-apis/auth/v3/tenant_access_token/internal", body)
            val root = parseObject(resp, "tenantAccessToken")
            val code = root["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (code != null && code != 0) {
                throw FeishuApiException(
                    root["msg"]?.jsonPrimitive?.contentOrNull ?: "tenantAccessToken failed",
                    code.toString(),
                )
            }
            val token = root["tenant_access_token"]?.jsonPrimitive?.contentOrNull
                ?: throw FeishuApiException("tenantAccessToken: missing token in response")
            val expire = root["expire"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 7_200L
            cachedToken = token
            // Refresh 60s before expiry; floor at 1s so we never store an already-expired token.
            tokenExpiresAtMs = now + maxOf(1_000L, (expire - 60) * 1_000L)
            token
        }
    }

    /** Drop the cached access token so the next call re-fetches. */
    fun invalidateToken() {
        cachedToken = null
        tokenExpiresAtMs = 0L
    }

    /**
     * Read the bot identity (`bot/v3/info/`). Used at connection start to learn the bot's
     * `open_id` so the channel can detect @-mentions in group chats. Mirrors feishu-app.mjs.
     */
    suspend fun getBotInfo(): FeishuBotInfo {
        val token = tenantAccessToken()
        return withContext(Dispatchers.IO) {
            val resp = getText("$base/open-apis/bot/v3/info/", mapOf(HEADER_AUTH to "Bearer $token"))
            val root = parseObject(resp, "getBotInfo")
            val code = root["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (code != null && code != 0) {
                throw FeishuApiException(
                    root["msg"]?.jsonPrimitive?.contentOrNull ?: "getBotInfo failed",
                    code.toString(),
                )
            }
            val bot = root["bot"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
            FeishuBotInfo(
                openId = bot["open_id"]?.jsonPrimitive?.contentOrNull,
                appName = bot["app_name"]?.jsonPrimitive?.contentOrNull
                    ?: bot["bot_name"]?.jsonPrimitive?.contentOrNull,
                activateStatus = bot["activate_status"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            )
        }
    }

    // ── Send / reply ────────────────────────────────────────────────────────

    /**
     * Send a message via `im/v1/messages`. [receiveIdType] is `chat_id` by default (the only
     * receive-id type Lxchat uses today); `open_id` / `user_id` are accepted for future
     * proactive single-user outreach. Returns Feishu's `message_id`.
     */
    suspend fun sendMessage(
        receiveId: String,
        msgType: String,
        content: String,
        receiveIdType: String = "chat_id",
    ): String {
        require(receiveId.isNotBlank()) { "receiveId is required" }
        val token = tenantAccessToken()
        return withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("receive_id", receiveId)
                put("msg_type", msgType)
                put("content", content)
            }.toString()
            val resp = postJson(
                "$base/open-apis/im/v1/messages?receive_id_type=$receiveIdType",
                body,
                mapOf(HEADER_AUTH to "Bearer $token"),
            )
            val root = parseObject(resp, "sendMessage")
            assertOk(root, "sendMessage")
            root["data"]?.jsonObject?.get("message_id")?.jsonPrimitive?.contentOrNull
                ?: throw FeishuApiException("sendMessage: missing message_id in response")
        }
    }

    /** Send a plain-text message to [chatId]. Convenience wrapper around [sendMessage]. */
    suspend fun sendText(chatId: String, text: String): String =
        sendMessage(chatId, "text", buildJsonObject { put("text", text) }.toString())

    /**
     * Reply to [messageId] via `im/v1/messages/{message_id}/reply`. Used when the agent wants
     * the reply threaded under the user's message. Returns Feishu's new `message_id`.
     */
    suspend fun replyMessage(messageId: String, msgType: String, content: String): String {
        require(messageId.isNotBlank()) { "messageId is required" }
        val token = tenantAccessToken()
        return withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("msg_type", msgType)
                put("content", content)
            }.toString()
            val resp = postJson(
                "$base/open-apis/im/v1/messages/$messageId/reply",
                body,
                mapOf(HEADER_AUTH to "Bearer $token"),
            )
            val root = parseObject(resp, "replyMessage")
            assertOk(root, "replyMessage")
            root["data"]?.jsonObject?.get("message_id")?.jsonPrimitive?.contentOrNull
                ?: throw FeishuApiException("replyMessage: missing message_id in response")
        }
    }

    /** Reply with plain text to [messageId]. Convenience wrapper around [replyMessage]. */
    suspend fun replyText(messageId: String, text: String): String =
        replyMessage(messageId, "text", buildJsonObject { put("text", text) }.toString())

    // ── Long connection (WebSocket) ─────────────────────────────────────────

    /**
     * Open a Feishu long connection and return the WSS endpoint to connect to.
     *
     * Mirrors the `lark.WSClient` handshake: POST `event/v1/establish` with the app id and the
     * event types we want delivered. Feishu responds with a `wss://` URL valid for the lifetime
     * of the connection; reconnects must re-establish.
     *
     * [eventTypes] defaults to the message-receive event (`im.message.receive_v1`), which is the
     * only event Lxchat subscribes to today. The dsh-im runtime additionally subscribes to
     * `im.message.reaction.created_v1` / `deleted_v1` and `card.action.trigger`; those are
     * omitted here because Lxchat does not implement reactions or card callbacks.
     */
    suspend fun establishLongConnection(
        eventTypes: List<String> = listOf(EVENT_MESSAGE_RECEIVE),
    ): FeishuLarkEndpoint {
        val token = tenantAccessToken()
        return withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("app_id", appId)
                put("schema", "2.0")
                putJsonArray("event_types") { eventTypes.forEach { add(JsonPrimitive(it)) } }
            }.toString()
            val resp = postJson(
                "$base/open-apis/event/v1/establish",
                body,
                mapOf(HEADER_AUTH to "Bearer $token"),
            )
            val root = parseObject(resp, "establish")
            assertOk(root, "establish")
            val data = root["data"]?.let { runCatching { it.jsonObject }.getOrNull() }
                ?: throw FeishuApiException("establish: missing data in response")
            val endpoint = data["endpoint"]?.jsonPrimitive?.contentOrNull
                ?: throw FeishuApiException("establish: missing endpoint in response")
            val keys = data["keys"]?.let { runCatching { it.jsonObject }.getOrNull() }
                ?.let { obj ->
                    obj.entries.mapNotNull { (k, v) ->
                        runCatching { v.jsonPrimitive.contentOrNull }.getOrNull()?.let { k to it }
                    }.toMap()
                } ?: emptyMap()
            FeishuLarkEndpoint(endpoint = endpoint, keys = keys)
        }
    }

    /**
     * Open the long-connection WebSocket and start delivering inbound events to [listener].
     *
     * The returned [FeishuLarkConnection] owns one WebSocket plus a reconnect loop with
     * exponential backoff (capped at [FeishuLarkConnection.MAX_BACKOFF_MS]). Reconnect is
     * triggered by `onClosed`/`onFailure` and by the initial `establish` failing; the loop
     * runs on [scope] and stops when [FeishuLarkConnection.close] is called or [scope] is
     * cancelled. Each inbound event is ACKed on the socket before [onMessage] is invoked, so
     * Feishu does not redeliver while we are still processing.
     */
    fun openLongConnection(
        scope: CoroutineScope,
        listener: FeishuLarkListener,
    ): FeishuLarkConnection = FeishuLarkConnection(this, wsClient, scope, listener)

    /**
     * Parse one WebSocket text frame into a [FeishuInboundMessage], or null when the frame is
     * not a `im.message.receive_v1` event (e.g. a pong, a different event type, or malformed
     * JSON). Public for tests.
     *
     * Feishu pushes events as `{"type":"event","schema":"2.0","header":{...},"event":{...}}`.
     * We extract the message body from `event.message`, strip @-mention placeholders from the
     * text, and surface the fields Lxchat needs.
     */
    internal fun parseInbound(frame: String): FeishuInboundMessage? {
        val envelope = runCatching { ImJson.parseToJsonElement(frame).jsonObject }.onFailure { e ->
            DebugLog.w(TAG, "入站事件帧解析失败，已丢弃: ${frame.take(200)}", e)
        }.getOrNull()
            ?: return null
        // Feishu long-connection frames carry type="event" for deliveries; ping/pong and
        // system frames have other types and are handled by the WebSocket listener directly.
        val type = envelope["type"]?.jsonPrimitive?.contentOrNull
        if (type != "event") return null
        val header = envelope["header"]?.let { raw ->
            runCatching { raw.jsonObject }.onFailure { e ->
                DebugLog.w(TAG, "入站事件 header 字段解析失败，已丢弃: ${raw.toString().take(200)}", e)
            }.getOrNull()
        } ?: return null
        if (header["event_type"]?.jsonPrimitive?.contentOrNull != EVENT_MESSAGE_RECEIVE) return null
        val event = envelope["event"]?.let { raw ->
            runCatching { raw.jsonObject }.onFailure { e ->
                DebugLog.w(TAG, "入站事件 event 字段解析失败，已丢弃: ${raw.toString().take(200)}", e)
            }.getOrNull()
        } ?: return null

        val message = event["message"]?.let { raw ->
            runCatching { raw.jsonObject }.onFailure { e ->
                DebugLog.w(TAG, "入站事件 message 字段解析失败，已丢弃: ${raw.toString().take(200)}", e)
            }.getOrNull()
        } ?: return null
        val messageId = message["message_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val chatId = message["chat_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val chatType = message["chat_type"]?.jsonPrimitive?.contentOrNull ?: "p2p"
        val messageType = message["message_type"]?.jsonPrimitive?.contentOrNull ?: "text"
        val createTimeMs = message["create_time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: System.currentTimeMillis()

        val sender = event["sender"]?.let { raw ->
            runCatching { raw.jsonObject }.onFailure { e ->
                DebugLog.w(TAG, "入站事件 sender 字段解析失败，按空发送者处理: ${raw.toString().take(200)}", e)
            }.getOrNull()
        } ?: JsonObject(emptyMap())
        val senderId = sender["sender_id"]?.let { raw ->
            runCatching { raw.jsonObject }.onFailure { e ->
                DebugLog.w(TAG, "入站事件 sender_id 字段解析失败，按空发送者处理: ${raw.toString().take(200)}", e)
            }.getOrNull()
        } ?: JsonObject(emptyMap())
        val senderOpenId = senderId["open_id"]?.jsonPrimitive?.contentOrNull
            ?: senderId["user_id"]?.jsonPrimitive?.contentOrNull ?: ""
        val senderType = sender["sender_type"]?.jsonPrimitive?.contentOrNull ?: "user"

        val mentions = message["mentions"]?.let { raw ->
            runCatching { raw.jsonArray }.onFailure { e ->
                DebugLog.w(TAG, "入站消息 mentions 字段解析失败，按无提及处理: ${raw.toString().take(200)}", e)
            }.getOrNull()
        }?.mapNotNull { element ->
            runCatching { element.jsonObject }.onFailure { e ->
                DebugLog.w(TAG, "入站消息 mentions 元素解析失败，已跳过: ${element.toString().take(200)}", e)
            }.getOrNull()
        } ?: emptyList()

        // Text-only for now; post/image/voice arrive with a blank text and are skipped by the
        // channel. The dsh-im bridge supports post (rich text) and image, but those need the
        // message-resource download API and are out of scope for this first cut.
        val text = if (messageType == "text") {
            extractText(message, mentions)
        } else {
            ""
        }

        return FeishuInboundMessage(
            messageId = messageId,
            chatId = chatId,
            chatType = chatType,
            senderOpenId = senderOpenId,
            senderType = senderType,
            messageType = messageType,
            text = text,
            mentions = mentions,
            createTimeMs = createTimeMs,
        )
    }

    /**
     * Extract the text body from a text message, stripping @-mention placeholders.
     * Mirrors `extractText` + `withoutMentions` in dsh-im message-utils.mjs.
     */
    private fun extractText(message: JsonObject, mentions: List<JsonObject>): String {
        val raw = message["content"]?.jsonPrimitive?.contentOrNull ?: return ""
        val parsed = runCatching { ImJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return ""
        var text = parsed["text"]?.jsonPrimitive?.contentOrNull ?: return ""
        for (mention in mentions) {
            val key = mention["key"]?.jsonPrimitive?.contentOrNull
            if (!key.isNullOrEmpty()) text = text.replace(key, "")
        }
        return text.trim()
    }

    /**
     * Build the ACK frame for one inbound event (echoes the header back to Feishu).
     *
     * Feishu's long-connection protocol requires the client to ACK each event by echoing the
     * event header with an empty `event` body. Constructed via [JsonObject] directly so the
     * header's original value types (strings, numbers) are preserved without relying on a
     * `put(key, JsonElement)` overload.
     */
    internal fun ackFrame(header: JsonObject): String = JsonObject(
        mapOf(
            "type" to JsonPrimitive("event"),
            "schema" to JsonPrimitive("2.0"),
            "header" to header,
            "event" to JsonObject(emptyMap()),
        )
    ).toString()

    /** Build a pong frame in response to a server `{"type":"ping"}`. */
    internal fun pongFrame(): String = buildJsonObject { put("type", "pong") }.toString()

    // ── HTTP helpers ────────────────────────────────────────────────────────

    private suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String {
        val resp = HttpClient.postTextResponse(url, body, headers)
        if (!resp.isSuccessful) {
            throw FeishuApiException(
                "Feishu request failed (HTTP ${resp.code}) at $url",
                resp.code.toString(),
            )
        }
        return resp.body
    }

    private suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val resp = HttpClient.getTextResponse(url, headers)
        if (!resp.isSuccessful) {
            throw FeishuApiException(
                "Feishu request failed (HTTP ${resp.code}) at $url",
                resp.code.toString(),
            )
        }
        return resp.body
    }

    private fun parseObject(body: String, action: String): JsonObject =
        runCatching { ImJson.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw FeishuApiException("$action: response is not a JSON object")

    /** Assert Feishu's business `code` is 0; throw with the `msg` otherwise. */
    private fun assertOk(root: JsonObject, action: String) {
        val code = root["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (code != null && code != 0) {
            throw FeishuApiException(
                root["msg"]?.jsonPrimitive?.contentOrNull ?: "$action failed",
                code.toString(),
            )
        }
    }

    companion object {
        /** Feishu event type for inbound robot messages. */
        const val EVENT_MESSAGE_RECEIVE = "im.message.receive_v1"
        private const val HEADER_AUTH = "Authorization"
        private const val TAG = "FeishuLarkApi"
    }
}

/**
 * API domain selector. Feishu (China) and Lark (international) share the same wire protocol
 * but differ in host. Mirrors `endpointFor(domain, path)` in dsh-im feishu-app.mjs.
 */
enum class FeishuDomain(val origin: String) {
    FEISHU("https://open.feishu.cn"),
    LARK("https://open.larksuite.com"),
    ;

    companion object {
        /** Resolve a domain by name; defaults to [FEISHU] for unknown values. */
        fun of(name: String?): FeishuDomain = when (name?.lowercase()?.trim()) {
            "lark", "larksuite", "larksuite.com" -> LARK
            else -> FEISHU
        }
    }
}

/**
 * Callbacks delivered by [FeishuLarkApi.openLongConnection]. All methods are invoked on the
 * connection's internal dispatcher and must be cheap to return; offload heavy work onto a
 * wider scope (the [FeishuLarkConnection] itself runs on the scope passed to openLongConnection).
 */
interface FeishuLarkListener {
    /** The WebSocket handshake completed; messages may now arrive. */
    fun onOpen() {}

    /** One inbound `im.message.receive_v1` event was received and ACKed. */
    fun onMessage(message: FeishuInboundMessage)

    /** The connection closed cleanly (server-initiated or local [FeishuLarkConnection.close]). */
    fun onClosed(code: Int, reason: String) {}

    /** A transport-level error occurred; reconnect is already scheduled unless [fatal] is true. */
    fun onError(error: Throwable, fatal: Boolean) {}
}

/**
 * Live handle to one Feishu long-connection WebSocket with automatic reconnect.
 *
 * Close with [close]; the reconnect loop stops and the current socket (if any) is torn down.
 * [isConnected] reflects the live socket state (best-effort, volatile).
 *
 * The reconnect loop mirrors `lark.WSClient`'s `onReconnecting`/`onReconnected` behavior: on a
 * clean close or transport failure we back off exponentially and re-run `establish` + connect,
 * so a transient Feishu outage does not require an app restart.
 */
class FeishuLarkConnection(
    private val api: FeishuLarkApi,
    private val wsClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val listener: FeishuLarkListener,
) {
    @Volatile private var webSocket: WebSocket? = null
    private val running = AtomicBoolean(true)
    private val connected = AtomicBoolean(false)
    private val attempt = AtomicInteger(0)
    private val loopJob = AtomicReference<Job?>(null)
    @Volatile private var fatal = false

    /** True when the WebSocket is currently open (best-effort, no lock). */
    val isConnected: Boolean get() = connected.get()

    init {
        // Launch the reconnect loop on the supplied scope; cancelled on close or scope death.
        loopJob.set(scope.launch(Dispatchers.IO) {
            while (isActive && running.get() && !fatal) {
                if (!connectOnce()) break
            }
        })
    }

    /**
     * Run one establish → connect → listen → disconnect cycle. Returns false when the loop
     * should stop (close called or scope cancelled); true when a reconnect should be attempted.
     */
    private suspend fun connectOnce(): Boolean {
        return try {
            val endpoint = api.establishLongConnection()
            if (!running.get()) return false
            val request = Request.Builder().url(endpoint.endpoint).build()
            // Bridge OkHttp's callback-based WebSocket into a suspending wait via a deferred
            // that completes on open, close, or failure. The listener below drives it.
            val openDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()
            val closeDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()
            val ws = wsClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connected.set(true)
                    attempt.set(0)
                    openDeferred.complete(Unit)
                    runCatching { listener.onOpen() }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // Server ping → reply pong immediately (keepalive).
                    if (text.contains("\"type\":\"ping\"")) {
                        runCatching { webSocket.send(api.pongFrame()) }
                        return
                    }
                    // Parse the envelope once; ACK the event before dispatching so Feishu does
                    // not redeliver while we process. The ACK echoes the event header back.
                    val envelope = runCatching {
                        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
                    }.getOrNull()
                    val header = envelope?.get("header")
                        ?.let { raw ->
                            runCatching { raw.jsonObject }.onFailure { e ->
                                DebugLog.w(TAG, "入站事件 header 字段解析失败，跳过 ACK: ${raw.toString().take(200)}", e)
                            }.getOrNull()
                        }
                    if (header != null) {
                        runCatching { webSocket.send(api.ackFrame(header)) }
                    }
                    val inbound = api.parseInbound(text) ?: return
                    runCatching { listener.onMessage(inbound) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Echo the close so the server sees a clean handshake.
                    runCatching { webSocket.close(code, reason) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connected.set(false)
                    openDeferred.complete(Unit) // unblock if closed before open (rare)
                    closeDeferred.complete(Unit)
                    runCatching { listener.onClosed(code, reason) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connected.set(false)
                    openDeferred.completeExceptionally(t)
                    closeDeferred.complete(Unit)
                    val isFatal = t is IllegalArgumentException || t is java.net.URISyntaxException
                    if (isFatal) fatal = true
                    runCatching { listener.onError(t, isFatal) }
                }
            })
            webSocket = ws
            // Wait for open (throws on failure) so we surface handshake errors to the retry path.
            try {
                openDeferred.await()
            } catch (e: Throwable) {
                // Handshake failed; fall through to backoff below.
                throw e
            }
            // Block until the socket closes; then loop and reconnect.
            closeDeferred.await()
            if (!running.get() || fatal) return false
            delay(backoffMs())
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            running.set(false)
            false
        } catch (e: Throwable) {
            DebugLog.e(TAG, "long-connection cycle failed", e)
            runCatching { listener.onError(e, false) }
            if (!running.get() || fatal) return false
            delay(backoffMs())
            true
        }
    }

    /** Exponential backoff with jitter, capped at [MAX_BACKOFF_MS]. */
    private fun backoffMs(): Long {
        val n = attempt.incrementAndGet().coerceAtMost(10)
        val base = (INITIAL_BACKOFF_MS shl (n - 1)).coerceAtMost(MAX_BACKOFF_MS)
        return base + (Math.random() * base * 0.1).toLong()
    }

    /** Stop the reconnect loop and tear down the current socket. Safe to call repeatedly. */
    fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { webSocket?.close(1000, "shutdown") }
        loopJob.get()?.cancel()
    }

    companion object {
        private const val TAG = "FeishuLarkStream"
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}