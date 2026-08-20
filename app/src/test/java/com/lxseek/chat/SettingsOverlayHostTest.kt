package com.lxseek.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsOverlayHostTest {
    @Test
    fun spatialMotionUsesOffsetAndScaleForBothBoundaries() {
        assertEquals(
            SettingsOverlayTransform(pageOffsetFraction = 0.25f, pageScale = 0.92f),
            settingsOverlayEnterTransform(allowSpatialTransitions = true),
        )
        assertEquals(
            SettingsOverlayTransform(pageOffsetFraction = 1f, pageScale = 0.94f),
            settingsOverlayExitTransform(allowSpatialTransitions = true),
        )
    }

    @Test
    fun reducedMotionKeepsPageTransformStable() {
        val stable = SettingsOverlayTransform(pageOffsetFraction = 0f, pageScale = 1f)

        assertEquals(stable, settingsOverlayEnterTransform(allowSpatialTransitions = false))
        assertEquals(stable, settingsOverlayExitTransform(allowSpatialTransitions = false))
    }
}
