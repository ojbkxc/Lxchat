package com.lxseek.chat.ui.chat

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAppDialogStateTest {
    @Test
    fun `rename and delete requests retain only their own dialog inputs`() {
        val state = ChatAppDialogState(mutableStateOf(false))

        state.requestRename("conversation", "Initial")
        state.requestDelete("other")

        assertEquals("conversation", state.renameConversationId)
        assertEquals("Initial", state.renameInitialName)
        assertEquals("other", state.deleteConversationId)

        state.dismissRename()
        state.dismissDelete()
        assertNull(state.renameConversationId)
        assertNull(state.deleteConversationId)
    }

    @Test
    fun `manual compact visibility remains backed by supplied saveable state`() {
        val visible = mutableStateOf(false)
        val state = ChatAppDialogState(visible)

        state.showManualCompact()
        assertTrue(visible.value)
        assertTrue(state.manualCompactVisible)

        state.dismissManualCompact()
        assertFalse(visible.value)
        assertFalse(state.manualCompactVisible)
    }
}
