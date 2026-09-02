package com.lxseek.chat.im.line

import com.lxseek.chat.im.ImApiException
import com.lxseek.chat.im.ImRestClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * LINE Messaging API 客户端。
 *
 * 仅依赖 [com.lxseek.chat.api.HttpClient] 共享的 OkHttp 实例。鉴权用 `Bearer <channel_access_token>`，
 * 签名校验用 <channel_secret> 做 HMAC-SHA256（webhook 入站时校验 X-Line-Signature）。
 *
 * 参照 AstrBot `astrbot/core/platform/sources/line/line_api.py` 的接口路径与鉴权方式，
 * 适配到 Kotlin/OkHttp 风格。LINE 在手机端无法暴露公网 webhook，因此本客户端只实现
 * 主动发送（push/reply），入站消息需外部 webhook 转发器投递到 Lxchat 本地接口。
 */
class LineApi(
    /** Channel Access Token（LONG-TERM），从 LINE Developers Console 颁发。 */
    val channelAccessToken: String,
    /** Channel Secret，用于 webhook 签名校验。 */
    val channelSecret: String,
    /** API 基址，默认官方；可被代理或自托管覆盖。 */
    baseUrl: String = DEFAULT_BASE_URL,
) : ImRestClient(
    baseUrl = baseUrl,
    authHeaders = mapOf("Authorization" to "Bearer ${channelAccessToken.trim()}"),
    onError = { _, op, httpCode -> ImApiException("LINE $op 失败 (HTTP $httpCode)", httpCode) },
) {
    init {
        require(channelAccessToken.isNotBlank()) { "LINE channel_access_token 不能为空" }
    }

    /**
     * POST /v2/bot/message/push — 主动推送消息给指定用户/群组/房间。
     * [to] 是 LINE 的目标 ID（userId / groupId / roomId）。
     */
    suspend fun pushText(to: String, text: String): JsonObject =
        post("v2/bot/message/push", buildJsonObject {
            put("to", to)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
        })

    /**
     * POST /v2/bot/message/reply — 用 replyToken 回复 webhook 事件。
     * replyToken 有效期约 1 分钟，仅 webhook 路径可用；手机端不消费 webhook，保留备用。
     */
    suspend fun replyText(replyToken: String, text: String): JsonObject =
        post("v2/bot/message/reply", buildJsonObject {
            put("replyToken", replyToken)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
        })

    /** GET /v2/bot/info — 获取机器人基本信息（userId / displayName）。 */
    suspend fun getBotInfo(): JsonObject = get("v2/bot/info")

    /**
     * 校验 webhook 签名：HMAC-SHA256(channelSecret, rawBody) → base64，与 X-Line-Signature 比较。
     * 手机端不消费 webhook，保留供外部转发器或桌面端使用。
     */
    fun verifySignature(rawBody: ByteArray, signature: String?): Boolean {
        if (signature.isNullOrBlank()) return false
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(channelSecret.toByteArray(), "HmacSHA256"))
        val expected = java.util.Base64.getEncoder().encodeToString(mac.doFinal(rawBody))
        return java.security.MessageDigest.isEqual(expected.toByteArray(), signature.trim().toByteArray())
    }

    companion object {
        /** LINE 官方 API 基址。 */
        const val DEFAULT_BASE_URL = "https://api.line.me"

        /** 单条文本消息最大长度（LINE 限制）。 */
        const val TEXT_MAX_CHARS = 5000
    }
}
