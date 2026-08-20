package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.repository.ConversationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Coordinates durable conversation metadata changes and deletion cleanup. */
internal class ConversationLifecycleController(
    private val currentConversationId: StateFlow<String?>,
    private val conversations: ConversationRepository,
    private val scope: CoroutineScope,
    private val stopLoop: suspend (String) -> Unit,
    private val withConversationLock: suspend (String, suspend () -> Unit) -> Unit,
    private val removeRuntime: (String) -> Unit,
    private val stopVisibleGeneration: () -> Unit,
    private val openNewChat: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    fun rename(conversationId: String, newTitle: String) {
        scope.launch {
            conversations.updateConversationTitle(conversationId, newTitle)
        }
    }

    fun setSystemPrompt(conversationId: String, promptId: String?) {
        scope.launch {
            val existing = conversations.getConversation(conversationId) ?: return@launch
            conversations.upsertConversation(existing.copy(systemPromptId = promptId))
        }
    }

    fun delete(conversationId: String) {
        if (currentConversationId.value == conversationId) {
            stopVisibleGeneration()
        }
        scope.launch(ioDispatcher) {
            stopLoop(conversationId)
            withConversationLock(conversationId) {
                conversations.deleteConversation(conversationId)
            }
            removeRuntime(conversationId)
            if (currentConversationId.value == conversationId) {
                withContext(mainDispatcher) { openNewChat() }
            }
        }
    }
}
