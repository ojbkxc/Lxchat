package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ChatMessage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Routes one conversation's private generation state into the global open-conversation UI mirror.
 *
 * The current-id check is deliberately performed on every emission. Structured cancellation in
 * ChatViewModel should stop the previous collector during a switch, but this identity gate is the
 * correctness boundary: even a late or accidentally leaked collector can never overwrite the
 * newly opened conversation's streaming/thinking state.
 */
internal class ConversationGenerationMirror(
    private val currentConversationId: StateFlow<String?>,
    private val onSnapshot: (conversationId: String, snapshot: Snapshot) -> Unit,
) {
    data class Snapshot(
        val streamingMessage: ChatMessage?,
        val isLoading: Boolean,
        val isGenerating: Boolean,
    )

    fun publishCurrent(conversationId: String, state: ConversationGenerationState) {
        publishIfCurrent(
            conversationId,
            Snapshot(
                streamingMessage = state.streamingMessage.value,
                isLoading = state.isLoading.value,
                isGenerating = state.generating.value,
            ),
        )
    }

    suspend fun collect(conversationId: String, state: ConversationGenerationState) {
        combine(
            state.streamingMessage,
            state.isLoading,
            state.generating,
        ) { streamingMessage, isLoading, isGenerating ->
            Snapshot(streamingMessage, isLoading, isGenerating)
        }.distinctUntilChanged().collect { snapshot ->
            publishIfCurrent(conversationId, snapshot)
        }
    }

    private fun publishIfCurrent(conversationId: String, snapshot: Snapshot) {
        if (currentConversationId.value == conversationId) {
            onSnapshot(conversationId, snapshot)
        }
    }
}
