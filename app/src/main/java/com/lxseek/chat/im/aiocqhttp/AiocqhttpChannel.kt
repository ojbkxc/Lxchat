package com.lxseek.chat.im.aiocqhttp

import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImMessageDirection
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.PushMessageChannel
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class AiocqhttpChannel(
    private val config: ImGatewayConfig,
) : PushMessageChannel {
    override val channelId: String get() = config.effectiveChannelId

    override val displayName: String
        get() = if (config.baseUrl.isNotBlank()) "QQ(OneBot) · ${config.baseUrl}" else "QQ(OneBot)"

    override val isConfigured: Boolean
        get() = config.enabled && config.baseUrl.startsWith("http")

    private val api = AiocqhttpApi(
        baseUrl = config.baseUrl.trimEnd('/'),
        accessToken = config.token,
    )

    private val knownConversations = LinkedHashMap<String, ImConversation>()

    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        if (!isConfigured) return ImSendResult.NotConfigured
        val targetId = conversationId.toLongOrNull()
            ?: return ImSendResult.Failure("OneBot target id must be numeric: $conversationId")
        return try {
            val isGroup = knownConversations[conversationId]?.isGroup
                ?: conversationId.startsWith("group:")
            val id = if (isGroup) {
                api.sendGroupMessage(targetId, text)
            } else {
                api.sendPrivateMessage(targetId, text)
            }
            ImSendResult.Success(id)
        } catch (e: Exception) {
            DebugLog.e(TAG, "sendMessage failed", e)
            ImSendResult.Failure(e.message ?: "OneBot send failed")
        }
    }

    override suspend fun listConversations(): List<ImConversation> = withContext(Dispatchers.IO) {
        try {
            api.fetchEvents().forEach { event ->
                rememberConversation(event.conversationId, event.isGroup, event.senderName)
            }
            knownConversations.values.toList()
        } catch (e: Exception) {
            DebugLog.w(TAG, "fetch events failed", e)
            knownConversations.values.toList()
        }
    }

    override suspend fun fetchMessages(
        conversationId: String,
        afterId: String?,
    ): List<ImMessage> = withContext(Dispatchers.IO) {
        try {
            api.fetchEvents()
                .filter { it.conversationId == conversationId }
                .filter { event -> afterId?.let { event.messageId > it } ?: true }
                .map { event ->
                    rememberConversation(event.conversationId, event.isGroup, event.senderName)
                    event.toImMessage()
                }
        } catch (e: Exception) {
            DebugLog.w(TAG, "fetch messages failed", e)
            emptyList()
        }
    }

    override suspend fun startListening(
        onMessage: (ImMessage) -> Unit,
        scope: CoroutineScope,
    ) {
        if (!isConfigured) return
        while (scope.isActive) {
            val events = try {
                withContext(Dispatchers.IO) { api.fetchEvents() }
            } catch (e: Exception) {
                DebugLog.w(TAG, "listen fetch failed", e)
                emptyList()
            }
            events.forEach { event ->
                rememberConversation(event.conversationId, event.isGroup, event.senderName)
                onMessage(event.toImMessage())
            }
            delay(config.pollIntervalMs)
        }
    }

    override fun stopListening() = Unit

    private fun rememberConversation(id: String, isGroup: Boolean, senderName: String) {
        val conversation = knownConversations[id]
        if (conversation == null) {
            knownConversations[id] = ImConversation(
                id = id,
                title = if (isGroup) "QQ group $id" else senderName,
                isGroup = isGroup,
            )
        }
    }

    private fun OneBotMessageEvent.toImMessage() = ImMessage(
        id = messageId,
        conversationId = conversationId,
        direction = ImMessageDirection.INCOMING,
        text = text,
        sender = senderName,
        timestampMs = timestampMs,
    )

    private companion object {
        const val TAG = "AiocqhttpChannel"
    }
}
