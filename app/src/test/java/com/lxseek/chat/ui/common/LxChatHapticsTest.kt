package com.lxseek.chat.ui.common

import android.os.Build
import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class LxChatHapticsTest {
    @Test
    fun selectionUsesSemanticSegmentTickWhenAvailable() {
        assertEquals(
            HapticFeedbackConstants.SEGMENT_TICK,
            selectionFeedbackForSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
        assertEquals(
            HapticFeedbackConstants.CLOCK_TICK,
            selectionFeedbackForSdk(Build.VERSION_CODES.TIRAMISU),
        )
    }

    @Test
    fun togglePreservesOnAndOffMeaningWhenAvailable() {
        assertEquals(
            HapticFeedbackConstants.TOGGLE_ON,
            toggleFeedbackForSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, isOn = true),
        )
        assertEquals(
            HapticFeedbackConstants.TOGGLE_OFF,
            toggleFeedbackForSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, isOn = false),
        )
        assertEquals(
            HapticFeedbackConstants.CLOCK_TICK,
            toggleFeedbackForSdk(Build.VERSION_CODES.TIRAMISU, isOn = true),
        )
    }

    @Test
    fun confirmAndRejectUseTheirSemanticConstantsWhenAvailable() {
        assertEquals(
            HapticFeedbackConstants.CONFIRM,
            confirmFeedbackForSdk(Build.VERSION_CODES.R),
        )
        assertEquals(
            HapticFeedbackConstants.REJECT,
            rejectFeedbackForSdk(Build.VERSION_CODES.R),
        )
        assertEquals(
            HapticFeedbackConstants.VIRTUAL_KEY,
            confirmFeedbackForSdk(Build.VERSION_CODES.Q),
        )
        assertEquals(
            HapticFeedbackConstants.LONG_PRESS,
            rejectFeedbackForSdk(Build.VERSION_CODES.Q),
        )
    }
}
