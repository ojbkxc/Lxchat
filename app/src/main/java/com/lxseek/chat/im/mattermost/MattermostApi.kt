package com.lxseek.chat.im.mattermost

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Mattermost REST API v4 客户端。
 *
 * 仅依赖 [HttpClient] 共享 OkHttp 实例。鉴权用 `Bearer <bot_token>`，
 * 所有 REST 路径前缀 `/api/v4/`。Mattermost 同时提供 WebSocket（`/api/v4/websocket`）
 * 用于实时事件推送，本类只封装 REST 部分，WebSocket 由 [MattermostChannel] 直接处理。
 *
 * 参照 AstrBot `astrbot/core/platform/sources/mattermost/client.py` 的接口路径与鉴权方式，
 * 适配到 Kotlin/OkHttp 风格，与 [com.lxseek.chat.im.discord.DiscordRestApi] 结构对齐。
 */
class MattermostApi(
    /** Mattermost 实例基址，如 `https://mattermost.example.com`。 */
    val baseUrl: String,
    /** Bot 个人访问令牌（Personal Access Token）或 Bot Account Token。 */
    val token: String,
) {
    init {
        require(baseUrl.isNotBlank()) { "Mattermost baseUrl 不能为空" }
        require(token.isNotBlank()) { "Mattermost token 不能为空" }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val base = baseUrl.trim().trimEnd('/')
    private val authHeaders = mapOf("Authorization" to "Bearer ${token.trim()}")

    /** GET /api/v4/users/me — 获取机器人自身信息（id / username）。 */
    suspend fun getMe(): JsonObject = get("users/me")

    /** GET /api/v4/channels/{channelId} — 获取频道信息。 */
    suspend fun getChannel(channelId: String): JsonObject = get("channels/$channelId")

    /** GET /api/v4/teams/{teamId} — 获取团队信息。 */
    suspend fun getTeam(teamId: String): JsonObject = get("teams/$teamId")

    /**
     * POST /api/v4/posts — 在指定频道发帖。
     * [rootId] 非空时作为线程回复（thread reply）。
     */
    suspend fun createPost(
        channelId: String,
        message: String,
        rootId: String? = null,
    ): JsonObject = post("posts", buildJsonObject {
        put("channel_id", channelId)
        put("message", message)
        if (rootId != null) put("root_id", rootId)
    })

    /** GET /api/v4/channels/{channelId}/posts — 拉取频道最近帖子（用于初始化/补漏）。 */
    suspend fun getChannelPosts(channelId: String, perPage: Int = 50): JsonObject =
        get("channels/$channelId/posts?per_page=$perPage")

    private suspend fun get(path: String): JsonObject = withContext(Dispatchers.IO) {
        val url = "$base/api/v4/$path"
        val response = HttpClient.getTextResponse(url, authHeaders)
        parseBody(response.body, "GET $path", response.code)
    }

    private suspend fun post(path: String, payload: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val url = "$base/api/v4/$path"
        val response = HttpClient.postTextResponse(url, payload.toString(), authHeaders)
        parseBody(response.body, "POST $path", response.code)
    }

    private fun parseBody(body: String, op: String, httpCode: Int): JsonObject {
        if (httpCode >= 400) {
            throw MattermostApiException("$op 失败 (HTTP $httpCode): ${body.take(200)}", httpCode)
        }
        return runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
    }

    companion object {
        fun isValidToken(value: String): Boolean = value.trim().isNotBlank()
        fun isValidBaseUrl(value: String): Boolean = value.trim().let { it.startsWith("http://") || it.startsWith("https://") }
    }
}

/** Mattermost API 异常。 */
class MattermostApiException(message: String, val httpCode: Int?) : Exception(message)