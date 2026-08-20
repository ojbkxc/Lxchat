package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.ChatConversation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Treats observing an unread selected conversation as its durable read boundary. */
internal class UnreadGenerationAcknowledger(
    private val currentConversation: Flow<ChatConversation?>,
    private val conversations: ConversationRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun start() {
        scope.launch(ioDispatcher) {
            currentConversation
                .filterNotNull()
                .filter { it.hasUnreadGeneration }
                .collect { conversation ->
                    conversations.setConversationUnreadGeneration(conversation.id, unread = false)
                }
        }
    }
}
