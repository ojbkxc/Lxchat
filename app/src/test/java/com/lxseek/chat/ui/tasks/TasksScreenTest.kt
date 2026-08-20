package com.lxseek.chat.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksScreenTest {
    @Test
    fun countdown_clampsExpiredRunsToZero() {
        assertEquals("0:00", formatTaskCountdown(-1L))
        assertEquals("0:00", formatTaskCountdown(0L))
    }

    @Test
    fun countdown_roundsUpPartialSeconds() {
        assertEquals("0:01", formatTaskCountdown(1L))
        assertEquals("0:43", formatTaskCountdown(42_001L))
    }

    @Test
    fun countdown_includesHoursWithoutWrappingAtOneDay() {
        assertEquals("1:02:03", formatTaskCountdown(3_723_000L))
        assertEquals("25:00:00", formatTaskCountdown(90_000_000L))
    }

    @Test
    fun scheduleEditorMode_detectsStructuredAndCustomSchedules() {
        assertEquals(
            ScheduleEditorMode.DAILY,
            initialScheduleEditorMode("30 9 * * *", null),
        )
        assertEquals(
            ScheduleEditorMode.CUSTOM,
            initialScheduleEditorMode("0 */2 * * *", null),
        )
        assertEquals(
            ScheduleEditorMode.CUSTOM,
            initialScheduleEditorMode("temporarily incomplete", null),
        )
    }

    @Test
    fun customScheduleDraft_mustBeNonBlankAndValid() {
        assertFalse(isScheduleDraftValid(ScheduleEditorMode.CUSTOM, ""))
        assertFalse(isScheduleDraftValid(ScheduleEditorMode.CUSTOM, "0 9 *"))
        assertTrue(isScheduleDraftValid(ScheduleEditorMode.CUSTOM, "0 9 * * *"))
        assertTrue(isScheduleDraftValid(ScheduleEditorMode.DAILY, ""))
    }

    @Test
    fun yearlyMonthDay_allowsLeapDayAndClampsShortMonths() {
        assertEquals(29, daysInYearlyMonth(2))
        assertEquals(30, daysInYearlyMonth(4))
        assertEquals(31, daysInYearlyMonth(12))
    }
}
