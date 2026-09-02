package com.lxseek.chat.im.mattermost

import com.lxseek.chat.im.ImJson
import com.lxseek.chat.im.ImApiException
import com.lxseek.chat.im.isValidHttpBaseUrl
import com.lxseek.chat.im.isValidImToken
import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImMessageDirection
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.PushMessageChannel
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred

/**
 * Mattermost push 渠道：把 Mattermost Bot Token + 实例地址适配为 [PushMessageChannel]，
 * 让 Lxchat 的 [com.lxseek.chat.im.ImPollingReceiver] 作为长连接监听者绑定。
 *
 * **Push 模型** — Mattermost 通过 WebSocket `/api/v4/websocket` 推送 `posted` 事件。
 * [startListening] 先发 `authentication_challenge` 完成鉴权，再监听 `posted` 事件，
 * 把每条非自身发出的消息回调给 [onMessage]。
 *
 * **出站** — 经 [MattermostApi.createPost] 发帖。
 * [conversationId] 约定为 Mattermost 的 channel_id。
 *
 * **配置** 复用 [ImGatewayConfig]：
 *  - `baseUrl` ← Mattermost 实例基址（如 `https://mattermost.example.com`）
 *  - `token`   ← Bot Personal Access Token
 *  - `botId`   ← Team ID（可选，用于显示团队信息；空则跳过）
 *  - `platform` 必须为 `"mattermost"`
 *
 * 参照 AstrBot `mattermost_adapter.py` / `client.py` 与 Lxchat
 * [com.lxseek.chat.im.discord.DiscordChannel] 的 push 模板。
 */
