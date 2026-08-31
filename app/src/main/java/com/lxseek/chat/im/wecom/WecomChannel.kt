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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.WebSocket
import java.util.concurrent.atomic.AtomicReference

// ── JSON 导航辅助（与 WecomBotApi.kt 一致的安全转型风格） ──
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

/**
 * 企业微信 AI 机器人渠道：把 [WecomBotApi] 的 WebSocket 长连接适配到 [PushMessageChannel]。
 *
 * **协议** — 企业微信 AI 机器人 WebSocket 协议（`@wecom/aibot-node-sdk`）：
 *  - 端点 `wss://openws.work.weixin.qq.com`，连接后发 `aibot_subscribe` 帧认证
 *  - 入站：`aibot_msg_callback`（消息）/ `aibot_event_callback`（事件）帧
 *  - 出站：`aibot_send_msg`（主动）/ `aibot_respond_msg`（被动回复，透传 req_id）
 *  - 心跳：`{cmd: "ping", headers: {req_id}}`，定时发送
 *
 * **推送模型** — [startListening] 建立 WebSocket 连接，每收到一条 `aibot_msg_callback`
 * 帧就解析为 [ImMessage] 并回调 [onMessage]；[stopListening] 关闭连接。发送走当前
 * 活跃的 WebSocket 连接（[WecomBotApi.buildSendMsgFrame] + `ws.send`）。
 *
 * **重连** — 连接断开（非主动 [stopListening]）时按指数退避重连，最多
 * [MAX_RECONNECT_ATTEMPTS] 次，与 dsh-im wecom-runtime.mjs 的 WSClient 行为一致。
 *
 * **配置** 复用 [ImGatewayConfig]（与 dsh-im 的 `config.remoteBotId` + `secret` 对齐）：
 *  - `token`   ← AI 机器人 Bot ID（dsh-im 的 remoteBotId）
 *  - `baseUrl` ← AI 机器人 Secret
 *
 * 不再需要 corpId / agentId / access_token——AI 机器人协议用 botId+secret 直接认证。
 *
 * 消息去重 / 会话绑定由 [com.lxseek.chat.im.ImPollingReceiver] 负责，本类只实现协议层。
 * 仅支持 text / voice / mixed 消息（mixed 提取文本部分，与 dsh-im wecom-bridge.mjs 一致）。
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
                botId = config.token,
                secret = config.baseUrl,
            )
        } else null


    // ── 连接状态 ──────────────────────────────────────────────────────
    // webSocketRef 持有当前活跃的 WebSocket 句柄，stopListening / sendMessage 都用它。
    // stopRequested 是单向标志：一旦置 true，重连循环不再发起新连接。
    private val webSocketRef = AtomicReference<WebSocket?>(null)
    @Volatile private var stopRequested = false

    // ── MessageChannel ──────────────────────────────────────────────

    /**
     * 通过当前活跃的 WebSocket 连接发送 `aibot_send_msg` 帧。
     *
     * [conversationId] 即 chatid：单聊填 userid，群聊填 chatid（与 dsh-im 的
     * `body.chattype === 'group' ? body.chatid : body.from?.userid` 一致）。
     * 内容以 markdown 格式发送（AI 机器人协议不支持纯 text 主动消息）。
     *
     * 若 WebSocket 未连接（[startListening] 未调用或已断开），返回 [ImSendResult.Failure]。
     */
    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        val api = api ?: return ImSendResult.NotConfigured
        if (!isConfigured) return ImSendResult.NotConfigured
        val chatId = conversationId.trim()
        if (chatId.isEmpty()) return ImSendResult.Failure("conversationId is empty")
        val content = text.trim()
        if (content.isEmpty()) return ImSendResult.Failure("text is empty")

        val ws = webSocketRef.get()
            ?: return ImSendResult.Failure("WebSocket not connected; call startListening first")

        return try {
            val frame = api.buildSendMsgFrame(chatId, content)
            val sent = ws.send(frame)
            if (!sent) {
                return ImSendResult.Failure("WebSocket.send returned false (queue full or closed)")
            }
            // WebSocket.send 是非阻塞入队操作，无法同步等待服务器 ack。
            // 用本地时间戳作为临时 msgId；真实 ack 通过 parseFrame 的 WecomFrame.Ack 异步到达。
            ImSendResult.Success("wecom-send-${System.currentTimeMillis()}")
        } catch (e: WecomApiException) {
            DebugLog.e("WecomChannel", "sendMessage failed (errcode=${e.errorCode})")
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
     * [scope] 取消、或重连耗尽）。每收到一条 `aibot_msg_callback` 帧解析为 [ImMessage]
     * 并回调 [onMessage]；其他帧（认证响应、心跳响应、事件回调、回执）只记录日志。
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
                connectAndAwait(api, onMessage, scope)
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
     * 连接期间在 [scope] 上启动心跳协程，定时发送 [WecomBotApi.buildPingFrame]，
     * 间隔 [PING_INTERVAL_MS]。scope 取消或连接关闭时心跳自动停止。
     *
     * @return Unit（正常关闭）；异常表示连接失败或异常断开。
     * @throws Exception 连接失败或异常断开时抛出，由上层重连循环处理。
     */
    private suspend fun connectAndAwait(
        api: WecomBotApi,
        onMessage: (ImMessage) -> Unit,
        scope: CoroutineScope,
    ) {
        val closed = CompletableDeferred<Throwable?>()

        val ws = api.openWebSocket(
            onMessage = { text ->
                // 在 OkHttp WebSocket 线程上解析并分发。parseFrame 是纯 CPU 操作，
                // 足够快。onMessage 必须便宜返回（文档约定），重活在回调内部 launch。
                handleFrame(api, text, onMessage)
            },
            onOpen = { DebugLog.d("WecomChannel", "WebSocket connected, auth frame sent") },
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

        // 心跳协程：在 scope 上启动，scope 取消时自动停止；连接关闭时通过 closed.isCompleted 退出
        val heartbeatJob = scope.launch(Dispatchers.IO) {
            while (scope.isActive && !closed.isCompleted) {
                delay(PING_INTERVAL_MS)
                if (!scope.isActive || closed.isCompleted) break
                runCatching { ws.send(api.buildPingFrame()) }
                    .onFailure { DebugLog.w("WecomChannel", "ping send failed", it) }
            }
        }

        // 挂起直到连接关闭。如果 scope 被取消，取消等待并关闭 socket。
        val error = try {
            closed.await()
        } catch (e: Exception) {
            // await 被取消（scope 取消）——主动关闭 socket
            if (webSocketRef.compareAndSet(ws, null)) {
                runCatching { ws.close(NORMAL_CLOSURE_CODE, "scope cancelled") }
            }
            throw e
        } finally {
            // 无论正常/异常退出，都取消心跳协程
            runCatching { heartbeatJob.cancel() }
        }

        if (error != null) throw error
    }

    /**
     * 分发一帧：消息回调→解析为 [ImMessage] 并回调；其他帧→日志。
     * 解析失败（未知帧、缺字段、非文本）静默忽略，不影响连接。
     */
    private fun handleFrame(
        api: WecomBotApi,
        raw: String,
        onMessage: (ImMessage) -> Unit,
    ) {
        when (val frame = api.parseFrame(raw)) {
            is WecomBotApi.WecomFrame.MessageCallback -> {
                val imMessage = parseMessageBody(frame.body)
                if (imMessage != null) {
                    runCatching { onMessage(imMessage) }
                        .onFailure { DebugLog.e("WecomChannel", "onMessage callback failed", it) }
                }
            }
            is WecomBotApi.WecomFrame.EventCallback -> {
                // 事件回调（enter_chat / template_card_event / feedback_event）——
                // 当前不处理，仅记录 eventtype 供调试
                val eventtype = frame.body["event"]?.obj()?.get("eventtype")?.str()
                DebugLog.d("WecomChannel", "event callback: $eventtype (req_id=${frame.reqId})")
            }
            is WecomBotApi.WecomFrame.AuthResponse -> {
                if (frame.errcode == 0) {
                    DebugLog.d("WecomChannel", "auth ok (req_id=${frame.reqId})")
                } else {
                    DebugLog.e(
                        "WecomChannel",
                        "auth failed: ${frame.errmsg} (errcode=${frame.errcode}, req_id=${frame.reqId})",
                    )
                }
            }
            is WecomBotApi.WecomFrame.PingResponse -> {
                if (frame.errcode != 0) {
                    DebugLog.w(
                        "WecomChannel",
                        "ping ack non-zero: ${frame.errmsg} (errcode=${frame.errcode})",
                    )
                }
            }
            is WecomBotApi.WecomFrame.Ack -> {
                DebugLog.d(
                    "WecomChannel",
                    "ack req_id=${frame.reqId} errcode=${frame.errcode} ${frame.errmsg ?: ""}",
                )
            }
            null -> DebugLog.d("WecomChannel", "unparseable frame ignored")
        }
    }

    // ── 消息帧解析 ──────────────────────────────────────────────────

    /**
     * 把 `aibot_msg_callback` 的 body 解析为 [ImMessage]。
     *
     * body 格式（基于 dsh-im wecom-bridge.mjs 的 bodyOf / messageText / conversationKey）：
     * ```
     * { "msgid": "...", "from": { "userid": "..." },
     *   "chattype": "single" | "group",
     *   "chatid": "...",          // 群聊时存在
     *   "msgtype": "text",
     *   "text": { "content": "..." } }
     * ```
     *
     * @return 解析成功的 [ImMessage]；帧格式不符、缺关键字段、或非文本消息时返回 null。
     */
    internal fun parseMessageBody(body: JsonObject): ImMessage? {
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

        // 心跳间隔（@wecom/aibot-node-sdk 默认 30s）
        private const val PING_INTERVAL_MS = 30_000L

        // 群聊消息开头的 @bot 提及（与 dsh-im wecom-bridge.mjs 的正则一致）
        private val GROUP_MENTION_PREFIX = Regex("""^\s*@\S+(?:\s+|$)""")
    }
}
