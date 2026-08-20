package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactGraphAnchorTest {
    @Test
    fun providerSuffixStartingAtToolAnchorsBeforeVisibleAggregate() {
        val model = entity("model", "user", Participant.MODEL, 1)
        val tool = entity("tool_round", "model", Participant.MODEL, 2)
        val result = entity("result_round", "tool_round", Participant.USER, 3)
        val byId = listOf(model, tool, result).associateBy(MessageEntity::id)

        assertEquals(
            "model",
            resolveCompactGraphSuffixRoot("tool_round", byId)?.id,
        )
        assertEquals(
            "model",
            resolveCompactGraphSuffixRoot("result_round", byId)?.id,
        )
    }

    @Test
    fun automaticSplitExcludesCurrentEmptyPlaceholderButKeepsDurableUserBoundary() {
        val oldUser = entity("old-user", null, Participant.USER, 1).copy(text = "old")
        val oldModel = entity("old-model", "old-user", Participant.MODEL, 2).copy(text = "answer")
        val currentUser = entity("current-user", "old-model", Participant.USER, 3).copy(text = "new")
        val placeholder = entity("placeholder", "current-user", Participant.MODEL, 4).copy(
            status = MessageStatus.SENDING,
        )

        val split = com.lxseek.chat.api.util.splitLogicalContext(
            compactSplitMessages(
                listOf(oldUser, oldModel, currentUser, placeholder).map {
                    it.toUiChatMessage { text -> text }
                }
            ),
            retainLogicalMessages = 1,
        )

        assertEquals(listOf("old-user", "old-model"), split.prefix.map { it.id })
        assertEquals(listOf("current-user"), split.suffix.map { it.id })
    }

    @Test
    fun automaticEligibilityDoesNotExposeCompactingBeforeThreshold() {
        val path = listOf(
            entity("old-user", null, Participant.USER, 1).copy(text = "old"),
            entity("old-model", "old-user", Participant.MODEL, 2).copy(text = "answer"),
            entity("current-user", "old-model", Participant.USER, 3).copy(text = "new"),
        ).map { it.toUiChatMessage { text -> text } }

        assertFalse(
            automaticCompactNeeded(
                path = path,
                contextLimit = Int.MAX_VALUE,
                retainLogicalMessages = 1,
            ),
        )
    }

    @Test
    fun automaticEligibilityRequiresARealCompactablePrefix() {
        val path = listOf(
            entity("current-user", null, Participant.USER, 1).copy(text = "new"),
        ).map { it.toUiChatMessage { text -> text } }

        assertFalse(
            automaticCompactNeeded(
                path = path,
                contextLimit = 1,
                retainLogicalMessages = 1,
            ),
        )
    }

    @Test
    fun automaticEligibilityStartsAboveThresholdWhenOlderPrefixExists() {
        val path = listOf(
            entity("old-user", null, Participant.USER, 1).copy(text = "old context"),
            entity("old-model", "old-user", Participant.MODEL, 2).copy(text = "old answer"),
            entity("current-user", "old-model", Participant.USER, 3).copy(text = "new request"),
        ).map { it.toUiChatMessage { text -> text } }

        assertTrue(
            automaticCompactNeeded(
                path = path,
                contextLimit = 1,
                retainLogicalMessages = 1,
            ),
        )
    }

    @Test
    fun automaticEligibilityIgnoresHistoryBeforeNearestCompact() {
        val path = listOf(
            entity("old-user", null, Participant.USER, 1).copy(text = "very old context"),
            entity("compact_boundary", "old-user", Participant.MODEL, 2).copy(text = "summary"),
            entity("current-user", "compact_boundary", Participant.USER, 3).copy(text = "new request"),
        ).map { it.toUiChatMessage { text -> text } }

        assertFalse(
            automaticCompactNeeded(
                path = path,
                contextLimit = 1,
                retainLogicalMessages = 1,
            ),
        )
    }

    @Test
    fun automaticEligibilityKeepsCompleteToolRoundInRetainedSuffix() {
        val oldUser = entity("old-user", null, Participant.USER, 1)
            .copy(text = "old context")
            .toUiChatMessage { text -> text }
        val tool = entity("tool_round", "old-user", Participant.MODEL, 2)
            .toUiChatMessage { text -> text }
            .copy(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = "test_tool",
                        toolArgs = "{}",
                        toolCallId = "call-1",
                    )
                )
            )
        val result = entity("result_round", "tool_round", Participant.USER, 3)
            .copy(text = "result")
            .toUiChatMessage { text -> text }
            .copy(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = "test_tool",
                        toolArgs = "{}",
                        toolResult = "result",
                        toolCallId = "call-1",
                    )
                )
            )
        val continuation = entity("continuation", "result_round", Participant.MODEL, 4)
            .copy(text = "answer")
            .toUiChatMessage { text -> text }

        val compactable = compactSplitMessages(listOf(oldUser, tool, result, continuation))
        val split = com.lxseek.chat.api.util.splitLogicalContext(
            compactable,
            retainLogicalMessages = 1,
        )

        assertEquals(listOf("old-user"), split.prefix.map { it.id })
        assertEquals(
            listOf("tool_round", "result_round", "continuation"),
            split.suffix.map { it.id },
        )
        assertTrue(
            automaticCompactNeeded(
                path = listOf(oldUser, tool, result, continuation),
                contextLimit = 1,
                retainLogicalMessages = 1,
            ),
        )
    }

    @Test
    fun automaticEligibilityUsesOnlySelectedConversationBranch() {
        val selectedRoot = entity("selected-root", null, Participant.USER, 1)
            .copy(text = "selected")
        val unselectedRoot = entity("unselected-root", null, Participant.USER, 2)
            .copy(text = "unselected old context")
        val unselectedAnswer = entity("unselected-answer", "unselected-root", Participant.MODEL, 3)
            .copy(text = "unselected answer")
        val unselectedCurrent = entity("unselected-current", "unselected-answer", Participant.USER, 4)
            .copy(text = "unselected new request")

        assertFalse(
            automaticCompactNeeded(
                entities = listOf(
                    selectedRoot,
                    unselectedRoot,
                    unselectedAnswer,
                    unselectedCurrent,
                ),
                selectedChildren = mapOf(null to selectedRoot.id),
                contextLimit = 1,
                retainLogicalMessages = 1,
            ),
        )
    }

    private fun entity(
        id: String,
        parentId: String?,
        participant: Participant,
        sequence: Long,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = "",
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = sequence,
        runId = "run",
        runSequence = sequence,
    )
}
