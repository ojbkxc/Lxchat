package com.lxseek.chat.im.kook

import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImMessageDirection
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.PushMessageChannel
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred

/**
 * KOOK push 渠道：把 KOOK 开放平台的 Bot Token 适配为 [PushMessageChannel]，
 * 让 Lxchat 的 [com.lxseek.chat.im.ImPollingReceiver] 作为长连接监听者绑定。
 *
 * **Push 模型** — KOOK 仅通过 WebSocket 推送事件，无 REST 历史拉取。[startListening]
 * 先经 [KookApi.getGatewayIndex] 获取网关地址，再走 HELLO → PING/PONG 心跳 → 监听
 * 0 信令（MESSAGE）的流程，把每条非自身发出的文本消息回调给 [onMessage]。
 *
 * **出站** — 经 [KookApi.createChannelMessage] / [createDirectMessage] 发送。
 * [conversationId] 约定为 `"channel:<id>"` 或 `"direct:<user_id>"`，由接收端绑定后回传。
 *
 * **配置** 复用 [ImGatewayConfig]：`token` 为 Bot Token，`baseUrl` 可覆盖 REST 基址
 * （空则用官方 [KookApi.DEFAULT_BASE_URL]），`platform` 必须为 `"kook"`。
 *
 * 参照 AstrBot `kook_client.py` 与 Lxchat [com.lxseek.chat.im.discord.DiscordChannel]
 * 的 push 模板，适配到 Kotlin/OkHttp WebSocket。
 */
