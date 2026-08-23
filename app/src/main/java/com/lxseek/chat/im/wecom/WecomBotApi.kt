package com.lxseek.chat.im.wecom

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder

// ── JSON 导航辅助：用 `as?` 安全转型，单字段类型不符不会让整条消息解析失败 ──
private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull

/** 企业微信 API 错误。errcode 非 0 或 HTTP 失败时抛出。 */
class WecomApiException(message: String, val errorCode: Int? = null) : Exception(message)

/**
 * 企业微信机器人 API 客户端：封装 access_token 获取/缓存、消息发送 REST API、
 * 以及 WebSocket 长连接的建立。
 *
 * 纯 HTTP/WebSocket over [HttpClient] 的共享 OkHttp 实例，无额外 SDK 依赖。
 *
 * 协议参考：
 *  - access_token: https://developer.work.weixin.qq.com/document/path/91039
 *  - 消息发送:     https://developer.work.weixin.qq.com/document/path/90236
 *  - WebSocket 长连接: 基于 dsh-im 的 @wecom/aibot-node-sdk 协议推断
 *    （dsh-im/src/channels/wecom/wecom-runtime.mjs 的 WSClient 用法）
 *
 * 配置映射（复用 [com.lxseek.chat.im.ImGatewayConfig] 字段）：
 *  - [corpId]  ← config.token   （企业 CorpID / AI 机器人 Bot ID）
 *  - [secret]  ← config.baseUrl （应用 Secret / Bot Secret）
 *  - [agentId] ← config.botId   （应用 AgentID，可选，默认 1000002）
 */
