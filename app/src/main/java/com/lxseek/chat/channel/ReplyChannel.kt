package com.lxseek.chat.channel

/**
 * 统一回复渠道接口。让 AI 回复可通过多种方式发送（微信、Telegram、邮件、Bark 等）。
 *
 * 与 [com.lxseek.chat.im.MessageChannel] 的区别：
 * - [com.lxseek.chat.im.MessageChannel] 是双向 IM 渠道（可收可发，有会话/轮询），用于 ImPollingReceiver。
 * - [ReplyChannel] 是单向「只发」渠道，仅把已生成好的 AI 回复推送到一个目的地（chat_id / device / 邮箱地址），
 *   不需要轮询、不需要会话状态。供 [com.lxseek.chat.notification.NotificationAutoReplyService] 在微信回复之外
 *   额外转发（例如同时把回复推到 Telegram、Bark、邮箱）。
 *
 * [recipient] 的语义由具体实现解释：
 * - Telegram：chat_id（数字字符串）
 * - Bark：留空（device key 已在配置里），或覆盖标题
 * - Email：收件人邮箱地址
 */
interface ReplyChannel {
    /** 渠道唯一标识（与 [ReplyChannelConfig.additionalChannels] 里的 id 对应）。 */
    val id: String
    /** 渠道显示名称（用于日志与设置页）。 */
    val displayName: String
    /** 是否已配置可用（token/key 等齐全且开关打开）。 */
    fun isConfigured(): Boolean
    /**
     * 发送消息。返回 [SendResult]。
     * @param recipient 目的地（语义由实现解释，见上）
     * @param message 已生成好的回复文本
     */
    suspend fun send(recipient: String, message: String): SendResult
}

/** 渠道发送结果。 */
sealed class SendResult {
    /** 发送成功。 */
    object Success : SendResult()
    /** 发送失败，[reason] 为人可读的原因（用于日志与 UI 提示）。 */
    data class Failure(val reason: String) : SendResult()
}