package com.lxseek.chat.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

internal enum class RegenerationTransitionStage {
    ANIMATING,
    COMMITTED,
}

internal data class RegenerationTransitionRequest(
    val id: Long,
    val conversationId: String,
    val oldMessageId: String,
    val targetUserMessageId: String,
    val stage: RegenerationTransitionStage,
    val fadeFinished: Boolean = false,
    val scrollFinished: Boolean = false,
    val scrollSucceeded: Boolean? = null,
)

/**
 * Coordinates the visual half of Regenerate without mutating the durable conversation graph.
 *
 * Fade and scroll are intentionally independent:
 *  1. alpha zero releases the generation coroutine immediately, so SENDING and the HTTP request
 *     never wait behind animation distance;
 *  2. scroll completion controls only when the transparent old composition may be released.
 *
 * COMMITTED selects the new Run while MessageList retains the old keyed composition. ChatApp clears
 * the transition only after both COMMITTED and scrollFinished are observable, preventing a height
 * collapse during the targeted animation without delaying request startup.
 */
internal class RegenerationTransitionCoordinator(
    private val fadeTimeoutMs: Long = 8_000L,
) {
    private data class ActiveTransition(
        val request: RegenerationTransitionRequest,
        val fadeFinished: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    private val lock = Any()
    private val ids = AtomicLong(0L)
    private var active: ActiveTransition? = null
    private val _request = MutableStateFlow<RegenerationTransitionRequest?>(null)
    val request: StateFlow<RegenerationTransitionRequest?> = _request.asStateFlow()

    fun begin(
        conversationId: String,
        oldMessageId: String,
        targetUserMessageId: String,
    ): RegenerationTransitionRequest? = synchronized(lock) {
        if (active != null) return null
        val request = RegenerationTransitionRequest(
            id = ids.incrementAndGet(),
            conversationId = conversationId,
            oldMessageId = oldMessageId,
            targetUserMessageId = targetUserMessageId,
            stage = RegenerationTransitionStage.ANIMATING,
        )
        active = ActiveTransition(request)
        _request.value = request
        request
    }

    fun acknowledgeFade(requestId: Long) {
        synchronized(lock) {
            val transition =
                active?.takeIf { candidate -> candidate.request.id == requestId } ?: return
            if (!transition.fadeFinished.complete(true)) return
            val updated = transition.request.copy(fadeFinished = true)
            active = transition.copy(request = updated)
            _request.value = updated
        }
    }

    fun acknowledgeScroll(requestId: Long, success: Boolean) {
        synchronized(lock) {
            val transition =
                active?.takeIf { candidate -> candidate.request.id == requestId } ?: return
            if (transition.request.scrollFinished) return
            val updated = transition.request.copy(
                scrollFinished = true,
                scrollSucceeded = success,
            )
            active = transition.copy(request = updated)
            _request.value = updated
        }
    }

    suspend fun awaitFade(requestId: Long): Boolean {
        val transition = synchronized(lock) {
            active?.takeIf { it.request.id == requestId }
        } ?: return false
        return withTimeoutOrNull(fadeTimeoutMs) {
            transition.fadeFinished.await()
        } == true
    }

    fun isAnimating(requestId: Long): Boolean = synchronized(lock) {
        active?.request?.let {
            it.id == requestId && it.stage == RegenerationTransitionStage.ANIMATING
        } == true
    }

    fun markCommitted(requestId: Long): Boolean = synchronized(lock) {
        val transition = active?.takeIf { it.request.id == requestId } ?: return false
        val committed = transition.request.copy(stage = RegenerationTransitionStage.COMMITTED)
        active = transition.copy(request = committed)
        _request.value = committed
        true
    }

    fun complete(requestId: Long): Boolean = synchronized(lock) {
        val transition = active?.takeIf { it.request.id == requestId } ?: return false
        active = null
        _request.value = null
        // Harmless if already complete; wakes a waiter if navigation clears a pre-commit request.
        transition.fadeFinished.complete(false)
        true
    }

    fun abort(requestId: Long): Boolean = complete(requestId)

    fun abortCurrent() {
        val requestId = synchronized(lock) { active?.request?.id } ?: return
        complete(requestId)
    }
}
