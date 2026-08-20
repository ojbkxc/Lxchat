package com.lxseek.chat.ui.chat.message

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalStreamingMarkdownTest {
    private val flavour = GFMFlavourDescriptor()

    @Test
    fun appendOnlyUpdate_scansOnlyDeltaAndReusesStableBlock() {
        val document = IncrementalMarkdownDocument(flavour)
        val first = "First paragraph.\n\nSecond"
        val firstSnapshot = document.update(first, first, isStreaming = true)
        val stable = firstSnapshot.stableBlocks.single()
        val scannedAfterFirst = document.scannedCodeUnits

        val second = "$first paragraph grows"
        val secondSnapshot = document.update(second, second, isStreaming = true)

        assertSame(stable, secondSnapshot.stableBlocks.single())
        assertEquals(
            (second.length - first.length).toLong(),
            document.scannedCodeUnits - scannedAfterFirst,
        )
        assertEquals("Second paragraph grows", secondSnapshot.tail)
    }

    @Test
    fun blankLineInsideFence_doesNotPromoteIncompleteCodeBlock() {
        val document = IncrementalMarkdownDocument(flavour)
        val incomplete = "```kotlin\nval answer = 42\n\n"

        val streaming = document.update(incomplete, incomplete, isStreaming = true)

        assertTrue(streaming.stableBlocks.isEmpty())
        assertEquals(incomplete, streaming.tail)

        val complete = "$incomplete```\n\nFollowing"
        val closed = document.update(complete, complete, isStreaming = true)
        assertEquals(1, closed.stableBlocks.size)
        assertEquals("Following", closed.tail)
    }

    @Test
    fun terminalUpdate_keepsTheLiveTailIdentityAndLayoutPath() {
        val document = IncrementalMarkdownDocument(flavour)
        val text = "Stable.\n\nFinal **tail**"
        val streaming = document.update(text, text, isStreaming = true)
        val stable = streaming.stableBlocks.single()
        val liveStart = streaming.liveBlock?.startOffset

        val terminal = document.update(text, text, isStreaming = false)

        assertSame(stable, terminal.stableBlocks.first())
        assertEquals(1, terminal.stableBlocks.size)
        assertEquals("Final **tail**", terminal.tail)
        assertEquals(liveStart, terminal.liveBlock?.startOffset)
        assertFalse(terminal.isStreaming)
    }

    @Test
    fun streamingAfterTerminal_resetsTheFinalizedDocument() {
        val document = IncrementalMarkdownDocument(flavour)
        val terminalText = "Finished"
        document.update(terminalText, terminalText, isStreaming = false)

        val restarted = document.update(terminalText, terminalText, isStreaming = true)

        assertTrue(restarted.stableBlocks.isEmpty())
        assertEquals(terminalText, restarted.tail)
        assertTrue(restarted.isStreaming)
    }

    @Test
    fun directGlyphAlpha_isBoundedMonotonicAndUnicodeSafe() {
        val text = "older text 😀 最新文字"
        val annotated = streamingTailAnnotatedString(
            text = text,
            color = Color.White,
            fadeCodePoints = 8,
            bands = 4,
            newestAlpha = 0.4f,
        )
        val styles = annotated.spanStyles

        assertEquals(4, styles.size)
        assertEquals(text.length, styles.last().end)
        assertEquals(0.4f, styles.last().item.color.alpha, 0.0001f)
        styles.zipWithNext().forEach { (older, newer) ->
            assertTrue(older.item.color.alpha > newer.item.color.alpha)
        }
        styles.forEach { range ->
            assertFalse(range.start.splitsSurrogatePair(text))
            assertFalse(range.end.splitsSurrogatePair(text))
        }
    }

    @Test
    fun temporalAlpha_usesPerAppendBirthTimesAndEventuallyBecomesSolid() {
        val tracker = StreamingTailFadeTracker(capacity = 8)
        tracker.update("ab", nowMs = 1_000L)
        val appended = tracker.update("abcd", nowMs = 1_100L)

        assertArrayEquals(
            longArrayOf(1_000L, 1_000L, 1_100L, 1_100L),
            appended.birthTimesMs,
        )

        val fading = streamingTailAnnotatedString(
            text = "abcd",
            color = Color.White,
            fadeCodePoints = 4,
            bands = 4,
            newestAlpha = 0.4f,
            birthTimesMs = appended.birthTimesMs,
            nowMs = 1_200L,
            alphaPerSecond = 2f,
        )
        assertEquals(0.6f, fading.spanStyles.last().item.color.alpha, 0.0001f)
        assertTrue(streamingTailFadeActive(appended.birthTimesMs, nowMs = 1_200L))

        val solid = streamingTailAnnotatedString(
            text = "abcd",
            color = Color.White,
            fadeCodePoints = 4,
            bands = 4,
            newestAlpha = 0.4f,
            birthTimesMs = appended.birthTimesMs,
            nowMs = 2_000L,
            alphaPerSecond = 2f,
        )
        assertTrue(solid.spanStyles.isEmpty())
        assertFalse(streamingTailFadeActive(appended.birthTimesMs, nowMs = 2_000L))
    }

    @Test
    fun directGlyphAlpha_preservesExistingMarkdownSpansAndMetrics() {
        val base = AnnotatedString.Builder().apply {
            append("bold tail")
            addStyle(
                SpanStyle(fontWeight = FontWeight.Bold),
                start = 0,
                end = 4,
            )
        }.toAnnotatedString()

        val faded = streamingTailAnnotatedString(
            text = base,
            color = Color.White,
            fadeCodePoints = 4,
            bands = 2,
            newestAlpha = 0.4f,
        )

        assertEquals(base.text, faded.text)
        assertTrue(
            faded.spanStyles.any {
                it.start == 0 && it.end == 4 && it.item.fontWeight == FontWeight.Bold
            }
        )
        assertEquals(3, faded.spanStyles.size)
    }

    @Test
    fun promotedTail_retainsOriginalGlyphAges() {
        val tracker = StreamingTailFadeTracker(capacity = 8)
        tracker.update("closed\n\nlive", nowMs = 1_000L)

        val promoted = tracker.update("live", nowMs = 1_200L)

        assertArrayEquals(
            longArrayOf(1_000L, 1_000L, 1_000L, 1_000L),
            promoted.birthTimesMs,
        )
    }

    private fun Int.splitsSurrogatePair(text: String): Boolean =
        this in 1 until text.length &&
            Character.isHighSurrogate(text[this - 1]) &&
            Character.isLowSurrogate(text[this])
}
