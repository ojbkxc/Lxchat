package com.lxseek.chat.im.aiocqhttp

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.im.ImApiException
import com.lxseek.chat.im.ImJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

data class OneBotMessageEvent(
    val messageId: String,
    val conversationId: String,
    val text: String,
    val senderId: String,
    val senderName: String,
    val timestampMs: Long,
    val isGroup: Boolean,
    val selfId: String,
)

class AiocqhttpApi(
    private val baseUrl: String,
    private val accessToken: String,
) {
    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    suspend fun callAction(action: String, params: JsonObject = buildJsonObject {}): JsonObject {
        val payload = buildJsonObject {
            put("action", action)
            put("params", params)
        }
        return postJson("$baseUrl/$action", payload.toString()).also { result ->
            val status = result["status"]?.jsonPrimitive?.contentOrNull
            val retcode = result["retcode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (status != "ok" || retcode != 0) {
                throw ImApiException("OneBot action $action failed: status=$status retcode=$retcode")
            }
        }
    }

    suspend fun sendPrivateMessage(userId: Long, text: String): String {
        val result = callAction("send_private_msg", messageParams(userId, text))
        return result["data"]?.jsonPrimitive?.contentOrNull ?: "unknown"
    }

    suspend fun sendGroupMessage(groupId: Long, text: String): String {
        val result = callAction("send_group_msg", messageParams(groupId, text, isGroup = true))
        return result["data"]?.jsonPrimitive?.contentOrNull ?: "unknown"
    }

    private fun messageParams(targetId: Long, text: String, isGroup: Boolean = false): JsonObject =
        buildJsonObject {
            if (isGroup) put("group_id", targetId) else put("user_id", targetId)
            putJsonArray("message") {
                add(buildJsonObject {
                    put("type", "text")
                    putJsonObject("data") { put("text", text) }
                })
            }
        }

    suspend fun fetchEvents(): List<OneBotMessageEvent> {
        val root = postJson("$baseUrl/_events", "{}")
        return root.values.mapNotNull { event ->
            if (event is JsonObject) parseMessageEvent(event) else null
        }
    }

    fun parseMessageEvent(event: JsonObject): OneBotMessageEvent? {
        if (event["post_type"]?.jsonPrimitive?.contentOrNull != "message") return null
        val isGroup = event["message_type"]?.jsonPrimitive?.contentOrNull == "group"
        val conversationId = (if (isGroup) event["group_id"] else event["user_id"])
            ?.jsonPrimitive?.contentOrNull ?: return null
        val rawMessage = event["raw_message"]?.jsonPrimitive?.contentOrNull
            ?: event["message"]?.jsonPrimitive?.contentOrNull ?: ""
        val sender = event["sender"]?.jsonObject
        val senderId = sender?.get("user_id")?.jsonPrimitive?.contentOrNull
            ?: event["user_id"]?.jsonPrimitive?.contentOrNull ?: ""
        val selfId = event["self_id"]?.jsonPrimitive?.contentOrNull ?: ""
        if (senderId == selfId) return null
        return OneBotMessageEvent(
            messageId = event["message_id"]?.jsonPrimitive?.contentOrNull ?: "",
            conversationId = conversationId,
            text = rawMessage,
            senderId = senderId,
            senderName = sender?.get("nickname")?.jsonPrimitive?.contentOrNull ?: senderId,
            timestampMs = event["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            isGroup = isGroup,
            selfId = selfId,
        )
    }

    private suspend fun postJson(url: String, body: String): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .apply { if (accessToken.isNotBlank()) header("Authorization", "Bearer $accessToken") }
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        HttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ImApiException("OneBot HTTP ${response.code}: ${response.message}")
            }
            val text = response.body?.string().orEmpty()
            ImJson.parseToJsonElement(text).jsonObject
        }
    }
}
