package com.lxseek.chat

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationNavigationTest {
    @Test
    fun staleConsumerCannotClearNewerNotificationTarget() {
        val target = MutableStateFlow<String?>("new")

        assertFalse(consumeNotificationTarget(target, "old"))
        assertEquals("new", target.value)
        assertTrue(consumeNotificationTarget(target, "new"))
        assertNull(target.value)
    }
}
