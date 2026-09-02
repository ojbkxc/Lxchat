package com.lxseek.chat.im.dingtalk

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
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

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Raised when a DingTalk REST/WebSocket call fails. [code] carries a stable diagnostic
 * token (HTTP status or local reason) so callers can branch without parsing messages.
 */
class DingtalkApiException(message: String, val code: String? = null) : Exception(message)

/**
 * DingTalk Stream Mode endpoint metadata returned by `gateway/connections/open`.
 *
 * [endpoint] is the fully-formed `wss://` URL (connectionId + ticket already embedded) that
 * the WebSocket client connects to; [connectionId] and [ticket] are kept for diagnostics.
 */
data class DingtalkStreamEndpoint(
    val endpoint: String,
    val connectionId: String,
    val ticket: String,
)

/**
 * A normalized inbound robot message extracted from one DingTalk Stream WebSocket frame.
 *
 * DingTalk pushes robot messages as `{ headers: { messageId, topic }, data: "<json string>" }`.
 * The `data` payload is the robot message body; we surface the fields Lxchat needs and keep
 * [rawData] for future extensions (images, rich text) without re-parsing.
 *
 * - [conversationId] is DingTalk's conversation id (single-chat or group).
 * - [conversationType] is "1" for single-chat, "2" for group.
 * - [sessionWebhook] is a short-lived (≈1h) reply URL the bot can POST to without an access
 *   token — the cheapest reply path while it lasts.
 * - [senderStaffId] / [senderNick] identify the human who sent the message.
 * - [text] is the message body; only text content is supported today.
 */
data class DingtalkInbound(
    val messageId: String,
    val topic: String,
    val conversationId: String,
    val conversationType: String,
    val senderStaffId: String,
    val senderNick: String,
    val text: String,
    val sessionWebhook: String,
    val msgId: String,
    val createAt: Long,
    val robotCode: String,
)

/**
 * Pure-Kotlin DingTalk Stream Mode client: REST API envelope + WebSocket long connection.
 *
 * No SDK, no extra dependencies — only [HttpClient]'s shared OkHttp instance and
 * kotlinx.serialization. Mirrors `dsh-im/src/channels/dingtalk/dingtalk-api.mjs` and the
 * `dingtalk-stream` npm package's connection handshake, but reimplemented for Android.
 *
 * REST surface:
 *  - [accessToken] — `v1.0/oauth2/accessToken`, cached with a 60s safety margin.
 *  - [openConnection] — `v1.0/gateway/connections/open`, returns the WSS endpoint.
 *  - [sendOtoMessage] — `v1.0/robot/oToMessages/batchSend` (proactive single-chat).
 *  - [sendGroupMessage] — `v1.0/robot/groupMessages/send` (proactive group).
 *  - [replyBySessionWebhook] — POST to the per-message reply URL (no token needed).
 *
 * WebSocket surface:
 *  - [openStream] — opens the WSS connection, invokes [DingtalkStreamListener.onMessage] for
 *    each inbound frame (ACKed automatically), and drives [onOpen]/[onClosed]/[onError] with
 *    automatic reconnect-with-backoff until [DingtalkStreamConnection.close] is called.
 *
 * Configuration is intentionally minimal: [clientId] + [clientSecret] from the DingTalk
 * developer console. [apiBase] is overridable for tests or a private deployment.
 */
