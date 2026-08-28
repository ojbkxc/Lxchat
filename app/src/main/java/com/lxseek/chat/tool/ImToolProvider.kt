package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.MessageChannel
import com.lxseek.chat.im.MultiSegmentMessageSender
import com.lxseek.chat.im.weixin.WeixinCompanionChannel
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
        "im_status", "im_conversations", "im_receive", "wechat_capabilities" -> RiskLevel.ReadOnly
        "im_send", "im_send_multi" -> RiskLevel.Moderate
        else -> RiskLevel.ReadOnly
    }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = "wechat_capabilities",
                description = "WeChat-specific capability self-check. When the active IM channel is a " +
                    "WeChat iLink binding this reports which operations actually work (send text / image / " +
                    "file / forward / typing / receive) and which are NOT confirmed (revoke, group management, " +
                    "moments, payment). Call this before assuming you can perform a WeChat-only action; it " +
                    "prevents blind attempts against unsupported operations.",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        ),
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
        ToolDefinition(
            function = ToolFunction(
                name = "im_send_multi",
                description = "Send a long reply as several shorter IM messages, split on " +
                    "sentence/line boundaries with a small delay between segments. Prefer this " +
                    "over im_send when the text is long, to keep messages readable and avoid " +
                    "gateway limits.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "conversationId" to ToolProperty(
                            type = "string",
                            description = "Conversation id to send the messages to.",
                        ),
                        "text" to ToolProperty(
                            type = "string",
                            description = "The full reply text; it is split automatically into segments.",
                        ),
                        "maxSegmentLength" to ToolProperty(
                            type = "integer",
                            description = "Optional max characters per segment (default 1800).",
                        ),
                        "delayMs" to ToolProperty(
                            type = "integer",
                            description = "Optional delay between segments in ms (default 800).",
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
            "wechat_capabilities" -> wechatCapabilitiesResult(channel)
            "im_status" -> statusResult(channel)
            "im_conversations" -> conversationsResult(channel)
            "im_receive" -> receiveResult(channel, arguments)
            "im_send" -> sendResult(channel, arguments)
            "im_send_multi" -> sendMultiResult(channel, arguments)
            else -> errorJson("Unknown IM tool: $name")
        }
    }

    // ── Tool implementations ──────────────────────────────────

    /**
     * 微信能力自检：当活动通道是 [WeixinCompanionChannel]（iLink 绑定）时，如实报告哪些操作
     * 已被协议支持，哪些仍属"未确认"。绝不把撤回/群管理等写死为可用——它们需要在线协议探测
     * 确认，本次只暴露"待确认"状态，供 AI 据此决策而不盲目尝试。
     */
    private fun wechatCapabilitiesResult(channel: MessageChannel?): String {
        val isWechat = channel is WeixinCompanionChannel
        val configured = channel?.isConfigured == true
        val wechat = isWechat && configured
        return buildJsonObject {
            put("ok", true)
            put("target", "wechat")
            put("bound", wechat)
            if (!isWechat) {
                put("reason", "当前 IM 通道不是微信 iLink（或尚未注册）。扫码绑定微信后此表才成立。")
            } else if (!configured) {
                put("reason", "微信通道未启用/未配置/令牌失效。")
            }
            putJsonObject("capabilities") {
                // W1-W4 + typing + receive：已实现的基线，直连官方 iLink。
                cap(this, "send_text", wechat, "ilink", "发文本消息")
                cap(this, "send_image", wechat, "ilink", "发图片（直链 /sendimage）")
                cap(this, "send_file", wechat, "ilink", "发文件（直链 /sendfile）")
                cap(this, "forward_media", wechat, "ilink", "转发已收到的媒体（/forward）")
                cap(this, "send_typing", wechat, "ilink", "发送“正在输入”状态")
                cap(this, "receive", wechat, "ilink", "长轮询接收消息")
                // 未在协议中确认的能力：如实标注不支持（pending），不乐观写死。
                cap(this, "revoke", false, null, "撤回消息：iLink 未确认支持，待协议探测")
                cap(this, "group_manage", false, null, "好友备注/群管理/置顶：iLink 未确认支持")
                cap(this, "moments", false, null, "朋友圈查看/发布/点赞：受限，需无障碍兜底")
                cap(this, "payment", false, null, "收款码/支付：受限，安全敏感须审批")
            }
        }.toString()
    }

    private fun cap(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        name: String,
        supported: Boolean,
        mode: String?,
        desc: String,
    ) {
        builder.putJsonObject(name) {
            put("supported", supported)
            mode?.let { put("mode", it) }
            put("desc", desc)
        }
    }

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

    private suspend fun conversationsResult(channel: MessageChannel?): String {
        if (channel == null || !channel.isConfigured) return notConfiguredJson(channel)
        val conversations: List<ImConversation> = try {
            channel.listConversations()
        } catch (e: Exception) {
            emptyList()
        }
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

    private suspend fun receiveResult(channel: MessageChannel?, args: String): String {
        if (channel == null || !channel.isConfigured) return notConfiguredJson(channel)
        val conversationId = argString(args, "conversationId")
        if (conversationId.isBlank()) return errorJson("Missing required argument: conversationId")
        val afterId = argString(args, "afterId")
        val messages: List<ImMessage> = try {
            channel.fetchMessages(conversationId, afterId.ifBlank { null })
        } catch (e: Exception) {
            emptyList()
        }
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

    private suspend fun sendResult(channel: MessageChannel?, args: String): String {
        if (channel == null || !channel.isConfigured) return notConfiguredJson(channel)
        val conversationId = argString(args, "conversationId")
        val text = argString(args, "text")
        if (conversationId.isBlank()) return errorJson("Missing required argument: conversationId")
        if (text.isBlank()) return errorJson("Missing required argument: text")
        val result: ImSendResult = try {
            channel.sendMessage(conversationId, text)
        } catch (e: Exception) {
            ImSendResult.Failure("send threw unexpectedly")
        }
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

    private suspend fun sendMultiResult(channel: MessageChannel?, args: String): String {
        if (channel == null || !channel.isConfigured) return notConfiguredJson(channel)
        val conversationId = argString(args, "conversationId")
        val text = argString(args, "text")
        if (conversationId.isBlank()) return errorJson("Missing required argument: conversationId")
        if (text.isBlank()) return errorJson("Missing required argument: text")
        val maxSegmentLength = argInt(args, "maxSegmentLength") ?: MultiSegmentMessageSender.DEFAULT_MAX_SEGMENT_LENGTH
        val delayMs = argLong(args, "delayMs") ?: MultiSegmentMessageSender.DEFAULT_DELAY_MS
        val sender = MultiSegmentMessageSender(
            maxSegmentLength = maxSegmentLength,
            defaultDelayMs = delayMs,
        )
        val results = try {
            sender.send(channel, conversationId, text, delayMs)
        } catch (e: Exception) {
            return buildJsonObject {
                put("ok", false)
                put("error", "send_multi threw unexpectedly")
            }.toString()
        }
        val sent = results.filterIsInstance<ImSendResult.Success>()
        return buildJsonObject {
            put("ok", sent.size == results.size)
            put("segmentCount", results.size)
            putJsonArray("messageIds") {
                sent.forEach { add(it.messageId) }
            }
            val firstFailure = results.filterIsInstance<ImSendResult.Failure>().firstOrNull()
            if (firstFailure != null) put("error", firstFailure.reason)
        }.toString()
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun argString(args: String, key: String): String {
        if (args.isBlank()) return ""
        return runCatching {
            json.parseToJsonElement(args).jsonObject[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.getOrDefault("")
    }

    private fun argInt(args: String, key: String): Int? {
        if (args.isBlank()) return null
        return runCatching {
            json.parseToJsonElement(args).jsonObject[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        }.getOrNull()
    }

    private fun argLong(args: String, key: String): Long? {
        if (args.isBlank()) return null
        return runCatching {
            json.parseToJsonElement(args).jsonObject[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        }.getOrNull()
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
            "wechat_capabilities",
            "im_status",
            "im_conversations",
            "im_receive",
            "im_send",
            "im_send_multi",
        )
    }
}