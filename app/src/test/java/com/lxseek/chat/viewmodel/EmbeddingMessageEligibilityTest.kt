package com.lxseek.chat.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingMessageEligibilityTest {
    @Test
    fun compactAndProtocolRowsNeverEnterEmbeddingIndex() {
        assertFalse(isEmbeddingMessageIdEligible("compact_boundary"))
        assertFalse(isEmbeddingMessageIdEligible("tool_call"))
        assertFalse(isEmbeddingMessageIdEligible("result_call"))
        assertTrue(isEmbeddingMessageIdEligible("user-message"))
        assertTrue(isEmbeddingMessageIdEligible("assistant-message"))
    }
}
