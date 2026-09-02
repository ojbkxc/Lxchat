package com.lxseek.chat.im.line

import com.lxseek.chat.im.ImApiException
import com.lxseek.chat.im.isValidImToken
import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.MessageChannel
import com.lxseek.chat.util.DebugLog

/**
 * LINE 渠道：把 [LineApi]（LINE Messaging API）适配到 [MessageChannel]，
 * 让 Lxchat 的 [com.lxseek.chat.im.ImPollingReceiver] 和
 * [com.lxseek.chat.tool.ImToolProvider] 能像操作其他渠道一样向 LINE 联系人发消息。
 *
 * **发送模型** — 通过 `/v2/bot/message/push` 主动推送文本消息。
 * [sendMessage] 接收的 `conversationId` 是 LINE 的目标 ID（userId / groupId / roomId）。
 *
 * **接收模型** — LINE Messaging API 是 webhook-only：LINE 平台把入站事件 POST 到
 * 公网 HTTPS 端点，无法轮询拉取。手机 App 无法暴露公网 webhook，因此本渠道在手机端
 * **仅支持发送**：[listConversations] / [fetchMessages] 返回空。入站消息需由外部
 * webhook 转发器把 LINE 推送转交到 Lxchat 的本地接口（后续任务实现）。
 *
 * **配置** 复用 [ImGatewayConfig]（与任务约束对齐）：
 *  - `token`   ← Channel Access Token（LONG-TERM）
 *  - `botId`   ← Channel Secret（webhook 签名校验；手机端可空但建议填写）
 *  - `baseUrl` ← API 基址（空 = 官方 https://api.line.me）
 *
 * 参照 AstrBot `line_adapter.py` / `line_api.py` 与 Lxchat
 * [com.lxseek.chat.im.whatsapp.WhatsappChannel] 的 webhook-only 模板。
 */
class LineChannel(
    private val config: ImGatewayConfig,
) : MessageChannel {

    override val channelId: String get() = CHANNEL_ID
    override val displayName: String get() = "LINE"
    override val isConfigured: Boolean
        get() = config.enabled && isValidImToken(config.token)

    /** 懒构建；token 缺失时为 null，[isConfigured] 同步返回 false。 */
    private val api: LineApi? =
        if (isValidImToken(config.token)) {
            try {
                LineApi(
                    channelAccessToken = config.token.trim(),
                    channelSecret = config.botId.trim(),
                    baseUrl = config.baseUrl.takeIf { it.isNotBlank() } ?: LineApi.DEFAULT_BASE_URL,
                )
            } catch (e: IllegalArgumentException) {
                DebugLog.w("LineChannel", "skipping API init")
                null
            }
        } else null

    /**
     * 发送文本消息给 [conversationId]（LINE userId / groupId / roomId）。
     *
     * 文本超过 [LineApi.TEXT_MAX_CHARS]（5000）时按段发送，返回最后一段的发送结果。
     * LINE 不返回消息 ID（push 端点 200 即成功），故用 `"sent"` 占位。
     */
    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        val api = api ?: return ImSendResult.NotConfigured
        if (!isConfigured) return ImSendResult.NotConfigured
        val to = conversationId.trim()
        if (to.isEmpty()) return ImSendResult.Failure("conversationId 为空")
        val content = text.trim()
        if (content.isEmpty()) return ImSendResult.Failure("text 为空")
        return try {
            val segments = splitText(content)
            for (segment in segments) {
                api.pushText(to, segment)
            }
            ImSendResult.Success("sent")
        } catch (e: ImApiException) {
            DebugLog.e("LineChannel", "sendMessage 失败 (http=${e.httpCode})")
            ImSendResult.Failure(e.message ?: "line send failed")
        } catch (e: Exception) {
            DebugLog.e("LineChannel", "sendMessage 失败", e)
            ImSendResult.Failure(e.message ?: "line send failed")
        }
    }

    /** webhook-only，无法轮询会话列表；手机端不接收消息，返回空。 */
    override suspend fun listConversations(): List<ImConversation> = emptyList()

    /** webhook-only，无法轮询入站消息；手机端不接收消息，返回空。 */
    override suspend fun fetchMessages(conversationId: String, afterId: String?): List<ImMessage> = emptyList()

    /** 把长文本切成 ≤ [LineApi.TEXT_MAX_CHARS] 的段，优先在换行边界切分。 */
    internal fun splitText(text: String): List<String> {
        if (text.length <= LineApi.TEXT_MAX_CHARS) return listOf(text)
        val result = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + LineApi.TEXT_MAX_CHARS, text.length)
            if (end == text.length) {
                result.add(text.substring(start))
                break
            }
            val window = text.substring(start, end)
            val newline = window.lastIndexOf('\n')
            val cut = if (newline > 0) newline + 1 else LineApi.TEXT_MAX_CHARS
            result.add(window.substring(0, cut).trimEnd())
            start += cut
        }
        return result
    }

    companion object {
        /** 平台标识，存入 [ImGatewayConfig.platform]。 */
        const val PLATFORM = "line"
        private const val CHANNEL_ID = "line"
    }
}