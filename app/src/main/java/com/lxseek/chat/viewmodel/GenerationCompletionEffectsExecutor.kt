package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.MessageStatus

internal data class GenerationCompletionEffectsRequest(
    val terminalPersisted: Boolean,
    val status: MessageStatus,
    val interruptedForQueuedSend: Boolean,
    val text: String,
    val conversationId: String,
    val modelMessageId: String,
    val foregroundLeaseAcquired: Boolean,
)

internal data class GenerationCompletionEffectsCallbacks(
    val onMessagePersisted: ((messageId: String, text: String) -> Unit)?,
    val onStreamClear: () -> Unit,
    val onLoadingChange: (Boolean) -> Unit,
    val hasQueuedSends: () -> Boolean,
)

internal fun GenerationCallbacks.completionEffectsCallbacks(
    onMessagePersisted: ((messageId: String, text: String) -> Unit)?,
) = GenerationCompletionEffectsCallbacks(
    onMessagePersisted = onMessagePersisted,
    onStreamClear = onStreamClear,
    onLoadingChange = onLoadingChange,
    hasQueuedSends = hasQueuedSends,
)

/** Executes post-finalization presentation/resource effects without owning Run-state authority. */
internal class GenerationCompletionEffectsExecutor(
    private val isAppInForeground: () -> Boolean,
    private val releaseForegroundLease: (modelMessageId: String) -> Unit,
    private val notify: (text: String, conversationId: String) -> Unit,
) {
    fun execute(
        request: GenerationCompletionEffectsRequest,
        callbacks: GenerationCompletionEffectsCallbacks,
    ) {
        // Terminal UI cleanup is independent of durability: a failed persist must never leave
        // the composer stuck in the generating state or a stale stream on screen (C3).
        callbacks.onStreamClear()
        callbacks.onLoadingChange(false)
        try {
            if (request.terminalPersisted && request.text.isNotBlank()) {
                callbacks.onMessagePersisted?.invoke(request.modelMessageId, request.text)
            }
        } catch (_: Exception) {
            // Indexing is non-authoritative and must never break terminal cleanup.
        }
        if (request.foregroundLeaseAcquired) {
            releaseForegroundLease(request.modelMessageId)
        }

        // A queued intervention ends this pass but not the generation cycle. Notify only when the
        // successful final pass has no remaining guidance, matching the former inline ordering.
        val generationCycleComplete =
            request.status == MessageStatus.SUCCESS &&
                !request.interruptedForQueuedSend &&
                !callbacks.hasQueuedSends()
        if (
            request.terminalPersisted &&
            !isAppInForeground() &&
            generationCycleComplete &&
            request.text.isNotBlank()
        ) {
            notify(request.text, request.conversationId)
        }
    }
}
