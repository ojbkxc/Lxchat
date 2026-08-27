package com.lxseek.chat.channel

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


/**
 * [ReplyChannel] 的邮件实现——**通过 HTTP API 发送，不引入 JavaMail/Sun Mail 等重型库**，
 * 保持 APK 体积不变（~0KB 增量，仅复用已有 OkHttp）。
 *
 * 支持三种服务商（[provider]）：
 * - **resend**：POST `https://api.resend.com/emails`，Header `Authorization: Bearer <key>`，
 *   body `{"from": ..., "to": [...], "subject": ..., "text": ...}`
 * - **sendgrid**：POST `https://api.sendgrid.com/v3/mail/send`，Header `Authorization: Bearer <key>`，
 *   body `{"personalizations":[{"to":[{"email":...}]}],"from":{"email":...},"subject":...,"content":[{"type":"text/plain","value":...}]}`
 * - **mailgun**：POST `https://api.mailgun.net/v3/<domain>/messages`，Header `Authorization: Basic <base64(api:key)>`，
 *   body `{"from":..., "to":..., "subject":..., "text":...}`（Mailgun 也接受 JSON）
 *
 * [recipient] 是收件人邮箱地址；为空时用 [defaultTo] 兜底（通知回复场景里 recipient 往往是
 * 微信 userId 而非邮箱，此时必须靠 defaultTo 才能发出）。
 */
class EmailChannel(
    /** 服务商：resend / sendgrid / mailgun。 */
    private val provider: String,
    /** API key。 */
    private val apiKey: String,
    /** 发件人地址。 */
    private val from: String,
    /** Mailgun 域名（仅 mailgun 用）。 */
    private val mailgunDomain: String = "",
    /** 默认收件人（recipient 为空时兜底）。 */
    private val defaultTo: String = "",
) : ReplyChannel {

    override val id: String = ReplyChannelConfig.CHANNEL_EMAIL
    override val displayName: String = "Email"


    override fun isConfigured(): Boolean {
        if (apiKey.isBlank() || from.isBlank()) return false
        return when (provider) {
            "resend", "sendgrid" -> true
            "mailgun" -> mailgunDomain.isNotBlank()
            else -> false
        }
    }

    override suspend fun send(recipient: String, message: String): SendResult {
        if (!isConfigured()) return SendResult.Failure("邮件渠道未配置完整（provider/key/from 或 mailgun 域名缺失）")
        if (message.isBlank()) return SendResult.Failure("消息为空")
        val to = recipient.ifBlank { defaultTo }.trim()
        if (to.isBlank()) return SendResult.Failure("收件人地址为空且未设置默认收件人")
        return withContext(Dispatchers.IO) {
            try {
                when (provider) {
                    "resend" -> sendResend(to, message)
                    "sendgrid" -> sendSendgrid(to, message)
                    "mailgun" -> sendMailgun(to, message)
                    else -> SendResult.Failure("未知邮件服务商: $provider")
                }
            } catch (e: Exception) {
                DebugLog.e("ReplyChannel/Email", "send failed ($provider)", e)
                SendResult.Failure(e.message ?: "email send failed")
            }
        }
    }

    // ── Resend ───────────────────────────────────────────────
    private fun sendResend(to: String, message: String): SendResult {
        val url = "https://api.resend.com/emails"
        val body = buildJsonObject {
            put("from", from)
            put("to", buildJsonArray {
                add(buildJsonObject { put("email", JsonPrimitive(to)) })
            })
            put("subject", DEFAULT_SUBJECT)
            put("text", message)
        }.toString()
        return postJson(url, body, mapOf("Authorization" to "Bearer $apiKey"), to)
    }

    // ── SendGrid v3 ─────────────────────────────────────────
    private fun sendSendgrid(to: String, message: String): SendResult {
        val url = "https://api.sendgrid.com/v3/mail/send"
        val body = buildJsonObject {
            put("personalizations", buildJsonArray {
                add(buildJsonObject {
                    put("to", buildJsonArray {
                        add(buildJsonObject { put("email", JsonPrimitive(to)) })
                    })
                })
            })
            put("from", buildJsonObject { put("email", JsonPrimitive(from)) })
            put("subject", DEFAULT_SUBJECT)
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text/plain")
                    put("value", message)
                })
            })
        }.toString()
        return postJson(url, body, mapOf("Authorization" to "Bearer $apiKey"), to)
    }

    // ── Mailgun ─────────────────────────────────────────────
    private fun sendMailgun(to: String, message: String): SendResult {
        val url = "https://api.mailgun.net/v3/${mailgunDomain.trim()}/messages"
        // Mailgun 用 Basic auth：username="api"，password=apiKey。
        val basic = android.util.Base64.encodeToString(
            "api:$apiKey".toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        val body = buildJsonObject {
            put("from", from)
            put("to", to)
            put("subject", DEFAULT_SUBJECT)
            put("text", message)
        }.toString()
        return postJson(url, body, mapOf("Authorization" to "Basic $basic"), to)
    }

    /** 统一 POST + 处理响应。[to] 仅用于成功日志。 */
    private fun postJson(url: String, body: String, headers: Map<String, String>, to: String): SendResult {
        val resp = HttpClient.postTextResponse(url, body, headers)
        return if (resp.isSuccessful) {
            DebugLog.d("ReplyChannel/Email", "sent via $provider to $to")
            SendResult.Success
        } else {
            DebugLog.e("ReplyChannel/Email", "$provider failed http=${resp.code}: ${resp.body.take(300)}")
            SendResult.Failure("$provider send failed (HTTP ${resp.code})")
        }
    }

    private companion object {
        private const val DEFAULT_SUBJECT = "LxChat 自动回复"
    }
}