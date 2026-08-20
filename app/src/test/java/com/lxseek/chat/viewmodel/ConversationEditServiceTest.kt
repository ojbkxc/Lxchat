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

class ConversationEditServiceTest {
    @Test
    fun rejectsNonBoundaryInputBeforeClaimingRuntimeOrReadingRoom() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation")
        val result = fixture.service.edit(
            fixture.request.copy(
                messageId = "later-input",
                visiblePath = listOf(
                    SOURCE_MESSAGE,
                    SOURCE_MESSAGE.copy(
                        id = "later-input",
                        parentId = "model",
                        runSequence = 2,
                    ),
                ),
            ),
            state,
        )

        assertFalse(result)
        assertFalse(state.generating.value)
        coVerify(exactly = 0) {
            fixture.conversations.getMessagesForConversationSnapshot(any())
        }
        coVerify(exactly = 0) {
            fixture.boundLauncher.launch(any(), any())
        }
        state.dispose()
        Unit
    }

    @Test
    fun commitsEditedGraphBeforeProjectionSettlementAndBoundLaunch() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation")
        coEvery {
            fixture.conversations.getMessagesForConversationSnapshot("conversation")
        } returns listOf(SOURCE_ENTITY)
        coEvery { fixture.conversations.getRun("source-run") } returns SOURCE_RUN
        coEvery {
            fixture.inputCloner.clone(
                sourceInputs = listOf(SOURCE_ENTITY),
                destinationRunId = "new-run",
                textOverrides = mapOf("source-input" to "edited text"),
            )
        } returns listOf(EDITED_ENTITY)
        val createdRun = slot<RunEntity>()
        val createdMessages = slot<List<MessageEntity>>()
        coEvery {
            fixture.conversations.createRunWithMessages(
                run = capture(createdRun),
                messages = capture(createdMessages),
                messageSelectionUpdates = EXPECTED_SELECTIONS,
                at = any(),
            )
        } returns RunGraphCommit(
            messages = listOf(EDITED_ENTITY, MODEL_ENTITY),
            messageSelections = EXPECTED_SELECTIONS,
            runSelections = emptyMap(),
        )
        coEvery { fixture.boundLauncher.launch(any(), state) } just Runs

        val result = fixture.service.edit(fixture.request, state)

        assertTrue(result)
        coVerify(timeout = 5_000, exactly = 1) {
            fixture.boundLauncher.launch(
                match {
                    it.conversationId == "conversation" &&
                        it.modelMessageId == "new-model" &&
                        it.runId == "new-run" &&
                        it.pass == 0 &&
                        it.callerTag == "editMessage"
                },
                state,
            )
        }
        assertEquals("source-parent-run", createdRun.captured.parentRunId)
        assertEquals(RunStatus.ACTIVE, createdRun.captured.status)
        assertEquals(listOf("new-user", "new-model"), createdMessages.captured.map { it.id })
        assertEquals(
            listOf(
                "indexed:new-user:edited text",
                "project:new-user,new-model",
                "scroll:new-user",
                "await:new-user",
            ),
            fixture.events,
        )
        assertEquals("new-model", state.streamingMessage.value?.id)
        state.dispose()
        Unit
    }

    private class Fixture {
        val conversations = mockk<ConversationRepository>()
        val inputCloner = mockk<EditedRunInputCloner>()
        val terminalSettlement = mockk<GenerationTerminalSettlementController>()
        val boundLauncher = mockk<BoundRunGenerationLauncher>()
        val events = mutableListOf<String>()
        private val ids = ArrayDeque(listOf("new-run", "new-model"))
        val service = ConversationEditService(
            conversations = conversations,
            executionCoordinator = ConversationExecutionCoordinator(),
            inputCloner = inputCloner,
            terminalSettlement = terminalSettlement,
            boundRunGenerationLauncher = boundLauncher,
            toUiMessage = ::toUiMessage,
            isConversationOpen = { true },
            projectGraph = { _, messages, _, _ ->
                events += "project:${messages.joinToString(",") { it.id }}"
            },
            awaitProjectedPath = { _, messageId -> events += "await:$messageId" },
            onUserMessagePersisted = { messageId, text ->
                events += "indexed:$messageId:$text"
            },
            onScrollToMessage = { events += "scroll:$it" },
            idFactory = ids::removeFirst,
        )
        val request = ConversationEditRequest(
            conversationId = "conversation",
            messageId = "source-input",
            newText = "edited text",
            modelId = "provider:model",
            providerName = "provider",
            activeKey = "key",
            visiblePath = listOf(SOURCE_MESSAGE),
        )
    }

    private companion object {
        val SOURCE_MESSAGE = ChatMessage(
            id = "source-input",
            parentId = "previous",
            text = "original",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            timestamp = 1L,
            runId = "source-run",
            runSequence = 0,
        )
        val SOURCE_ENTITY = MessageEntity(
            id = "source-input",
            conversationId = "conversation",
            parentId = "previous",
            text = "original",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            timestamp = 1L,
            runId = "source-run",
            runSequence = 0,
            consumedAtPass = 0,
        )
        val EDITED_ENTITY = SOURCE_ENTITY.copy(
            id = "new-user",
            text = "edited text",
            timestamp = 10L,
            runId = "new-run",
        )
        val MODEL_ENTITY = MessageEntity(
            id = "new-model",
            conversationId = "conversation",
            parentId = "new-user",
            text = "",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
            timestamp = 11L,
            modelName = "provider:model",
            runId = "new-run",
            runSequence = 1,
        )
        val SOURCE_RUN = RunEntity(
            id = "source-run",
            conversationId = "conversation",
            parentRunId = "source-parent-run",
            status = RunStatus.COMPLETED,
            activeSlot = null,
            startedAt = 1L,
            lastCheckpointAt = 2L,
            endedAt = 2L,
            endReason = RunEndReason.MODEL_COMPLETED,
        )
        val EXPECTED_SELECTIONS: Map<String?, String> = mapOf(
            "previous" to "new-user",
            "new-user" to "new-model",
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
