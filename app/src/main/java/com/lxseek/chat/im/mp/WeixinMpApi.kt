package com.lxseek.chat.im.mp

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.im.ImApiException
import com.lxseek.chat.im.ImJson
import com.lxseek.chat.im.ImRestClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 微信公众号 API 客户端（腾讯官方 cgi-bin）。
 *
 * 仅依赖 [HttpClient] 共享 OkHttp 实例。鉴权流程：用 app_id + app_secret 换
 * access_token（GET /cgi-bin/token），再带 access_token 调业务接口。
 * access_token 有效期 2 小时，本客户端懒获取并缓存，过期时按错误码 40001/42001 重新获取。
 *
 * 参照 AstrBot `weixin_offacc_adapter.py` 的接口路径与鉴权方式，
 * 适配到 Kotlin/OkHttp 风格。公众号在手机端无法暴露公网 webhook，因此本客户端
 * 只实现主动发送（客服消息 /template_send），入站消息需外部 webhook 转发器投递。
 */
class WeixinMpApi(
    /** 公众号 AppID。 */
    val appId: String,
    /** 公众号 AppSecret。 */
    val appSecret: String,
    /** API 基址，默认官方；可被代理或自托管覆盖。 */
    baseUrl: String = DEFAULT_BASE_URL,
) : ImRestClient(
    baseUrl = baseUrl,
    onError = ::parseError,
) {
    init {
        require(appId.isNotBlank()) { "公众号 app_id 不能为空" }
        require(appSecret.isNotBlank()) { "公众号 app_secret 不能为空" }
    }

    @Volatile private var cachedAccessToken: String? = null
    @Volatile private var tokenExpiresAtMs: Long = 0L

    /**
     * GET /cgi-bin/token — 用 app_id + app_secret 换 access_token。
     * 缓存到 [cachedAccessToken]，提前 [TOKEN_REFRESH_LEAD_MS] 刷新。
     */
    suspend fun getAccessToken(forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedAccessToken != null && now < tokenExpiresAtMs) {
            return cachedAccessToken!!
        }
        return withContext(Dispatchers.IO) {
            val url = "$base/token?grant_type=client_credential&appid=${appId.trim()}&secret=${appSecret.trim()}"
            val response = HttpClient.getTextResponse(url)
            val root = runCatching { ImJson.parseToJsonElement(response.body).jsonObject }.getOrNull()
                ?: throw ImApiException("token 接口返回非法 JSON", response.code)
            val token = root["access_token"]?.jsonPrimitive?.contentOrNull
                ?: throw ImApiException(
                    root["errmsg"]?.jsonPrimitive?.contentOrNull ?: "token 接口未返回 access_token",
                    response.code,
                )
            val expiresIn = root["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 7200L
            cachedAccessToken = token
            tokenExpiresAtMs = now + (expiresIn * 1000L) - TOKEN_REFRESH_LEAD_MS
            token
        }
    }

    /**
     * POST /cgi-bin/message/custom/send — 发送客服消息（文本）。
     * [toUser] 是 OpenID；要求用户 48 小时内与公众号有交互。
     */
    suspend fun sendCustomText(toUser: String, text: String): JsonObject {
        val token = getAccessToken()
        return post("message/custom/send?access_token=$token", buildJsonObject {
            put("touser", toUser)
            put("msgtype", "text")
            putJsonObject("text") { put("content", text) }
        })
    }

    /** GET /cgi-bin/user/info — 获取用户基本信息（昵称等）。 */
    suspend fun getUserInfo(openId: String): JsonObject {
        val token = getAccessToken()
        return get("user/info?access_token=$token&openid=$openId&lang=zh_CN")
    }

    /** 解析公众号响应包：HTTP 层先于业务 errcode 检查，errcode != 0 抛 [ImApiException]。 */
    private fun parseError(body: String, op: String, httpCode: Int): ImApiException? {
        val root = runCatching { ImJson.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ImApiException("$op 返回非法 JSON (HTTP $httpCode)", httpCode)
        val errcode = root["errcode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        // access_token 过期或失效：清缓存，下次调用会重新获取。
        if (errcode == 40001 || errcode == 42001 || errcode == 40014) {
            cachedAccessToken = null
            tokenExpiresAtMs = 0L
        }
        if (errcode != null && errcode != 0) {
            val errmsg = root["errmsg"]?.jsonPrimitive?.contentOrNull
            return ImApiException("$op 失败: errcode=$errcode errmsg=${errmsg ?: ""}", httpCode, errcode)
        }
        return null
    }

    companion object {
        /** 微信公众号官方 API 基址。 */
        const val DEFAULT_BASE_URL = "https://api.weixin.qq.com/cgi-bin"

        /** access_token 提前刷新量（5 分钟），避免边界过期。 */
        private const val TOKEN_REFRESH_LEAD_MS = 5 * 60 * 1000L

        /** 单条客服消息文本上限（公众号限制）。 */
        const val TEXT_MAX_CHARS = 2048

        fun isValidAppId(value: String): Boolean = value.trim().isNotBlank()
        fun isValidAppSecret(value: String): Boolean = value.trim().isNotBlank()
    }
}
