package com.lxseek.chat.im.wecom

import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImMessageDirection
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.PushMessageChannel
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.WebSocket
import java.util.concurrent.atomic.AtomicReference

// ── JSON 导航辅助（与 WecomBotApi.kt 一致的安全转型风格） ──
private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

/**
 * 企业微信渠道：把 [WecomBotApi] 的 WebSocket 长连接 + REST API 适配到 [PushMessageChannel]。
 *
 * **推送模型** — 企业微信 AI 机器人通过 WebSocket 长连接推送消息到 Lxchat：
 * [startListening] 建立 WebSocket 连接，每收到一条消息帧就解析为 [ImMessage] 并回调
 * [onMessage]；[stopListening] 关闭连接。发送走 [WecomBotApi.sendMessage] 的 REST API
 * （`https://qyapi.weixin.qq.com/cgi-bin/message/send`）。
 *
 * **重连** — 连接断开（非主动 [stopListening]）时按指数退避重连，最多
 * [MAX_RECONNECT_ATTEMPTS] 次，与 dsh-im wecom-runtime.mjs 的 WSClient 行为一致。
 *
 * **配置** 复用 [ImGatewayConfig]（与任务约束对齐）：
 *  - `token`   ← 企业 CorpID / Bot ID
 *  - `baseUrl` ← 应用 Secret / Bot Secret
 *  - `botId`   ← 应用 AgentID（可选，默认 1000002）
 *
 * 消息去重 / 会话绑定由 [com.lxseek.chat.im.ImPollingReceiver] 负责，本类只实现协议层。
 * 仅支持 text 消息（voice/mixed 也会提取文本部分，与 dsh-im wecom-bridge.mjs 一致）。
 *
 * 协议参考：dsh-im/src/channels/wecom/wecom-bridge.mjs（消息帧解析）、
 * dsh-im/src/channels/wecom/wecom-runtime.mjs（连接生命周期）。
 */
