package com.lxseek.chat.im.kook

import com.lxseek.chat.im.ImApiException
import com.lxseek.chat.im.ImJson
import com.lxseek.chat.im.ImRestClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * KOOK Bot REST API 客户端（v3）。
 *
 * 仅依赖 [com.lxseek.chat.api.HttpClient] 共享的 OkHttp 实例，无外部 SDK。Bot token 来自 KOOK 开放平台，
 * 请求头以 `Bot <token>` 形式鉴权，与 Discord 一致。所有响应形如
 * `{ "code": 0, "data": ..., "message": "" }`，code != 0 视为失败。
 *
 * 参照 AstrBot `astrbot/core/platform/sources/kook/kook_client.py` 的接口路径与鉴权方式，
 * 适配到 Kotlin/OkHttp 风格，与 [com.lxseek.chat.im.discord.DiscordRestApi] 结构对齐。
 */
class KookApi(
    /** KOOK Bot Token，开放平台 → 应用 → 机器人 → 获取 Token。 */
    token: String,
    /** REST 基址，默认官方地址；可被测试或代理覆盖。 */
    baseUrl: String = DEFAULT_BASE_URL,
) : ImRestClient(
    baseUrl = baseUrl,
    authHeaders = mapOf("Authorization" to "Bot ${token.trim()}"),
    onError = { body, op, httpCode -> parseError(body, op, httpCode) },
) {
    init {
        require(token.isNotBlank()) { "KOOK token 不能为空" }
    }

    /** GET /api/v3/users/me — 获取机器人自身信息（id / username / nickname）。 */
    suspend fun getMe(): JsonObject = get("users/me")

    /** GET /api/v3/gateway/index — 获取 WebSocket 网关地址。 */
    suspend fun getGatewayIndex(): JsonObject = get("gateway/index")

    /**
     * POST /api/v3/message/create — 发送频道消息。
     * [targetId] 为频道 channel_id；返回新建消息的 data 对象。
     */
    suspend fun createChannelMessage(targetId: String, content: String, quote: String? = null): JsonObject =
        post("message/create", buildJsonObject {
            put("target_id", targetId)
            put("content", content)
            put("type", KOOK_MSG_TYPE_TEXT)
            if (quote != null) put("quote", quote)
        })

    /**
     * POST /api/v3/direct-message/create — 发送私聊消息。
     * [targetId] 为目标用户 user_id（code 阶段 target_id）。
     */
    suspend fun createDirectMessage(targetId: String, content: String, quote: String? = null): JsonObject =
        post("direct-message/create", buildJsonObject {
            put("target_id", targetId)
            put("content", content)
            put("type", KOOK_MSG_TYPE_TEXT)
            if (quote != null) put("quote", quote)
        })

    companion object {
        /** 解析 KOOK 统一响应包：`{code, data, message}`，code != 0 抛 [ImApiException]。 */
        fun parseError(body: String, op: String, httpCode: Int): ImApiException? {
            val root = runCatching { ImJson.parseToJsonElement(body).jsonObject }.getOrNull()
            val code = root?.get("code")?.jsonPrimitive?.intOrNull ?: return ImApiException("$op 失败 (HTTP $httpCode)", httpCode)
            if (code == 0) return null // HTTP 失败但业务码为 0：按成功处理。
            val msg = root["message"]?.jsonPrimitive?.contentOrNull
            return ImApiException("$op 失败: code=$code message=${msg ?: ""}", httpCode, code)
        }

        /** KOOK 官方 REST 基址。 */
        const val DEFAULT_BASE_URL = "https://www.kookapp.cn/api/v3"

        /** KOOK 默认 WebSocket 网关（实际应通过 /gateway/index 动态获取）。 */
        const val DEFAULT_GATEWAY_URL = "wss://kook.kaiheila.com/api/v3/gateway/websocket"

        /** 文本消息类型（KOOK type=1 表示 KMarkdown，type=9 表示纯文本）。 */
        const val KOOK_MSG_TYPE_TEXT = 9
    }
}