class WecomBotApi(
    /** 企业 CorpID（或 AI 机器人 Bot ID）。 */
    val corpId: String,
    /** 应用 Secret（或 AI 机器人 Secret）。 */
    val secret: String,
    /** 应用 AgentID；为空时用 [DEFAULT_AGENT_ID]。 */
    val agentId: String = "",
    /** REST API 基地址，可覆盖用于测试或自建网关。 */
    private val apiBase: String = DEFAULT_API_BASE,
    /** WebSocket 端点，可覆盖用于测试。 */
    private val wsEndpoint: String = DEFAULT_WS_ENDPOINT,
    private val client: OkHttpClient = HttpClient.client,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    init {
        require(corpId.isNotBlank()) { "corpId (Bot ID) is required" }
        require(secret.isNotBlank()) { "secret is required" }
    }

    // ── access_token 缓存 ──────────────────────────────────────────────
    // 企业微信 access_token 有效期 7200 秒；提前 [TOKEN_REFRESH_MARGIN_S] 秒刷新，
    // 避免在边界上用到过期 token。两个字段一起读，@Volatile 保证可见性。
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAtMs: Long = 0L

    /**
     * 获取有效的 access_token，带本地缓存。并发调用可能触发多次刷新，但结果一致，
     * 企业微信不会因此限流（gettoken 的 QPS 限制远高于此）。
     */
    suspend fun getAccessToken(): String {
        val now = System.currentTimeMillis()
        val cached = cachedToken
        if (cached != null && now < tokenExpiresAtMs) return cached
        return refreshAccessToken()
    }

    private suspend fun refreshAccessToken(): String = withContext(Dispatchers.IO) {
        val url = "$apiBase/cgi-bin/gettoken?corpid=${urlEncode(corpId)}&corpsecret=${urlEncode(secret)}"
        DebugLog.d("WecomBotApi", "refreshing access_token")
        val response = HttpClient.getTextResponse(url)
        if (!response.isSuccessful) {
            throw WecomApiException("获取 access_token 失败 (HTTP ${response.code})")
        }
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: throw WecomApiException("access_token 响应非合法 JSON")
        val errcode = root["errcode"]?.int() ?: 0
        if (errcode != 0) {
            val errmsg = root["errmsg"]?.str() ?: "unknown"
            throw WecomApiException("企业微信返回错误: $errmsg (errcode=$errcode)", errcode)
        }
        val token = root["access_token"]?.str()
            ?: throw WecomApiException("access_token 响应缺少 access_token 字段")
        val expiresIn = root["expires_in"]?.long() ?: 7200L
        cachedToken = token
        // 提前 5 分钟刷新，避免边界过期
        tokenExpiresAtMs = System.currentTimeMillis() + (expiresIn - TOKEN_REFRESH_MARGIN_S) * 1000L
        token
    }

    // ── 消息发送 REST API ──────────────────────────────────────────────

    /**
     * 发送文本消息给指定用户（[toUser]）。返回企业微信分配的 msgid。
     *
     * POST `https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=ACCESS_TOKEN`
     * body: `{"touser":"UserID","msgtype":"text","agentid":1000002,"text":{"content":"..."}}`
     *
     * access_token 自动获取并缓存；过期时自动刷新重试一次。
     */
    suspend fun sendMessage(toUser: String, text: String): String = withContext(Dispatchers.IO) {
        val recipient = toUser.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("toUser is required")
        val content = text.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("text is required")
        val agentIdInt = agentId.trim().ifBlank { DEFAULT_AGENT_ID }.toIntOrNull()
            ?: DEFAULT_AGENT_ID.toInt()

        // 确保 access_token 已获取（首次调用或缓存过期时触发刷新）
        getAccessToken()
        try {
            callMessageSend(recipient, content, agentIdInt)
        } catch (e: WecomApiException) {
            // errcode 42001 = access_token 过期，强制刷新后重试一次
            if (e.errorCode == ERR_ACCESS_TOKEN_EXPIRED) {
                DebugLog.w("WecomBotApi", "access_token expired, refreshing and retrying")
                cachedToken = null
                getAccessToken()
                callMessageSend(recipient, content, agentIdInt)
            } else throw e
        }
    }

    private fun callMessageSend(toUser: String, text: String, agentIdInt: Int): String {
        val token = cachedToken
            ?: throw WecomApiException("access_token not available; call getAccessToken() first")
        val url = "$apiBase/cgi-bin/message/send?access_token=${urlEncode(token)}"
        val body = buildJsonObject {
            put("touser", toUser)
            put("msgtype", "text")
            put("agentid", agentIdInt)
            putJsonObject("text") { put("content", text) }
            // 启用重复检查避免快速重发同一内容
            put("enable_duplicate_check", 1)
            put("duplicate_check_interval", 1800)
        }.toString()
        val response = HttpClient.postTextResponse(url, body, emptyMap())
        if (!response.isSuccessful) {
            throw WecomApiException("发送消息失败 (HTTP ${response.code})")
        }
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: throw WecomApiException("message/send 响应非合法 JSON")
        val errcode = root["errcode"]?.int() ?: 0
        if (errcode != 0) {
            val errmsg = root["errmsg"]?.str() ?: "unknown"
            throw WecomApiException("企业微信发送失败: $errmsg (errcode=$errcode)", errcode)
        }
        root["msgid"]?.str() ?: "wecom-sent-${System.currentTimeMillis()}"
    }

    // ── WebSocket 长连接 ──────────────────────────────────────────────

    /**
     * 打开企业微信 AI 机器人 WebSocket 长连接，返回 [WebSocket] 句柄。
     *
     * 基于 dsh-im 的 @wecom/aibot-node-sdk 协议：用 botId + secret 认证，
     * 服务器通过 WebSocket 推送消息帧。消息帧格式（dsh-im wecom-bridge.mjs）：
     * ```
     * { "body": { "msgid", "from": { "userid" }, "chattype": "single"|"group",
     *            "chatid", "msgtype": "text", "text": { "content" } } }
     * ```
     *
     * 认证信息通过 URL 查询参数传递（`bot_id` + `secret`），这是 SDK 内部协议的
     * 常见方式。如实际协议要求连接后发送认证消息，可在 [onOpen] 回调中发送。
     *
     * [onMessage] 在收到文本帧时调用（原始 JSON 字符串）；
     * [onOpen] 在连接建立时调用；[onClose] 在连接关闭时调用；[onError] 在异常时调用。
     *
     * 调用 [WebSocket.close] 主动断开。非同步——回调在 OkHttp 的线程池上执行。
     */
    fun openWebSocket(
        onMessage: (String) -> Unit,
        onOpen: () -> Unit = {},
        onClose: (Int, String) -> Unit = { _, _ -> },
        onError: (Throwable) -> Unit = {},
    ): WebSocket {
        // 认证信息通过 URL 参数传递（基于 dsh-im WSClient { botId, secret } 推断）
        val url = "$wsEndpoint?bot_id=${urlEncode(corpId)}&secret=${urlEncode(secret)}"
        val request = Request.Builder().url(url).build()
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DebugLog.d("WecomBotApi", "WebSocket onOpen")
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // 服务器发起关闭——回 ack 并触发 onClosed
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                DebugLog.d("WecomBotApi", "WebSocket onClosed: $code $reason")
                onClose(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                DebugLog.e("WecomBotApi", "WebSocket onFailure", t)
                onError(t)
            }
        })
    }

    companion object {
        /** 企业微信 REST API 基地址。 */
        const val DEFAULT_API_BASE = "https://qyapi.weixin.qq.com"

        /**
         * 企业微信 AI 机器人 WebSocket 端点。
         *
         * 基于 dsh-im @wecom/aibot-node-sdk 的 WSClient 用法推断——SDK 内部协议
         * 未公开文档，此 URL 为合理默认值。如需调整，构造时传入 [wsEndpoint]。
         */
        const val DEFAULT_WS_ENDPOINT = "wss://qyapi.weixin.qq.com/cgi-bin/aibot/wsconnect"

        /** 默认应用 AgentID（企业微信管理后台分配）。 */
        const val DEFAULT_AGENT_ID = "1000002"

        // access_token 提前刷新余量（秒）
        private const val TOKEN_REFRESH_MARGIN_S = 300L

        // 企业微信 errcode: access_token 过期
        private const val ERR_ACCESS_TOKEN_EXPIRED = 42001

        internal fun urlEncode(value: String): String =
            URLEncoder.encode(value, "UTF-8")
    }
}