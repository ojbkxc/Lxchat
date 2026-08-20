package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants
import org.junit.Assert.*
import org.junit.Test

class ConversationUiStateTest {

    private val now = System.currentTimeMillis()

    private fun msg(
        id: String, parentId: String? = null, text: String = "text",
        participant: Participant = Participant.USER
    ) = ChatMessage(
        id = id, parentId = parentId, text = text,
        participant = participant, timestamp = now + id.hashCode()
    )

    @Test
    fun emptyState_returnsEmptyPath() {
        val path = ConversationUiState.resolvePath(emptyList(), null, emptyMap())
        assertTrue(path.isEmpty())
    }

    @Test
    fun linearConversation_returnsAllMessages() {
        val msgs = listOf(
            msg("u1", null, "q1"),
            msg("m1", "u1", "a1", Participant.MODEL),
            msg("u2", "m1", "q2"),
            msg("m2", "u2", "a2", Participant.MODEL)
        )
        val path = ConversationUiState.resolvePath(msgs, null, emptyMap())
        assertEquals(4, path.size)
        assertEquals("u1", path[0].id)
        assertEquals("m2", path[3].id)
    }

    @Test
    fun branchSelection_followsSelectedChild() {
        val msgs = listOf(
            msg("u1", null, "q1"),
            msg("m1a", "u1", "a1a", Participant.MODEL), // first sibling
            msg("m1b", "u1", "a1b", Participant.MODEL)  // second sibling (regenerated)
        )
        // Select the first sibling
        val path = ConversationUiState.resolvePath(msgs, null, mapOf("u1" to "m1a"))
        assertEquals(2, path.size)
        assertEquals("m1a", path[1].id)
    }

    @Test
    fun branchSelection_defaultsToLast() {
        val msgs = listOf(
            msg("u1", null, "q1"),
            msg("m1a", "u1", "a1a", Participant.MODEL),
            msg("m1b", "u1", "a1b", Participant.MODEL)
        )
        // No selection → last sibling
        val path = ConversationUiState.resolvePath(msgs, null, emptyMap())
        assertEquals(2, path.size)
        assertEquals("m1b", path[1].id)
    }

    @Test
    fun syntheticToolMessages_filteredOut() {
        val msgs = listOf(
            msg("u1", null, "q1"),
            msg("m1", "u1", "a1", Participant.MODEL),
            msg(Constants.TOOL_MSG_PREFIX + "t1", "m1", "", Participant.MODEL),
            msg(Constants.RESULT_MSG_PREFIX + "r1", Constants.TOOL_MSG_PREFIX + "t1", "result", Participant.MODEL)
        )
        val path = ConversationUiState.resolvePath(msgs, null, emptyMap())
        assertEquals(2, path.size)
        assertEquals("u1", path[0].id)
        assertEquals("m1", path[1].id)
    }

    @Test
    fun streamingMessage_substitutesMatchingId() {
        val dbMsgs = listOf(
            msg("u1", null, "q1"),
            msg("m1", "u1", "streaming...", Participant.MODEL)
        )
        val streaming = ChatMessage(
            id = "m1", parentId = "u1", text = "updated stream text",
            participant = Participant.MODEL, status = MessageStatus.SENDING
        )
        val path = ConversationUiState.resolvePath(dbMsgs, streaming, emptyMap())
        assertEquals(2, path.size)
        assertEquals("updated stream text", path[1].text)
        assertEquals(MessageStatus.SENDING, path[1].status)
    }

    @Test
    fun streamingMessage_appendedIfNew() {
        val msgs = listOf(msg("u1", null, "q1"))
        val streaming = ChatMessage(
            id = "m1", parentId = "u1", text = "new response",
            participant = Participant.MODEL, status = MessageStatus.SENDING
        )
        val path = ConversationUiState.resolvePath(msgs, streaming, emptyMap())
        assertEquals(2, path.size)
        assertEquals("m1", path[1].id)
    }

