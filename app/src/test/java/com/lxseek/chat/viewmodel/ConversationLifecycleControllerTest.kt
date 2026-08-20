package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.ChatEntity
import com.lxseek.chat.data.repository.ConversationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationLifecycleControllerTest {
    @Test
    fun renamePersistsTheExactTitle() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.updateConversationTitle(any(), any()) } returns true

        fixture.controller.rename("conversation", "New title")
        runCurrent()

        coVerify(exactly = 1) {
            fixture.conversations.updateConversationTitle("conversation", "New title")
        }
    }

    @Test
    fun systemPromptUpdatesOnlyAnExistingConversation() = runTest {
        val fixture = Fixture(this)
        val existing = ChatEntity("conversation", "Title", systemPromptId = "old")
        coEvery { fixture.conversations.getConversation("conversation") } returns existing
        coEvery { fixture.conversations.getConversation("missing") } returns null
        coEvery { fixture.conversations.upsertConversation(any()) } returns Unit

        fixture.controller.setSystemPrompt("conversation", "new")
        fixture.controller.setSystemPrompt("missing", "ignored")
        runCurrent()

        coVerify(exactly = 1) {
            fixture.conversations.upsertConversation(existing.copy(systemPromptId = "new"))
        }
    }

    @Test
    fun visibleDeletionPreservesStopLockCleanupAndNewChatOrder() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }

        fixture.controller.delete("conversation")
        assertEquals(listOf("stop"), fixture.events)
        runCurrent()

        assertEquals(
            listOf("stop", "stop-loop", "lock-start", "delete", "lock-end", "remove", "new"),
            fixture.events,
        )
    }

    @Test
    fun deletionDoesNotStopOrReplaceAnotherVisibleConversation() = runTest {
        val fixture = Fixture(this, currentConversationId = "other")
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }

        fixture.controller.delete("conversation")
        runCurrent()

        assertEquals(listOf("stop-loop", "lock-start", "delete", "lock-end", "remove"), fixture.events)
        assertTrue("stop" !in fixture.events)
        assertTrue("new" !in fixture.events)
    }

    @Test
    fun visibilityIsRecheckedAfterDurableDeletion() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }
        fixture.onRemove = { fixture.currentConversationId.value = "other" }

        fixture.controller.delete("conversation")
        runCurrent()

        assertTrue("stop" in fixture.events)
        assertTrue("new" !in fixture.events)
    }

    private class Fixture(
        testScope: kotlinx.coroutines.test.TestScope,
        currentConversationId: String? = "conversation",
    ) {
        val conversations = mockk<ConversationRepository>()
        val currentConversationId = MutableStateFlow(currentConversationId)
        val events = mutableListOf<String>()
        var onRemove: () -> Unit = {}
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val controller = ConversationLifecycleController(
            currentConversationId = this.currentConversationId,
            conversations = conversations,
            scope = testScope,
            stopLoop = { events += "stop-loop" },
            withConversationLock = { _, block ->
                events += "lock-start"
                block()
                events += "lock-end"
            },
            removeRuntime = {
                events += "remove"
                onRemove()
            },
            stopVisibleGeneration = { events += "stop" },
            openNewChat = { events += "new" },
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )
    }
}
