package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.flow.StateFlow

/**
 * Adapts the visible UI's Stop intent to the matching conversation runtime and its authorized
 * durable finalization effect. It owns no Run state, Job, scope, barrier, or effect identity.
 */
internal class GenerationStopAdapter(
    private val currentConversationId: StateFlow<String?>,
    private val registry: ConversationStateRegistry,
    private val renderStore: ConversationRenderStore,
    private val finalizer: GenerationFinalizer,
    private val failureText: () -> String,
    private val onFailure: (String) -> Unit,
) {
    fun stopVisibleConversation() {
        val conversationId = currentConversationId.value ?: return
        val state = registry.get(conversationId) ?: return
        state.requestStop { result ->
            val stoppedMessage = result.stoppedMessage
            val messages = when {
                stoppedMessage != null -> listOf(stoppedMessage)
                currentConversationId.value == result.conversationId ->
                    snapshotVisibleStoppedRows()
                else -> emptyList()
            }
            val effect = result.finalizationEffect ?: return@requestStop
            // The finalizer receives exactly the reducer-emitted identity and returns an identified
            // persistence result to this same runtime host. It cannot release the slot itself.
            finalizer.launchStopFinalization(
                scope = state.scope,
                identity = effect.identity,
                messages = messages,
                onFinalized = { completion ->
                    val outcome = state.finishStopFinalization(completion)
                    // Delayed/duplicate/stale completions cannot change runtime or presentation.
                    if (!outcome.accepted) return@launchStopFinalization
                    if (completion.success) {
                        // Room invalidation and the runtime projection settle asynchronously. Keep
                        // the exact terminal overlay visible until the durable result is accepted.
                        if (
                            stoppedMessage != null &&
                            currentConversationId.value == result.conversationId
                        ) {
                            renderStore.commitTerminalStreamingMessage(stoppedMessage)
                        }
                        state.clearStoppedOverlay()
                    } else {
                        onFailure(failureText())
                    }
                },
            )
        }
    }

    private fun snapshotVisibleStoppedRows(): List<ChatMessage> = runCatching {
        renderStore.allMessages.mapNotNull { message ->
            if (
                message.participant == Participant.MODEL &&
                message.status.isInFlight()
            ) {
                message.copy(status = MessageStatus.STOPPED).also { stopped ->
                    renderStore.updateAllMessages { rows ->
                        rows.map { if (it.id == message.id) stopped else it }
                    }
                }
            } else {
                null
            }
        }
    }.getOrElse { error ->
        DebugLog.e("GenerationStopAdapter", "Failed to snapshot stopped render rows", error)
        emptyList()
    }
}

private fun MessageStatus.isInFlight(): Boolean =
    this == MessageStatus.SENDING ||
        this == MessageStatus.THINKING ||
        this == MessageStatus.TOOL_CALLING ||
        this == MessageStatus.TRANSCRIBING
