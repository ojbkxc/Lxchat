package com.lxseek.chat.util

/**
 * Lightweight content-safety keyword filter inspired by AstrBot's
 * ContentSafetyCheckStage / keywords strategy.
 *
 * Pure-Kotlin, zero dependencies, sub-millisecond per check.
 * Designed to be called before sending a message to the LLM or
 * before displaying a model response. Does not block; returns a
 * [Result] so the caller can decide whether to warn, reject, or log.
 */
class ContentSafetyFilter(
    private val blockedKeywords: List<String> = DEFAULT_BLOCKED_KEYWORDS,
    private val warnKeywords: List<String> = DEFAULT_WARN_KEYWORDS,
) {
    data class Result(val allowed: Boolean, val reason: String? = null, val matchedKeyword: String? = null)

    /** Check user input before sending. Returns [Result] with allowed=false if a blocked keyword matches. */
    fun checkInput(text: String): Result {
        val lower = text.lowercase()
        for (kw in blockedKeywords) {
            if (lower.contains(kw)) {
                return Result(allowed = false, reason = "blocked", matchedKeyword = kw)
            }
        }
        for (kw in warnKeywords) {
            if (lower.contains(kw)) {
                return Result(allowed = true, reason = "warn", matchedKeyword = kw)
            }
        }
        return Result(allowed = true)
    }

    /** Check model output before displaying. Always allows but may flag for logging. */
    fun checkOutput(text: String): Result {
        val lower = text.lowercase()
        for (kw in blockedKeywords) {
            if (lower.contains(kw)) {
                return Result(allowed = true, reason = "output_flagged", matchedKeyword = kw)
            }
        }
        return Result(allowed = true)
    }

    companion object {
        // Minimal default list — extend via constructor for app-specific keywords.
        val DEFAULT_BLOCKED_KEYWORDS: List<String> = emptyList()
        val DEFAULT_WARN_KEYWORDS: List<String> = listOf("ignore previous", "system prompt", "jailbreak")
    }
}
