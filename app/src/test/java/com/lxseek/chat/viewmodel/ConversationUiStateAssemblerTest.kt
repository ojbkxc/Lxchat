package com.lxseek.chat.viewmodel

import android.content.Context
import com.lxseek.chat.automation.ConversationExecutionCoordinator
import com.lxseek.chat.data.local.ChatEntity
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.DebugLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationUiStateAssemblerTest {
    @Test
    fun `room graph becomes ready atomically before runtime overlay is published`() = runTest {
        val conversations = mockk<ConversationRepository>()
        val registry = mockk<ConversationStateRegistry>()
        val executionCoordinator = mockk<ConversationExecutionCoordinator>()
        val state = generationState(isLoading = true, generating = false)
        val roomMessages = MutableSharedFlow<List<MessageEntity>>(replay = 1)
        coEvery { conversations.ensureRunRecovery() } returns Unit
        coEvery { conversations.fixStuckMessages(CONVERSATION_ID) } returns Unit
        coEvery { conversations.getConversation(CONVERSATION_ID) } returns ChatEntity(
            id = CONVERSATION_ID,
            title = "Conversation",
            selectedBranchesJson = "{\"null\":\"root\"}",
        )
        every { conversations.getUiMessagesForConversation(CONVERSATION_ID, null) } returns
            roomMessages
        every { registry.getOrCreate(CONVERSATION_ID) } returns state
        every { executionCoordinator.activeAutomationConversationIds } returns
            MutableStateFlow(emptySet())
        val currentConversationId = MutableStateFlow<String?>(null)
        val assembler = ConversationUiStateAssembler(
            conversations = conversations,
            registry = registry,
            executionCoordinator = executionCoordinator,
            currentConversationId = currentConversationId,
            appContext = mockk<Context>(relaxed = true),
            scope = backgroundScope,
            projectionDispatcher = StandardTestDispatcher(testScheduler),
        )
        assembler.start()

        currentConversationId.value = CONVERSATION_ID
        runCurrent()
        assertNull(assembler.loadedMessagesConversationId.value)
        assertFalse(assembler.isLoading.value)

        roomMessages.emit(emptyList())
        runCurrent()

        assertEquals(CONVERSATION_ID, assembler.loadedMessagesConversationId.value)
        assertEquals(mapOf<String?, String>(null to "root"), assembler.renderStore.selectedChildren)
        assertTrue(assembler.isLoading.value)
        assertNull(assembler.generatingInConversationId.value)
        coVerify(exactly = 1) { conversations.ensureRunRecovery() }
        coVerify(exactly = 1) { conversations.fixStuckMessages(CONVERSATION_ID) }

        currentConversationId.value = null
        runCurrent()
        assertNull(assembler.loadedMessagesConversationId.value)
        assertFalse(assembler.isLoading.value)
        assertNull(assembler.generatingInConversationId.value)
        assertEquals(emptyList<ChatMessage>(), assembler.renderStore.allMessages)
    }

    @Test
    fun `active automation prevents foreground recovery from stopping its live row`() = runTest {
        val conversations = mockk<ConversationRepository>()
        val registry = mockk<ConversationStateRegistry>()
        val executionCoordinator = mockk<ConversationExecutionCoordinator>()
        val state = generationState(isLoading = false, generating = false)
        coEvery { conversations.ensureRunRecovery() } returns Unit
        coEvery { conversations.getConversation(CONVERSATION_ID) } returns ChatEntity(
            id = CONVERSATION_ID,
            title = "Conversation",
        )
        every { conversations.getUiMessagesForConversation(CONVERSATION_ID, null) } returns
            MutableStateFlow(emptyList())
        every { registry.getOrCreate(CONVERSATION_ID) } returns state
        every { executionCoordinator.activeAutomationConversationIds } returns
            MutableStateFlow(setOf(CONVERSATION_ID))
        val currentConversationId = MutableStateFlow<String?>(CONVERSATION_ID)
        val assembler = ConversationUiStateAssembler(
            conversations = conversations,
            registry = registry,
            executionCoordinator = executionCoordinator,
            currentConversationId = currentConversationId,
            appContext = mockk<Context>(relaxed = true),
            scope = backgroundScope,
            projectionDispatcher = StandardTestDispatcher(testScheduler),
        )

        assembler.start()
        runCurrent()

        assertEquals(CONVERSATION_ID, assembler.loadedMessagesConversationId.value)
        coVerify(exactly = 0) { conversations.fixStuckMessages(any()) }
    }

    @Test
    fun `late callbacks for another conversation cannot change open projection state`() = runTest {
        val currentConversationId = MutableStateFlow<String?>(CONVERSATION_ID)
        val assembler = ConversationUiStateAssembler(
            conversations = mockk(),
            registry = mockk(),
            executionCoordinator = mockk(),
            currentConversationId = currentConversationId,
            appContext = mockk(relaxed = true),
            scope = backgroundScope,
            projectionDispatcher = StandardTestDispatcher(testScheduler),
        )

        assembler.markActive("other")
        assertFalse(assembler.isLoading.value)
        assertNull(assembler.generatingInConversationId.value)

        assembler.markActive(CONVERSATION_ID)
        assembler.markIdle("other")
        assertTrue(assembler.isLoading.value)
        assertEquals(CONVERSATION_ID, assembler.generatingInConversationId.value)
    }

    @Test
    fun `current projection failure is reported without replacing the selection`() = runTest {
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any(), any()) } returns Unit
        try {
            val conversations = mockk<ConversationRepository>()
            coEvery { conversations.ensureRunRecovery() } throws IllegalStateException("failed")
            val failedIds = mutableListOf<String>()
            val assembler = ConversationUiStateAssembler(
                conversations = conversations,
                registry = mockk(),
                executionCoordinator = mockk(),
                currentConversationId = MutableStateFlow(CONVERSATION_ID),
                appContext = mockk(relaxed = true),
                scope = backgroundScope,
                projectionDispatcher = StandardTestDispatcher(testScheduler),
                onConversationLoadFailed = failedIds::add,
            )
            assembler.renderStore.replaceConversation(
                allMessages = listOf(
                    ChatMessage(
                        id = "old-message",
                        text = "old projection",
                        participant = Participant.USER,
                        status = MessageStatus.SUCCESS,
                        timestamp = 1L,
                    )
                ),
                selectedChildren = mapOf(null to "old-message"),
            )
            assembler.markActive(CONVERSATION_ID)

            assembler.start()
            runCurrent()

            assertEquals(listOf(CONVERSATION_ID), failedIds)
            assertNull(assembler.loadedMessagesConversationId.value)
            assertFalse(assembler.isLoading.value)
            assertNull(assembler.generatingInConversationId.value)
            assertEquals(emptyList<ChatMessage>(), assembler.renderStore.allMessages)
            assertEquals(emptyMap<String?, String>(), assembler.renderStore.selectedChildren)
        } finally {
            unmockkObject(DebugLog)
        }
    }

    private fun generationState(
        isLoading: Boolean,
        generating: Boolean,
    ): ConversationGenerationState = mockk<ConversationGenerationState>().also { state ->
        every { state.streamingMessage } returns MutableStateFlow(null)
        every { state.isLoading } returns MutableStateFlow(isLoading)
        every { state.generating } returns MutableStateFlow(generating)
    }

    private companion object {
        const val CONVERSATION_ID = "conversation"
    }
}
