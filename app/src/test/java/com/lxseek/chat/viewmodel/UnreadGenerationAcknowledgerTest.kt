package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.ChatConversation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UnreadGenerationAcknowledgerTest {
    @Test
    fun onlyUnreadObservedConversationsCrossTheDurableReadBoundary() = runTest {
        val current = MutableStateFlow<ChatConversation?>(null)
        val conversations = mockk<ConversationRepository>()
        coEvery { conversations.setConversationUnreadGeneration(any(), false) } returns true
        UnreadGenerationAcknowledger(
            currentConversation = current,
            conversations = conversations,
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ).start()
        runCurrent()

        current.value = ChatConversation("read", "Read", hasUnreadGeneration = false)
        runCurrent()
        coVerify(exactly = 0) { conversations.setConversationUnreadGeneration(any(), any()) }

        current.value = ChatConversation("unread", "Unread", hasUnreadGeneration = true)
        runCurrent()
        coVerify(exactly = 1) {
            conversations.setConversationUnreadGeneration("unread", unread = false)
        }
    }
}
