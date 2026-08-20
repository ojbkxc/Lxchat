package com.lxseek.chat.api.openai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallTextParserTest {

    @Test
    fun parsesBareAnthropicXmlInvoke() {
        val content = """
            I will inspect it.
            <invoke name="file_grep">
              <parameter name="pattern">notifySendAccepted|acquireForSend()</parameter>
              <parameter name="path">F:\workspace\repo\LxChat\MessageGenerationController.kt</parameter>
              <parameter name="server">Quantum</parameter>
            </invoke>
        """.trimIndent()

        val parsed = ToolCallTextParser.parse(content)

        assertEquals(1, parsed.size)
        assertEquals("file_grep", parsed.single().name)
        assertTrue(parsed.single().arguments.contains("notifySendAccepted|acquireForSend()"))
        assertTrue(parsed.single().arguments.contains("MessageGenerationController.kt"))
        assertTrue(parsed.single().arguments.contains("Quantum"))
    }

    @Test
    fun parsesNamespacedAnthropicXmlAndDecodesEntities() {
        val parsed = ToolCallTextParser.parse(
            """
            <antml:invoke name='execute_shell_command'>
              <antml:parameter name='command'>echo &quot;a&amp;b&quot;</antml:parameter>
              <antml:parameter name='timeout_ms'>60000</antml:parameter>
            </antml:invoke>
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        assertEquals("execute_shell_command", parsed.single().name)
        assertTrue(parsed.single().arguments.contains("echo \\\"a&b\\\""))
        assertTrue(parsed.single().arguments.contains("60000"))
    }

    @Test
    fun parsesMultipleXmlInvokes() {
        val parsed = ToolCallTextParser.parse(
            """
            <invoke name="file_read"><parameter name="path">a.txt</parameter></invoke>
            <invoke name="file_read"><parameter name="path">b.txt</parameter></invoke>
            """.trimIndent()
        )

        assertEquals(listOf("file_read", "file_read"), parsed.map { it.name })
        assertTrue(parsed[0].arguments.contains("a.txt"))
        assertTrue(parsed[1].arguments.contains("b.txt"))
    }

    @Test
    fun proseMentionOfInvokeIsNotParsed() {
        val parsed = ToolCallTextParser.parse(
            "Use an invoke tag with a parameter tag, but do not execute anything."
        )

        assertTrue(parsed.isEmpty())
    }

    @Test
    fun rejectsPrimitiveArgumentsAndUnsafeNames() {
        assertTrue(
            ToolCallTextParser.parse(
                """{"name":"file_read","arguments":"not-an-object"}"""
            ).isEmpty()
        )
        assertTrue(
            ToolCallTextParser.parse(
                """{"name":"bad name","arguments":{}}"""
            ).isEmpty()
        )
    }

    @Test
    fun malformedMemberRejectsWholeJsonBatch() {
        val parsed = ToolCallTextParser.parse(
            """[{"name":"file_read","arguments":{"path":"a"}},{"name":"file_write","arguments":"broken"}]"""
        )

        assertTrue(parsed.isEmpty())
    }

    @Test
    fun streamingParserWithholdsSplitXmlAndCompletesWithoutLeakingMarkup() = runTest {
        val parser = StreamingTextToolCallParser()
        val text = StringBuilder()
        val updates = mutableListOf<StreamingTextToolCallParser.Snapshot>()
        val completed = mutableListOf<StreamingTextToolCallParser.CompletedCall>()
        val malformed = mutableListOf<String>()

        suspend fun feed(chunk: String) {
            parser.feed(
                content = chunk,
                onText = { text.append(it) },
                onUpdate = { updates += it },
                onComplete = { completed += it },
                onMalformed = { malformed += it },
            )
        }

        feed("Checking. <inv")
        feed("oke name=\"file_grep\"><parameter name=\"pattern\">needle")
        feed("</parameter></invoke>")
        parser.flush(
            onText = { text.append(it) },
            onUpdate = { updates += it },
            onComplete = { completed += it },
            onMalformed = { malformed += it },
        )

        assertEquals("Checking. ", text.toString())
        assertEquals(1, completed.size)
        assertEquals("file_grep", completed.single().name)
        assertTrue(completed.single().arguments.contains("needle"))
        assertTrue(malformed.isEmpty())
        assertFalse(text.contains("invoke"))
        assertTrue(updates.isNotEmpty())
    }
}
