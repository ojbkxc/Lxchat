package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.automation.TaskManager
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
    // Resolved lazily so AppContainer can avoid a create-order cycle; used by wechat_schedule_send (W5).
    private val taskManagerProvider: (() -> TaskManager)? = null,
) : ToolProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun handles(name: String): Boolean = name in SUPPORTED_NAMES

    override fun riskLevel(name: String): RiskLevel = when (name) {
        "im_status", "im_conversations", "im_receive", "wechat_capabilities" -> RiskLevel.ReadOnly
        "im_send", "im_send_multi", "wechat_schedule_send", "wechat_revoke", "wechat_group_manage" ->
            RiskLevel.Moderate
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
        ToolDefinition(
            function = ToolFunction(
                name = "wechat_schedule_send",
                description = "Schedule a WeChat message to be sent later (W5). Requires contact (name/remark) " +
                    "and the message text, plus exactly one of: cron (a 5-field expression like '0 9 * * 1' for " +
                    "a recurring send) or runAt (epoch milliseconds for a one-off send). The fire-time agent will " +
                    "locate the contact via im_conversations and send the text with im_send; if the contact is not " +
                    "found it reports that the send did not happen. Returns the created background Task.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "contact" to ToolProperty(type = "string", description = "WeChat contact name or remark (备注), e.g. '张三'."),
                        "text" to ToolProperty(type = "string", description = "The message text to send."),
                        "cron" to ToolProperty(type = "string", description = "Optional 5-field cron for recurring sends, e.g. '0 9 * * 1'."),
                        "runAt" to ToolProperty(type = "integer", description = "Optional epoch milliseconds for a one-off send."),
                    ),
                    required = listOf("contact", "text"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "wechat_revoke",
                description = "Revoke a previously sent WeChat message. Honest capability reporting (W6): the " +
                    "iLink protocol does not currently confirm a revoke endpoint, so this returns supported=false " +
                    "unless the upstream adds it; the message is NOT actually retracted. Do not pretend success.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "conversationId" to ToolProperty(type = "string", description = "Conversation (contact/group) id."),
                        "messageId" to ToolProperty(type = "string", description = "Id of the message to revoke."),
                    ),
                    required = listOf("conversationId", "messageId"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "wechat_group_manage",
                description = "Best-effort WeChat group/roster management (W7). Honest capability reporting: the " +
                    "iLink protocol does not currently confirm these operations. action one of: pin (置顶聊天), " +
                    "unpin, remark_friend (好友备注), group_rename. Returns supported=false with guidance when the " +
                    "protocol lacks the endpoint, since silently failing is worse than telling the user to do it manually.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "action" to ToolProperty(type = "string", description = "pin | unpin | remark_friend | group_rename."),
                        "target" to ToolProperty(type = "string", description = "Conversation id or contact to act on."),
                        "name" to ToolProperty(type = "string", description = "New remark / group name when relevant."),
                    ),
                    required = listOf("action", "target"),
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
            "wechat_schedule_send" -> scheduleSendResult(channel, arguments)
            "wechat_revoke" -> revokeResult(channel, arguments)
            "wechat_group_manage" -> groupManageResult(channel, arguments)
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

    // ── W5/W6/W7: schedule-send / revoke / group manage ─────

    private suspend fun scheduleSendResult(channel: MessageChannel?, args: String): String {
        val manager = taskManagerProvider ?: return buildJsonObject {
            put("ok", false)
            put("error", "scheduling is not available on this build")
        }.toString()
        val contact = argString(args, "contact").trim()
        val text = argString(args, "text").trim()
        if (contact.isBlank()) return errorJson("Missing required argument: contact")
        if (text.isBlank()) return errorJson("Missing required argument: text")
        val cron = argString(args, "cron").trim()
        val runAt = argLong(args, "runAt")
        if (cron.isBlank() && runAt == null) return errorJson("Must provide exactly one of cron or runAt")
        if (cron.isNotBlank() && runAt != null) return errorJson("Provide only one of cron or runAt, not both")
        val prompt = "WeChat 定时发送任务：给联系人「$contact」发送消息。\n消息内容：\n$text" +
            "\n\n执行要求：先调用 im_conversations 找到联系人「$contact」的 conversationId，再调用 im_send " +
            "发送整段消息原文；若找不到该联系人，则如实返回未发送及其原因，不要编造成功。"
        val task = try {
            manager().createTask(
                name = "微信定时发送 · ${contact.take(12)}",
                prompt = prompt,
                cronExpr = cron,
                modelId = null,
                runAt = runAt,
            )
        } catch (e: Exception) {
            return errorJson("schedule failed: ${e.message}")
        }
        return buildJsonObject {
            put("ok", true)
            put("tool", "wechat_schedule_send")
            put("contact", contact)
            put("task_id", task.id)
            runAt?.let { put("run_at", it) }
            if (cron.isNotBlank()) put("cron", cron)
        }.toString()
    }

    private fun revokeResult(channel: MessageChannel?, args: String): String {
        val conversationId = argString(args, "conversationId")
        val messageId = argString(args, "messageId")
        if (conversationId.isBlank()) return errorJson("Missing required argument: conversationId")
        if (messageId.isBlank()) return errorJson("Missing required argument: messageId")
        // 撤回属"未确认支持"：如实返回 supported=false，绝不伪装成功。
        return buildJsonObject {
            put("ok", false)
            put("tool", "wechat_revoke")
            put("supported", false)
            put("reason", "iLink 协议当前未确认支持撤回消息，消息未被撤回。")
            put("hint", "如需撤回，请由用户在微信 App 内手动撤回；不要告知用户已撤回。")
        }.toString()
    }

    private fun groupManageResult(channel: MessageChannel?, args: String): String {
        val action = argString(args, "action").trim().lowercase()
        val target = argString(args, "target").trim()
        if (action.isBlank()) return errorJson("Missing required argument: action")
        if (target.isBlank()) return errorJson("Missing required argument: target")
        if (action !in setOf("pin", "unpin", "remark_friend", "group_rename")) {
            return errorJson("action must be pin|unpin|remark_friend|group_rename")
        }
        // 群/好友管理同样"未确认支持"：如实报告，未做任何改动。
        return buildJsonObject {
            put("ok", false)
            put("tool", "wechat_group_manage")
            put("supported", false)
            put("action", action)
            put("reason", "iLink 协议当前未确认支持此类群/好友管理操作，未执行任何改动。")
            put("hint", "「$action」请在微信 App 内手动完成（聊天里长按可置顶/取消置顶，联系人详情页可改备注）。")
        }.toString()
    }

    // ── Helpers ──────────────────────────────────────────────

    /**
     * 参数读取统一委托 ToolArgHelpers.argPrimitive 共享核心（W4F 合并三处重复实现，
     * 参数顺序保持本类原有的 (args, key)）。本类语义不变：缺失/JSON null/非法一律
     * 返回空串而非 null，调用方依赖这一非空契约（如 `.trim()` 链式调用），绝不能
     * 改为返回 null。contentOrNull 扩展恰好复刻旧实现：仅对 JsonNull 返回 null，
     * 字面量 "null" 字符串原样保留。
     */
    private fun argString(args: String, key: String): String =
        argPrimitive(key, args, json)?.contentOrNull ?: ""

    /** 同上，缺失/无效返回 null；数值语义与旧实现一致（不 trim）。 */
    private fun argInt(args: String, key: String): Int? =
        argPrimitive(key, args, json)?.contentOrNull?.toIntOrNull()

    private fun argLong(args: String, key: String): Long? =
        argPrimitive(key, args, json)?.contentOrNull?.toLongOrNull()

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
            "wechat_schedule_send",
            "wechat_revoke",
            "wechat_group_manage",
        )
    }
}
