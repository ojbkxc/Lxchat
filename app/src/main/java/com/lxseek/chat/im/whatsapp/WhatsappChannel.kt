package com.lxseek.chat.im.whatsapp

import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImMessage

import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.MessageChannel
import com.lxseek.chat.util.DebugLog

/**
 * WhatsApp 渠道：把 [WhatsappCloudApi]（Meta WhatsApp Business Cloud API）适配到
 * [MessageChannel]，让 Lxchat 的 [com.lxseek.chat.im.ImPollingReceiver] 和
 * [com.lxseek.chat.tool.ImToolProvider] 能像操作其他渠道一样向 WhatsApp 联系人发消息。
 *
 * **发送模型** — 通过 Graph API `/messages` 端点主动发送文本/图片/模板消息。
 * [sendMessage] 接收的 `conversationId` 是 WhatsApp 联系人的 E.164 电话号码（不带 `+`）。
 *
 * **接收模型** — WhatsApp Cloud API 是 webhook-only：Meta 服务器把入站消息 POST 到一个
 * 公网 HTTPS 端点，无法轮询拉取。手机 App 无法暴露公网 webhook，因此本渠道在手机端
 * **仅支持发送**：[listConversations] / [fetchMessages] 返回空。入站消息需由外部 webhook
 * 转发器把 Meta 推送转交到 Lxchat 的本地接口（后续任务实现），或在桌面/服务器部署时
 * 直接接收。这与任务约束一致——文档中说明 WhatsApp 渠道在手机上仅支持发送。
 *
 * **24 小时窗口** — WhatsApp 规定商家只能在用户最近一次互动的 24 小时内主动发送
 * 非模板消息；超时后 [sendMessage] 会收到 Meta 错误码 470（re-engagement message），
 * 此时调用方应改用 [WhatsappCloudApi.sendTemplate] 发送预审批模板。本渠道的
 * [sendMessage] 不自动降级到模板（模板名需业务方提供），仅把 470 错误透传为
 * [ImSendResult.Failure]，由上层决策。
 *
 * **配置** 复用 [ImGatewayConfig]（与任务约束对齐）：
 *  - `botId`   ← Phone Number ID（WhatsApp Business 电话号码 ID）
 *  - `token`   ← Access Token（Meta 系统用户访问令牌）
 *  - `baseUrl` ← Verify Token（Webhook 订阅验证 token；手机端可空）
 *
 * 协议参考：Meta WhatsApp Business Cloud API 文档
 *  - https://developers.facebook.com/docs/whatsapp/cloud-api/get-started
 *  - https://developers.facebook.com/docs/whatsapp/cloud-api/reference/messages
 *
 * 与 dsh-im 的 WhatsApp 渠道（基于 @whiskeysockets/baileys 的 WhatsApp Web 多设备协议）
 * 不同：dsh-im 走非官方 Web 协议可双向收发但需扫码绑定；本实现走官方 Cloud API，
 * 仅发送但无需扫码、合规稳定，适合手机端 Lxchat 主动通知场景。
 */
