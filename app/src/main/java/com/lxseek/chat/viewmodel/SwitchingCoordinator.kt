package com.lxseek.chat.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class SwitchingRequestKind {
    CONVERSATION,
    TREE_MUTATION,
    NEW_CHAT,
}

data class SwitchingScrollRequest(
    val id: Long,
    val conversationId: String?,
    val targetMessageId: String?,
    val kind: SwitchingRequestKind,
    val readyForUi: Boolean,
    val hapticOnCompletion: Boolean,
)

/**
 * Single owner for the full-screen switching cover.
 *
 * A request id, rather than a conversation-id value change, defines one transition. This matters
 * when a delayed New Chat transition is superseded by selecting the conversation that is still
 * current: assigning the same conversation id produces no StateFlow emission, but the new request
 * still has a distinct owner and must still settle and release the cover.
 */
internal class SwitchingCoordinator {
    private val ids = AtomicLong(0L)
    private val _isSwitching = MutableStateFlow(false)
    private val _request = MutableStateFlow<SwitchingScrollRequest?>(null)

    val isSwitching: StateFlow<Boolean> = _isSwitching.asStateFlow()
    val request: StateFlow<SwitchingScrollRequest?> = _request.asStateFlow()

    fun beginConversation(
        conversationId: String,
        hapticOnCompletion: Boolean = true,
    ): SwitchingScrollRequest =
        begin(
            conversationId = conversationId,
            targetMessageId = null,
            kind = SwitchingRequestKind.CONVERSATION,
            readyForUi = false,
            hapticOnCompletion = hapticOnCompletion,
        )

    fun beginTreeMutation(conversationId: String): SwitchingScrollRequest =
        begin(
            conversationId = conversationId,
            targetMessageId = null,
            kind = SwitchingRequestKind.TREE_MUTATION,
            readyForUi = false,
            hapticOnCompletion = false,
        )

    fun beginNewChat(): SwitchingScrollRequest =
        begin(
            conversationId = null,
            targetMessageId = null,
            kind = SwitchingRequestKind.NEW_CHAT,
            readyForUi = false,
            hapticOnCompletion = false,
        )

    private fun begin(
        conversationId: String?,
        targetMessageId: String?,
        kind: SwitchingRequestKind,
        readyForUi: Boolean,
        hapticOnCompletion: Boolean,
    ): SwitchingScrollRequest {
        val next = SwitchingScrollRequest(
            id = ids.incrementAndGet(),
            conversationId = conversationId,
            targetMessageId = targetMessageId,
            kind = kind,
            readyForUi = readyForUi,
            hapticOnCompletion = hapticOnCompletion,
        )
        _request.value = next
        _isSwitching.value = true
        return next
    }

    fun markTreeMutationReady(
        requestId: Long,
        targetMessageId: String?,
    ): SwitchingScrollRequest? {
        val current = _request.value
        if (
            current?.id != requestId ||
            current.kind != SwitchingRequestKind.TREE_MUTATION
        ) {
            return null
        }
        return current.copy(
            targetMessageId = targetMessageId,
            readyForUi = true,
        ).also { _request.value = it }
    }

    fun markConversationReady(requestId: Long): SwitchingScrollRequest? {
        val current = _request.value
        if (
            current?.id != requestId ||
            current.kind != SwitchingRequestKind.CONVERSATION
        ) {
            return null
        }
        return current.copy(readyForUi = true).also { _request.value = it }
    }

    fun isCurrent(requestId: Long): Boolean = _request.value?.id == requestId

    /**
     * Completes only [requestId]. A cancelled/stale transition can therefore never uncover a
     * newer transition that superseded it.
     */
    fun complete(requestId: Long): Boolean {
        if (_request.value?.id != requestId) return false
        _request.value = null
        _isSwitching.value = false
        return true
    }
}
