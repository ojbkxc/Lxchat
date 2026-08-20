package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.ConversationCommand
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunEffectIdentity
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationStopAdapterTest {
    @Test
    fun noOpenConversationOrRuntimeIsANoOp() {
        val fixture = Fixture(currentConversationId = null)

        fixture.adapter.stopVisibleConversation()
        fixture.currentConversationId.value = "missing"
        every { fixture.registry.get("missing") } returns null
        fixture.adapter.stopVisibleConversation()

        verify(exactly = 0) { fixture.state.requestStop(any()) }
    }

    @Test
    fun missingStreamingOverlaySnapshotsOnlyVisibleInflightModelRows() {
        val fixture = Fixture()
        fixture.renderStore.replaceGraph(
            allMessages = listOf(USER, SENDING_MODEL, SUCCESS_MODEL),
            selectedChildren = emptyMap(),
        )
        fixture.stubStop(stoppedMessage = null)
        val capturedMessages = slot<List<ChatMessage>>()
        fixture.stubFinalization(
            outcome = ConversationGenerationState.StopFinalizationOutcome.RECORDED,
            success = true,
            capturedMessages = capturedMessages,
        )

        fixture.adapter.stopVisibleConversation()

        assertEquals(listOf("sending"), capturedMessages.captured.map { it.id })
        assertEquals(MessageStatus.STOPPED, capturedMessages.captured.single().status)
        assertEquals(
            MessageStatus.STOPPED,
            fixture.renderStore.allMessages.single { it.id == "sending" }.status,
        )
        assertEquals(MessageStatus.SUCCESS, fixture.renderStore.allMessages
            .single { it.id == "success" }.status)
        verify(exactly = 1) { fixture.state.clearStoppedOverlay() }
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun staleCompletionCannotClearOrCommitStoppedOverlay() {
        val fixture = Fixture()
        fixture.renderStore.commitGraph(
            committedMessages = listOf(USER, SENDING_MODEL),
            selectedChildren = emptyMap(),
            streamingMessage = SENDING_MODEL,
        )
        val stopped = SENDING_MODEL.copy(status = MessageStatus.STOPPED)
        fixture.stubStop(stoppedMessage = stopped)
        fixture.stubFinalization(
            outcome = ConversationGenerationState.StopFinalizationOutcome.REJECTED,
            success = true,
        )

        fixture.adapter.stopVisibleConversation()

        assertEquals(MessageStatus.SENDING, fixture.renderStore.streamingMessage?.status)
        verify(exactly = 0) { fixture.state.clearStoppedOverlay() }
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun acceptedPersistenceFailureReportsErrorWithoutClearingOverlay() {
        val fixture = Fixture()
        fixture.stubStop(stoppedMessage = SENDING_MODEL.copy(status = MessageStatus.STOPPED))
        fixture.stubFinalization(
            outcome = ConversationGenerationState.StopFinalizationOutcome.FAILED,
            success = false,
        )

        fixture.adapter.stopVisibleConversation()

        assertEquals(listOf("failed"), fixture.failures)
        verify(exactly = 0) { fixture.state.clearStoppedOverlay() }
    }

    private class Fixture(currentConversationId: String? = "conversation") {
        val currentConversationId = MutableStateFlow(currentConversationId)
        val registry = mockk<ConversationStateRegistry>()
        val state = mockk<ConversationGenerationState>()
        val renderStore = ConversationRenderStore()
        val finalizer = mockk<GenerationFinalizer>()
        val failures = mutableListOf<String>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val adapter = GenerationStopAdapter(
            currentConversationId = this.currentConversationId,
            registry = registry,
            renderStore = renderStore,
            finalizer = finalizer,
            failureText = { "failed" },
            onFailure = failures::add,
        )

        init {
            every { registry.get("conversation") } returns state
            every { state.scope } returns scope
            every { state.clearStoppedOverlay() } just Runs
        }

        fun stubStop(stoppedMessage: ChatMessage?) {
            every { state.requestStop(any()) } answers {
                firstArg<(ConversationGenerationState.StopResult) -> Unit>().invoke(
                    ConversationGenerationState.StopResult(
                        stoppedMessage = stoppedMessage,
                        conversationId = "conversation",
                        runId = "run",
                        finalizationEffect = RunEffect.FinalizeStop(IDENTITY),
                    ),
                )
                completedJob()
            }
        }

        fun stubFinalization(
            outcome: ConversationGenerationState.StopFinalizationOutcome,
            success: Boolean,
            capturedMessages: io.mockk.CapturingSlot<List<ChatMessage>>? = null,
        ) {
            coEvery { state.finishStopFinalization(any()) } returns outcome
            every {
                finalizer.launchStopFinalization(
                    scope = scope,
                    identity = IDENTITY,
                    messages = if (capturedMessages != null) capture(capturedMessages) else any(),
                    onFinalized = any(),
                )
            } answers {
                val callback = arg<suspend (ConversationCommand.PersistenceSettled) -> Unit>(3)
                runBlocking {
                    callback(ConversationCommand.PersistenceSettled(IDENTITY, success))
                }
                completedJob()
            }
        }

        private fun completedJob(): CompletableJob = Job().also { it.complete() }
    }

    private companion object {
        val IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 1L,
            runId = "run",
            pass = 0,
            effectId = "stop-1",
        )
        val USER = ChatMessage(
            id = "user",
            text = "input",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val SENDING_MODEL = ChatMessage(
            id = "sending",
            parentId = "user",
            text = "partial",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
        )
        val SUCCESS_MODEL = ChatMessage(
            id = "success",
            parentId = "user",
            text = "done",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
        )
    }
}
