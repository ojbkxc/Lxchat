package com.lxseek.chat.viewmodel

import com.lxseek.chat.automation.ConversationExecutionCoordinator
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.data.local.RunGraphCommit
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRegenerationServiceTest {
    @Test
    fun rejectsNonBoundaryOutputBeforeTransitionOrRuntimeClaim() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation")
        val result = fixture.service.regenerate(
            fixture.request.copy(
                visiblePath = listOf(
                    SOURCE_USER,
                    TARGET_MODEL,
                    TARGET_MODEL.copy(id = "later-model", runSequence = 2),
                ),
            ),
            state,
        )

        assertFalse(result)
        assertEquals(null, fixture.transitions.request.value)
        assertFalse(state.generating.value)
        coVerify(exactly = 0) {
            fixture.conversations.getMessagesForConversationSnapshot(any())
        }
        state.dispose()
        Unit
    }

    @Test
    fun transitionConflictDelegatesExactUnlaunchedSlotRelease() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation")
        val existing = checkNotNull(
            fixture.transitions.begin("conversation", "other-model", "source-input"),
        )
        coEvery {
            fixture.guidanceDrain.releaseUnlaunchedSlotAndDrain(state, any())
        } just Runs

        assertFalse(fixture.service.regenerate(fixture.request, state))

        coVerify(timeout = 5_000, exactly = 1) {
            fixture.guidanceDrain.releaseUnlaunchedSlotAndDrain(state, any())
        }
        fixture.transitions.abort(existing.id)
        state.dispose()
        Unit
    }

    @Test
    fun fadeAdmissionCommitsReplacementRunBeforeProjectionAndBoundLaunch() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation")
        coEvery {
            fixture.conversations.getMessagesForConversationSnapshot("conversation")
        } returns listOf(SOURCE_USER_ENTITY, TARGET_MODEL_ENTITY)
        coEvery { fixture.conversations.getRun("source-run") } returns SOURCE_RUN
        val createdRun = slot<RunEntity>()
        val createdMessages = slot<List<MessageEntity>>()
        coEvery {
            fixture.conversations.createRunWithMessages(
                run = capture(createdRun),
                messages = capture(createdMessages),
                messageSelectionUpdates = mapOf("source-input" to "new-model"),
                at = any(),
            )
        } answers {
            RunGraphCommit(
                messages = secondArg(),
                messageSelections = thirdArg(),
                runSelections = emptyMap(),
            )
        }
        coEvery { fixture.boundLauncher.launch(any(), state) } just Runs

        assertTrue(fixture.service.regenerate(fixture.request, state))
        val transition = checkNotNull(fixture.transitions.request.value)
        fixture.transitions.acknowledgeFade(transition.id)

        coVerify(timeout = 5_000, exactly = 1) {
            fixture.boundLauncher.launch(
                match {
                    it.conversationId == "conversation" &&
                        it.modelMessageId == "new-model" &&
                        it.modelId == "provider:model" &&
                        it.runId == "new-run" &&
                        it.pass == 0 &&
                        it.callerTag == "regenerate"
                },
                state,
            )
        }
        assertEquals(RegenerationTransitionStage.COMMITTED, fixture.transitions.request.value?.stage)
        assertEquals("source-run", createdRun.captured.parentRunId)
        assertEquals(50L, createdRun.captured.startedAt)
        assertEquals(listOf("new-model"), createdMessages.captured.map { it.id })
        assertEquals("source-input", createdMessages.captured.single().parentId)
        assertEquals(listOf("project:new-model"), fixture.events)
        assertEquals("new-model", state.streamingMessage.value?.id)
        fixture.transitions.complete(transition.id)
        state.dispose()
        Unit
    }

    private class Fixture {
        val conversations = mockk<ConversationRepository>()
        val transitions = RegenerationTransitionCoordinator(fadeTimeoutMs = 5_000L)
        val terminalSettlement = mockk<GenerationTerminalSettlementController>()
        val boundLauncher = mockk<BoundRunGenerationLauncher>()
        val guidanceDrain = mockk<QueuedGuidanceDrainExecutor>()
        val events = mutableListOf<String>()
        private val ids = ArrayDeque(listOf("new-run", "new-model"))
        val service = ConversationRegenerationService(
            conversations = conversations,
            executionCoordinator = ConversationExecutionCoordinator(),
            transitions = transitions,
            terminalSettlement = terminalSettlement,
            boundRunGenerationLauncher = boundLauncher,
            guidanceDrain = guidanceDrain,
            toUiMessage = ::toUiMessage,
            isConversationOpen = { true },
            projectGraph = { _, messages, _, _ ->
                events += "project:${messages.joinToString(",") { it.id }}"
            },
            idFactory = ids::removeFirst,
            clock = { 50L },
        )
        val request = ConversationRegenerationRequest(
            conversationId = "conversation",
            messageId = "target-model",
            modelId = "provider:model",
            providerName = "provider",
            activeKey = "key",
            visiblePath = listOf(SOURCE_USER, TARGET_MODEL),
        )
    }

    private companion object {
        val SOURCE_USER = ChatMessage(
            id = "source-input",
            parentId = "previous-model",
            text = "input",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            timestamp = 1L,
            runId = "source-run",
            runSequence = 0,
        )
        val TARGET_MODEL = ChatMessage(
            id = "target-model",
            parentId = "source-input",
            text = "answer",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 2L,
            runId = "source-run",
            runSequence = 1,
        )
        val SOURCE_USER_ENTITY = MessageEntity(
            id = "source-input",
            conversationId = "conversation",
            parentId = "previous-model",
            text = "input",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            timestamp = 1L,
            runId = "source-run",
            runSequence = 0,
            consumedAtPass = 0,
        )
        val TARGET_MODEL_ENTITY = MessageEntity(
            id = "target-model",
            conversationId = "conversation",
            parentId = "source-input",
            text = "answer",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 2L,
            runId = "source-run",
            runSequence = 1,
        )
        val SOURCE_RUN = RunEntity(
            id = "source-run",
            conversationId = "conversation",
            parentRunId = "parent-run",
            status = RunStatus.COMPLETED,
            activeSlot = null,
            startedAt = 1L,
            lastCheckpointAt = 2L,
            endedAt = 2L,
            endReason = RunEndReason.MODEL_COMPLETED,
        )

        fun toUiMessage(entity: MessageEntity) = ChatMessage(
            id = entity.id,
            parentId = entity.parentId,
            text = entity.text,
            participant = entity.participant,
            status = entity.status,
            timestamp = entity.timestamp,
            modelName = entity.modelName,
            runId = entity.runId,
            runSequence = entity.runSequence,
        )
    }
}
