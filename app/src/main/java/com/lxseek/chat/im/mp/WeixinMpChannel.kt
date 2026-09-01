package com.lxseek.chat.im.mp

import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.MessageChannel
import com.lxseek.chat.util.DebugLog

/**
 * 微信公众号渠道：把 [WeixinMpApi]（腾讯官方 cgi-bin）适配到 [MessageChannel]，
 * 让 Lxchat 的 [com.lxseek.chat.im.ImPollingReceiver] 和
 * [com.lxseek.chat.tool.ImToolProvider] 能像操作其他渠道一样向公众号粉丝发消息。
 *
 * **发送模型** — 通过 `/cgi-bin/message/custom/send` 发送客服消息。
 * [sendMessage] 接收的 `conversationId` 是粉丝的 OpenID。
 * 客服消息要求用户 48 小时内与公众号有交互，超时发送会收到 errcode=45015，
 * 此时调用方应改用模板消息（后续任务实现）。
 *
 * **接收模型** — 公众号是 webhook-only：腾讯服务器把用户消息/事件 POST 到
 * 公网 HTTPS 端点（需在公众号后台配置回调 URL），无法轮询拉取。手机 App 无法
 * 暴露公网 webhook，因此本渠道在手机端 **仅支持发送**：
 * [listConversations] / [fetchMessages] 返回空。入站消息需由外部 webhook 转发器
 * 把腾讯推送（XML 格式）转交到 Lxchat 的本地接口（后续任务实现）。
 *
 * **配置** 复用 [ImGatewayConfig]（与任务约束对齐）：
 *  - `token`   ← AppID
 *  - `botId`   ← AppSecret
 *  - `baseUrl` ← API 基址（空 = 官方 https://api.weixin.qq.com/cgi-bin）
 *
 * 参照 AstrBot `weixin_offacc_adapter.py` 与 Lxchat
 * [com.lxseek.chat.im.whatsapp.WhatsappChannel] 的 webhook-only 模板。
 */
class WeixinMpChannel(
    private val config: ImGatewayConfig,
) : MessageChannel {

    override val channelId: String get() = CHANNEL_ID
    override val displayName: String get() = "公众号"
    override val isConfigured: Boolean
        get() = config.enabled &&
            WeixinMpApi.isValidAppId(config.token) &&
            WeixinMpApi.isValidAppSecret(config.botId)

    /** 懒构建；配置不全时为 null，[isConfigured] 同步返回 false。 */
    private val api: WeixinMpApi? =
        if (WeixinMpApi.isValidAppId(config.token) && WeixinMpApi.isValidAppSecret(config.botId)) {
            try {
                WeixinMpApi(
                    appId = config.token.trim(),
                    appSecret = config.botId.trim(),
                    baseUrl = config.baseUrl.takeIf { it.isNotBlank() } ?: WeixinMpApi.DEFAULT_BASE_URL,
                )
            } catch (e: IllegalArgumentException) {
                DebugLog.w("WeixinMpChannel", "skipping API init")
                null
            }
        } else null

    /**
     * 发送客服文本消息给 [conversationId]（粉丝 OpenID）。
     *
     * 文本超过 [WeixinMpApi.TEXT_MAX_CHARS]（2048）时按段发送。客服消息要求
     * 用户 48 小时内与公众号有交互；超时返回 errcode=45015，本方法将其透传为
     * [ImSendResult.Failure]，由调用方决定是否改用模板消息。
     */
    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        val api = api ?: return ImSendResult.NotConfigured
        if (!isConfigured) return ImSendResult.NotConfigured
        val toUser = conversationId.trim()
        if (toUser.isEmpty()) return ImSendResult.Failure("conversationId (OpenID) 为空")
        val content = text.trim()
        if (content.isEmpty()) return ImSendResult.Failure("text 为空")
        return try {
            val segments = splitText(content)
            for (segment in segments) {
                api.sendCustomText(toUser, segment)
            }
            ImSendResult.Success("sent")
        } catch (e: WeixinMpApiException) {
            DebugLog.e("WeixinMpChannel", "sendMessage 失败 (http=${e.httpCode})")
            ImSendResult.Failure(e.message ?: "weixin-mp send failed")
        } catch (e: Exception) {
            DebugLog.e("WeixinMpChannel", "sendMessage 失败", e)
            ImSendResult.Failure(e.message ?: "weixin-mp send failed")
        }
    }

    /** webhook-only，无法轮询会话列表；手机端不接收消息，返回空。 */
    override suspend fun listConversations(): List<ImConversation> = emptyList()

    /** webhook-only，无法轮询入站消息；手机端不接收消息，返回空。 */
    override suspend fun fetchMessages(conversationId: String, afterId: String?): List<ImMessage> = emptyList()

    /** 把长文本切成 ≤ [WeixinMpApi.TEXT_MAX_CHARS] 的段，优先在换行边界切分。 */
    internal fun splitText(text: String): List<String> {
        if (text.length <= WeixinMpApi.TEXT_MAX_CHARS) return listOf(text)
        val result = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + WeixinMpApi.TEXT_MAX_CHARS, text.length)
            if (end == text.length) {
                result.add(text.substring(start))
                break
            }
            val window = text.substring(start, end)
            val newline = window.lastIndexOf('\n')
            val cut = if (newline > 0) newline + 1 else WeixinMpApi.TEXT_MAX_CHARS
            result.add(window.substring(0, cut).trimEnd())
            start += cut
        }
        return result
    }

    companion object {
        /** 平台标识，存入 [ImGatewayConfig.platform]。 */
        const val PLATFORM = "mp"
        private const val CHANNEL_ID = "mp"
    }
}