class DingtalkStreamApi(
    /** DingTalk application Client ID (appKey) from the developer console. */
    val clientId: String,
    /** DingTalk application Client Secret (appSecret). */
    val clientSecret: String,
    /** REST API base, overrideable for tests or a self-hosted gateway. */
    private val apiBase: String = DEFAULT_API_BASE,
    /** Shared OkHttp client used for the WebSocket upgrade. */
    private val wsClient: OkHttpClient = HttpClient.client,
) {
    init {
        require(clientId.isNotBlank()) { "clientId is required" }
        require(clientSecret.isNotBlank()) { "clientSecret is required" }
    }


    // ── Access token cache ─────────────────────────────────────────────────
    // Single-credential client, so a pair of volatiles is enough; refresh is rare and
    // idempotent enough that a racing double refresh is harmless (both return valid tokens).
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAtMs: Long = 0L

    /**
     * Fetch (or return cached) a DingTalk access token. Refreshed 60s before expiry to avoid
     * a race where a call uses a token that expires mid-flight.
     */
    suspend fun accessToken(): String {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < tokenExpiresAtMs) return it }
        return withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("appKey", clientId)
                put("appSecret", clientSecret)
            }.toString()
            val resp = postJson("$apiBase/v1.0/oauth2/accessToken", body)
            val root = parseObject(resp, "accessToken")
            val token = root["accessToken"]?.jsonPrimitive?.contentOrNull
                ?: throw DingtalkApiException("accessToken: missing accessToken in response")
            val expireIn = root["expireIn"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: root["expiresIn"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: 7_200L
            cachedToken = token
            // Refresh 60s before expiry; floor at 1s so we never store an already-expired token.
            tokenExpiresAtMs = now + maxOf(1_000L, (expireIn - 60) * 1_000L)
            token
        }
    }

    /** Drop the cached access token so the next call re-fetches. */
    fun invalidateToken() {
        cachedToken = null
        tokenExpiresAtMs = 0L
    }

    /**
     * Open a Stream Mode connection and return the WSS endpoint to connect to.
     *
     * [subscriptionTopic] defaults to the robot-message topic; DingTalk delivers every bot
     * message addressed to this clientId on that topic over the resulting WebSocket.
     */
    suspend fun openConnection(
        subscriptionTopic: String = TOPIC_ROBOT,
    ): DingtalkStreamEndpoint {
        val token = accessToken()
        return withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("clientId", clientId)
                putJsonArray("subscriptions") {
                    add(buildJsonObject {
                        put("type", "EVENT")
                        put("topic", subscriptionTopic)
                    })
                }
            }.toString()
            val resp = postJson(
                "$apiBase/v1.0/gateway/connections/open",
                body,
                mapOf(HEADER_ACCESS_TOKEN to token),
            )
            val root = parseObject(resp, "openConnection")
            val endpoint = root["endpoint"]?.jsonPrimitive?.contentOrNull
                ?: throw DingtalkApiException("openConnection: missing endpoint in response")
            DingtalkStreamEndpoint(
                endpoint = endpoint,
                connectionId = root["connectionId"]?.jsonPrimitive?.contentOrNull ?: "",
                ticket = root["ticket"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }

    /**
     * Proactively send a text message to a single user via `robot/oToMessages/batchSend`.
     * Requires the bot to be added to the user's chat list. Returns DingTalk's `processQueryKey`.
     */
    suspend fun sendOtoMessage(userId: String, text: String): String {
        require(userId.isNotBlank()) { "userId is required" }
        require(text.isNotBlank()) { "text is required" }
        val token = accessToken()
        return withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("robotCode", clientId)
                putJsonArray("userIds") { add(JsonPrimitive(userId)) }
                put("msgKey", "sampleText")
                put("msgParam", buildJsonObject { put("content", text) }.toString())
            }.toString()
            val resp = postJson(
                "$apiBase/v1.0/robot/oToMessages/batchSend",
                body,
                mapOf(HEADER_ACCESS_TOKEN to token),
            )
            val root = parseObject(resp, "sendOtoMessage")
            // DingTalk returns either a processQueryKey (success) or an errcode/msg on failure.
            val errcode = root["errcode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (errcode != null && errcode != 0) {
                throw DingtalkApiException(
                    root["errmsg"]?.jsonPrimitive?.contentOrNull ?: "sendOtoMessage rejected",
                    errcode.toString(),
                )
            }
            root["processQueryKey"]?.jsonPrimitive?.contentOrNull ?: ""
        }
    }

    /**
     * Proactively send a text message to a group chat via `robot/groupMessages/send`.
     * [openConversationId] is DingTalk's group conversation id ( surfaced in inbound messages
     * as `conversationId` when `conversationType == "2"`). Returns `processQueryKey`.
     */
    suspend fun sendGroupMessage(openConversationId: String, text: String): String {
        require(openConversationId.isNotBlank()) { "openConversationId is required" }
        require(text.isNotBlank()) { "text is required" }
        val token = accessToken()
        return withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("robotCode", clientId)
                put("openConversationId", openConversationId)
                put("msgKey", "sampleText")
                put("msgParam", buildJsonObject { put("content", text) }.toString())
            }.toString()
            val resp = postJson(
                "$apiBase/v1.0/robot/groupMessages/send",
                body,
                mapOf(HEADER_ACCESS_TOKEN to token),
            )
            val root = parseObject(resp, "sendGroupMessage")
            val errcode = root["errcode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (errcode != null && errcode != 0) {
                throw DingtalkApiException(
                    root["errmsg"]?.jsonPrimitive?.contentOrNull ?: "sendGroupMessage rejected",
                    errcode.toString(),
                )
            }
            root["processQueryKey"]?.jsonPrimitive?.contentOrNull ?: ""
        }
    }

    /**
     * Reply via the per-message [sessionWebhook] embedded in each inbound push.
     *
     * This is the cheapest reply path: no robotCode lookup — just POST
     * `{ msgtype: "text", text: { content } }` to the URL DingTalk handed us, with the
     * `x-acs-dingtalk-access-token` header attached (some tenants reject headerless calls).
     * The webhook URL is validated to be https + `dingtalk.com` before posting (SSRF guard).
     * The webhook is short-lived (~1h), so callers should fall back to
     * [sendOtoMessage]/[sendGroupMessage] when this throws. Returns true on a 2xx + `errcode==0`
     * response.
     */
    suspend fun replyBySessionWebhook(sessionWebhook: String, text: String): Boolean {
        require(sessionWebhook.isNotBlank()) { "sessionWebhook is required" }
        require(text.isNotBlank()) { "text is required" }
        // Security: constrain the reply target to DingTalk's own https domain to prevent SSRF.
        // Mirrors `normalizeDingtalkSessionWebhook` in dsh-im.
        val safeWebhook = validateSessionWebhook(sessionWebhook)
        // dsh-im sends x-acs-dingtalk-access-token on the webhook reply too; some tenants
        // reject the call with errcode 40001 when the header is absent.
        val token = accessToken()
        return withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("msgtype", "text")
                putJsonObject("text") { put("content", text) }
            }.toString()
            val resp = HttpClient.postTextResponse(
                safeWebhook, body,
                mapOf(HEADER_ACCESS_TOKEN to token),
            )
            if (!resp.isSuccessful) return@withContext false
            val root = runCatching { ImJson.parseToJsonElement(resp.body).jsonObject }.getOrNull()
                ?: return@withContext true // Empty 2xx body — treat as success.
            val errcode = root["errcode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val code = root["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            (errcode == null || errcode == 0) && (code == null || code == 0)
        }
    }

    /**
     * Validate a DingTalk sessionWebhook URL: must be https and on a `dingtalk.com` host
     * (either the apex or any subdomain). Throws [IllegalArgumentException] otherwise.
     *
     * Mirrors `normalizeDingtalkSessionWebhook` in dsh-im — prevents SSRF by constraining the
     * reply target to DingTalk's own domain before we POST to it.
     */
    private fun validateSessionWebhook(url: String): String {
        val parsed = java.net.URL(url)
        require(parsed.protocol == "https") { "sessionWebhook must be https" }
        require(parsed.host.endsWith("dingtalk.com")) { "sessionWebhook must be dingtalk.com domain" }
        return url
    }

    // ── WebSocket stream ────────────────────────────────────────────────────

    /**
     * Open the Stream Mode WebSocket and start delivering inbound messages to [listener].
     *
     * The returned [DingtalkStreamConnection] owns one WebSocket plus a reconnect loop with
     * exponential backoff (capped at [DingtalkStreamConnection.MAX_BACKOFF_MS]). Reconnect is
     * triggered by `onClosed`/`onFailure` and by the initial `openConnection` failing; the loop
     * runs on [scope] and stops when [DingtalkStreamConnection.close] is called or [scope] is
     * cancelled. Each inbound frame is ACKed on the socket before [onMessage] is invoked, so
     * DingTalk does not redeliver while we are still processing.
     */
    fun openStream(
        scope: CoroutineScope,
        listener: DingtalkStreamListener,
    ): DingtalkStreamConnection = DingtalkStreamConnection(this, wsClient, scope, listener)

    /**
     * Parse one WebSocket text frame into a [DingtalkInbound], or null when the frame is not a
     * robot-message push (e.g. a system event, a ping, or malformed JSON). Public for tests.
     */
    internal fun parseInbound(frame: String): DingtalkInbound? {
        val envelope = runCatching { ImJson.parseToJsonElement(frame).jsonObject }.onFailure { e ->
            DebugLog.w(TAG, "入站帧解析失败，已丢弃: ${frame.take(200)}", e)
        }.getOrNull()
            ?: return null
        val headers = envelope["headers"]?.let { raw ->
            runCatching { raw.jsonObject }.onFailure { e ->
                DebugLog.w(TAG, "入站帧 headers 解析失败，已丢弃: ${raw.toString().take(200)}", e)
            }.getOrNull()
        } ?: return null
        val messageId = headers["messageId"]?.jsonPrimitive?.contentOrNull ?: return null
        val topic = headers["topic"]?.jsonPrimitive?.contentOrNull ?: ""
        val dataRaw = envelope["data"]?.let { raw ->
            runCatching { raw.jsonPrimitive.contentOrNull }.onFailure { e ->
                DebugLog.w(TAG, "入站帧 data 为非字符串类型，改用序列化兜底解析: ${raw.toString().take(200)}", e)
            }.getOrNull()
                ?: runCatching { raw.toString() }.onFailure { e ->
                    DebugLog.w(TAG, "入站帧 data 序列化兜底失败，已丢弃: ${raw.toString().take(200)}", e)
                }.getOrNull()
        } ?: return null
        val data = runCatching { ImJson.parseToJsonElement(dataRaw).jsonObject }.onFailure { e ->
            DebugLog.w(TAG, "入站帧 data 载荷解析失败，已丢弃: ${dataRaw.take(200)}", e)
        }.getOrNull()
            ?: return null

        // Robot messages carry text.content; everything else (card events, lifecycle) is ignored.
        val text = data["text"]?.let { raw ->
            runCatching { raw.jsonObject }.onFailure { e ->
                DebugLog.w(TAG, "入站消息 text 字段解析失败: ${raw.toString().take(200)}", e)
            }.getOrNull()
        }?.get("content")?.jsonPrimitive?.contentOrNull
            ?: return null
        val conversationId = data["conversationId"]?.jsonPrimitive?.contentOrNull ?: return null
        return DingtalkInbound(
            messageId = messageId,
            topic = topic,
            conversationId = conversationId,
            conversationType = data["conversationType"]?.jsonPrimitive?.contentOrNull ?: "1",
            senderStaffId = data["senderStaffId"]?.jsonPrimitive?.contentOrNull
                ?: data["senderId"]?.jsonPrimitive?.contentOrNull ?: "",
            senderNick = data["senderNick"]?.jsonPrimitive?.contentOrNull ?: "",
            text = text,
            sessionWebhook = data["sessionWebhook"]?.jsonPrimitive?.contentOrNull ?: "",
            msgId = data["msgId"]?.jsonPrimitive?.contentOrNull ?: messageId,
            createAt = data["createAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            robotCode = data["robotCode"]?.jsonPrimitive?.contentOrNull ?: clientId,
        )
    }

    /** Build the ACK frame for one inbound message id. */
    internal fun ackFrame(messageId: String): String = buildJsonObject {
        put("code", 200)
        putJsonObject("headers") { put("messageId", messageId) }
        put("data", """{"success":true}""")
    }.toString()

    // ── HTTP helpers ────────────────────────────────────────────────────────

    private suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String {
        val resp = HttpClient.postTextResponse(url, body, headers)
        if (!resp.isSuccessful) {
            throw DingtalkApiException(
                "DingTalk request failed (HTTP ${resp.code}) at $url",
                resp.code.toString(),
            )
        }
        return resp.body
    }

    private fun parseObject(body: String, action: String): JsonObject =
        runCatching { ImJson.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw DingtalkApiException("$action: response is not a JSON object")

    companion object {
        const val DEFAULT_API_BASE = "https://api.dingtalk.com"
        const val TOPIC_ROBOT = "/v1.0/im/bot/messages/get"
        const val HEADER_ACCESS_TOKEN = "x-acs-dingtalk-access-token"
        private const val TAG = "DingtalkStreamApi"
    }
}

/**
 * Callbacks delivered by [DingtalkStreamApi.openStream]. All methods are invoked on the
 * connection's internal dispatcher and must be cheap to return; offload heavy work onto a
 * wider scope (the [DingtalkStreamConnection] itself runs on the scope passed to openStream).
 */
interface DingtalkStreamListener {
    /** The WebSocket handshake completed; messages may now arrive. */
    fun onOpen() {}

    /** One inbound robot message was received and ACKed. */
    fun onMessage(message: DingtalkInbound)

    /** The connection closed cleanly (server-initiated or local [DingtalkStreamConnection.close]). */
    fun onClosed(code: Int, reason: String) {}

    /** A transport-level error occurred; reconnect is already scheduled unless [fatal] is true. */
    fun onError(error: Throwable, fatal: Boolean) {}
}

/**
 * Live handle to one DingTalk Stream WebSocket connection with automatic reconnect.
 *
 * Close with [close]; the reconnect loop stops and the current socket (if any) is torn down.
 * [isConnected] reflects the live socket state (best-effort, volatile).
 */
class DingtalkStreamConnection(
    private val api: DingtalkStreamApi,
    private val wsClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val listener: DingtalkStreamListener,
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
     * Run one connect → listen → disconnect cycle. Returns false when the loop should stop
     * (close called or scope cancelled); true when a reconnect should be attempted.
     */
    private suspend fun connectOnce(): Boolean {
        return try {
            val endpoint = api.openConnection()
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
                    val inbound = api.parseInbound(text) ?: return
                    // ACK first so DingTalk does not redeliver while we process.
                    runCatching { webSocket.send(api.ackFrame(inbound.messageId)) }
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
            DebugLog.e(TAG, "stream connect cycle failed", e)
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
        private const val TAG = "DingtalkStream"
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}