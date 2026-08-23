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
