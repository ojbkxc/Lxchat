package com.lxseek.chat.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The editor model ↔ storage mapping. A regression here silently changes when a task fires, so
 * every recurrence type's cron shape and its round trip are pinned. ONCE is the sharp case: it
 * must NOT become a cron (a 5-field cron has no year, so a date would repeat every year).
 */
class TaskScheduleTest {

    @Test fun daily_roundTrips() {
        val s = TaskSchedule(ScheduleType.DAILY, hour = 9, minute = 30)
        assertEquals("30 9 * * *", s.toCron())
        assertNull(s.toRunAt())
        val back = TaskSchedule.parse(s.toCron(), null)!!
        assertEquals(ScheduleType.DAILY, back.type)
        assertEquals(9, back.hour); assertEquals(30, back.minute)
    }

    @Test fun weekly_multipleDays_roundTrips() {
        val s = TaskSchedule(ScheduleType.WEEKLY, hour = 8, minute = 0, daysOfWeek = setOf(1, 3, 5))
        assertEquals("0 8 * * 1,3,5", s.toCron())
        val back = TaskSchedule.parse(s.toCron(), null)!!
        assertEquals(ScheduleType.WEEKLY, back.type)
        assertEquals(setOf(1, 3, 5), back.daysOfWeek)
    }

    @Test fun weekly_sundayAsSeven_normalizesToZero() {
        // cron accepts 7 for Sunday; the model canonicalizes to 0.
        val back = TaskSchedule.parse("0 8 * * 7", null)!!
        assertEquals(ScheduleType.WEEKLY, back.type)
        assertEquals(setOf(0), back.daysOfWeek)
    }

    @Test fun monthly_roundTrips() {
        val s = TaskSchedule(ScheduleType.MONTHLY, hour = 0, minute = 0, dayOfMonth = 15)
        assertEquals("0 0 15 * *", s.toCron())
        val back = TaskSchedule.parse(s.toCron(), null)!!
        assertEquals(ScheduleType.MONTHLY, back.type)
        assertEquals(15, back.dayOfMonth)
    }

    @Test fun yearly_roundTrips() {
        val s = TaskSchedule(ScheduleType.YEARLY, hour = 12, minute = 0, dayOfMonth = 3, month = 3)
        assertEquals("0 12 3 3 *", s.toCron())
        val back = TaskSchedule.parse(s.toCron(), null)!!
        assertEquals(ScheduleType.YEARLY, back.type)
        assertEquals(3, back.dayOfMonth); assertEquals(3, back.month)
    }

    @Test fun once_isNeverACron() {
        val future = System.currentTimeMillis() + 86_400_000L
        val s = TaskSchedule(ScheduleType.ONCE, hour = 9, minute = 0, onceAtMillis = future)
        assertEquals("", s.toCron())
        assertEquals(future, s.toRunAt())
    }

    @Test fun once_parsesFromRunAt_notCron() {
        val future = System.currentTimeMillis() + 86_400_000L
        val back = TaskSchedule.parse("", future)!!
        assertEquals(ScheduleType.ONCE, back.type)
        assertEquals(future, back.onceAtMillis)
    }

    @Test fun bothDomAndDow_restricted_isUnmappable() {
        // cron ORs day-of-month and day-of-week; no single editor row expresses that.
        assertNull(TaskSchedule.parse("0 9 1 * 1", null))
    }

    @Test fun stepExpression_isUnmappable() {
        // A legacy "every hour" preset must be preserved as custom cron, not coerced.
        assertNull(TaskSchedule.parse("0 * * * *", null))
    }

    @Test fun withTime_onDaily_keepsCronAndChangesTime() {
        val s = TaskSchedule(ScheduleType.DAILY, hour = 9, minute = 0).withTime(6, 45)
        assertEquals("45 6 * * *", s.toCron())
    }

    @Test fun withOnceAt_pastDateRemainsPastForValidation() {
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
        val s = TaskSchedule(ScheduleType.ONCE, hour = 9, minute = 0).withOnceAt(
            yesterday.get(Calendar.YEAR),
            yesterday.get(Calendar.MONTH) + 1,
            yesterday.get(Calendar.DAY_OF_MONTH),
        )
        assertTrue(
            "an explicit past date must not mutate into another year",
            s.onceAtMillis < System.currentTimeMillis(),
        )
    }

    @Test fun switchedTo_dailyFromMonthly_dropsToPlainCron() {
        val monthly = TaskSchedule(ScheduleType.MONTHLY, hour = 7, minute = 0, dayOfMonth = 20)
        val daily = monthly.switchedTo(ScheduleType.DAILY)
        assertEquals(ScheduleType.DAILY, daily.type)
        assertEquals("0 7 * * *", daily.toCron())
    }

    @Test fun switchedTo_once_producesFutureInstant() {
        val daily = TaskSchedule(ScheduleType.DAILY, hour = 0, minute = 0)
        val once = daily.switchedTo(ScheduleType.ONCE)
        assertEquals(ScheduleType.ONCE, once.type)
        assertTrue(once.toRunAt()!! > System.currentTimeMillis())
        assertTrue(once.toRunAt()!! < System.currentTimeMillis() + 25 * 60 * 60 * 1000L)
    }
}
