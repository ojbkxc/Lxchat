package com.lxseek.chat.automation

import java.util.Calendar
import java.util.TimeZone

/**
 * A parsed standard 5-field cron expression: `minute hour day-of-month month day-of-week`.
 *
 * Supported per field: `*`, single values, lists (`a,b`), ranges (`a-b`), and steps
 * (`* /n`, `a-b/n`, `a/n` meaning a, a+n, … up to the field max). Day-of-week is 0–6 with
 * 0 = Sunday; `7` is also accepted as Sunday. Month and day-of-week are numeric only.
 *
 * Pure and timezone-explicit so it is fully unit-testable with no Android dependencies.
 * When both day-of-month and day-of-week are restricted, a tick matches if **either**
 * matches — the standard Vixie-cron rule.
 */
class CronExpression private constructor(
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>,
    private val domRestricted: Boolean,
    private val dowRestricted: Boolean,
) {
    /**
     * The first instant strictly after [afterMillis] that matches this expression, evaluated
     * in [zone]. Returns null if no match is found within the search horizon (8 years — covers
     * Feb-29-only schedules). Seconds/millis of the result are zeroed.
     *
     * Walks day → hour → minute, skipping whole days/hours whose date/hour fields cannot
     * match (they are constant across the skipped span, so no minute inside can match).
     * Worst case is ~3k day probes instead of the 4.2M minute steps a naive walk needs for
     * a Feb-29-only schedule. Minute stepping inside a matching hour keeps the walk exact
     * across DST gaps/repeats — instants advance monotonically, wall-clock fields decide.
     */
    fun next(afterMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = afterMillis
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, 1) // strictly after
        }
        val deadline = cal.timeInMillis + HORIZON_MILLIS
        while (cal.timeInMillis <= deadline) {
            if (!dayMatches(cal)) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                continue
            }
            if (cal.get(Calendar.HOUR_OF_DAY) !in hours) {
                cal.add(Calendar.HOUR_OF_DAY, 1)
                cal.set(Calendar.MINUTE, 0)
                continue
            }
            if (cal.get(Calendar.MINUTE) in minutes) return cal.timeInMillis
            cal.add(Calendar.MINUTE, 1)
        }
        return null
    }

    /** Whether the civil day under [cal] satisfies the month + dom/dow constraints. */
    private fun dayMatches(cal: Calendar): Boolean {
        if (cal.get(Calendar.MONTH) + 1 !in months) return false
        val dom = cal.get(Calendar.DAY_OF_MONTH) in daysOfMonth
        val dow = (cal.get(Calendar.DAY_OF_WEEK) - 1) in daysOfWeek // Calendar SUNDAY=1 → 0
        return when {
            domRestricted && dowRestricted -> dom || dow // standard Vixie-cron OR rule
            else -> dom && dow
        }
    }

    companion object {
        // 8 years expressed in wall time; a valid expression always resolves far sooner.
        private const val HORIZON_MILLIS = 366L * 24 * 60 * 8 * 60_000L

        /** Parses [expr]; returns null if it is not a well-formed 5-field expression. */
        fun parse(expr: String): CronExpression? {
            val parts = expr.trim().split(Regex("\\s+"))
            if (parts.size != 5) return null
            val minutes = parseField(parts[0], 0, 59) ?: return null
            val hours = parseField(parts[1], 0, 23) ?: return null
            val daysOfMonth = parseField(parts[2], 1, 31) ?: return null
            val months = parseField(parts[3], 1, 12) ?: return null
            val daysOfWeek = parseField(parts[4], 0, 7)?.map { if (it == 7) 0 else it }?.toSet() ?: return null
            return CronExpression(
                minutes, hours, daysOfMonth, months, daysOfWeek,
                domRestricted = parts[2].trim() != "*",
                dowRestricted = parts[4].trim() != "*",
            )
        }

        fun isValid(expr: String): Boolean = parse(expr) != null

        /** Expands one field ("*", "a", "a,b", "a-b", "* /n", "a-b/n", "a/n") to its value set. */
        private fun parseField(field: String, min: Int, max: Int): Set<Int>? {
            val result = sortedSetOf<Int>()
            for (token in field.split(",")) {
                if (token.isEmpty()) return null
                val (rangePart, stepPart) = token.split("/").let {
                    when (it.size) {
                        1 -> it[0] to null
                        2 -> it[0] to it[1]
                        else -> return null
                    }
                }
                val step = stepPart?.toIntOrNull()?.takeIf { it > 0 } ?: if (stepPart == null) 1 else return null
                val (start, end) = when {
                    rangePart == "*" -> min to max
                    rangePart.contains("-") -> {
                        val r = rangePart.split("-")
                        if (r.size != 2) return null
                        val a = r[0].toIntOrNull() ?: return null
                        val b = r[1].toIntOrNull() ?: return null
                        a to b
                    }
                    else -> {
                        val v = rangePart.toIntOrNull() ?: return null
                        // A bare value with a step (a/n) runs from a up to the field max.
                        v to (if (stepPart != null) max else v)
                    }
                }
                if (start < min || end > max || start > end) return null
                var v = start
                while (v <= end) {
                    result.add(v)
                    v += step
                }
            }
            return result.ifEmpty { null }
        }
    }
}
