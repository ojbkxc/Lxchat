package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import com.lxseek.chat.util.DebugLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationTerminalSettlementControllerTest {
    @Test
    fun boundFailureUsesAuthorizedIdentityAndCommitsOverlayBeforeClear() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        coEvery {
            conversations.finishGeneration(
                FAILED_MESSAGE,
                "conversation",
                "run",
                RunStatus.FAILED,
                RunEndReason.PROVIDER_ERROR,
                false,
                any(),
            )
        } returns true
        val state = ConversationGenerationState("conversation")
        val token = requireNotNull(state.acquireForSend())
        state.bindRun(token, "run", pass = 2)
        val committed = mutableListOf<ChatMessage>()
        state.onStreamCommit = { _, message -> committed += message }

        val success = controller(conversations).finalizeBoundFailure(
            conversationId = "conversation",
            runId = "run",
            pass = 2,
            uiToken = token,
            state = state,
            failedMessage = FAILED_MESSAGE,
            effectId = "failure-effect",
        )

        assertTrue(success)
        assertEquals(listOf(FAILED_MESSAGE), committed)
        assertEquals(null, state.streamingMessage.value)
        assertEquals(
            "failure-effect",
            state.runtimeTraceSnapshot().first { it.commandType == "FinalizationRequested" }
                .effectId,
        )
        assertTrue(state.endGeneration(token))
        state.dispose()
        Unit
    }

    @Test
    fun cancelledActiveRunSettlesThroughStopPersistence() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        coEvery { conversations.requestRunStop("run", any()) } returns true
        coEvery { conversations.finishStoppedGeneration(any(), "run", any()) } returns true
        val state = ConversationGenerationState("conversation")
        val token = requireNotNull(state.acquireForSend())
        state.bindRun(token, "run", pass = 1)
        state.streamUpdate(token, FAILED_MESSAGE.copy(status = MessageStatus.SENDING))

        val claimed = controller(conversations).settleCancelledDurableRun(
            state,
            ConversationGenerationState.RunBindingOutcome.Active,
        )

        assertTrue(claimed)
        coVerify(exactly = 1) { conversations.requestRunStop("run", any()) }
        coVerify(exactly = 1) { conversations.finishStoppedGeneration(any(), "run", any()) }
        assertFalse(state.generating.value)
        assertFalse(state.stopping.value)
        assertEquals(null, state.streamingMessage.value)
        state.dispose()
        Unit
    }

    @Test
    fun noWriterRepairBypassesRuntimeButStillUsesAtomicTerminalTransaction() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        coEvery { conversations.getMessagesForConversationSnapshot("conversation") } returns
            listOf(MESSAGE_ENTITY)
        val persisted = slot<ChatMessage>()
        coEvery {
            conversations.finishGeneration(
                capture(persisted),
                "conversation",
                "run",
                RunStatus.FAILED,
                RunEndReason.PROVIDER_ERROR,
                false,
                any(),
            )
        } returns true
        val snackbars = mutableListOf<String>()
        val state = ConversationGenerationState("conversation")
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any()) } returns Unit
        try {
            controller(conversations, snackbars::add).failGenerationSetup(
                conversationId = "conversation",
                runId = "run",
                modelMessageId = "model",
                uiToken = 1L,
                state = state,
                error = IllegalStateException("private detail"),
            )
        } finally {
            unmockkObject(DebugLog)
        }

        assertEquals(MessageStatus.ERROR, persisted.captured.status)
        assertEquals("Failed to generate", persisted.captured.text)
        assertEquals(listOf("Failed to generate"), snackbars)
        assertTrue(state.runtimeTraceSnapshot().isEmpty())
        state.dispose()
        Unit
    }

    private fun controller(
        conversations: ConversationRepository,
        onSnackbar: (String) -> Unit = {},
    ) = GenerationTerminalSettlementController(
        conversations = conversations,
        stopFinalizer = GenerationFinalizer(conversations) { _, _ -> },
        runFinalizationEffects = RunFinalizationEffectCoordinator(),
        failureText = { "Failed to generate" },
        toUiMessage = { entity ->
            ChatMessage(
                id = entity.id,
                parentId = entity.parentId,
                text = entity.text,
                participant = entity.participant,
                status = entity.status,
                runId = entity.runId,
                runSequence = entity.runSequence,
            )
        },
        onSnackbar = onSnackbar,
    )

    private companion object {
        val FAILED_MESSAGE = ChatMessage(
            id = "model",
            text = "failure",
            participant = Participant.MODEL,
            status = MessageStatus.ERROR,
            runId = "run",
            runSequence = 0,
        )
        val MESSAGE_ENTITY = MessageEntity(
            id = "model",
            conversationId = "conversation",
            text = "",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
            timestamp = 1L,
            runId = "run",
            runSequence = 0,
        )
    }
}
