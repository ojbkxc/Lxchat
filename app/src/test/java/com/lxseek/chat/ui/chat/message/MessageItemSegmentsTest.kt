package com.lxseek.chat.ui.chat.message

import com.lxseek.chat.model.MessageSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageItemSegmentsTest {

    @Test
    fun reducedMotionRetainsExpandedLayoutUntilCollapseFadeSettles() {
        assertTrue(
            retainExpandedLayoutDuringFade(
                currentExpanded = true,
                targetExpanded = false,
            )
        )
        assertFalse(
            retainExpandedLayoutDuringFade(
                currentExpanded = false,
                targetExpanded = false,
            )
        )
    }

    @Test
    fun reducedMotionReservesExpandedLayoutAsExpansionFadeStarts() {
        assertTrue(
            retainExpandedLayoutDuringFade(
                currentExpanded = false,
                targetExpanded = true,
            )
        )
    }

    @Test
    fun streamingSegmentAnimatesOnlyOnItsFirstSessionAppearance() {
        val registry = SegmentAppearanceRegistry()
        val key = "message:timeline:0"

        assertTrue(registry.shouldAnimate(key, isStreaming = true))
        registry.markSeen(key)
        assertFalse(registry.shouldAnimate(key, isStreaming = true))
    }

    @Test
    fun historicalSegmentNeverReplaysAnEntrance() {
        val registry = SegmentAppearanceRegistry()

        assertFalse(
            registry.shouldAnimate(
                key = "message:timeline:0",
                isStreaming = false,
            )
        )
    }

    @Test
    fun segmentContainerAndCardBodyHaveIndependentFirstAppearances() {
        val registry = SegmentAppearanceRegistry()
        val segmentKey = "message:timeline:0"
        val cardKey = "$segmentKey:card"

        registry.markSeen(segmentKey)

        assertFalse(registry.shouldAnimate(segmentKey, isStreaming = true))
        assertTrue(registry.shouldAnimate(cardKey, isStreaming = true))
    }

    @Test
    fun everyStreamingSegmentTypeGetsOneFirstAppearance() {
        val registry = SegmentAppearanceRegistry()

        listOf("answer", "thought", "tool", "transcription").forEachIndexed { index, type ->
            val key = segmentAppearanceKey(
                messageId = "message",
                mergedIndex = index,
                segment = MessageSegment(type = type),
            )
            assertTrue("$type must animate when first inserted", registry.shouldAnimate(key, true))
            registry.markSeen(key)
            assertFalse("$type must not replay while updating", registry.shouldAnimate(key, true))
        }
    }

    @Test
    fun segmentAppearanceIdentityIgnoresStreamingPayloadGrowth() {
        val partial = MessageSegment(
            type = "tool",
            toolName = "shell",
            toolArgs = """{"command":"cp"}""",
        )
        val complete = partial.copy(
            toolArgs = """{"command":"cp source destination"}""",
            toolResult = "done",
        )

        assertEquals(
            segmentAppearanceKey("message", 2, partial),
            segmentAppearanceKey("message", 2, complete),
        )
        assertNotEquals(
            segmentAppearanceKey("message", 2, partial),
            segmentAppearanceKey("message", 3, partial),
        )
    }

    @Test
    fun detailAndContainerIdentitiesIgnoreStreamingPayloadGrowth() {
        val partial = MessageSegment(
            type = "tool",
            toolName = "shell",
            toolArgs = """{"command":"cp"}""",
        )
        val complete = partial.copy(
            toolArgs = """{"command":"cp source destination"}""",
            toolResult = "done",
        )

        assertEquals(
            detailSegmentAppearanceKey("message", 1, partial),
            detailSegmentAppearanceKey("message", 1, complete),
        )
        assertEquals("message:compact", compactSegmentBlockAppearanceKey("message"))
        assertEquals(
            "message:group:1",
            groupedSegmentBlockAppearanceKey("message", 1),
        )
    }

    @Test
    fun activeGroupedSegmentExpandsOnlyOnce() {
        val controller = GroupedSegmentAutoExpansionController()
        val key = "message:group:0"

        assertEquals(
            GroupedSegmentAutoExpansionAction.EXPAND,
            controller.update(key, isActive = true, enabled = true),
        )
        assertEquals(
            GroupedSegmentAutoExpansionAction.NONE,
            controller.update(key, isActive = true, enabled = true),
        )
    }

    @Test
    fun groupedSegmentCollapsesOnceWhenItStopsBeingActive() {
        val controller = GroupedSegmentAutoExpansionController()
        val key = "message:group:0"

        controller.update(key, isActive = true, enabled = true)

        assertEquals(
            GroupedSegmentAutoExpansionAction.COLLAPSE,
            controller.update(key, isActive = false, enabled = true),
        )
        assertEquals(
            GroupedSegmentAutoExpansionAction.NONE,
            controller.update(key, isActive = false, enabled = true),
        )
    }

    @Test
    fun historicalGroupedSegmentNeverAutoExpands() {
        val controller = GroupedSegmentAutoExpansionController()
        val key = "message:group:0"

        assertEquals(
            GroupedSegmentAutoExpansionAction.NONE,
            controller.update(key, isActive = false, enabled = true),
        )
        assertEquals(
            GroupedSegmentAutoExpansionAction.NONE,
            controller.update(key, isActive = true, enabled = true),
        )
    }

    @Test
    fun enablingAutomationWhileAGroupIsActiveCanExpandIt() {
        val controller = GroupedSegmentAutoExpansionController()
        val key = "message:group:0"

        assertEquals(
            GroupedSegmentAutoExpansionAction.NONE,
            controller.update(key, isActive = true, enabled = false),
        )
        assertEquals(
            GroupedSegmentAutoExpansionAction.EXPAND,
            controller.update(key, isActive = true, enabled = true),
        )
    }
}
