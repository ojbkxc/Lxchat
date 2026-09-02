package com.lxseek.chat.im.misskey

import com.lxseek.chat.im.ImApiException
import com.lxseek.chat.im.ImJson
import com.lxseek.chat.im.ImRestClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Misskey API 客户端。
 *
 * 仅依赖 [com.lxseek.chat.api.HttpClient] 共享的 OkHttp 实例。鉴权用 `Bearer <access_token>` 请求头，
 * 也可在 body 里带 `i` 字段（Misskey 双鉴权风格）。本客户端统一用请求头。
 *
 * Misskey 同时提供 WebSocket Streaming（`/streaming?i=<token>`）用于实时推送，
 * 但手机端长连接受限，本客户端只封装 REST polling 部分（notes/timeline、notes/create），
 * 实时由 [MisskeyChannel] 通过轮询 timeline 拉取。
 *
 * 参照 AstrBot `astrbot/core/platform/sources/misskey/misskey_api.py` 的接口路径与鉴权方式，
 * 适配到 Kotlin/OkHttp 风格。
 */
class MisskeyApi(
    /** Misskey 实例基址，如 `https://misskey.io`。 */
    val baseUrl: String,
    /** 用户访问令牌（Access Token），从设置 → API → 生成 Token。 */
    token: String,
) : ImRestClient(
    baseUrl = baseUrl,
    authHeaders = mapOf("Authorization" to "Bearer ${token.trim()}"),
    pathPrefix = "api",
    onError = { body, op, httpCode -> parseError(body, op, httpCode) },
) {
    init {
        require(baseUrl.isNotBlank()) { "Misskey baseUrl 不能为空" }
        require(token.isNotBlank()) { "Misskey token 不能为空" }
    }

    /** POST /api/i — 获取自身账号信息。 */
    suspend fun getI(): JsonObject = post("i", buildJsonObject {})

    /**
     * POST /api/notes/timeline — 拉取主页时间线（含关注者最新 note）。
     * [sinceId] / [untilId] 用于增量拉取；[limit] 上限 100。
     */
    suspend fun getTimeline(limit: Int = 30, sinceId: String? = null): JsonObject =
        post("notes/timeline", buildJsonObject {
            put("limit", limit)
            if (sinceId != null) put("sinceId", sinceId)
        })

    /** POST /api/notes/mentions — 拉取提及自己的 note（机器人常用入口）。 */
    suspend fun getMentions(limit: Int = 30, sinceId: String? = null): JsonObject =
        post("notes/mentions", buildJsonObject {
            put("limit", limit)
            if (sinceId != null) put("sinceId", sinceId)
        })

    /** POST /api/notes/show — 查询指定 note 详情。 */
    suspend fun showNote(noteId: String): JsonObject = post("notes/show", buildJsonObject {
        put("noteId", noteId)
    })

    /**
     * POST /api/notes/create — 创建 note（发帖）。
     * [replyId] 非空时作为回复；[visibility] 默认 public。
     */
    suspend fun createNote(
        text: String,
        replyId: String? = null,
        visibility: String = "public",
    ): JsonObject = post("notes/create", buildJsonObject {
        put("text", text)
        put("visibility", visibility)
        if (replyId != null) put("replyId", replyId)
    })

    companion object {
        /** Misskey 错误体：`{message|error}` 字段优先于通用消息。 */
        fun parseError(body: String, op: String, httpCode: Int): ImApiException? {
            val apiMsg = runCatching {
                ImJson.parseToJsonElement(body).jsonObject.let {
                    it["message"]?.jsonPrimitive?.contentOrNull ?: it["error"]?.jsonPrimitive?.contentOrNull
                }
            }.getOrNull()
            return ImApiException(apiMsg ?: "Misskey $op 失败 (HTTP $httpCode)", httpCode)
        }
    }
}
