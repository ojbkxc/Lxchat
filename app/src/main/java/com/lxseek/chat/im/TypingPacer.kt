package com.lxseek.chat.im

import kotlin.random.Random

/**
 * Humanlike typing delay for outbound IM messages, borrowing the reference python bot's pacing
 * idea (long messages are "typed" over a longer, randomized window) without turning it into a
 * hard gate: the delay is bounded so a slow reply never feels broken, and it is skipped entirely
 * when [enabled] is false.
 *
 * Heuristic: roughly `ceil(chars / charsPerSecond)` seconds, then add jitter and clamp to
 * [MAX_DELAY_MS]. Short replies stay snappy; long ones get a natural, slightly staggered feel.
 */
object TypingPacer {

    /** How long to "think/type" before actually sending [text] best-effort. Callers decide whether to await. */
    fun delayMsFor(text: String, enabled: Boolean = true): Long {
        if (!enabled || text.isBlank()) return 0L
        val chars = text.length
        val seconds = (chars.toDouble() / CHARS_PER_SECOND).toLong().coerceAtLeast(1L)
        val base = seconds * 1000L
        val jitter = Random.nextLong(-JITTER_MS, JITTER_MS + 1)
        return (base + jitter).coerceIn(0L, MAX_DELAY_MS)
    }

    private const val CHARS_PER_SECOND = 12.0
    private const val JITTER_MS = 800L
    private const val MAX_DELAY_MS = 3_000L
}