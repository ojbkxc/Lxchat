package com.lxseek.chat.im.weixin

import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImMessageDirection
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.MessageChannel
import com.lxseek.chat.util.DebugLog

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 微信 iLink 渠道：把 [WeixinIlinkApi] 的长轮询协议适配到 [MessageChannel]。
 *
 * 直连 ilinkai.weixin.qq.com，无需外部网关。会话列表和消息历史在内存中维护：
 * [listConversations] 触发一次 [WeixinIlinkApi.getUpdates] 长轮询（~35s），把新消息
 * 归档到对应会话；[fetchMessages] 只读内存，返回 INCOMING 消息。发送走 [WeixinIlinkApi.sendText]。
 *
 * 消息去重 / 会话绑定由 [com.lxseek.chat.im.ImPollingReceiver] 负责，本类只实现协议层。
 */
class WeixinChannel(
    private val config: com.lxseek.chat.im.ImGatewayConfig,
    private val api: WeixinIlinkApi = WeixinIlinkApi(),
) : MessageChannel {

    override val channelId: String get() = "wechat"
    override val displayName: String get() = "微信 · iLink"
    override val isConfigured: Boolean
        get() = config.enabled && config.token.isNotBlank()

    /** iLink base URL：配置优先，否则用协议默认。 */
    private val baseUrl: String
        get() = config.baseUrl.trim().ifBlank { WeixinIlinkApi.WEIXIN_QR_BASE_URL }

    private val state = ChannelState()

    /** Ensures notifyStart is called once before the first getUpdates poll. */
    @Volatile
    private var notified = false

    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        if (!isConfigured) return ImSendResult.NotConfigured
        val recipient = conversationId.trim()
        if (recipient.isEmpty()) return ImSendResult.Failure("conversationId is empty")
        return try {
            api.sendText(baseUrl, config.token, recipient, text)
            // 用 client_id 风格的本地 id 作为成功回执（iLink 不回 server id）
            ImSendResult.Success("lxchat-weixin-sent-${System.currentTimeMillis()}")
        } catch (e: WeixinApiError) {
            DebugLog.e("WeixinChannel", "sendMessage failed: ${e.code}", e)
            ImSendResult.Failure(e.message ?: e.code)
        } catch (e: Exception) {
            DebugLog.e("WeixinChannel", "sendMessage failed", e)
            ImSendResult.Failure(e.message ?: "send failed")
        }
    }

    override suspend fun listConversations(): List<ImConversation> {
        if (!isConfigured) return emptyList()
        // 长轮询拉新消息，更新会话列表。失败不抛（返回当前快照）。
        pollUpdates()
        return state.conversations()
    }

    override suspend fun fetchMessages(conversationId: String, afterId: String?): List<ImMessage> {
        if (!isConfigured) return emptyList()
        // 只读内存（listConversations 已经 poll 过）；iLink 入站消息全部 INCOMING。
        return state.messagesFor(conversationId, afterId)
            .filter { it.direction == ImMessageDirection.INCOMING }
    }

    private suspend fun pollUpdates() {
        try {
            // dsh-im calls notifyStart before the monitor loop; without this the WeChat server
            // does not push messages to getupdates, so every poll returns an empty list.
            if (!notified) {
                DebugLog.d("WeixinChannel", "pollUpdates: calling notifyStart, baseUrl=$baseUrl, tokenLen=${config.token.length}")
                api.notifyStart(baseUrl, config.token)
                notified = true
                DebugLog.d("WeixinChannel", "pollUpdates: notifyStart succeeded")
            }
            val updates = api.getUpdates(baseUrl, config.token, state.getUpdatesBuf())
            DebugLog.d("WeixinChannel", "pollUpdates: received ${updates.msgs.size} msgs, ret=${updates.ret}, bufLen=${updates.getUpdatesBuf.length}")
            // Check for server-side rejection (dsh-im checks ret and errcode).
            val errcode = updates.raw["errcode"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
            if ((updates.ret != 0) || (errcode != null && errcode != 0)) {
                val code = errcode ?: updates.ret
                DebugLog.e("WeixinChannel", "pollUpdates rejected: ret=${updates.ret} errcode=$errcode")
                if (code == -14) {
                    DebugLog.e("WeixinChannel", "stale token — re-scan required")
                }
                return
            }
            state.applyUpdates(updates)
        } catch (e: WeixinApiError) {
            DebugLog.e("WeixinChannel", "pollUpdates failed: ${e.code}", e)
        } catch (e: Exception) {
            DebugLog.e("WeixinChannel", "pollUpdates failed", e)
        }
    }

    // ── 内存状态 ─────────────────────────────────────────────────────────

    private class ChannelState {
        @Volatile
        private var _getUpdatesBuf: String = ""

        private val conversations = ConcurrentHashMap<String, ImConversation>()
        private val messages = ConcurrentHashMap<String, CopyOnWriteArrayList<ImMessage>>()

        fun getUpdatesBuf(): String = _getUpdatesBuf

        fun applyUpdates(updates: WeixinIlinkApi.Updates) {
            _getUpdatesBuf = updates.getUpdatesBuf
            DebugLog.d("WeixinChannel", "applyUpdates: processing ${updates.msgs.size} msgs")
            for (msg in updates.msgs) {
                // dsh-im skips message_type === 2 (outgoing messages sent by the bot itself).
                val msgType = msg["message_type"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
                if (msgType == 2) { DebugLog.d("WeixinChannel", "applyUpdates: skipped - outgoing msg (type=2)"); continue }
                val text = WeixinIlinkApi.extractWeixinText(msg)
                val msgId = WeixinIlinkApi.weixinMessageId(msg)
                val fromUserId = msg["from_user_id"]?.strSafe()
                DebugLog.d("WeixinChannel", "applyUpdates: msg text=${text?.take(50)} id=$msgId from=$fromUserId keys=${msg.keys}")
                if (text == null) { DebugLog.w("WeixinChannel", "applyUpdates: skipped - text is null"); continue }
                if (msgId == null) { DebugLog.w("WeixinChannel", "applyUpdates: skipped - msgId is null"); continue }
                if (fromUserId == null) { DebugLog.w("WeixinChannel", "applyUpdates: skipped - fromUserId is null"); continue }
                if (fromUserId.isEmpty()) { DebugLog.w("WeixinChannel", "applyUpdates: skipped - fromUserId is empty"); continue }
                val timestampMs = normalizeTimestamp(msg["create_time"]?.longSafe())
                val imMsg = ImMessage(
                    id = msgId,
                    conversationId = fromUserId,
                    direction = ImMessageDirection.INCOMING,
                    text = text,
                    sender = fromUserId,
                    timestampMs = timestampMs,
                )
                val list = messages.computeIfAbsent(fromUserId) { CopyOnWriteArrayList() }
                if (list.none { it.id == msgId }) list.add(imMsg)
                conversations.compute(fromUserId) { _, existing ->
                    val lastMs = existing?.lastMessageAtMs ?: 0L
                    if (lastMs < timestampMs) {
                        ImConversation(
                            id = fromUserId,
                            title = existing?.title ?: fromUserId,
                            platform = "wechat",
                            lastMessageAtMs = maxOf(lastMs, timestampMs),
                            unreadCount = (existing?.unreadCount ?: 0) + 1,
                            isGroup = existing?.isGroup ?: false,
                        )
                    } else existing
                }
            }
        }

        fun conversations(): List<ImConversation> =
            conversations.values.sortedByDescending { it.lastMessageAtMs }

        fun messagesFor(conversationId: String, afterId: String?): List<ImMessage> {
            val list = messages[conversationId] ?: return emptyList()
            if (afterId.isNullOrBlank()) return list.toList()
            val idx = list.indexOfFirst { it.id == afterId }
            if (idx < 0) return list.toList()
            return list.subList(idx + 1, list.size).toList()
        }

        /** iLink create_time 可能是秒也可能是毫秒；>1e12 视作毫秒。 */
        private fun normalizeTimestamp(value: Long?): Long {
            if (value == null || value <= 0L) return System.currentTimeMillis()
            return if (value > 1_000_000_000_000L) value else value * 1_000L
        }
    }
}

// ── JsonElement 安全取值（避免类型不符抛 IllegalStateException） ──
private fun kotlinx.serialization.json.JsonElement?.strSafe(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private fun kotlinx.serialization.json.JsonElement?.longSafe(): Long? =
    (this as? JsonPrimitive)?.longOrNull

