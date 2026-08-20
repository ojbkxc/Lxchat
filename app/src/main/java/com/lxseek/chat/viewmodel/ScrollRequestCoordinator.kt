package com.lxseek.chat.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class AnimatedScrollDestination {
    MESSAGE,
    ABSOLUTE_BOTTOM,
}

data class AnimatedScrollRequest(
    val id: Long,
    val conversationId: String,
    val targetMessageId: String?,
    val destination: AnimatedScrollDestination = AnimatedScrollDestination.MESSAGE,
    val attachedOnly: Boolean = false,
)

/** Owns one-shot chat scroll requests and their composer/open-transition suppression flags. */
internal class ScrollRequestCoordinator {
    private val ids = AtomicLong(0L)
    private val _request = MutableStateFlow<AnimatedScrollRequest?>(null)
    val request: StateFlow<AnimatedScrollRequest?> = _request.asStateFlow()

    @Volatile
    var suppressNextOpenScroll: Boolean = false

    @Volatile
    var loadingDraft: Boolean = false

    fun requestMessage(conversationId: String?, messageId: String?) {
        if (conversationId == null) return
        _request.value = AnimatedScrollRequest(
            id = ids.incrementAndGet(),
            conversationId = conversationId,
            targetMessageId = messageId,
        )
    }

    fun requestAbsoluteBottomAfter(
        conversationId: String,
        messageId: String,
        attachedOnly: Boolean = false,
    ) {
        _request.value = AnimatedScrollRequest(
            id = ids.incrementAndGet(),
            conversationId = conversationId,
            targetMessageId = messageId,
            destination = AnimatedScrollDestination.ABSOLUTE_BOTTOM,
            attachedOnly = attachedOnly,
        )
    }

    fun complete(requestId: Long) {
        if (_request.value?.id == requestId) {
            _request.value = null
        }
    }

    fun clear() {
        _request.value = null
    }
}
