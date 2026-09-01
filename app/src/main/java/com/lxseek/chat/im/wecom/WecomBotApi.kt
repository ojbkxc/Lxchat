package com.lxseek.chat.im.wecom

import com.lxseek.chat.util.DebugLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom

// ── JSON 导航辅助：用 `as?` 安全转型，单字段类型不符不会让整条消息解析失败 ──
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
internal fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull

/** 企业微信 AI 机器人协议错误。errcode 非 0 或协议帧非法时抛出。 */
class WecomApiException(message: String, val errorCode: Int? = null) : Exception(message)

/**
 * 企业微信 AI 机器人 WebSocket 协议客户端。
 *
 * 协议来自 `@wecom/aibot-node-sdk@1.0.7` 源码分析（dsh-im 的 wecom-runtime.mjs 用法），
 * 与"经典企业微信 REST API"（cgi-bin/gettoken + cgi-bin/message/send）完全不同：
 *
 * - **端点**：`wss://openws.work.weixin.qq.com`（不是 qyapi.weixin.qq.com）
 * - **认证**：连接建立后发送 `aibot_subscribe` 帧（不是 URL 查询参数，也不是 access_token）
 * - **心跳**：定时发送 `{cmd: "ping", headers: {req_id}}`
 * - **被动回复**：`aibot_respond_msg`（透传收到的 req_id，stream 分片）
 * - **主动发送**：`aibot_send_msg`（用 chatid + markdown）
 * - **入站推送**：`aibot_msg_callback`（消息）/ `aibot_event_callback`（事件）
 *
 * 配置映射（与 dsh-im 的 `config.remoteBotId` + `secret` 对齐）：
 *  - [botId]  ← config.token   （AI 机器人 Bot ID，即 dsh-im 的 remoteBotId）
 *  - [secret] ← config.baseUrl  （AI 机器人 Secret）
 *
 * 不需要 corpId / agentId / access_token——AI 机器人协议用 botId+secret 直接认证。
 */
