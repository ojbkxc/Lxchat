package com.lxseek.chat.viewmodel

/**
 * Durable acceptance result returned to the composer.
 *
 * A direct send enters the visible conversation. Its Controller-owned UI commit also requests the
 * scroll; the composer only uses this result to decide whether it may clear the submitted draft.
 * A queued send remains exclusively in the queue banner until a legal boundary starts its fresh
 * normal Send/Run.
 */
sealed interface SendAcceptance {
    val messageId: String
    val conversationId: String

    data class Direct(
        override val messageId: String,
        override val conversationId: String,
    ) : SendAcceptance

    data class Queued(
        override val messageId: String,
        override val conversationId: String,
    ) : SendAcceptance
}

internal fun SendAcceptance.hasDurableAttachmentOwner(): Boolean =
    this is SendAcceptance.Direct
