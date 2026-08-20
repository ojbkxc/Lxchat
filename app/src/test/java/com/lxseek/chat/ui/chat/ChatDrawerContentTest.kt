package com.lxseek.chat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDrawerContentTest {
    @Test
    fun generationIndicatorHasPriorityOverUnread() {
        assertEquals(
            DrawerConversationIndicator.GENERATING,
            resolveDrawerConversationIndicator(
                isGenerating = true,
                isSelected = false,
                hasUnreadGeneration = true,
            ),
        )
    }

    @Test
    fun unreadIndicatorIsHiddenForOpenConversation() {
        assertEquals(
            DrawerConversationIndicator.NONE,
            resolveDrawerConversationIndicator(
                isGenerating = false,
                isSelected = true,
                hasUnreadGeneration = true,
            ),
        )
    }

    @Test
    fun backgroundConversationShowsUnreadIndicator() {
        assertEquals(
            DrawerConversationIndicator.UNREAD,
            resolveDrawerConversationIndicator(
                isGenerating = false,
                isSelected = false,
                hasUnreadGeneration = true,
            ),
        )
    }
}