class WecomChannel(
    private val config: ImGatewayConfig,
) : PushMessageChannel {

    override val channelId: String get() = CHANNEL_ID
    override val displayName: String get() = "企业微信"
    override val isConfigured: Boolean
        get() = config.enabled && config.token.isNotBlank() && config.baseUrl.isNotBlank()

    /** 懒构建；null 当配置不完整时，[isConfigured] 同步返回 false。 */
    private val api: WecomBotApi? =
        if (config.token.isNotBlank() && config.baseUrl.isNotBlank()) {
            WecomBotApi(
                corpId = config.token,
                secret = config.baseUrl,
                agentId = config.botId,
            )
        } else null

    private val json = Json { ignoreUnknownKeys = true }

    // ── 连接状态 ──────────────────────────────────────────────────────
    // webSocketRef 持有当前活跃的 WebSocket 句柄，stopListening 用它主动断开。
    // stopRequested 是单向标志：一旦置 true，重连循环不再发起新连接。
    private val webSocketRef = AtomicReference<WebSocket?>(null)
    @Volatile private var stopRequested = false

    // ── MessageChannel ──────────────────────────────────────────────

    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        val api = api ?: return ImSendResult.NotConfigured
        if (!isConfigured) return ImSendResult.NotConfigured
        val toUser = conversationId.trim()
        if (toUser.isEmpty()) return ImSendResult.Failure("conversationId is empty")
        return try {
            val msgId = api.sendMessage(toUser, text)
            ImSendResult.Success(msgId)
        } catch (e: WecomApiException) {
            DebugLog.e("WecomChannel", "sendMessage failed: ${e.message} (errcode=${e.errorCode})")
            ImSendResult.Failure(e.message ?: "wecom send failed")
        } catch (e: Exception) {
            DebugLog.e("WecomChannel", "sendMessage failed", e)
            ImSendResult.Failure(e.message ?: "wecom send failed")
        }
    }

    /** 推送型渠道：会话列表由入站消息隐式建立，不主动拉取。 */
    override suspend fun listConversations(): List<ImConversation> = emptyList()

    /** 推送型渠道：消息通过 [startListening] 回调到达，不通过拉取。 */
    override suspend fun fetchMessages(conversationId: String, afterId: String?): List<ImMessage> =
        emptyList()

    // ── PushMessageChannel ──────────────────────────────────────────

    /**
     * 打开 WebSocket 长连接并持续接收消息。挂起直到连接最终关闭（[stopListening]、
     * [scope] 取消、或重连耗尽）。每收到一条消息帧解析为 [ImMessage] 并回调 [onMessage]。
     *
     * [onMessage] 在 OkHttp WebSocket 线程上调用，必须便宜返回——重活（agent 生成）
     * 应在回调内部 launch 到 [scope]（[com.lxseek.chat.im.ImPollingReceiver] 正是这样做的）。
     */
    override suspend fun startListening(onMessage: (ImMessage) -> Unit, scope: CoroutineScope) {
        val api = api ?: return
        if (!isConfigured) return

        stopRequested = false
        var reconnectAttempts = 0

        while (!stopRequested && scope.isActive) {
            val closedNormally = try {
                connectAndAwait(api, onMessage)
                true
            } catch (e: Exception) {
                if (stopRequested || !scope.isActive) return
                DebugLog.e("WecomChannel", "WebSocket disconnected", e)
                false
            }

            if (stopRequested || !scope.isActive) return

            if (closedNormally) {
                // 正常关闭（服务器发起 1000），重置重连计数
                reconnectAttempts = 0
            } else {
                reconnectAttempts++
            }

            if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
                DebugLog.e(
                    "WecomChannel",
                    "reconnect exhausted after $MAX_RECONNECT_ATTEMPTS attempts, giving up",
                )
                return
            }

            // 指数退避：1s, 2s, 4s, 8s, ... 上限 60s
            val backoffMs = minOf(
                BASE_RECONNECT_DELAY_MS * (1L shl (reconnectAttempts - 1)),
                MAX_RECONNECT_DELAY_MS,
            )
            DebugLog.d("WecomChannel", "reconnecting in ${backoffMs}ms (attempt $reconnectAttempts)")
            delay(backoffMs)
        }
    }

    /** 关闭 WebSocket 连接并停止重连。安全可重入。 */
    override fun stopListening() {
        stopRequested = true
        webSocketRef.getAndSet(null)?.close(NORMAL_CLOSURE_CODE, "stopped by caller")
    }

    // ── WebSocket 连接 ──────────────────────────────────────────────

    /**
     * 建立一次 WebSocket 连接，挂起直到连接关闭（正常或异常）。
     *
     * @return Unit（正常关闭）；异常表示连接失败或异常断开。
     * @throws Exception 连接失败或异常断开时抛出，由上层重连循环处理。
     */
    private suspend fun connectAndAwait(
        api: WecomBotApi,
        onMessage: (ImMessage) -> Unit,
    ) {
        val closed = CompletableDeferred<Throwable?>()

        val ws = api.openWebSocket(
            onMessage = { text ->
                // 在 OkHttp WebSocket 线程上解析并回调。parseFrame 是纯 CPU 操作，
                // 足够快。onMessage 必须便宜返回（文档约定），重活在回调内部 launch。
                val imMessage = parseFrame(text)
                if (imMessage != null) {
                    runCatching { onMessage(imMessage) }
                        .onFailure { DebugLog.e("WecomChannel", "onMessage callback failed", it) }
                }
            },
            onOpen = { DebugLog.d("WecomChannel", "WebSocket connected") },
            onClose = { code, reason ->
                DebugLog.d("WecomChannel", "WebSocket closed: $code $reason")
                webSocketRef.set(null)
                // 正常关闭（1000）返回 null，异常关闭返回异常以触发重连
                if (code == NORMAL_CLOSURE_CODE) closed.complete(null)
                else closed.complete(WecomApiException("WebSocket closed: $code $reason"))
            },
            onError = { t ->
                DebugLog.e("WecomChannel", "WebSocket error", t)
                webSocketRef.set(null)
                closed.complete(t)
            },
        )
        webSocketRef.set(ws)

        // 挂起直到连接关闭。如果 scope 被取消，取消等待并关闭 socket。
        val error = try {
            closed.await()
        } catch (e: Exception) {
            // await 被取消（scope 取消）——主动关闭 socket
            if (webSocketRef.compareAndSet(ws, null)) {
                runCatching { ws.close(NORMAL_CLOSURE_CODE, "scope cancelled") }
            }
            throw e
        }

        if (error != null) throw error
    }

    // ── 消息帧解析 ──────────────────────────────────────────────────

    /**
     * 把企业微信 WebSocket 消息帧（JSON 字符串）解析为 [ImMessage]。
     *
     * 帧格式（基于 dsh-im wecom-bridge.mjs 的 bodyOf / messageText / conversationKey）：
     * ```
     * { "body": { "msgid": "...", "from": { "userid": "..." },
     *            "chattype": "single" | "group",
     *            "chatid": "...",          // 群聊时存在
     *            "msgtype": "text",
     *            "text": { "content": "..." } } }
     * ```
     *
     * @return 解析成功的 [ImMessage]；帧格式不符、缺关键字段、或非文本消息时返回 null。
     */
    internal fun parseFrame(raw: String): ImMessage? {
        val frame = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val body = frame["body"]?.obj() ?: return null

        val messageId = body["msgid"]?.str()?.takeIf { it.isNotBlank() } ?: return null
        val senderId = body["from"]?.obj()?.get("userid")?.str()?.takeIf { it.isNotBlank() }
            ?: return null
        val chattype = body["chattype"]?.str() ?: return null
        if (chattype != "single" && chattype != "group") return null

        // 单聊：conversationId = userid；群聊：conversationId = chatid
        val conversationId = if (chattype == "group") {
            body["chatid"]?.str()?.takeIf { it.isNotBlank() } ?: return null
        } else {
            senderId
        }

        val text = extractMessageText(body, chattype)
        if (text.isNullOrBlank()) return null

        return ImMessage(
            id = messageId,
            conversationId = conversationId,
            direction = ImMessageDirection.INCOMING,
            text = text,
            sender = senderId,
            timestampMs = System.currentTimeMillis(),
        )
    }

    /**
     * 提取消息文本。支持 text / voice / mixed 类型（与 dsh-im wecom-bridge.mjs 一致）。
     *
     * 群聊消息去掉开头的 @bot 提及——这是路由元数据而非用户输入的一部分
     * （dsh-im: `text.replace(/^\s*@\S+(?:\s+|$)/u, '').trim()`）。
     *
     * @return 提取到的文本（已 trim）；非文本消息或字段缺失时返回 null。
     */
    private fun extractMessageText(body: JsonObject, chattype: String): String? {
        val msgtype = body["msgtype"]?.str() ?: return null
        val raw: String? = when (msgtype) {
            "text" -> body["text"]?.obj()?.get("content")?.str()
            // 语音转写也走 text 字段（dsh-im 把 voice.content 当文本处理）
            "voice" -> body["voice"]?.obj()?.get("content")?.str()
            // mixed 消息：拼接所有 text item 的 content
            "mixed" -> {
                val items = body["mixed"]?.obj()?.get("msg_item")?.arr()
                items?.mapNotNull { item ->
                    val obj = item.obj() ?: return@mapNotNull null
                    if (obj["msgtype"]?.str() == "text") {
                        obj["text"]?.obj()?.get("content")?.str()
                    } else null
                }?.joinToString("\n")
            }
            else -> null
        }
        if (raw == null) return null
        // 群聊去掉开头的 @bot 提及
        return if (chattype == "group") {
            raw.replace(GROUP_MENTION_PREFIX, "").trim()
        } else {
            raw.trim()
        }
    }

    companion object {
        /** 平台标识，存入 [ImGatewayConfig.platform]。 */
        const val PLATFORM = "wecom"

        private const val CHANNEL_ID = "wecom"

        // WebSocket 正常关闭码（RFC 6455）
        private const val NORMAL_CLOSURE_CODE = 1000

        // 重连参数（与 dsh-im wecom-runtime.mjs maxReconnectAttempts=10 对齐）
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L

        // 群聊消息开头的 @bot 提及（与 dsh-im wecom-bridge.mjs 的正则一致）
        private val GROUP_MENTION_PREFIX = Regex("""^\s*@\S+(?:\s+|$)""")
    }
}