package com.lxseek.chat.im

/**
 * Transport-agnostic abstraction for instant-messaging I/O. Separates the agent loop and
 * [com.lxseek.chat.tool.ImToolProvider] from any concrete gateway, so the same integration
 * works over an HTTP bridge ([GatewayChannel]) today and a native SDK channel later.
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
     */
    suspend fun fetchMessages(conversationId: String, afterId: String? = null): List<ImMessage>
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