class WhatsappChannel(
    private val config: ImGatewayConfig,
) : MessageChannel {

    override val channelId: String get() = CHANNEL_ID
    override val displayName: String get() = "WhatsApp"
    override val isConfigured: Boolean
        get() = config.enabled &&
            config.botId.isNotBlank() &&
            config.token.isNotBlank()

    /** 懒构建；null 当配置不完整时，[isConfigured] 同步返回 false。 */
    private val api: WhatsappCloudApi? =
        if (config.botId.isNotBlank() && config.token.isNotBlank()) {
            try {
                WhatsappCloudApi(
                    phoneNumberId = config.botId,
                    accessToken = config.token,
                    verifyToken = config.baseUrl,
                )
            } catch (e: IllegalArgumentException) {
                DebugLog.w("WhatsappChannel", "skipping API init")
                null
            }
        } else null

    // ── MessageChannel ──────────────────────────────────────────────────

    /**
     * 发送文本消息给 [conversationId]（E.164 电话号码，不带 `+`）。
     *
     * 文本超过 [WhatsappCloudApi.TEXT_MAX_CHARS]（4096）时按段发送，返回最后一段的
     * Meta 消息 ID（`wamid.*`）。分段在换行/句末边界优先切分，避免割裂句子。
     *
     * 24 小时窗口外发送会收到 Meta 错误码 470，本方法将其透传为 [ImSendResult.Failure]，
     * 由调用方决定是否改用模板消息（[WhatsappCloudApi.sendTemplate]）。
     */
    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        val api = api ?: return ImSendResult.NotConfigured
        if (!isConfigured) return ImSendResult.NotConfigured
        val to = conversationId.trim()
        if (to.isEmpty()) return ImSendResult.Failure("conversationId (phone number) is empty")
        val content = text.trim()
        if (content.isEmpty()) return ImSendResult.Failure("text is empty")
        return try {
            val segments = splitText(content)
            var lastId = ""
            for (segment in segments) {
                lastId = api.sendText(to, segment)
            }
            ImSendResult.Success(lastId)
        } catch (e: WhatsappApiException) {
            DebugLog.e(
                "WhatsappChannel",
                "sendMessage failed (code=${e.errorCode}, http=${e.httpStatus})",
            )
            ImSendResult.Failure(e.message ?: "whatsapp send failed")
        } catch (e: Exception) {
            DebugLog.e("WhatsappChannel", "sendMessage failed", e)
            ImSendResult.Failure(e.message ?: "whatsapp send failed")
        }
    }

    /**
     * WhatsApp Cloud API 是 webhook-only，无法轮询会话列表。手机端不接收消息，
     * 始终返回空。会话由发送动作隐式建立（用户首次发消息给商家时由 Meta webhook 推送，
     * 但本渠道不消费 webhook）。
     */
    override suspend fun listConversations(): List<ImConversation> = emptyList()

    /**
     * WhatsApp Cloud API 是 webhook-only，无法轮询入站消息。手机端不接收消息，
     * 始终返回空。入站消息需由外部 webhook 转发器投递到 Lxchat 本地接口。
     */
    override suspend fun fetchMessages(conversationId: String, afterId: String?): List<ImMessage> =
        emptyList()

    // ── 辅助 ──────────────────────────────────────────────────────────────

    /**
     * 把长文本切成 ≤ [WhatsappCloudApi.TEXT_MAX_CHARS] 的段，优先在换行/句末边界切分。
     *
     * 与 [com.lxseek.chat.im.MultiSegmentMessageSender.split] 的切分策略一致，但阈值用
     * WhatsApp 的 4096 字符上限（而非 IM 通用的 1800），减少分段次数。空输入返回单条
     * 空串以让 [sendMessage] 的 trim 检查兜底（实际不会到达这里）。
     */
    internal fun splitText(text: String): List<String> {
        if (text.length <= WhatsappCloudApi.TEXT_MAX_CHARS) return listOf(text)
        val result = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + WhatsappCloudApi.TEXT_MAX_CHARS, text.length)
            if (end == text.length) {
                result.add(text.substring(start))
                break
            }
            val window = text.substring(start, end)
            val breakAt = lastBreakIndex(window)
            val cut = if (breakAt > 0) breakAt else WhatsappCloudApi.TEXT_MAX_CHARS
            result.add(window.substring(0, cut).trimEnd())
            start += cut
        }
        return result
    }

    /** 返回 [s] 中最佳自然断点（exclusive index），无则 -1。与 MultiSegmentMessageSender 一致。 */
    private fun lastBreakIndex(s: String): Int {
        val newline = s.lastIndexOf('\n')
        if (newline > 0) return newline + 1
        for (ender in SENTENCE_ENDERS) {
            val idx = s.lastIndexOf(ender)
            if (idx > 0) return idx + 1
        }
        return -1
    }

    companion object {
        /** 平台标识，存入 [ImGatewayConfig.platform]。 */
        const val PLATFORM = "whatsapp"

        private const val CHANNEL_ID = "whatsapp"

        // 句末标点（与 MultiSegmentMessageSender 一致，中英兼顾）
        private val SENTENCE_ENDERS = charArrayOf(
            '。', '！', '？', '！', '…',
            '.', '!', '?',
            '；', ';',
            '）', ')',
        )
    }
}