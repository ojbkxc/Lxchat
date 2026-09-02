package com.lxseek.chat.im.mattermost

import com.lxseek.chat.im.ImApiException
import com.lxseek.chat.im.ImRestClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mattermost REST API v4 客户端。
 *
 * 仅依赖 [com.lxseek.chat.api.HttpClient] 共享的 OkHttp 实例。鉴权用 `Bearer <bot_token>`，
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
    token: String,
) : ImRestClient(
    baseUrl = baseUrl,
    authHeaders = mapOf("Authorization" to "Bearer ${token.trim()}"),
    pathPrefix = "api/v4",
    onError = { body, op, httpCode -> ImApiException("$op 失败 (HTTP $httpCode): ${body.take(200)}", httpCode) },
) {
    init {
        require(baseUrl.isNotBlank()) { "Mattermost baseUrl 不能为空" }
        require(token.isNotBlank()) { "Mattermost token 不能为空" }
    }

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
}
