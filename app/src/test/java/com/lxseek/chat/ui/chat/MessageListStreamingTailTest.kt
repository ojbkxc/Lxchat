package com.lxseek.chat.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListStreamingTailTest {
    @Test
    fun attachedStreamingTailSurvivesContentGrowth() {
        var mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.INACTIVE,
            StreamingTailFollowEvent.GenerationChanged(
                active = true,
            ),
        )
        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = false,
            ),
        )
        assertEquals(StreamingTailFollowMode.ATTACHED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.GenerationChanged(active = true),
        )
        assertEquals(StreamingTailFollowMode.ATTACHED, mode)
    }

    @Test
    fun realUserDragDetachesUntilAnExplicitBottomRequestCompletes() {
        var mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.INACTIVE,
            StreamingTailFollowEvent.GenerationChanged(
                active = true,
            ),
        )
        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = false,
            ),
        )
        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.UserDragStarted,
        )
        assertEquals(StreamingTailFollowMode.DETACHED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.GenerationChanged(active = true),
        )
        assertEquals(StreamingTailFollowMode.DETACHED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = true,
            ),
        )
        assertEquals(StreamingTailFollowMode.DETACHED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = false,
            ),
        )
        assertEquals(StreamingTailFollowMode.ATTACHED, mode)
    }

    @Test
    fun detachedTailIgnoresStreamingGeometryUntilExplicitUserReturn() {
        var mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.DETACHED,
            StreamingTailFollowEvent.GenerationChanged(
                active = true,
            ),
        )
        assertEquals(StreamingTailFollowMode.DETACHED, mode)
    }

    @Test
    fun attachedGenerationSettlesFinalLayoutBeforeReleasingTailFollow() {
        var mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.ATTACHED,
            StreamingTailFollowEvent.GenerationChanged(
                active = false,
            ),
        )

        assertEquals(StreamingTailFollowMode.SETTLING, mode)
        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.SettlingFinished,
        )
        assertEquals(StreamingTailFollowMode.INACTIVE, mode)
    }

    @Test
    fun detachedGenerationDoesNotReattachWhileFinishing() {
        val mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.DETACHED,
            StreamingTailFollowEvent.GenerationChanged(
                active = false,
            ),
        )

        assertEquals(StreamingTailFollowMode.INACTIVE, mode)
    }

    @Test
    fun streamingTailAttachesOnlyAfterNearBottomMotionSettles() {
        var mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.INACTIVE,
            StreamingTailFollowEvent.GenerationChanged(
                active = true,
            ),
        )
        assertEquals(StreamingTailFollowMode.ARMED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = true,
            ),
        )
        assertEquals(StreamingTailFollowMode.ARMED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = false,
            ),
        )
        assertEquals(StreamingTailFollowMode.ATTACHED, mode)
    }

    @Test
    fun programmaticSendScrollPausesTailWithoutCreatingASecondScrollOwner() {
        var mode = reduceStreamingTailGenerationAvailability(
            current = StreamingTailFollowMode.INACTIVE,
            active = true,
            autoFollowEnabled = false,
            autoFollowPaused = true,
        )
        assertEquals(StreamingTailFollowMode.INACTIVE, mode)

        mode = reduceStreamingTailGenerationAvailability(
            current = mode,
            active = true,
            autoFollowEnabled = true,
            autoFollowPaused = false,
        )
        assertEquals(StreamingTailFollowMode.ARMED, mode)
    }

    @Test
    fun absoluteBottomScrollIsAFollowHandoffRatherThanDetachment() {
        val availability = streamingTailAvailability(
            generationActive = true,
            blocked = false,
            programmaticHandoff = true,
        )

        assertFalse(availability.enabled)
        assertTrue(availability.paused)
        assertEquals(
            StreamingTailFollowMode.ATTACHED,
            reduceStreamingTailGenerationAvailability(
                current = StreamingTailFollowMode.ATTACHED,
                active = true,
                autoFollowEnabled = availability.enabled,
                autoFollowPaused = availability.paused,
            ),
        )
    }

    @Test
    fun realCompetingUiStillDisablesStreamingFollow() {
        val availability = streamingTailAvailability(
            generationActive = true,
            blocked = true,
            programmaticHandoff = true,
        )

        assertFalse(availability.enabled)
        assertFalse(availability.paused)
    }

    @Test
    fun nonScrollCompetitionStillDetachesStreamingTail() {
        val mode = reduceStreamingTailGenerationAvailability(
            current = StreamingTailFollowMode.ATTACHED,
            active = true,
            autoFollowEnabled = false,
            autoFollowPaused = false,
        )

        assertEquals(StreamingTailFollowMode.DETACHED, mode)
    }

    @Test
    fun coalescedTailStepIsBoundedAndMovesTowardTarget() {
        assertEquals(
            32f,
            coalescedScrollStep(
                errorPx = 500f,
                elapsedSeconds = 0.016f,
                timeConstantSeconds = 0.055f,
                maximumVelocityPxPerSecond = 2_000f,
                minimumStepPx = 2f,
            ),
            0.001f,
        )
        assertTrue(
            coalescedScrollStep(
                errorPx = -20f,
                elapsedSeconds = 0.016f,
                timeConstantSeconds = 0.055f,
                maximumVelocityPxPerSecond = 2_000f,
                minimumStepPx = 2f,
            ) < 0f,
        )
    }

    @Test
    fun sendEasingOnlyShapesStartupThenReturnsTheAdaptiveTailUnchanged() {
        val adaptiveStep = 120f
        val startupSpec = FeedbackScrollStartupSpec(
            durationMillis = 240L,
            easing = FastOutSlowInEasing,
        )
        val sendSpec = DefaultFeedbackScrollSpec.copy(startup = startupSpec)
        val initial = applyFeedbackScrollStartup(
            adaptiveStepPx = adaptiveStep,
            elapsedNanos = 0L,
            startup = sendSpec.startup,
        )
        val startup = applyFeedbackScrollStartup(
            adaptiveStepPx = adaptiveStep,
            elapsedNanos = 120_000_000L,
            startup = sendSpec.startup,
        )
        val adaptiveTail = applyFeedbackScrollStartup(
            adaptiveStepPx = adaptiveStep,
            elapsedNanos = 240_000_000L,
            startup = sendSpec.startup,
        )
        val bottomButtonStep = applyFeedbackScrollStartup(
            adaptiveStepPx = -adaptiveStep,
            elapsedNanos = 0L,
            startup = DefaultFeedbackScrollSpec.startup,
        )

        assertEquals(
            DefaultFeedbackScrollSpec,
            sendSpec.copy(startup = null),
        )
        assertEquals(0f, initial, 0.001f)
        assertTrue(startup in 0f..adaptiveStep)
        assertEquals(adaptiveStep, adaptiveTail, 0.001f)
        assertEquals(-adaptiveStep, bottomButtonStep, 0.001f)
    }

}
