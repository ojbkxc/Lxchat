package com.lxseek.chat.util

/**
 * Context compression policy inspired by AstrBot's ContextManager / compressor.py.
 *
 * Two strategies:
 * 1. [TruncateByTurns] — when token count exceeds threshold, drop the oldest
 *    N turns. Simple, fast, no LLM call needed.
 * 2. [LLMSummary] — when token count exceeds threshold, ask the LLM to
 *    summarize older messages into a single system message, keeping the
 *    most recent [keepRecentRatio] fraction verbatim.
 *
 * The [shouldCompress] check is pure; the actual compression is deferred
 * to the caller so this class stays dependency-free.
 */
object ContextCompressor {

    /** Default compression trigger: 82% of max tokens (AstrBot default). */
    const val DEFAULT_COMPRESSION_THRESHOLD = 0.82

    /** Returns true if current tokens exceed threshold * maxTokens. */
    fun shouldCompress(currentTokens: Int, maxTokens: Int, threshold: Double = DEFAULT_COMPRESSION_THRESHOLD): Boolean {
        if (maxTokens <= 0) return false
        return currentTokens >= (maxTokens * threshold).toInt()
    }

    /**
     * Truncate by turns: keep only the [keepTurns] most recent user+assistant turn pairs.
     * System messages are always preserved. The first user message after system is always kept.
     *
     * Returns the indices of messages to keep (caller maps back to its own message list).
     */
    fun truncateByTurnsIndices(
        roles: List<String>,
        keepTurns: Int,
    ): List<Int> {
        if (roles.isEmpty() || keepTurns <= 0) return emptyList()
        val result = mutableListOf<Int>()
        // Always keep leading system messages.
        var firstNonSystem = 0
        for (i in roles.indices) {
            if (roles[i] == "system") {
                result.add(i)
                firstNonSystem = i + 1
            } else {
                firstNonSystem = i
                break
            }
        }
        // Count user turns from the end.
        val userTurnIndices = mutableListOf<Int>()
        for (i in firstNonSystem until roles.size) {
            if (roles[i] == "user") userTurnIndices.add(i)
        }
        val keepFromIndex = if (userTurnIndices.size <= keepTurns) {
            firstNonSystem
        } else {
            userTurnIndices[userTurnIndices.size - keepTurns]
        }
        // Always keep the first user message (required by many LLM APIs).
        if (firstNonSystem < roles.size && roles[firstNonSystem] == "user" && firstNonSystem !in result) {
            result.add(firstNonSystem)
        }
        for (i in keepFromIndex until roles.size) {
            if (i !in result) result.add(i)
        }
        return result.sorted()
    }

    /**
     * Compute the split point for LLM summary compression.
     * Messages before [splitPoint] will be summarized; messages from [splitPoint]
     * onward will be kept verbatim.
     *
     * [keepRecentRatio] is the fraction of recent messages to keep (0.0–1.0).
     */
    fun summarySplitPoint(messageCount: Int, keepRecentRatio: Double = 0.3): Int {
        if (messageCount <= 0) return 0
        val keep = (messageCount * keepRecentRatio).toInt().coerceAtLeast(1)
        return messageCount - keep
    }
}