class KookChannel(
    private val config: ImGatewayConfig,
) : PushMessageChannel {

    override val channelId: String get() = CHANNEL_ID

    override val displayName: String
        get() {
            val name = botNickname
            return if (name != null) "KOOK · $name" else "KOOK"
        }

    override val isConfigured: Boolean
        get() = config.enabled && KookApi.isValidToken(config.token)

    /** 懒构建；token 非法时为 null，[isConfigured] 同步返回 false。 */
    private val api: KookApi? =
        if (KookApi.isValidToken(config.token)) {
            KookApi(
                token = config.token.trim(),
                baseUrl = config.baseUrl.takeIf { it.isNotBlank() } ?: KookApi.DEFAULT_BASE_URL,
            )
        } else null

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var botNickname: String? = null
    @Volatile private var botId: String? = null
    private var heartbeatJob: Job? = null

    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        val api = api ?: return ImSendResult.NotConfigured
        if (!isConfigured) return ImSendResult.NotConfigured
        // 解析 "channel:<id>" / "direct:<user_id>"
        val parsed = parseConversationId(conversationId)
            ?: return ImSendResult.Failure("KOOK conversationId 必须为 'channel:<id>' 或 'direct:<user_id>': $conversationId")
        return try {
            val data = when (parsed.scope) {
                "channel" -> api.createChannelMessage(parsed.targetId, text)
                "direct" -> api.createDirectMessage(parsed.targetId, text)
                else -> return ImSendResult.Failure("未知 KOOK scope: ${parsed.scope}")
            }
            val msgId = data["msg_id"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            ImSendResult.Success(msgId)
        } catch (e: KookApiException) {
            DebugLog.e("KookChannel", "sendMessage 失败 (apiCode=${e.apiCode})")
            ImSendResult.Failure(e.message ?: "kook send failed")
        } catch (e: Exception) {
            DebugLog.e("KookChannel", "sendMessage 失败", e)
            ImSendResult.Failure(e.message ?: "kook send failed")
        }
    }

    /** Push 渠道：会话由入站事件学习，不主动轮询。 */
    override suspend fun listConversations(): List<ImConversation> = emptyList()

    /** Push 渠道：消息经 [startListening] 投递，不按需拉取。 */
    override suspend fun fetchMessages(conversationId: String, afterId: String?): List<ImMessage> = emptyList()

    override suspend fun startListening(onMessage: (ImMessage) -> Unit, scope: CoroutineScope) {
        if (!isConfigured) return
        val api = api ?: return
        // 动态获取网关地址；失败则回退默认 URL，避免短暂故障阻塞上线。
        val gatewayUrl = try {
            api.getGatewayIndex()["url"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            DebugLog.w("KookChannel", "getGatewayIndex 失败，回退默认网关 URL")
            null
        } ?: KookApi.DEFAULT_GATEWAY_URL

        // 拉一次机器人自身信息，用于过滤自身消息与显示名。
        scope.launch {
            try {
                val me = api.getMe()
                botId = me["id"]?.jsonPrimitive?.contentOrNull
                botNickname = me["nickname"]?.jsonPrimitive?.contentOrNull
                    ?: me["username"]?.jsonPrimitive?.contentOrNull
            } catch (e: Exception) {
                DebugLog.w("KookChannel", "getMe 失败", e)
            }
        }

        val incoming = Channel<String>(Channel.UNLIMITED)
        val opened = CompletableDeferred<Unit>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@KookChannel.webSocket = webSocket
                opened.complete(Unit)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.trySend(text)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                DebugLog.w("KookChannel", "websocket closed: code=$code reason=$reason")
                incoming.close()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                DebugLog.e("KookChannel", "websocket failure: ${t.message}", t)
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
            return
        }

        // 心跳协程：每 30s 发一次 PING（KOOK 信令 5）。
        heartbeatJob = scope.launch {
            while (scope.isActive && !stopped) {
                delay(HEARTBEAT_INTERVAL_MS)
                runCatching { webSocket?.send("{\"s\":5}") }
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

    /** 解析一帧 KOOK 网关事件，仅处理信令 0（MESSAGE）。 */
    private fun handlePayload(text: String, onMessage: (ImMessage) -> Unit) {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.onFailure { e ->
            DebugLog.w("KookChannel", "入站帧解析失败，已丢弃: ${text.take(200)}", e)
        }.getOrNull() ?: return
        val signal = root["s"]?.jsonPrimitive?.intOrNull ?: return
        if (signal != KOOK_SIGNAL_MESSAGE) return
        val data = root["d"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return
        // 仅处理文本类消息（type=9 纯文本 / type=1 KMarkdown）；非文本跳过。
        val type = data["type"]?.jsonPrimitive?.intOrNull ?: return
        if (type != KookApi.KOOK_MSG_TYPE_TEXT && type != 1) return
        val content = data["content"]?.jsonPrimitive?.contentOrNull ?: return
        val authorId = data["author_id"]?.jsonPrimitive?.contentOrNull ?: ""
        // 过滤自身消息。
        if (authorId == botId) return
        val msgId = data["msg_id"]?.jsonPrimitive?.contentOrNull ?: return
        val channelType = data["channel_type"]?.jsonPrimitive?.contentOrNull ?: "PERSON"
        val targetId = (if (channelType == "GROUP") data["target_id"] else data["target_id"])
            ?.jsonPrimitive?.contentOrNull ?: ""
        val conversationId = if (channelType == "GROUP") "channel:$targetId" else "direct:$targetId"
        val timestampMs = data["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L
        onMessage(
            ImMessage(
                id = msgId,
                conversationId = conversationId,
                direction = ImMessageDirection.INCOMING,
                text = content,
                sender = authorId,
                timestampMs = timestampMs,
            ),
        )
    }

    private fun parseConversationId(conversationId: String): ParsedTarget? {
        val idx = conversationId.indexOf(':')
        if (idx <= 0) return null
        val scope = conversationId.substring(0, idx)
        val targetId = conversationId.substring(idx + 1)
        if (targetId.isBlank()) return null
        return if (scope == "channel" || scope == "direct") ParsedTarget(scope, targetId) else null
    }

    private data class ParsedTarget(val scope: String, val targetId: String)

    companion object {
        /** 平台标识，存入 [ImGatewayConfig.platform]。 */
        const val PLATFORM = "kook"
        private const val CHANNEL_ID = "kook"
        /** KOOK 信令：0 = MESSAGE。 */
        private const val KOOK_SIGNAL_MESSAGE = 0
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}