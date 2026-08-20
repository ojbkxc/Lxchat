package com.lxseek.chat.automation

/** Cost and cadence guardrails shared by the Loop domain layer and model-facing tools. */
object LoopPolicy {
    const val MIN_INTERVAL_SECONDS = 10L
    const val MAX_INTERVAL_SECONDS = 604_800L // 7 days
    const val MIN_INTERVAL_MS = MIN_INTERVAL_SECONDS * 1_000L
    const val MAX_INTERVAL_MS = MAX_INTERVAL_SECONDS * 1_000L

    const val DEFAULT_MAX_CYCLES = 10
    const val MIN_MAX_CYCLES = 1
    const val MAX_MAX_CYCLES = 100

    const val DEFAULT_PROMPT = "Continue."

    fun validate(intervalMs: Long, maxCycles: Int): String? = when {
        intervalMs !in MIN_INTERVAL_MS..MAX_INTERVAL_MS ->
            "interval must be between $MIN_INTERVAL_SECONDS and $MAX_INTERVAL_SECONDS seconds"
        maxCycles !in MIN_MAX_CYCLES..MAX_MAX_CYCLES ->
            "maxCycles must be between $MIN_MAX_CYCLES and $MAX_MAX_CYCLES"
        else -> null
    }

    fun normalizePrompt(prompt: String?): String? = prompt?.trim()?.takeIf { it.isNotEmpty() }

    fun promptForExecution(prompt: String?): String = normalizePrompt(prompt) ?: DEFAULT_PROMPT

    fun nextFireAt(now: Long, intervalMs: Long): Long =
        if (now > Long.MAX_VALUE - intervalMs) Long.MAX_VALUE else now + intervalMs

    fun nextRevision(current: Long): Long =
        if (current == Long.MAX_VALUE) 0L else current + 1L
}
