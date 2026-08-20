package com.lxseek.chat.viewmodel

import com.lxseek.chat.automation.ConversationExecutionCoordinator
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import com.lxseek.chat.util.Constants
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationBranchMutationServiceTest {
    @Test
    fun compactBoundaryUsesDedicatedTransactionAndProjectsRemainingGraph() = runTest {
        val conversations = mockk<ConversationRepository>()
        val compactId = "${Constants.COMPACT_MSG_PREFIX}one"
        val remaining = entity("user", null, Participant.USER)
        val events = mutableListOf<String>()
        coEvery { conversations.getLiveRun("conversation") } coAnswers {
            events += "live-check"
            null
        }
        coEvery { conversations.removeContextCompact(compactId) } coAnswers {
            events += "remove"
            true
        }
        coEvery { conversations.getMessagesForConversationSnapshot("conversation") } coAnswers {
            events += "snapshot"
            listOf(remaining)
        }
        coEvery { conversations.restoreBranchSelections("conversation") } coAnswers {
            events += "selections"
            emptyMap()
        }
        val failed = mutableListOf<Long?>()
        val service = service(
            conversations = conversations,
            events = events,
            onFailed = failed::add,
        )
        val state = ConversationGenerationState("conversation")

        val previewCount = service.delete(
            conversationId = "conversation",
            messageId = compactId,
            state = state,
            snapshot = listOf(chat(compactId, null, Participant.MODEL)),
        )
        advanceUntilIdle()

        assertEquals(1, previewCount)
        assertEquals(
            listOf("start", "live-check", "remove", "snapshot", "selections", "project:user", "settle:user"),
            events,
        )
        assertEquals(emptyList<Long?>(), failed)
        state.dispose()
        Unit
    }

    @Test
    fun structuralDeletionCommitsRoomBeforeDeletingFilesAndSettlingUi() = runTest {
        val conversations = mockk<ConversationRepository>()
        val user = entity("user", null, Participant.USER, sequence = 0)
        val model = entity("model", "user", Participant.MODEL, sequence = 1)
        val events = mutableListOf<String>()
        coEvery { conversations.getLiveRun("conversation") } returns null
        coEvery { conversations.getRunsForConversationSnapshot("conversation") } returns
            listOf(run())
        coEvery { conversations.getMessagesForConversationSnapshot("conversation") } returns
            listOf(user, model)
        coEvery { conversations.restoreBranchSelections("conversation") } returns
            mapOf("user" to "model")
        coEvery { conversations.restoreRunBranchSelections("conversation") } returns emptyMap()
        coEvery {
            conversations.deleteMessageSubtree(
                conversationId = "conversation",
                rootMessageId = "model",
                staleMessageIds = any(),
                rootRunIdsToDelete = any(),
                messageSelections = any(),
                runSelections = any(),
                at = any(),
            )
        } coAnswers {
            events += "room-commit"
            true
        }
        coEvery { conversations.deleteMessageFiles(any()) } coAnswers {
            events += "file-delete"
        }
        val service = service(conversations, events)
        val state = ConversationGenerationState("conversation")

        val previewCount = service.delete(
            conversationId = "conversation",
            messageId = "model",
            state = state,
            snapshot = listOf(chat("user", null, Participant.USER), chat("model", "user", Participant.MODEL)),
        )
        advanceUntilIdle()

        assertEquals(1, previewCount)
        assertEquals(
            listOf("start", "room-commit", "file-delete", "project:user", "settle:user"),
            events,
        )
        state.dispose()
        Unit
    }

    @Test
    fun activeRunRejectsDeletionBeforeLaunchingMutation() = runTest {
        val conversations = mockk<ConversationRepository>()
        val state = ConversationGenerationState("conversation")
        requireNotNull(state.acquireForSend())

        val previewCount = service(conversations, mutableListOf()).delete(
            conversationId = "conversation",
            messageId = "model",
            state = state,
            snapshot = listOf(chat("model", null, Participant.MODEL)),
        )
        advanceUntilIdle()

        assertEquals(0, previewCount)
        coVerify(exactly = 0) { conversations.getLiveRun(any()) }
        state.dispose()
        Unit
    }

    private fun kotlinx.coroutines.test.TestScope.service(
        conversations: ConversationRepository,
        events: MutableList<String>,
        onFailed: (Long?) -> Unit = { events += "failed:$it" },
    ) = ConversationBranchMutationService(
        scope = this,
        conversations = conversations,
        executionCoordinator = ConversationExecutionCoordinator(),
        toUiMessage = { entity ->
            chat(entity.id, entity.parentId, entity.participant)
        },
        isConversationOpen = { true },
        projectGraph = { messages, _ -> events += "project:${messages.joinToString { it.id }}" },
        onMutationStart = {
            events += "start"
            7L
        },
        onMutationSettling = { _, target -> events += "settle:$target" },
        onMutationFailed = onFailed,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun entity(
        id: String,
        parentId: String?,
        participant: Participant,
        sequence: Long = 0,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = id,
        participant = participant,
        timestamp = sequence + 1,
        runId = "run",
        runSequence = sequence,
    )

    private fun chat(id: String, parentId: String?, participant: Participant) = ChatMessage(
        id = id,
        parentId = parentId,
        text = id,
        participant = participant,
        runId = "run",
    )

    private fun run() = RunEntity(
        id = "run",
        conversationId = "conversation",
        parentRunId = null,
        status = RunStatus.COMPLETED,
        activeSlot = null,
        startedAt = 1L,
        lastCheckpointAt = 2L,
        endedAt = 2L,
        endReason = RunEndReason.MODEL_COMPLETED,
    )
}
