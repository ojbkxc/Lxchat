package com.lxseek.chat.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTitleGeneratorTest {
    @Test
    fun fallbackTitleCollapsesWhitespaceAndTruncates() {
        val response = "  First line\n\nSecond\tline  " + "x".repeat(80)

        val title = fallbackConversationTitle(response)

        assertEquals(60, title.length)
        assertEquals("First line Second line " + "x".repeat(37), title)
    }

    @Test
    fun fallbackTitleKeepsEmptyResponseEmpty() {
        assertEquals("", fallbackConversationTitle(" \n\t "))
    }
}
