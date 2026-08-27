package com.lxseek.chat.channel

import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * [ReplyChannel] 的邮件实现——**通过手写 SMTP 客户端直连邮箱服务器发送，不引入
 * JavaMail/Sun Mail 等重型库**，APK 体积零增量（仅复用 Android 内置 SSLSocket）。
 *
 * 用法与 HTTP API 方案的区别：不需要向 Resend/SendGrid/Mailgun 申请 key，只需一个
 * 普通邮箱（QQ/163/Gmail 等）+ 客户端生成的 SMTP 授权码，配置在设置页填写。
 *
 * 可靠性：发送失败会按 [retries] 自动重试（指数退避），全部失败才返回
 * [SendResult.Failure]，避免瞬时网络抖动导致回复丢失。
 */
class EmailChannel(
    /** 发件邮箱（即 SMTP 登录用户名）。 */
    private val from: String,
    /** SMTP 授权码。 */
    private val password: String,
    /** SMTP 服务器地址。 */
    private val host: String,
    /** SMTP 端口。 */
    private val port: Int = 465,
    /** 加密方式。 */
    private val security: SmtpSender.Security = SmtpSender.Security.SSL,
    /** 默认收件人（recipient 为空时兜底）。 */
    private val defaultTo: String = "",
    /** 失败重试次数。 */
    private val retries: Int = DEFAULT_RETRIES,
) : ReplyChannel {

    override val id: String = ReplyChannelConfig.CHANNEL_EMAIL
    override val displayName: String = "Email"

    override fun isConfigured(): Boolean =
        from.isNotBlank() && password.isNotBlank() && host.isNotBlank()

    override suspend fun send(recipient: String, message: String): SendResult {
        if (!isConfigured()) return SendResult.Failure("邮件渠道未配置完整（发件邮箱 / SMTP 服务器 / 授权码缺失）")
        if (message.isBlank()) return SendResult.Failure("消息为空")
        val to = recipient.ifBlank { defaultTo }.trim()
        if (to.isBlank()) return SendResult.Failure("收件人地址为空且未设置默认收件人")

        val sender = SmtpSender(host, port, security, from, password)
        var lastError: String? = null
        return withContext(Dispatchers.IO) {
            repeat(retries.coerceAtLeast(1)) { attempt ->
                try {
                    sender.send(to, DEFAULT_SUBJECT, message)
                    DebugLog.d("ReplyChannel/Email", "sent to $to (attempt ${attempt + 1})")
                    return@withContext SendResult.Success
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    if (attempt < retries - 1) {
                        delay(500L * (attempt + 1))
                    }
                }
            }
            DebugLog.e("ReplyChannel/Email", "send failed after $retries attempts: $lastError")
            SendResult.Failure(lastError ?: "email send failed")
        }
    }

    companion object {
        private const val DEFAULT_SUBJECT = "LxChat 自动回复"
        private const val DEFAULT_RETRIES = 3
    }
}