    @Test
    fun durableQueuedInput_staysOutOfPathUntilCurrentPassReleases() {
        val initial = ChatMessage(
            id = "u1",
            text = "initial",
            participant = Participant.USER,
            runId = "run",
            runSequence = 0,
            consumedAtPass = 0,
        )
        val persistedModel = ChatMessage(
            id = "m1",
            parentId = "u1",
            text = "partial",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
            runId = "run",
            runSequence = 1,
        )
        val queued = ChatMessage(
            id = "u2",
            parentId = "m1",
            text = "steer",
            participant = Participant.USER,
            runId = "run",
            runSequence = 2,
            consumedAtPass = null,
        )
        val streaming = persistedModel.copy(text = "latest partial")
        val selected = mapOf<String?, String>(
            null to "u1",
            "u1" to "m1",
            "m1" to "u2",
        )

        val whileQueued = ConversationUiState.resolvePath(
            listOf(initial, persistedModel, queued),
            streaming,
            selected,
        )
        val afterRelease = ConversationUiState.resolvePath(
            listOf(initial, persistedModel.copy(status = MessageStatus.SUCCESS), queued),
            null,
            selected,
        )

        assertEquals(listOf("u1", "m1"), whileQueued.map { it.id })
        assertEquals("latest partial", whileQueued.last().text)
        assertEquals(listOf("u1", "m1", "u2"), afterRelease.map { it.id })
    }

    @Test
    fun toolResultBeforeError_remainsTraversableWhileNextRunStreams() {
        val initial = ChatMessage(
            id = "u1",
            text = "initial",
            participant = Participant.USER,
            runId = "failed-run",
            runSequence = 0,
            consumedAtPass = 0,
        )
        val firstModel = ChatMessage(
            id = "m1",
            parentId = initial.id,
            text = "working",
            participant = Participant.MODEL,
            runId = "failed-run",
            runSequence = 1,
        )
        val tool = ChatMessage(
            id = "${Constants.TOOL_MSG_PREFIX}call",
            parentId = firstModel.id,
            text = "",
            participant = Participant.MODEL,
            runId = "failed-run",
            runSequence = 2,
        )
        val result = ChatMessage(
            id = "${Constants.RESULT_MSG_PREFIX}call",
            parentId = tool.id,
            text = "tool result",
            participant = Participant.USER,
            runId = "failed-run",
            runSequence = 3,
            consumedAtPass = null,
        )
        val error = ChatMessage(
            id = "error",
            parentId = result.id,
            text = "provider error",
            participant = Participant.MODEL,
            status = MessageStatus.ERROR,
            runId = "failed-run",
            runSequence = 4,
        )
        val nextUser = ChatMessage(
            id = "u2",
            parentId = error.id,
            text = "continue",
            participant = Participant.USER,
            runId = "next-run",
            runSequence = 0,
            consumedAtPass = 0,
        )
        val streaming = ChatMessage(
            id = "m2",
            parentId = nextUser.id,
            text = "",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
            runId = "next-run",
            runSequence = 1,
        )

        val path = ConversationUiState.resolvePath(
            allMessages = listOf(initial, firstModel, tool, result, error, nextUser),
            streamingMsg = streaming,
            selectedChildren = emptyMap(),
        )

        assertEquals(listOf("u1", "m1", "error", "u2", "m2"), path.map { it.id })
        assertEquals(MessageStatus.ERROR, path[2].status)
    }

