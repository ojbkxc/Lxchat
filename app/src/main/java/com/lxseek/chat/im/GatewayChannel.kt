package com.lxseek.chat.im

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * HTTP gateway bridge that turns a remote IM adapter (OneBot / wechaty-style REST gateway)
 * into a [MessageChannel] LxChat can talk to. Polling is used instead of a long-lived socket,
 * so the bridge never needs to hold an Android background connection open — a deliberate choice
 * to keep APK size small and avoid doze/background-restriction breakage.
 *
 * Wire contract (all JSON):
 *  - POST {base}/v1/im/send            {platform, conversationId, text} -> {messageId}
 *  - GET  {base}/v1/im/conversations   ?platform=...                     -> {conversations:[...]}
 *  - GET  {base}/v1/im/messages        ?platform=...&conversationId=&after= -> {messages:[...]}
 *
 * Every network call is wrapped so the agent always receives a structured result, never an
 * uncaught IOException.
 */
class GatewayChannel(
    private val config: ImGatewayConfig,
) : MessageChannel {

    override val channelId: String get() = config.platform
    override val displayName: String get() = config.name
    override val isConfigured: Boolean get() = config.isConfigured

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        if (!isConfigured) return ImSendResult.NotConfigured
        return withContext(Dispatchers.IO) {
            try {
                val url = endpoint("/v1/im/send")
                val body = buildJsonObject {
                    put("platform", config.platform)
                    put("conversationId", conversationId)
                    put("text", text)
                }.toString()
                val response = HttpClient.postTextResponse(url, body, authHeaders())
                if (response.isSuccessful) {
                    ImSendResult.Success(parseMessageId(response.body))
                } else {
                    ImSendResult.Failure("HTTP ${response.code}: ${truncate(response.body)}")
                }
            } catch (e: Exception) {
                DebugLog.e("GatewayChannel", "sendMessage failed", e)
                ImSendResult.Failure(e.message ?: "gateway unreachable")
            }
        }
    }

    override suspend fun listConversations(): List<ImConversation> {
        if (!isConfigured) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val url = endpoint("/v1/im/conversations?platform=${config.platform}")
                val response = HttpClient.getTextResponse(url, authHeaders())
                if (!response.isSuccessful) return@withContext emptyList<ImConversation>()
                parseConversations(response.body)
            } catch (e: Exception) {
                DebugLog.e("GatewayChannel", "listConversations failed", e)
                emptyList()
            }
        }
    }

    override suspend fun fetchMessages(conversationId: String, afterId: String?): List<ImMessage> {
        if (!isConfigured) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val after = afterId?.takeIf { it.isNotBlank() }?.let { "&after=$it" } ?: ""
                val url = endpoint(
                    "/v1/im/messages?platform=${config.platform}&conversationId=$conversationId$after",
                )
                val response = HttpClient.getTextResponse(url, authHeaders())
                if (!response.isSuccessful) return@withContext emptyList<ImMessage>()
                parseMessages(response.body)
            } catch (e: Exception) {
                DebugLog.e("GatewayChannel", "fetchMessages failed", e)
                emptyList()
            }
        }
    }

    private fun endpoint(path: String): String =
        config.baseUrl.trim().trimEnd('/') + path

    private fun authHeaders(): Map<String, String> =
        if (config.token.isBlank()) emptyMap()
        else mapOf("Authorization" to "Bearer ${config.token}")

    // ── Response parsing (defensive; a bad gateway payload must never crash the agent) ──

    private fun parseMessageId(body: String): String {
        val id = runCatching {
            json.parseToJsonElement(body).jsonObject["messageId"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return id?.takeIf { it.isNotBlank() } ?: "unknown"
    }

    private fun parseConversations(body: String): List<ImConversation> {
        val arr = runCatching {
            json.parseToJsonElement(body).jsonObject["conversations"]?.jsonArray
        }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                ImConversation(
                    id = str(o, "id") ?: "",
                    title = str(o, "title") ?: "Untitled",
                    platform = str(o, "platform") ?: config.platform,
                    lastMessageAtMs = (o["lastMessageAtMs"]?.jsonPrimitive?.contentOrNull
                        ?.toLongOrNull()) ?: 0L,
                    unreadCount = (o["unreadCount"]?.jsonPrimitive?.contentOrNull
                        ?.toIntOrNull()) ?: 0,
                )
            }.getOrNull()
        }
    }

    private fun parseMessages(body: String): List<ImMessage> {
        val arr = runCatching {
            json.parseToJsonElement(body).jsonObject["messages"]?.jsonArray
        }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                ImMessage(
                    id = str(o, "id") ?: "unknown",
                    conversationId = str(o, "conversationId") ?: "",
                    direction = if (str(o, "direction") == "outgoing") {
                        ImMessageDirection.OUTGOING
                    } else {
                        ImMessageDirection.INCOMING
                    },
                    text = str(o, "text") ?: "",
                    sender = str(o, "sender") ?: "",
                    timestampMs = (o["timestampMs"]?.jsonPrimitive?.contentOrNull
                        ?.toLongOrNull()) ?: 0L,
                )
            }.getOrNull()
        }
    }

    private fun str(o: kotlinx.serialization.json.JsonObject, key: String): String? =
        o[key]?.jsonPrimitive?.contentOrNull

    private fun truncate(s: String): String = s.take(200)
}