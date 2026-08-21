package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.MessageChannel
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Exposes instant-messaging to the agent through a [MessageChannel]. Reads the active channel
 * lazily via [channelProvider] so the bridge can be (re)configured at runtime without rebuilding
 * the provider. All tools are safe when no gateway is configured — they report a structured
 * "not configured" state instead of throwing.
 */
class ImToolProvider(
    private val channelProvider: () -> MessageChannel?,
) : ToolProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun handles(name: String): Boolean = name in SUPPORTED_NAMES

    override fun riskLevel(name: String): RiskLevel = when (name) {
        "im_status", "im_conversations", "im_receive" -> RiskLevel.ReadOnly
        "im_send" -> RiskLevel.Moderate
        else -> RiskLevel.ReadOnly
    }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = "im_status",
                description = "Report the IM gateway bridge status: whether a channel is " +
                    "configured/enabled, its platform, and why it cannot be used when offline.",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "im_conversations",
                description = "List the IM conversations the configured gateway knows about, " +
                    "newest first. Useful before replying to find the conversationId.",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "im_receive",
                description = "Fetch inbound IM messages for a conversation, optionally only " +
                    "those newer than a given message id. Use im_conversations / im_status first.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "conversationId" to ToolProperty(
                            type = "string",
                            description = "Conversation (contact/group) id to fetch messages for.",
                        ),
                        "afterId" to ToolProperty(
                            type = "string",
                            description = "Only fetch messages newer than this message id. Optional.",
                        ),
                    ),
                    required = listOf("conversationId"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "im_send",
                description = "Send a text message into an IM conversation through the gateway. " +
                    "Use the conversationId from im_conversations.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "conversationId" to ToolProperty(
                            type = "string",
                            description = "Conversation id to send the message to.",
                        ),
                        "text" to ToolProperty(
                            type = "string",
                            description = "The message text to send.",
                        ),
                    ),
                    required = listOf("conversationId", "text"),
                ),
            ),
        ),
    )

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val channel = channelProvider()
        return when (name) {
            "im_status" -> statusResult(channel)
            "im_conversations" -> conversationsResult(channel)
            "im_receive" -> receiveResult(channel, arguments)
            "im_send" -> sendResult(channel, arguments)
            else -> errorJson("Unknown IM tool: $name")
        }
    }

    // ── Tool implementations ──────────────────────────────────

    private fun statusResult(channel: MessageChannel?): String {
        if (channel == null) {
            return buildJsonObject {
                put("configured", false)
                put("reason", "No IM channel registered")
            }.toString()
        }
        return buildJsonObject {
            put("configured", channel.isConfigured)
            put("channel", channel.displayName)
            if (!channel.isConfigured) put("reason", "Gateway disabled or base URL is empty")
        }.toString()
    }

    private fun conversationsResult(channel: MessageChannel?): String {
        if (channel == null || !channel.isConfigured) return notConfiguredJson(channel)
        val conversations: List<ImConversation> = runCatching {
            channel.listConversations()
        }.getOrDefault(emptyList())
        return buildJsonObject {
            put("ok", true)
            putJsonArray("conversations") {
                conversations.forEach { c ->
                    add(
                        buildJsonObject {
                            put("id", c.id)
                            put("title", c.title)
                            put("platform", c.platform)
                            put("unreadCount", c.unreadCount)
                        },
                    )
                }
            }
        }.toString()
    }

    private fun receiveResult(channel: MessageChannel?, args: String): String {
        if (channel == null || !channel.isConfigured) return notConfiguredJson(channel)
        val conversationId = argString(args, "conversationId")
        if (conversationId.isBlank()) return errorJson("Missing required argument: conversationId")
        val afterId = argString(args, "afterId")
        val messages: List<ImMessage> = runCatching {
            channel.fetchMessages(conversationId, afterId.ifBlank { null })
        }.getOrDefault(emptyList())
        return buildJsonObject {
            put("ok", true)
            put("conversationId", conversationId)
            putJsonArray("messages") {
                messages.forEach { m ->
                    add(
                        buildJsonObject {
                            put("id", m.id)
                            put("direction", m.direction.name.lowercase())
                            put("sender", m.sender)
                            put("text", m.text)
                            put("timestampMs", m.timestampMs)
                        },
                    )
                }
            }
        }.toString()
    }

    private fun sendResult(channel: MessageChannel?, args: String): String {
        if (channel == null || !channel.isConfigured) return notConfiguredJson(channel)
        val conversationId = argString(args, "conversationId")
        val text = argString(args, "text")
        if (conversationId.isBlank()) return errorJson("Missing required argument: conversationId")
        if (text.isBlank()) return errorJson("Missing required argument: text")
        val result: ImSendResult = runCatching {
            channel.sendMessage(conversationId, text)
        }.getOrDefault(ImSendResult.Failure("send threw unexpectedly"))
        return when (result) {
            is ImSendResult.Success -> buildJsonObject {
                put("ok", true)
                put("messageId", result.messageId)
            }.toString()
            is ImSendResult.Failure -> buildJsonObject {
                put("ok", false)
                put("error", result.reason)
            }.toString()
            ImSendResult.NotConfigured -> notConfiguredJson(channel)
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun argString(args: String, key: String): String {
        if (args.isBlank()) return ""
        return runCatching {
            json.parseToJsonElement(args).jsonObject[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.getOrDefault("")
    }

    private fun notConfiguredJson(channel: MessageChannel?): String = buildJsonObject {
        put("ok", false)
        put("configured", false)
        put(
            "reason",
            channel?.displayName?.let { "Gateway not configured or disabled ($it)" }
                ?: "No IM channel registered",
        )
    }.toString()

    private fun errorJson(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()

    private companion object {
        val SUPPORTED_NAMES = setOf(
            "im_status",
            "im_conversations",
            "im_receive",
            "im_send",
        )
    }
}