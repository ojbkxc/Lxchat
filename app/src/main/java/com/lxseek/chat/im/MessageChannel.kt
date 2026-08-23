package com.lxseek.chat.im

import kotlinx.coroutines.CoroutineScope

/**
 * Transport-agnostic abstraction for instant-messaging I/O. Separates the agent loop and
 * [com.lxseek.chat.tool.ImToolProvider] from any concrete gateway, so the same integration
 * works over an HTTP bridge ([GatewayChannel]) today and a native SDK channel later.
 *
 * Two delivery models are supported:
 *  - **Polling** (default): the receiver calls [listConversations] + [fetchMessages] on a
 *    timer. Used by platforms whose servers do not push to mobile (wechat, telegram, sms).
 *  - **Push** ([PushMessageChannel]): the channel opens a long-lived connection and invokes
 *    a callback for each inbound message. Used by platforms with webhook/websocket SDKs
 *    (lark, dingtalk, wecom, qq, discord, slack).
 */
interface MessageChannel {
    /** Stable identifier for the bridge, derived from the configured platform (e.g. "wechat"). */
    val channelId: String

    /** Human-readable name shown in tool output. */
    val displayName: String

    /** True when the channel has a usable, enabled configuration. */
    val isConfigured: Boolean

    /**
     * Send a text message into [conversationId]. Returns the assigned message id on success.
     * Must be safe to call when offline: a gateway failure yields [ImSendResult.Failure],
     * and an unconfigured channel yields [ImSendResult.NotConfigured].
     */
    suspend fun sendMessage(conversationId: String, text: String): ImSendResult

    /** List the conversations this bridge currently knows about, newest first. */
    suspend fun listConversations(): List<ImConversation>

    /**
     * Fetch inbound messages for [conversationId], optionally only those newer than [afterId].
     * Empty list when nothing new or when the channel is unreachable.
     *
     * Polling-only channels implement this; push channels may return an empty list (their
     * messages arrive via [PushMessageChannel.startListening]).
     */
    suspend fun fetchMessages(conversationId: String, afterId: String? = null): List<ImMessage>

    /**
     * 该渠道是否支持编辑已发送的消息（流式回复需要）。
     *
     * 默认 false；支持编辑的平台（如 Telegram、Discord、Slack）在子类中覆盖为 true。
     * [MultiSegmentMessageSender.sendStreaming] 据此决定走真正的流式编辑路径还是退化为
     * 一次性发送。
     */
    val supportsEdit: Boolean get() = false

    /**
     * 编辑已发送的消息（用于流式回复）。返回是否成功。不支持编辑的平台返回 false。
     *
     * 实现应当：
     *  - 在 [conversationId] 内找到 [messageId] 对应的消息，将其文本替换为 [newText]；
     *  - 网关不可达 / 消息不存在 / 权限不足等失败情况返回 false，不抛异常；
     *  - 调用方据此决定是否退化为发送新消息（见 [MultiSegmentMessageSender.sendStreaming]）。
     *
     * 默认实现返回 false，与 [supportsEdit] 的默认值一致，所以未覆盖此方法的渠道
     * 不会被流式路径调用。
     */
    suspend fun editMessage(conversationId: String, messageId: String, newText: String): Boolean = false
}

/**
 * A [MessageChannel] that receives inbound messages via a long-lived push connection
 * (webhook/websocket/SSE) instead of polling. The receiver calls [startListening] once
 * and the channel invokes [onMessage] for each inbound message until [stopListening] is
 * called or the supplied [CoroutineScope] is cancelled.
 *
 * This is the contract for platforms whose servers push to clients (lark, dingtalk, wecom,
 * qq, discord, slack). Polling platforms (wechat, telegram, sms) stay on [MessageChannel]
 * and are driven by [ImPollingReceiver]'s poll loop.
 *
 * Concrete push channel implementations are plugged in by [ImChannelFactory] and wired by
 * [ImPollingReceiver], which launches a dedicated listening job per push channel and feeds
 * every callback message into the same agent-trigger / reply-write-back pipeline that the
 * polling path uses.
 */
interface PushMessageChannel : MessageChannel {
    /**
     * Open the push connection and start delivering inbound messages to [onMessage].
     * Suspends until the connection is closed (by [stopListening] or [scope] cancellation);
     * the caller is expected to run it inside a coroutine launched on [scope].
     *
     * [onMessage] is invoked on the channel's internal dispatcher and must be cheap to
     * return. Heavy work (agent generation) should be launched onto [scope] inside the
     * callback — [ImPollingReceiver] does exactly that.
     */
    suspend fun startListening(onMessage: (ImMessage) -> Unit, scope: CoroutineScope)

    /** Close the push connection and stop delivering messages. Safe to call when not listening. */
    fun stopListening()
}

/** Outcome of a single [MessageChannel.sendMessage] attempt. */
sealed interface ImSendResult {
    /** Message accepted by the gateway. */
    data class Success(val messageId: String) : ImSendResult

    /** Gateway reachable but rejected or relay failed. */
    data class Failure(val reason: String) : ImSendResult

    /** The channel has no usable configuration (or is disabled). */
    object NotConfigured : ImSendResult
}
