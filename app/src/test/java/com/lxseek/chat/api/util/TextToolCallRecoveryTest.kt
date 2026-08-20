package com.lxseek.chat.api.util

import com.lxseek.chat.api.StreamEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolCallRecoveryTest {

    @Test
    fun disabledPassesTextThroughUntouched() = runTest {
        val recovery = TextToolCallRecovery(enabled = false)
        val events = mutableListOf<StreamEvent>()

        recovery.route(StreamEvent.TextChunk("<invoke name=\"x\"></invoke>"), events::add)
        recovery.finish(events::add)

        assertEquals(1, events.size)
        assertEquals("<invoke name=\"x\"></invoke>", (events.single() as StreamEvent.TextChunk).text)
    }

    @Test
    fun splitXmlEmitsToolCallAndNoMarkupText() = runTest {
        val recovery = TextToolCallRecovery(enabled = true)
        val events = mutableListOf<StreamEvent>()

        recovery.route(StreamEvent.TextChunk("Before <antml:inv"), events::add)
        recovery.route(
            StreamEvent.TextChunk(
                "oke name=\"file_read\"><antml:parameter name=\"path\">a.txt" +
                    "</antml:parameter></antml:invoke>"
            ),
            events::add,
        )
        recovery.finish(events::add)

        assertTrue(events.any { it is StreamEvent.TextChunk && it.text == "Before " })
        val call = events.filterIsInstance<StreamEvent.ToolCallRequest>().single()
        assertEquals("file_read", call.name)
        assertTrue(call.arguments.contains("a.txt"))
        assertTrue(recovery.emittedToolCall)
        assertFalse(events.filterIsInstance<StreamEvent.TextChunk>().any { it.text.contains("invoke") })
    }
}
