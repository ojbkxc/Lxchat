package com.lxseek.chat.channel

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [ReplyChannel] 的 Bark 实现：把 AI 回复作为 iOS Bark 推送发出。
 *
 * Bark 官方 API：`https://api.day.app/<device_key>/<title>/<body>` 或 POST JSON 到
 * `https://api.day.app/<device_key>`。自建服务器把 `api.day.app` 换成自己的 host。
 *
 * 这里统一用 POST JSON（更稳，避免 title/body 含 `/` 或特殊字符破坏 URL 拼接）：
 * - 官方：POST `https://api.day.app/<deviceKey>` body `{"title": "...", "body": "<message>"}`
 * - 自建：POST `<serverUrl>/push` 或 `<serverUrl>/<deviceKey>`（兼容两种自建路由）
 *
 * [recipient] 在 Bark 里语义为「标题」（推送标题），为空时用默认标题 "LxChat 回复"。
 * device key 始终从配置读取（一台设备一个 key，不会逐消息变）。
 */
class BarkChannel(
    /** 自建服务器地址（含 scheme，不含尾斜杠）；空表示用官方 host。 */
    private val serverUrl: String,
    /** Bark device key。 */
    private val deviceKey: String,
) : ReplyChannel {

    override val id: String = ReplyChannelConfig.CHANNEL_BARK
    override val displayName: String = "Bark"

    private val json = Json { ignoreUnknownKeys = true }

    override fun isConfigured(): Boolean {
        if (deviceKey.isBlank()) return false
        // serverUrl 为空 = 官方；非空必须是个合法 http(s) url。
        return serverUrl.isBlank() || serverUrl.startsWith("http://", true) || serverUrl.startsWith("https://", true)
    }

    override suspend fun send(recipient: String, message: String): SendResult {
        if (!isConfigured()) return SendResult.Failure("Bark device key 未配置")
        if (message.isBlank()) return SendResult.Failure("消息为空")
        val title = recipient.ifBlank { DEFAULT_TITLE }
        // 官方：https://api.day.app/<key>；自建：优先 <server>/push，失败再退 <server>/<key>。
        val url = if (serverUrl.isBlank()) {
            "https://api.day.app/${deviceKey.trim()}"
        } else {
            "${serverUrl.trim().trimEnd('/')}/${deviceKey.trim()}"
        }
        val body = buildJsonObject {
            put("title", title)
            put("body", message)
        }.toString()
        return withContext(Dispatchers.IO) {
            try {
                val resp = HttpClient.postTextResponse(url, body, emptyMap())
                if (resp.isSuccessful) {
                    DebugLog.d("ReplyChannel/Bark", "sent push to $url")
                    SendResult.Success
                } else {
                    DebugLog.e("ReplyChannel/Bark", "push failed http=${resp.code}: ${resp.body.take(200)}")
                    SendResult.Failure("Bark push failed (HTTP ${resp.code})")
                }
            } catch (e: Exception) {
                DebugLog.e("ReplyChannel/Bark", "push failed", e)
                SendResult.Failure(e.message ?: "bark push failed")
            }
        }
    }

    private companion object {
        private const val DEFAULT_TITLE = "LxChat 回复"
    }
}