class WecomBotApi(
    /** AI 机器人 Bot ID（dsh-im 的 remoteBotId）。 */
    val botId: String,
    /** AI 机器人 Secret。 */
    val secret: String,
    /** WebSocket 端点，可覆盖用于测试。 */
    private val wsEndpoint: String = DEFAULT_WS_ENDPOINT,
    private val client: OkHttpClient = com.lxseek.chat.api.HttpClient.client,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    init {
        require(botId.isNotBlank()) { "botId is required" }
        require(secret.isNotBlank()) { "secret is required" }
    }

    // ── WebSocket 长连接 ──────────────────────────────────────────────

    /**
     * 打开企业微信 AI 机器人 WebSocket 长连接，返回 [WebSocket] 句柄。
     *
     * **认证流程**（与 dsh-im 的 WSClient 一致）：
     *  1. 连接 `wss://openws.work.weixin.qq.com`（不带查询参数）
     *  2. onOpen 后立即发送 `aibot_subscribe` 帧：
     *     ```
     *     { "cmd": "aibot_subscribe",
     *       "headers": { "req_id": "aibot_subscribe_{ts}_{rand}" },
     *       "body": { "bot_id": "...", "secret": "..." } }
     *     ```
     *  3. 服务器回 `{ "headers": { "req_id": "..." }, "errcode": 0, "errmsg": "ok" }`
     *     （req_id 以 "aibot_subscribe" 开头），认证完成。
     *
     * [onMessage] 在收到文本帧时调用（原始 JSON 字符串），由上层解析 cmd/headers/body。
     * [onOpen] 在连接建立（认证帧发送前）调用；[onClose] / [onError] 同 OkHttp 语义。
     *
     * 调用 [WebSocket.close] 主动断开。非同步——回调在 OkHttp 的线程池上执行。
     */
    fun openWebSocket(
        onMessage: (String) -> Unit,
        onOpen: () -> Unit = {},
        onClose: (Int, String) -> Unit = { _, _ -> },
        onError: (Throwable) -> Unit = {},
    ): WebSocket {
        // 不带 bot_id/secret 查询参数——认证通过连接后的 aibot_subscribe 帧
        val request = Request.Builder().url(wsEndpoint).build()
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DebugLog.d("WecomBotApi", "WebSocket onOpen, sending aibot_subscribe")
                // 连接建立后立即发送认证帧
                val sent = runCatching { webSocket.send(buildAuthFrame()) }
                    .onFailure { DebugLog.e("WecomBotApi", "send aibot_subscribe failed", it) }
                    .getOrDefault(false)
                if (!sent) {
                    DebugLog.e("WecomBotApi", "aibot_subscribe send returned false, closing")
                    webSocket.close(1001, "auth frame send failed")
                    return
                }
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

    // ── 协议帧构造 ──────────────────────────────────────────────────

    /** 构造认证帧（aibot_subscribe）。 */
    private fun buildAuthFrame(): String = buildJsonObject {
        put("cmd", "aibot_subscribe")
        putJsonObject("headers") { put("req_id", generateReqId("aibot_subscribe")) }
        putJsonObject("body") {
            put("bot_id", botId)
            put("secret", secret)
        }
    }.toString()

    /**
     * 构造心跳帧（ping）。返回 JSON 字符串，调用方负责 `ws.send(...)`。
     * 服务器回执：`{headers: {req_id}, errcode: 0, errmsg: "ok"}`（req_id 以 "ping" 开头）。
     */
    fun buildPingFrame(): String = buildJsonObject {
        put("cmd", "ping")
        putJsonObject("headers") { put("req_id", generateReqId("ping")) }
    }.toString()

    /**
     * 主动发送消息（aibot_send_msg）。
     *
     * 用于不依赖入站帧的场景（如主动问候、连接测试）。回复收到的消息应改用
     * [buildRespondMsgFrame] 透传 req_id。
     *
     * @param chatId 单聊填 userid，群聊填 chatid（与 dsh-im wecom-bridge.mjs 一致）
     * @param text   markdown 文本
     * @return JSON 字符串，调用方负责 `ws.send(...)`
     */
    fun buildSendMsgFrame(chatId: String, text: String): String = buildJsonObject {
        put("cmd", "aibot_send_msg")
        putJsonObject("headers") { put("req_id", generateReqId("aibot_send_msg")) }
        putJsonObject("body") {
            put("chatid", chatId)
            put("msgtype", "markdown")
            putJsonObject("markdown") { put("content", text) }
        }
    }.toString()

    /**
     * 被动回复消息（aibot_respond_msg）——透传收到的 req_id。
     *
     * 与 dsh-im 的 `client.replyStream(frame, streamId, content, finish)` 对齐：
     * 用同一 streamId 分片发送，最后一帧 `finish=true`。回执无 cmd，req_id 匹配发送的 req_id。
     *
     * @param reqId    透传收到的 req_id（不是新生成的）
     * @param streamId 流式分片 ID（同一回复的所有分片共享）
     * @param content  markdown 内容分片
     * @param finish   是否为最后一帧
     * @return JSON 字符串，调用方负责 `ws.send(...)`
     */
    fun buildRespondMsgFrame(reqId: String, streamId: String, content: String, finish: Boolean): String =
        buildJsonObject {
            put("cmd", "aibot_respond_msg")
            putJsonObject("headers") { put("req_id", reqId) }
            putJsonObject("body") {
                put("msgtype", "stream")
                putJsonObject("stream") {
                    put("id", streamId)
                    put("finish", finish)
                    put("content", content)
                }
            }
        }.toString()

    // ── 协议帧解析 ──────────────────────────────────────────────────

    /**
     * 解析协议帧的 [WecomFrame] 视图。识别：
     *  - 认证响应：无 cmd，`headers.req_id` 以 "aibot_subscribe" 开头
     *  - 心跳响应：无 cmd，`headers.req_id` 以 "ping" 开头
     *  - 消息回调：`cmd = "aibot_msg_callback"`
     *  - 事件回调：`cmd = "aibot_event_callback"`
     *  - 回执（发送/回复）：无 cmd，`headers.req_id` 匹配之前发出的 req_id
     *
     * @return 解析失败的帧返回 null（调用方应忽略而非崩溃）。
     */
    fun parseFrame(raw: String): WecomFrame? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.onFailure { e ->
            DebugLog.w(TAG, "入站协议帧解析失败，已丢弃: ${raw.take(200)}", e)
        }.getOrNull() ?: return null
        val cmd = root["cmd"]?.str()
        val headers = root["headers"]?.obj()
        val reqId = headers?.get("req_id")?.str()
        val errcode = root["errcode"]?.int()
        val errmsg = root["errmsg"]?.str()
        val body = root["body"]?.obj()

        return when {
            // 消息回调（企微→开发者）
            cmd == "aibot_msg_callback" -> WecomFrame.MessageCallback(
                reqId = reqId ?: return null,
                body = body ?: return null,
            )
            // 事件回调（企微→开发者）
            cmd == "aibot_event_callback" -> WecomFrame.EventCallback(
                reqId = reqId ?: return null,
                body = body ?: return null,
            )
            // 认证响应
            reqId != null && reqId.startsWith("aibot_subscribe") -> WecomFrame.AuthResponse(
                reqId = reqId,
                errcode = errcode ?: 0,
                errmsg = errmsg,
            )
            // 心跳响应
            reqId != null && reqId.startsWith("ping") -> WecomFrame.PingResponse(
                reqId = reqId,
                errcode = errcode ?: 0,
                errmsg = errmsg,
            )
            // 通用回执（aibot_send_msg / aibot_respond_msg 的 ack）
            reqId != null -> WecomFrame.Ack(
                reqId = reqId,
                errcode = errcode ?: 0,
                errmsg = errmsg,
            )
            else -> null
        }
    }

    /** 协议帧的解构视图，按 cmd / req_id 前缀分类。 */
    sealed class WecomFrame {
        /** 认证响应（req_id 以 "aibot_subscribe" 开头）。errcode=0 表示认证成功。 */
        data class AuthResponse(val reqId: String, val errcode: Int, val errmsg: String?) : WecomFrame()

        /** 心跳响应（req_id 以 "ping" 开头）。 */
        data class PingResponse(val reqId: String, val errcode: Int, val errmsg: String?) : WecomFrame()

        /** 通用回执（aibot_send_msg / aibot_respond_msg 的 ack）。 */
        data class Ack(val reqId: String, val errcode: Int, val errmsg: String?) : WecomFrame()

        /** 消息回调（cmd = "aibot_msg_callback"）。 */
        data class MessageCallback(val reqId: String, val body: JsonObject) : WecomFrame()

        /** 事件回调（cmd = "aibot_event_callback"）。 */
        data class EventCallback(val reqId: String, val body: JsonObject) : WecomFrame()
    }

    companion object {
        private const val TAG = "WecomBotApi"

        /**
         * 企业微信 AI 机器人 WebSocket 端点。
         *
         * 来自 `@wecom/aibot-node-sdk@1.0.7` 源码（dsh-im wecom-runtime.mjs 的 WSClient 用此端点）。
         */
        const val DEFAULT_WS_ENDPOINT = "wss://openws.work.weixin.qq.com"

        private val RNG = SecureRandom()

        /**
         * 生成 req_id：`{prefix}_{timestamp}_{random8hex}`，与 @wecom/aibot-node-sdk 的
         * `generateReqId` 格式一致。
         */
        internal fun generateReqId(prefix: String): String {
            val ts = System.currentTimeMillis()
            val rand = RNG.nextInt(0x10000000).toString(16).padStart(7, '0')
            return "${prefix}_${ts}_${rand}"
        }
    }
}
