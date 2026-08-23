package com.lxseek.chat.im.slack

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume

/**
 * Pure-Kotlin Slack client covering the two surfaces Lxchat needs:
 *
 *  1. **Web API REST** ([authTest], [openConnection], [postMessage]) — POST JSON to
 *     `https://slack.com/api/<method>` with a Bearer token. The Bot token (`xoxb-`)
 *     authorizes bot actions; the App-Level token (`xapp-`) authorizes Socket Mode
 *     connection opening.
 *  2. **Socket Mode WebSocket** ([connectSocketMode]) — once [openConnection] returns a
 *     WSS URL, open a WebSocket and pump every inbound envelope to a callback until the
 *     socket closes. ACKs (`{"envelope_id":"..."}`) are sent automatically; `disconnect`
 *     envelopes close the socket so the caller can reconnect with a fresh URL.
 *
 * No Slack SDK, no extra dependencies — only [HttpClient]'s shared OkHttp instance for
 * both REST and WebSocket. Mirrors `dsh-im/src/channels/slack/slack-api.mjs` (`SlackApi`).
 *
 * Token storage in [com.lxseek.chat.im.ImGatewayConfig]:
 *  - `token`   → Bot Token (`xoxb-...`)
 *  - `baseUrl` → App-Level Token (`xapp-...`)   (REST base is fixed to [DEFAULT_BASE_URL])
 */
class SlackSocketModeApi(
    /** Bot token, format `xoxb-<16+ alnum/dash>`. Authorizes chat.postMessage, auth.test. */
    val botToken: String,
    /** App-Level token, format `xapp-<16+ alnum/dash>`. Authorizes apps.connections.open. */
    val appToken: String,
    /** REST API base, overrideable for tests. Default is the official Slack host. */
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    init {
        require(isValidBotToken(botToken)) { "Invalid Slack Bot Token (must start with xoxb-)" }
        require(isValidAppToken(appToken)) { "Invalid Slack App Token (must start with xapp-)" }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val base = baseUrl.trim().trimEnd('/')

    // ── Web API REST ───────────────────────────────────────────────────────

    /** `auth.test` — verify the bot token and fetch the bot's team/user/bot ids. */
    suspend fun authTest(): JsonObject = call("auth.test", botToken, buildJsonObject {})

    /**
     * `apps.connections.open` — negotiate a fresh Socket Mode WSS URL.
     * Called with the App-Level token; the returned URL is single-use and short-lived,
     * so fetch a new one on every reconnect.
     */
    suspend fun openConnection(): String {
        val result = call("apps.connections.open", appToken, buildJsonObject {})
        return result["url"]?.jsonPrimitive?.contentOrNull
            ?: throw SlackApiException("apps.connections.open returned no url")
    }

    /**
     * `chat.postMessage` — send a text message to a Slack channel (id or name).
     * Returns the assigned message timestamp (`ts`), used as the message id.
     */
    suspend fun postMessage(channel: String, text: String, threadTs: String? = null): String {
        val result = call("chat.postMessage", botToken, buildJsonObject {
            put("channel", channel)
            put("text", text)
            put("mrkdwn", true)
            put("link_names", false)
            put("unfurl_links", false)
            put("unfurl_media", false)
            if (threadTs != null) put("thread_ts", threadTs)
        })
        return result["ts"]?.jsonPrimitive?.contentOrNull
            ?: throw SlackApiException("chat.postMessage returned no ts")
    }

    /**
     * POST a JSON payload to a Slack Web API method. Throws [SlackApiException] on transport
     * failure, non-2xx HTTP, or `{ok:false}`. Returns the full response object on success.
     */
    private suspend fun call(method: String, token: String, payload: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val url = "$base/$method"
            val response = try {
                HttpClient.postTextResponse(
                    url,
                    payload.toString(),
                    mapOf("Authorization" to "Bearer ${token.trim()}"),
                )
            } catch (e: Exception) {
                DebugLog.e("SlackApi", "$method transport failed", e)
                throw SlackApiException(
                    "Slack $method transport failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            if (!response.isSuccessful) {
                throw SlackApiException("Slack $method failed (HTTP ${response.code})")
            }
            val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
                ?: throw SlackApiException("Slack $method returned invalid JSON")
            if (root["ok"]?.jsonPrimitive?.contentOrNull != "true") {
                val error = root["error"]?.jsonPrimitive?.contentOrNull ?: "unknown_error"
                throw SlackApiException("Slack $method failed: ${error.replace('_', ' ')}")
            }
            root
        }

    // ── Socket Mode WebSocket ──────────────────────────────────────────────

    /**
     * Open a Socket Mode WebSocket to [wssUrl] (from [openConnection]) and deliver every
     * inbound envelope to [onEnvelope] until the socket closes or fails. Suspends until
     * then; the caller is expected to loop and reconnect with a fresh WSS URL.
     *
     * Protocol handled internally:
     *  - Every envelope is ACKed with `{"envelope_id":"..."}` so Slack doesn't retry.
     *  - `disconnect` envelopes close the socket (Slack asks us to reconnect with a new URL).
     *  - `hello` / other control envelopes are forwarded to [onEnvelope] for inspection.
     *
     * [onOpen] is invoked once the WebSocket handshake completes, with the live [WebSocket]
     * handle so the caller can force-close it from [SlackChannel.stopListening].
     */
    suspend fun connectSocketMode(
        wssUrl: String,
        onEnvelope: (envelope: JsonObject) -> Unit,
        onOpen: (webSocket: WebSocket) -> Unit = {},
    ): Unit = suspendCancellableCoroutine { cont ->
        val request = Request.Builder().url(wssUrl).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runCatching { onOpen(webSocket) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val envelope = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: return
                // ACK every envelope so Slack doesn't retry delivery.
                val envelopeId = envelope["envelope_id"]?.jsonPrimitive?.contentOrNull
                if (envelopeId != null) {
                    runCatching { webSocket.send("""{"envelope_id":"$envelopeId"}""") }
                }
                // `disconnect` is a server-initiated close request: close the socket so the
                // caller's reconnect loop fetches a fresh WSS URL.
                if (envelope["type"]?.jsonPrimitive?.contentOrNull == "disconnect") {
                    runCatching { webSocket.close(1000, "disconnect") }
                    return
                }
                runCatching { onEnvelope(envelope) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                DebugLog.e("SlackApi", "socket mode failure", t)
                if (cont.isActive) cont.resume(Unit) // resume → caller reconnects
            }
        }
        val ws = HttpClient.client.newWebSocket(request, listener)
        cont.invokeOnCancellation { runCatching { ws.cancel() } }
    }

    companion object {
        /** Official Slack Web API base. */
        const val DEFAULT_BASE_URL = "https://slack.com/api"

        /** Bot token shape: `xoxb-` + 16+ alphanumeric/dash chars. */
        fun isValidBotToken(value: String): Boolean = BOT_TOKEN_REGEX.matches(value.trim())

        /** App-Level token shape: `xapp-` + 16+ alphanumeric/dash chars. */
        fun isValidAppToken(value: String): Boolean = APP_TOKEN_REGEX.matches(value.trim())

        private val BOT_TOKEN_REGEX = Regex("""^xoxb-[A-Za-z0-9-]{16,}$""")
        private val APP_TOKEN_REGEX = Regex("""^xapp-[A-Za-z0-9-]{16,}$""")
    }
}

/** Raised when a Slack Web API call fails (transport, non-2xx, or `{ok:false}`). */
class SlackApiException(message: String) : Exception(message)