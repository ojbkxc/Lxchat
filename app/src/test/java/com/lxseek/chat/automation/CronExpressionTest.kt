package com.lxseek.chat.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CronExpressionTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun next(expr: String, from: Long): Long? =
        CronExpression.parse(expr)!!.next(from, utc)

    // ── Validation ────────────────────────────────────────────

    @Test fun rejectsWrongFieldCount() {
        assertNull(CronExpression.parse("* * * *"))
        assertNull(CronExpression.parse("* * * * * *"))
    }

    @Test fun rejectsOutOfRange() {
        assertFalse(CronExpression.isValid("60 * * * *"))
        assertFalse(CronExpression.isValid("* 24 * * *"))
        assertFalse(CronExpression.isValid("* * 0 * *"))   // dom min is 1
        assertFalse(CronExpression.isValid("* * * 13 *"))
        assertFalse(CronExpression.isValid("* * * * 8"))   // dow max is 7
    }

    @Test fun rejectsGarbage() {
        assertFalse(CronExpression.isValid("a b c d e"))
        assertFalse(CronExpression.isValid("*/0 * * * *"))
        assertFalse(CronExpression.isValid("5-1 * * * *")) // inverted range
    }

    @Test fun acceptsCommonForms() {
        assertTrue(CronExpression.isValid("*/15 * * * *"))
        assertTrue(CronExpression.isValid("0 9 * * 1-5"))
        assertTrue(CronExpression.isValid("0 0,12 1 */2 *"))
        assertTrue(CronExpression.isValid("0 9 * * 7")) // 7 == Sunday
    }

    // ── next() ────────────────────────────────────────────────

    @Test fun everyDayAt0900() {
        // From 08:00 → same day 09:00.
        assertEquals(at(2026, 6, 25, 9, 0), next("0 9 * * *", at(2026, 6, 25, 8, 0)))
        // From 09:00 exactly → next day (strictly after).
        assertEquals(at(2026, 6, 26, 9, 0), next("0 9 * * *", at(2026, 6, 25, 9, 0)))
    }

    @Test fun everyFifteenMinutes() {
        assertEquals(at(2026, 6, 25, 8, 15), next("*/15 * * * *", at(2026, 6, 25, 8, 1)))
        assertEquals(at(2026, 6, 25, 9, 0), next("*/15 * * * *", at(2026, 6, 25, 8, 45)))
    }

    @Test fun weekdaysOnly() {
        // 2026-06-26 is a Friday; next weekday 09:00 after Fri 10:00 is Mon 2026-06-29.
        assertEquals(at(2026, 6, 29, 9, 0), next("0 9 * * 1-5", at(2026, 6, 26, 10, 0)))
    }

    @Test fun domOrDowUnion() {
        // dom=1 OR dow=1(Mon). From mid-June 2026: Monday 2026-06-22 fires before the 1st.
        val result = next("0 0 1 * 1", at(2026, 6, 18, 0, 0))
        assertEquals(at(2026, 6, 22, 0, 0), result) // 2026-06-22 is a Monday
    }

    @Test fun rollsOverYear() {
        assertEquals(at(2027, 1, 1, 0, 0), next("0 0 1 1 *", at(2026, 6, 25, 12, 0)))
    }

    @Test fun specificListOfHours() {
        assertEquals(at(2026, 6, 25, 12, 0), next("0 0,12 * * *", at(2026, 6, 25, 9, 0)))
        assertEquals(at(2026, 6, 26, 0, 0), next("0 0,12 * * *", at(2026, 6, 25, 12, 0)))
    }

    @Test fun parsesAndResolvesNonNull() {
        assertNotNull(CronExpression.parse("0 9 * * *")!!.next(System.currentTimeMillis(), utc))
    }
}