class MattermostChannel(
    private val config: ImGatewayConfig,
) : PushMessageChannel {

    override val channelId: String get() = CHANNEL_ID

    override val displayName: String
        get() {
            val name = botUsername
            return if (name != null) "Mattermost · @$name" else "Mattermost"
        }

    override val isConfigured: Boolean
        get() = config.enabled &&
            isValidHttpBaseUrl(config.baseUrl) &&
            isValidImToken(config.token)

    /** 懒构建；配置不全时为 null，[isConfigured] 同步返回 false。 */
    private val api: MattermostApi? =
        if (isValidHttpBaseUrl(config.baseUrl) && isValidImToken(config.token)) {
            runCatching {
                MattermostApi(
                    baseUrl = config.baseUrl.trim(),
                    token = config.token.trim(),
                )
            }.getOrElse {
                DebugLog.e("MattermostChannel", "MattermostApi 构造失败: ${it.message}", it)
                null
            }
        } else null

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var botUserId: String? = null
    @Volatile private var botUsername: String? = null
    private var heartbeatJob: Job? = null

    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        val api = api ?: return ImSendResult.NotConfigured
        if (!isConfigured) return ImSendResult.NotConfigured
        if (conversationId.isBlank()) return ImSendResult.Failure("channel_id 为空")
        return try {
            val post = api.createPost(channelId = conversationId, message = text)
            val postId = post["id"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            ImSendResult.Success(postId)
        } catch (e: ImApiException) {
            DebugLog.e("MattermostChannel", "sendMessage 失败 (http=${e.httpCode})")
            ImSendResult.Failure(e.message ?: "mattermost send failed")
        } catch (e: Exception) {
            DebugLog.e("MattermostChannel", "sendMessage 失败", e)
            ImSendResult.Failure(e.message ?: "mattermost send failed")
        }
    }

    /** Push 渠道：会话由入站事件学习，不主动轮询。 */
    override suspend fun listConversations(): List<ImConversation> = emptyList()

    /** Push 渠道：消息经 [startListening] 投递，不按需拉取。 */
    override suspend fun fetchMessages(conversationId: String, afterId: String?): List<ImMessage> = emptyList()

    override suspend fun startListening(onMessage: (ImMessage) -> Unit, scope: CoroutineScope) {
        if (!isConfigured) return
        val api = api ?: return
        // 拉一次机器人自身信息，用于过滤自身消息与显示名。
        scope.launch {
            try {
                val me = api.getMe()
                botUserId = me["id"]?.jsonPrimitive?.contentOrNull
                botUsername = me["username"]?.jsonPrimitive?.contentOrNull
            } catch (e: Exception) {
                DebugLog.w("MattermostChannel", "getMe 失败", e)
            }
        }

        // WebSocket URL：把 http(s):// 换成 ws(s)://，再追加 /api/v4/websocket。
        val wsUrl = config.baseUrl.trim().trimEnd('/')
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "/api/v4/websocket"

        val incoming = Channel<String>(Channel.UNLIMITED)
        val opened = CompletableDeferred<Unit>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@MattermostChannel.webSocket = webSocket
                // 鉴权帧：Mattermost WebSocket 协议要求首帧发 authentication_challenge。
                webSocket.send(buildJsonObject {
                    put("seq", 1)
                    put("action", "authentication_challenge")
                    putJsonObject("data") { put("token", config.token.trim()) }
                }.toString())
                opened.complete(Unit)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.trySend(text)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                DebugLog.w("MattermostChannel", "websocket closed: code=$code reason=$reason")
                incoming.close()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                DebugLog.e("MattermostChannel", "websocket failure: ${t.message}", t)
                opened.completeExceptionally(t)
                incoming.close()
            }
        }
        val request = Request.Builder().url(wsUrl).build()
        HttpClient.client.newWebSocket(request, listener)
        try {
            opened.await()
        } catch (e: Exception) {
            incoming.close()
            return
        }

        // 心跳：Mattermost WebSocket ~30s 一次 keepalive（发空帧）。
        heartbeatJob = scope.launch {
            while (scope.isActive && !stopped) {
                delay(HEARTBEAT_INTERVAL_MS)
                runCatching { webSocket?.send("{}") }
            }
        }

        try {
            while (scope.isActive && !stopped) {
                val text = incoming.receiveCatching().getOrNull() ?: break
                handlePayload(text, onMessage)
            }
        } finally {
            heartbeatJob?.cancel()
            heartbeatJob = null
            webSocket = null
        }
    }

    override fun stopListening() {
        stopped = true
        heartbeatJob?.cancel()
        runCatching { webSocket?.close(1000, "client stop") }
    }

    /** 解析一帧 Mattermost WebSocket 事件，仅处理 event=="posted"。 */
    private fun handlePayload(text: String, onMessage: (ImMessage) -> Unit) {
            val root = runCatching { ImJson.parseToJsonElement(text).jsonObject }.onFailure { e ->
            DebugLog.w("MattermostChannel", "入站帧解析失败，已丢弃: ${text.take(200)}", e)
        }.getOrNull() ?: return
        val event = root["event"]?.jsonPrimitive?.contentOrNull ?: return
        if (event != "posted") return
        val dataStr = root["data"]?.jsonPrimitive?.contentOrNull ?: return
        val data = runCatching { ImJson.parseToJsonElement(dataStr).jsonObject }.getOrNull() ?: return
        val postId = data["id"]?.jsonPrimitive?.contentOrNull ?: return
        val message = data["message"]?.jsonPrimitive?.contentOrNull ?: return
        val userId = data["user_id"]?.jsonPrimitive?.contentOrNull ?: ""
        // 过滤自身消息。
        if (userId == botUserId) return
        val channelId = data["channel_id"]?.jsonPrimitive?.contentOrNull ?: ""
        val createAt = data["create_at"]?.jsonPrimitive?.longOrNull ?: 0L
        onMessage(
            ImMessage(
                id = postId,
                conversationId = channelId,
                direction = ImMessageDirection.INCOMING,
                text = message,
                sender = userId,
                timestampMs = createAt,
            ),
        )
    }

    companion object {
        /** 平台标识，存入 [ImGatewayConfig.platform]。 */
        const val PLATFORM = "mattermost"
        private const val CHANNEL_ID = "mattermost"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}