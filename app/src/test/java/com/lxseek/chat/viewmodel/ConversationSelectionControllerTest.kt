package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.ChatEntity
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSelectionControllerTest {
    @Test
    fun selectPublishesConversationAndModelBeforeUiReadiness() = runTest {
        val fixture = Fixture(backgroundScope)
        coEvery { fixture.conversations.getConversation("conversation") } returns
            ChatEntity("conversation", "Title", modelId = "provider:model")

        fixture.controller.selectConversation("conversation", hapticOnCompletion = false)
        runCurrent()

        assertEquals("conversation", fixture.controller.currentConversationId.value)
        assertEquals("provider:model", fixture.controller.currentActiveModel.value)
        assertFalse(fixture.controller.isNewChatMode.value)
        val request = fixture.controller.switchingScrollRequest.value
        assertEquals(SwitchingRequestKind.CONVERSATION, request?.kind)
        assertTrue(request?.readyForUi == true)
        assertFalse(request?.hapticOnCompletion ?: true)
        assertTrue(fixture.controller.completeSwitchingScroll(checkNotNull(request).id))

        fixture.controller.selectConversation("conversation")
        assertNull(fixture.controller.switchingScrollRequest.value)
        coVerify(exactly = 1) { fixture.conversations.getConversation("conversation") }
    }

    @Test
    fun newChatKeepsOldConversationUntilFadeThenClearsProjection() = runTest {
        val fadeGate = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, fadeDelay = { fadeGate.await() })
        fixture.controller.publishAcceptedConversation("conversation")

        fixture.controller.createNewChat()

        assertTrue(fixture.controller.isNewChatMode.value)
        assertTrue(fixture.controller.isTransitioningToNewChat.value)
        assertEquals("conversation", fixture.controller.currentConversationId.value)
        assertEquals(1, fixture.clearPromptCount)
        assertEquals(0, fixture.clearSettingsCount)
        assertEquals(2L, fixture.controller.newChatEntryId.value)

        fadeGate.complete(Unit)
        runCurrent()

        assertNull(fixture.controller.currentConversationId.value)
        assertFalse(fixture.controller.isTransitioningToNewChat.value)
        assertEquals(1, fixture.clearSettingsCount)
        assertEquals(1, fixture.clearGraphCount)
        assertEquals(1, fixture.abortRegenerationCount)
    }

    @Test
    fun newerConversationSelectionSupersedesPendingNewChat() = runTest {
        val fadeGate = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, fadeDelay = { fadeGate.await() })
        fixture.controller.publishAcceptedConversation("old")
        coEvery { fixture.conversations.getConversation("new") } returns
            ChatEntity("new", "New", modelId = "new-model")

        fixture.controller.createNewChat()
        fixture.controller.selectConversation("new")
        fadeGate.complete(Unit)
        runCurrent()

        assertEquals("new", fixture.controller.currentConversationId.value)
        assertEquals("new-model", fixture.controller.currentActiveModel.value)
        assertFalse(fixture.controller.isNewChatMode.value)
        assertFalse(fixture.controller.isTransitioningToNewChat.value)
        assertEquals(0, fixture.clearSettingsCount)
        assertEquals(0, fixture.clearGraphCount)
        assertEquals(SwitchingRequestKind.CONVERSATION, fixture.controller
            .switchingScrollRequest.value?.kind)
    }

    @Test
    fun missingTargetDoesNotReplaceTheCurrentConversation() = runTest {
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any()) } returns Unit
        try {
            val fixture = Fixture(backgroundScope)
            fixture.controller.publishAcceptedConversation("current")
            coEvery { fixture.conversations.getConversation("missing") } returns null

            fixture.controller.selectConversation("missing")
            runCurrent()

            assertEquals("current", fixture.controller.currentConversationId.value)
            assertFalse(fixture.controller.isNewChatMode.value)
            assertNull(fixture.controller.switchingScrollRequest.value)
            coVerify(exactly = 1) { fixture.conversations.getConversation("missing") }
        } finally {
            unmockkObject(DebugLog)
        }
    }

    @Test
    fun projectionFailureOnlyReleasesItsMatchingSwitchRequest() = runTest {
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any()) } returns Unit
        try {
            val fixture = Fixture(backgroundScope)
            coEvery { fixture.conversations.getConversation("conversation") } returns
                ChatEntity("conversation", "Title")

            fixture.controller.selectConversation("conversation")
            runCurrent()
            val request = checkNotNull(fixture.controller.switchingScrollRequest.value)

            fixture.controller.failConversationLoad("stale-conversation")
            assertEquals(request, fixture.controller.switchingScrollRequest.value)

            fixture.controller.failConversationLoad("conversation")
            assertNull(fixture.controller.switchingScrollRequest.value)
            assertEquals("conversation", fixture.controller.currentConversationId.value)
            assertFalse(fixture.controller.isNewChatMode.value)
        } finally {
            unmockkObject(DebugLog)
        }
    }

    @Test
    fun branchSelectionCommitsRoomBeforePublishingReadyTarget() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.publishAcceptedConversation("conversation")
        fixture.renderStore.replaceGraph(
            allMessages = listOf(PARENT, FIRST_BRANCH, SECOND_BRANCH),
            selectedChildren = mapOf("parent" to "first"),
        )
        coEvery {
            fixture.conversations.selectRunBranch(
                conversationId = "conversation",
                parentRunId = "parent-run",
                runId = "second-run",
                messageSelections = mapOf("parent" to "second"),
                at = any(),
            )
        } coAnswers {
            assertFalse(fixture.controller.switchingScrollRequest.value?.readyForUi ?: true)
        }

        fixture.controller.switchBranch("parent", "first", direction = 1)
        runCurrent()

        assertEquals("second", fixture.renderStore.selectedChildren["parent"])
        val request = fixture.controller.switchingScrollRequest.value
        assertEquals(SwitchingRequestKind.TREE_MUTATION, request?.kind)
        assertEquals("second", request?.targetMessageId)
        assertTrue(request?.readyForUi == true)
        coVerify(exactly = 1) {
            fixture.conversations.selectRunBranch(
                "conversation",
                "parent-run",
                "second-run",
                mapOf("parent" to "second"),
                any(),
            )
        }
        fixture.registry.remove("conversation")
    }

    @Test
    fun activeRunRejectsBranchMutationBeforeRoom() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.publishAcceptedConversation("conversation")
        fixture.renderStore.replaceGraph(
            allMessages = listOf(PARENT, FIRST_BRANCH, SECOND_BRANCH),
            selectedChildren = mapOf("parent" to "first"),
        )
        val state = fixture.registry.getOrCreate("conversation")
        assertTrue(state.acquireForSend() != null)

        fixture.controller.switchBranch("parent", "first", direction = 1)
        runCurrent()

        assertEquals("first", fixture.renderStore.selectedChildren["parent"])
        assertNull(fixture.controller.switchingScrollRequest.value)
        coVerify(exactly = 0) {
            fixture.conversations.selectRunBranch(any(), any(), any(), any(), any())
        }
        fixture.registry.remove("conversation")
    }

    private class Fixture(
        scope: CoroutineScope,
        fadeDelay: suspend () -> Unit = {},
    ) {
        val conversations = mockk<ConversationRepository>()
        val registry = ConversationStateRegistry()
        val renderStore = ConversationRenderStore()
        var clearGraphCount = 0
        var clearPromptCount = 0
        var clearSettingsCount = 0
        var abortRegenerationCount = 0
        val controller = ConversationSelectionController(
            scope = scope,
            conversations = conversations,
            registry = registry,
            defaultModel = MutableStateFlow("default-model"),
            scrollRequests = ScrollRequestCoordinator(),
            renderStore = { renderStore },
            clearConversationGraph = { clearGraphCount += 1 },
            clearPendingSystemPrompt = { clearPromptCount += 1 },
            clearPendingConversationSettings = { clearSettingsCount += 1 },
            abortRegeneration = { abortRegenerationCount += 1 },
            fadeDelay = fadeDelay,
        )
    }

    private companion object {
        val PARENT = ChatMessage(
            id = "parent",
            text = "input",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            timestamp = 1L,
            runId = "parent-run",
        )
        val FIRST_BRANCH = ChatMessage(
            id = "first",
            parentId = "parent",
            text = "first",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 2L,
            runId = "first-run",
        )
        val SECOND_BRANCH = ChatMessage(
            id = "second",
            parentId = "parent",
            text = "second",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 3L,
            runId = "second-run",
        )
    }
}
