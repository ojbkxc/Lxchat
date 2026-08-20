package com.lxseek.chat.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskHistoryPreviewStateTest {
    @Test
    fun firstHistoryEntryCapturesTheOriginalConversation() {
        val state = TaskHistoryPreviewState.Idle.open(
            taskId = "task-1",
            currentConversationId = "conversation-1",
            isNewChatMode = false,
        )

        assertEquals(TaskHistoryPreviewPhase.VIEWING, state.phase)
        assertEquals("task-1", state.taskId)
        assertEquals("conversation-1", state.originConversationId)
        assertFalse(state.originWasNewChat)
    }

    @Test
    fun movingBetweenHistoryEntriesPreservesTheOriginalDestination() {
        val first = TaskHistoryPreviewState.Idle.open(
            taskId = "task-1",
            currentConversationId = "conversation-1",
            isNewChatMode = false,
        )
        val returning = first.requestReturn()
        val second = returning.open(
            taskId = "task-2",
            currentConversationId = "history-conversation-1",
            isNewChatMode = false,
        )

        assertEquals(TaskHistoryPreviewPhase.VIEWING, second.phase)
        assertEquals("task-2", second.taskId)
        assertEquals("conversation-1", second.originConversationId)
        assertFalse(second.originWasNewChat)
    }

    @Test
    fun newChatOriginIsRestorable() {
        val state = TaskHistoryPreviewState.Idle.open(
            taskId = "task-1",
            currentConversationId = null,
            isNewChatMode = true,
        )

        assertTrue(state.originWasNewChat)
        assertEquals(TaskHistoryPreviewPhase.RETURNING, state.requestReturn().phase)
    }
}
