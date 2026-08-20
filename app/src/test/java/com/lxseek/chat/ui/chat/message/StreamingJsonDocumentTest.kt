package com.lxseek.chat.ui.chat.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingJsonDocumentTest {
    @Test
    fun emptyInput_isIncompleteWithoutInventingARoot() {
        val document = StreamingJsonParser.parse("")

        assertEquals(StreamingJsonStatus.INCOMPLETE, document.status)
        assertNull(document.root)
    }

    @Test
    fun openString_isRenderedAsAnIncompleteStructuredLeaf() {
        val document = StreamingJsonParser.parse("""{"alpha":"growing""")
        val root = document.root as StreamingJsonObject
        val entry = root.entries.single()
        val value = entry.value as StreamingJsonScalar

        assertEquals(StreamingJsonStatus.INCOMPLETE, document.status)
        assertEquals("alpha", entry.key)
        assertEquals("growing", value.content)
        assertEquals(StreamingJsonScalarKind.STRING, value.kind)
        assertFalse(value.complete)
        assertFalse(root.complete)
    }

    @Test
    fun nestedObjectAndKeywordPrefix_keepEveryCompletedAncestor() {
        val document = StreamingJsonParser.parse(
            """{"outer":{"enabled":tr""",
        )
        val root = document.root as StreamingJsonObject
        val nested = root.entries.single().value as StreamingJsonObject
        val value = nested.entries.single().value as StreamingJsonScalar

        assertEquals(StreamingJsonStatus.INCOMPLETE, document.status)
        assertEquals("tr", value.content)
        assertEquals(StreamingJsonScalarKind.BOOLEAN, value.kind)
        assertFalse(value.complete)
    }

    @Test
    fun splitEscapeAndUnicode_areIncompleteUntilTheirGrammarCloses() {
        val partial = StreamingJsonParser.parse("""{"value":"line\n\u4F""")
        val partialValue = ((partial.root as StreamingJsonObject)
            .entries.single().value as StreamingJsonScalar)

        assertEquals(StreamingJsonStatus.INCOMPLETE, partial.status)
        assertEquals("line\n", partialValue.content)

        val complete = StreamingJsonParser.parse("""{"value":"line\n\u4F60"}""")
        val completeValue = ((complete.root as StreamingJsonObject)
            .entries.single().value as StreamingJsonScalar)

        assertEquals(StreamingJsonStatus.COMPLETE, complete.status)
        assertEquals("line\n你", completeValue.content)
        assertTrue(completeValue.complete)
    }

    @Test
    fun incompleteNumberExponent_isNotMisclassifiedAsValid() {
        val document = StreamingJsonParser.parse("""{"value":1.2e+""")
        val value = ((document.root as StreamingJsonObject)
            .entries.single().value as StreamingJsonScalar)

        assertEquals(StreamingJsonStatus.INCOMPLETE, document.status)
        assertEquals("1.2e+", value.content)
        assertFalse(value.complete)
    }

    @Test
    fun impossiblePrefix_isInvalidInsteadOfBeingRepaired() {
        val document = StreamingJsonParser.parse("""{"value":]}""")

        assertEquals(StreamingJsonStatus.INVALID, document.status)
        assertEquals(9, document.errorOffset)
    }

    @Test
    fun closedArraysAndObjects_areComplete() {
        val document = StreamingJsonParser.parse(
            """{"values":[1,true,null,{"nested":"yes"}]}""",
        )

        assertEquals(StreamingJsonStatus.COMPLETE, document.status)
        assertTrue(checkNotNull(document.root).complete)
    }

    @Test
    fun whitespaceSeparatedTopLevelValues_formACompleteJsonSequence() {
        val document = StreamingJsonParser.parse(
            """{"first":1}

                {"second":2}
            """.trimIndent(),
        )

        assertEquals(StreamingJsonStatus.COMPLETE, document.status)
        assertEquals(2, document.roots.size)
        assertTrue(document.roots.all(StreamingJsonNode::complete))
    }

    @Test
    fun secondTopLevelValue_canRemainIncompleteWhileStreaming() {
        val document = StreamingJsonParser.parse(
            """{"first":1}
                {"second":""".trimIndent(),
        )

        assertEquals(StreamingJsonStatus.INCOMPLETE, document.status)
        assertEquals(2, document.roots.size)
        assertTrue(document.roots.first().complete)
        assertFalse(document.roots.last().complete)
    }

    @Test
    fun topLevelValuesWithoutWhitespaceRemainInvalid() {
        val document = StreamingJsonParser.parse("""{"first":1}{"second":2}""")

        assertEquals(StreamingJsonStatus.INVALID, document.status)
        assertEquals(11, document.errorOffset)
    }

    @Test
    fun adjacentIdenticalCompleteRootsAreDisplayedOnce() {
        val document = StreamingJsonParser.parse(
            """{"ok":true}
                {"ok":true}
            """.trimIndent(),
        )

        val visible = visibleJsonRoots(document.roots)

        assertEquals(1, visible.size)
    }
}