    @Test
    fun pendingInputFromStoppedRun_remainsTraversableWhileNextRunStreams() {
        val initial = ChatMessage(
            id = "u1",
            text = "initial",
            participant = Participant.USER,
            runId = "stopped-run",
            runSequence = 0,
            consumedAtPass = 0,
        )
        val stopped = ChatMessage(
            id = "m1",
            parentId = initial.id,
            text = "partial",
            participant = Participant.MODEL,
            status = MessageStatus.STOPPED,
            runId = "stopped-run",
            runSequence = 1,
        )
        val acceptedBeforeStop = ChatMessage(
            id = "u2",
            parentId = stopped.id,
            text = "accepted before stop",
            participant = Participant.USER,
            runId = "stopped-run",
            runSequence = 2,
            consumedAtPass = null,
        )
        val nextUser = ChatMessage(
            id = "u3",
            parentId = acceptedBeforeStop.id,
            text = "continue",
            participant = Participant.USER,
            runId = "next-run",
            runSequence = 0,
            consumedAtPass = 0,
        )
        val nextPlaceholder = ChatMessage(
            id = "m2",
            parentId = nextUser.id,
            text = "new answer",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
            runId = "next-run",
            runSequence = 1,
        )

        val path = ConversationUiState.resolvePath(
            allMessages = listOf(initial, stopped, acceptedBeforeStop, nextUser, nextPlaceholder),
            streamingMsg = nextPlaceholder,
            selectedChildren = mapOf(
                initial.id to stopped.id,
                stopped.id to acceptedBeforeStop.id,
                acceptedBeforeStop.id to nextUser.id,
                nextUser.id to nextPlaceholder.id,
            ),
        )

        assertEquals(listOf("u1", "m1", "u2", "u3", "m2"), path.map { it.id })
        assertEquals("new answer", path.last().text)
    }

    @Test
    fun deleteTargetUsesTheNearestRealUserAncestor() {
        val rootUser = msg("u1")
        val answer = msg("m1", rootUser.id, participant = Participant.MODEL)
        val toolResult = msg(
            Constants.RESULT_MSG_PREFIX + "r1",
            answer.id,
            participant = Participant.USER,
        )
        val nextAnswer = msg("m2", toolResult.id, participant = Participant.MODEL)

        assertEquals(
            rootUser.id,
            nearestUserAncestorId(
                messages = listOf(rootUser, answer, toolResult, nextAnswer),
                messageId = nextAnswer.id,
            ),
        )
    }

    @Test
    fun deletingAModelTargetsItsImmediateUserParent() {
        val rootUser = msg("u1")
        val answer = msg("m1", rootUser.id, participant = Participant.MODEL)

        assertEquals(
            rootUser.id,
            nearestUserAncestorId(
                messages = listOf(rootUser, answer),
                messageId = answer.id,
            ),
        )
        assertNull(
            nearestUserAncestorId(
                messages = listOf(rootUser, answer),
                messageId = rootUser.id,
            ),
        )
    }

    @Test
    fun deletingUserWithSurvivingSiblingStaysAtSiblingBranchLevel() {
        val rootUser = msg("u1")
        val answer = msg("m1", rootUser.id, participant = Participant.MODEL)
        val firstEdit = msg("u2", answer.id)
        val firstReply = msg("m2", firstEdit.id, participant = Participant.MODEL)
        val secondEdit = msg("u3", answer.id)
        val secondReply = msg("m3", secondEdit.id, participant = Participant.MODEL)

        assertEquals(
            firstEdit.id,
            deleteSettlementTargetMessageId(
                messagesBeforeDelete =
                    listOf(rootUser, answer, firstEdit, firstReply, secondEdit, secondReply),
                deletedRootMessageId = secondEdit.id,
                remainingPath = listOf(rootUser, answer, firstEdit, firstReply),
            ),
        )
    }

    @Test
    fun deletingLastUserSiblingFallsBackToNearestUserAncestor() {
        val rootUser = msg("u1")
        val answer = msg("m1", rootUser.id, participant = Participant.MODEL)
        val onlyEdit = msg("u2", answer.id)
        val reply = msg("m2", onlyEdit.id, participant = Participant.MODEL)

        assertEquals(
            rootUser.id,
            deleteSettlementTargetMessageId(
                messagesBeforeDelete = listOf(rootUser, answer, onlyEdit, reply),
                deletedRootMessageId = onlyEdit.id,
                remainingPath = listOf(rootUser, answer),
            ),
        )
    }
}
