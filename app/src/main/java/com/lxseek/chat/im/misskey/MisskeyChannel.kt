package com.lxseek.chat.im.misskey

import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImMessageDirection
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.MessageChannel
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Misskey 渠道：把 [MisskeyApi]（Misskey API）适配到 [MessageChannel]，
 * 让 Lxchat 的 [com.lxseek.chat.im.ImPollingReceiver] 通过轮询 timeline 拉取消息。
 *
 * **Polling 模型** — Misskey 虽有 WebSocket Streaming，但手机端长连接受限，本渠道
 * 走 REST 轮询：[listConversations] 拉一次 `notes/mentions`（机器人常用入口），
 * 把每条 note 路由到对应会话缓冲；[fetchMessages] 排空一个会话的缓冲。
 * 跨周期去重由 [com.lxseek.chat.im.ImPollingReceiver] 的 seenMessageIds 集合负责。
 *
 * **出站** — 经 [MisskeyApi.createNote] 发帖。
 * [conversationId] 约定为 Misskey 的 noteId（作为回复目标）；空则发公开 note。
 *
 * **配置** 复用 [ImGatewayConfig]：
 *  - `baseUrl` ← Misskey 实例基址（如 `https://misskey.io`）
 *  - `token`   ← Access Token
 *  - `platform` 必须为 `"misskey"`
 *
 * 参照 AstrBot `misskey_adapter.py` / `misskey_api.py` 与 Lxchat
 * [com.lxseek.chat.im.telegram.TelegramChannel] 的 polling 模板。
 */
class MisskeyChannel(
    private val config: ImGatewayConfig,
) : MessageChannel {

    override val channelId: String get() = CHANNEL_ID

    override val displayName: String
        get() {
            val name = botUsername
            return if (name != null) "Misskey · @$name" else "Misskey"
        }

    override val isConfigured: Boolean
        get() = config.enabled &&
            MisskeyApi.isValidBaseUrl(config.baseUrl) &&
            MisskeyApi.isValidToken(config.token)

    /** 懒构建；配置不全时为 null，[isConfigured] 同步返回 false。 */
    private val api: MisskeyApi? =
        if (MisskeyApi.isValidBaseUrl(config.baseUrl) && MisskeyApi.isValidToken(config.token)) {
            runCatching {
                MisskeyApi(
                    baseUrl = config.baseUrl.trim(),
                    token = config.token.trim(),
                )
            }.getOrElse {
                DebugLog.e("MisskeyChannel", "MisskeyApi 构造失败: ${it.message}", it)
                null
            }
        } else null

    @Volatile private var botUsername: String? = null
    @Volatile private var botId: String? = null
    @Volatile private var lastSinceId: String? = null
    private val knownConversations = LinkedHashMap<String, ImConversation>()
    private val pendingByConv = HashMap<String, MutableList<ImMessage>>()

    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        val api = api ?: return ImSendResult.NotConfigured
        if (!isConfigured) return ImSendResult.NotConfigured
        return try {
            // conversationId 为回复目标 noteId；空则发公开 note。
            val replyId = conversationId.takeIf { it.isNotBlank() }
            val result = api.createNote(text = text, replyId = replyId)
            val noteId = result["id"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                ?: result["createdNote"]?.let { runCatching { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
                ?: "unknown"
            ImSendResult.Success(noteId)
        } catch (e: MisskeyApiException) {
            DebugLog.e("MisskeyChannel", "sendMessage 失败 (http=${e.httpCode})")
            ImSendResult.Failure(e.message ?: "misskey send failed")
        } catch (e: Exception) {
            DebugLog.e("MisskeyChannel", "sendMessage 失败", e)
            ImSendResult.Failure(e.message ?: "misskey send failed")
        }
    }

    override suspend fun listConversations(): List<ImConversation> {
        if (!isConfigured) return emptyList()
        val api = api ?: return emptyList()
        try {
            ensureBotIdentity(api)
            pollMentions(api)
        } catch (e: Exception) {
            DebugLog.e("MisskeyChannel", "listConversations poll 失败", e)
        }
        return knownConversations.values.sortedByDescending { it.lastMessageAtMs }
    }

    override suspend fun fetchMessages(conversationId: String, afterId: String?): List<ImMessage> {
        if (!isConfigured) return emptyList()
        // afterId 由 ImPollingReceiver 的 seen 集合负责；这里直接排空缓冲。
        return pendingByConv.remove(conversationId) ?: emptyList()
    }

    /** 拉一次机器人自身信息，用于过滤自身消息与显示名。 */
    private suspend fun ensureBotIdentity(api: MisskeyApi) {
        if (botUsername != null) return
        try {
            val me = api.getI()
            botId = me["id"]?.jsonPrimitive?.contentOrNull
            botUsername = me["username"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            DebugLog.e("MisskeyChannel", "getI 失败", e)
        }
    }

    /** 拉一次 mentions，路由到对应会话缓冲。 */
    private suspend fun pollMentions(api: MisskeyApi) = withContext(Dispatchers.IO) {
        val result = api.getMentions(limit = 30, sinceId = lastSinceId)
        val notes = result.arrayField("notes")
        var maxId: String? = null
        for (note in notes) {
            val noteId = note["id"]?.jsonPrimitive?.contentOrNull ?: continue
            if (maxId == null || noteId > maxId) maxId = noteId
            val userId = note["userId"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                ?: note["user"]?.let { runCatching { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
                ?: ""
            // 过滤自身消息。
            if (userId == botId) continue
            val text = note["text"]?.jsonPrimitive?.contentOrNull ?: continue
            val username = note["user"]?.let { runCatching { it.jsonObject["username"]?.jsonPrimitive?.contentOrNull }.getOrNull() } ?: userId
            val createdAt = note["createdAt"]?.jsonPrimitive?.contentOrNull?.let { parseIsoMs(it) } ?: 0L
            // conversationId 用 userId（一个用户一个会话）。
            val convId = "user:$userId"
            knownConversations[convId] = ImConversation(
                id = convId,
                title = "@$username",
                platform = CHANNEL_ID,
                lastMessageAtMs = createdAt,
                isGroup = false,
            )
            pendingByConv.getOrPut(convId) { mutableListOf() }.add(
                ImMessage(
                    id = noteId,
                    conversationId = convId,
                    direction = ImMessageDirection.INCOMING,
                    text = text,
                    sender = username,
                    timestampMs = createdAt,
                ),
            )
        }
        if (maxId != null) lastSinceId = maxId
    }

    /** 从 JsonObject 取一个数组字段，返回 List<JsonObject>。 */
    private fun JsonObject.arrayField(key: String): List<JsonObject> {
        val arr = this[key] ?: return emptyList()
        return runCatching { arr.jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())
    }

    /** 简易 ISO 8601 → 毫秒解析（Misskey 返回形如 2024-01-01T00:00:00.000Z）。 */
    private fun parseIsoMs(iso: String): Long {
        return runCatching {
            java.time.Instant.parse(iso).toEpochMilli()
        }.getOrDefault(0L)
    }

    companion object {
        /** 平台标识，存入 [ImGatewayConfig.platform]。 */
        const val PLATFORM = "misskey"
        private const val CHANNEL_ID = "misskey"
    }
}