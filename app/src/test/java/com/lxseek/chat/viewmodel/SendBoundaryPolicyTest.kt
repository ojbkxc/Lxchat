package com.lxseek.chat.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendBoundaryPolicyTest {
    @Test
    fun `queued acceptance keeps attachment ownership until durable drain`() {
        assertFalse(SendAcceptance.Queued("queued", "conversation").hasDurableAttachmentOwner())
        assertTrue(SendAcceptance.Direct("message", "conversation").hasDurableAttachmentOwner())
    }
}
