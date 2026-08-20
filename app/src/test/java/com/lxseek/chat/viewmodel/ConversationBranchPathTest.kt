package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBranchPathTest {
    @Test
    fun selectedBranchClosesOverEveryParallelToolResult() {
        val messages = listOf(
            message("user", null, Participant.USER, 0),
            message("model", "user", Participant.MODEL, 1),
            message("tool_round", "model", Participant.MODEL, 2),
            message("result_a", "tool_round", Participant.USER, 3),
            message("result_b", "tool_round", Participant.USER, 4),
        )

        val branch = checkNotNull(
            resolveConversationBranchPath(
                messages = messages,
                runs = listOf(run()),
                selectedChildren = mapOf(
                    null to "user",
                    "user" to "model",
                    "model" to "tool_round",
                    "tool_round" to "result_b",
                ),
                throughMessageId = "model",
            )
        )

        assertEquals(listOf("user", "model"), branch.visibleMessages.map { it.id })
        assertEquals(listOf("user", "model"), branch.selectedPathMessages.map { it.id })
        assertTrue(branch.structuralMessages.any { it.id == "result_a" })
        assertTrue(branch.structuralMessages.any { it.id == "result_b" })
    }

    @Test
    fun throughMessageDoesNotLeakLaterVisibleMessagesFromTheSameRun() {
        val messages = listOf(
            message("user", null, Participant.USER, 0),
            message("model", "user", Participant.MODEL, 1),
            message("later_user", "model", Participant.USER, 2),
            message("later_model", "later_user", Participant.MODEL, 3),
        )

        val branch = checkNotNull(
            resolveConversationBranchPath(
                messages = messages,
                runs = listOf(run()),
                selectedChildren = emptyMap(),
                throughMessageId = "model",
            )
        )

        assertEquals(listOf("user", "model"), branch.visibleMessages.map { it.id })
        assertFalse(branch.structuralMessages.any { it.id == "later_user" })
        assertFalse(branch.structuralMessages.any { it.id == "later_model" })
    }

    @Test
    fun clonedProtocolRowsRetainTheirKindAndDoNotEnterTheVisiblePath() {
        val sourceMessages = listOf(
            message("user", null, Participant.USER, 0),
            message("model", "user", Participant.MODEL, 1),
            message("tool_round", "model", Participant.MODEL, 2),
            message("result_a", "tool_round", Participant.USER, 3),
            message("result_b", "tool_round", Participant.USER, 4),
        )
        val sourceSelections = mapOf<String?, String>(
            null to "user",
            "user" to "model",
            "model" to "tool_round",
            "tool_round" to "result_b",
        )
        val sourceBranch = checkNotNull(
            resolveConversationBranchPath(
                messages = sourceMessages,
                runs = listOf(run()),
                selectedChildren = sourceSelections,
            )
        )
        val messageIds = sourceMessages.associate { message ->
            message.id to remapForkMessageId(message.id, "clone_${message.id}")
        }
        val clonedMessages = sourceMessages.map { message ->
            message.copy(
                id = messageIds.getValue(message.id),
                conversationId = "fork",
                parentId = message.parentId?.let(messageIds::getValue),
            )
        }
        val clonedSelections = sourceSelections.entries.associate { (parentId, childId) ->
            parentId?.let(messageIds::getValue) to messageIds.getValue(childId)
        }
        val clonedBranch = checkNotNull(
            resolveConversationBranchPath(
                messages = clonedMessages,
                runs = listOf(run().copy(conversationId = "fork")),
                selectedChildren = clonedSelections,
            )
        )

        assertTrue(messageIds.getValue("tool_round").startsWith("tool_"))
        assertTrue(messageIds.getValue("result_a").startsWith("result_"))
        assertEquals(
            sourceBranch.visibleMessages.map { messageIds.getValue(it.id) },
            clonedBranch.visibleMessages.map { it.id },
        )
    }

    private fun message(
        id: String,
        parentId: String?,
        participant: Participant,
        sequence: Long,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = id,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = sequence,
        runId = "run",
        runSequence = sequence,
    )

    private fun run() = RunEntity(
        id = "run",
        conversationId = "conversation",
        parentRunId = null,
        status = RunStatus.COMPLETED,
        activeSlot = null,
        startedAt = 0,
        lastCheckpointAt = 1,
        endedAt = 1,
        endReason = RunEndReason.MODEL_COMPLETED,
    )
}
