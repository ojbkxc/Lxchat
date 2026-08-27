package com.lxseek.chat.channel

import com.lxseek.chat.im.telegram.TelegramApiException
import com.lxseek.chat.im.telegram.TelegramBotApi
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ReplyChannel] 的 Telegram 实现：把 AI 回复通过 Telegram Bot API `sendMessage` 推送到指定 chat。
 *
 * 复用 [TelegramBotApi]（与 [com.lxseek.chat.im.telegram.TelegramChannel] 共享同一 HTTP 客户端与
 * token 校验逻辑），避免重复实现。本类只负责「发」：不轮询、不维护会话状态，是纯单向出口。
 *
 * [recipient] 是 Telegram chat_id（数字字符串，私聊为用户 id，群为负数 id）。
 * 长消息按 4000 字符切块逐条发送（Telegram 单条上限 4096，留 headroom）。
 */
class TelegramChannel(
    private val token: String,
    private val baseUrl: String = "",
) : ReplyChannel {

    override val id: String = ReplyChannelConfig.CHANNEL_TELEGRAM
    override val displayName: String = "Telegram"

    /** token 合法才构建 client，否则 [isConfigured] 保持 false，send 直接返回 NotConfigured。 */
    private val client: TelegramBotApi? =
        if (TelegramBotApi.isValidTelegramToken(token)) {
            TelegramBotApi(
                token = token,
                baseUrl = baseUrl.takeIf { it.isNotBlank() } ?: TelegramBotApi.DEFAULT_BASE_URL,
            )
        } else null

    override fun isConfigured(): Boolean = client != null && TelegramBotApi.isValidTelegramToken(token)

    override suspend fun send(recipient: String, message: String): SendResult {
        val api = client ?: return SendResult.Failure("Telegram bot token 未配置或格式非法")
        if (message.isBlank()) return SendResult.Failure("消息为空")
        val chatId = recipient.toLongOrNull()
            ?: return SendResult.Failure("Telegram chat_id 必须是数字: $recipient")
        return withContext(Dispatchers.IO) {
            try {
                // 与 im/telegram/TelegramChannel 一致的切块策略：4000 字符，留 headroom。
                val chunks = splitMessageText(message, MAX_MESSAGE_LENGTH)
                if (chunks.isEmpty()) {
                    return@withContext SendResult.Success
                }
                for (chunk in chunks) {
                    api.sendMessage(chatId, chunk)
                }
                DebugLog.d("ReplyChannel/TG", "sent ${chunks.size} chunk(s) to chat $chatId")
                SendResult.Success
            } catch (e: TelegramApiException) {
                DebugLog.e("ReplyChannel/TG", "sendMessage failed: ${e.message} (code=${e.errorCode})")
                SendResult.Failure(e.message ?: "telegram send failed")
            } catch (e: Exception) {
                DebugLog.e("ReplyChannel/TG", "sendMessage failed", e)
                SendResult.Failure(e.message ?: "telegram send failed")
            }
        }
    }

    /** 与 [com.lxseek.chat.im.telegram.TelegramChannel.splitMessageText] 同逻辑，保持一致体验。 */
    private fun splitMessageText(value: String, limit: Int): List<String> {
        val text = value.trim()
        if (text.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        var remaining = text
        while (remaining.length > limit) {
            var cut = remaining.lastIndexOf('\n', limit)
            if (cut < limit * 0.55) cut = remaining.lastIndexOf(' ', limit)
            if (cut < limit * 0.55) cut = limit
            chunks.add(remaining.substring(0, cut).trim())
            remaining = remaining.substring(cut).trimStart()
        }
        if (remaining.isNotEmpty()) chunks.add(remaining)
        return chunks
    }

    private companion object {
        private const val MAX_MESSAGE_LENGTH = 4000
    }
}