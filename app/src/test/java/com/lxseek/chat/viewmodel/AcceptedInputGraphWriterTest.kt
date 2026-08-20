package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.data.local.RunGraphCommit
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunEffectIdentity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcceptedInputGraphWriterTest {
    @Test
    fun commit_usesTheSelectedLeafAndCreatesTheWholeRunBoundaryAtomically() = runTest {
        val repository = mockk<ConversationRepository>()
        val root = message("root", null, Participant.USER, 1L, "old-run")
        val selected = message("selected", "root", Participant.MODEL, 2L, "selected-run")
        val newerSibling = message("newer", "root", Participant.MODEL, 3L, "other-run")
        coEvery { repository.getMessagesForConversationSnapshot("conversation") } returns
            listOf(root, selected, newerSibling)
        coEvery { repository.restoreBranchSelections("conversation") } returns
            mapOf("root" to "selected")

        lateinit var insertedRun: RunEntity
        lateinit var insertedMessages: List<MessageEntity>
        lateinit var insertedSelections: Map<String?, String>
        coEvery {
            repository.createRunWithMessages(any(), any(), any(), any())
        } coAnswers {
            insertedRun = firstArg()
            insertedMessages = secondArg()
            insertedSelections = thirdArg()
            RunGraphCommit(insertedMessages, insertedSelections, emptyMap())
        }

        var beforeCommitCalled = false
        val result = AcceptedInputGraphWriter(repository).commit(
            request = AcceptedInputGraphWriter.Request(
                inputEffect = inputEffect("conversation", "new-run"),
                userMessageId = "new-user",
                modelMessageId = "new-model",
                userText = "prompt",
                modelId = "OpenAI:model",
                userTimestamp = 100L,
            ),
            beforeRoomCommit = { beforeCommitCalled = true },
        )

        assertEquals("selected-run", insertedRun.parentRunId)
        assertEquals("selected", result.userMessage.parentId)
        assertEquals("new-user", result.modelMessage.parentId)
        assertEquals(listOf("new-user", "new-model"), insertedMessages.map { it.id })
        assertEquals("new-user", insertedSelections["selected"])
        assertEquals("new-model", insertedSelections["new-user"])
        assertEquals(insertedSelections, result.messageSelections)
        assertEquals(true, beforeCommitCalled)
    }

    @Test
    fun newConversation_startsAtTheRootWithoutReadingAStaleGraph() = runTest {
        val repository = mockk<ConversationRepository>()
        coEvery {
            repository.createConversationRunWithMessages(any(), any(), any(), any(), any())
        } coAnswers {
            val messages = thirdArg<List<MessageEntity>>()
            val selections = arg<Map<String?, String>>(3)
            RunGraphCommit(messages, selections, emptyMap())
        }

        val result = AcceptedInputGraphWriter(repository).commit(
            AcceptedInputGraphWriter.Request(
                inputEffect = inputEffect("conversation", "run"),
                userMessageId = "user",
                modelMessageId = "model",
                userText = "prompt",
                modelId = "OpenAI:model",
                userTimestamp = 100L,
                newConversation = com.lxseek.chat.data.local.ChatEntity(
                    id = "conversation",
                    title = "New",
                ),
            )
        )

        assertNull(result.userMessage.parentId)
        assertEquals("model", result.messageSelections["user"])
    }

    private fun message(
        id: String,
        parentId: String?,
        participant: Participant,
        timestamp: Long,
        runId: String,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = id,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = timestamp,
        runId = runId,
        runSequence = timestamp,
    )

    private fun inputEffect(conversationId: String, runId: String) =
        RunEffect.PersistAcceptedInput(
            RunEffectIdentity(
                conversationId = conversationId,
                ownerToken = 1L,
                runId = runId,
                pass = 0,
                effectId = "send-$runId",
            )
        )
}
