package com.lxseek.chat.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenPendingImagesTest {
    @Test
    fun drain_isIsolatedByConversationAndIdempotent() {
        val pending = PendingImagesByConversation()
        pending.add("conversation-a", "a-1.jpg")
        pending.add("conversation-b", "b-1.jpg")
        pending.add("conversation-a", "a-2.jpg")

        assertEquals(listOf("a-1.jpg", "a-2.jpg"), pending.drain("conversation-a"))
        assertTrue(pending.drain("conversation-a").isEmpty())
        assertEquals(listOf("b-1.jpg"), pending.drain("conversation-b"))
    }
}
