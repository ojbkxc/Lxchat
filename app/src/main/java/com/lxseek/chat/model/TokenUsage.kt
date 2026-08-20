package com.lxseek.chat.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Provider-reported token usage for one or more completed model requests.
 *
 * Optional categories stay null when a provider did not report enough information to derive them.
 * In particular, an unknown cache split must never be presented as a real zero.
 *
 * [outputTokenCount] includes reasoning tokens when the provider bills/reports them as output;
 * [reasoningTokenCount] is the reported subset available for a later detailed breakdown.
 */
@Immutable
@Serializable
data class TokenUsage(
    val totalTokenCount: Int,
    val inputTokenCount: Int? = null,
    val cachedInputTokenCount: Int? = null,
    val uncachedInputTokenCount: Int? = null,
    val outputTokenCount: Int? = null,
    val reasoningTokenCount: Int? = null,
) {
    fun plusRequest(other: TokenUsage): TokenUsage = TokenUsage(
        totalTokenCount = addCounts(totalTokenCount, other.totalTokenCount),
        inputTokenCount = addReported(inputTokenCount, other.inputTokenCount),
        cachedInputTokenCount =
            addReported(cachedInputTokenCount, other.cachedInputTokenCount),
        uncachedInputTokenCount =
            addReported(uncachedInputTokenCount, other.uncachedInputTokenCount),
        outputTokenCount = addReported(outputTokenCount, other.outputTokenCount),
        reasoningTokenCount =
            addReported(reasoningTokenCount, other.reasoningTokenCount),
    )

    companion object {
        fun fromPersisted(
            totalTokenCount: Int,
            inputTokenCount: Int?,
            cachedInputTokenCount: Int?,
            uncachedInputTokenCount: Int?,
            outputTokenCount: Int?,
            reasoningTokenCount: Int?,
        ): TokenUsage? {
            if (
                totalTokenCount <= 0 &&
                inputTokenCount == null &&
                cachedInputTokenCount == null &&
                uncachedInputTokenCount == null &&
                outputTokenCount == null &&
                reasoningTokenCount == null
            ) {
                return null
            }
            return TokenUsage(
                totalTokenCount = totalTokenCount.coerceAtLeast(0),
                inputTokenCount = inputTokenCount.nonNegativeOrNull(),
                cachedInputTokenCount = cachedInputTokenCount.nonNegativeOrNull(),
                uncachedInputTokenCount = uncachedInputTokenCount.nonNegativeOrNull(),
                outputTokenCount = outputTokenCount.nonNegativeOrNull(),
                reasoningTokenCount = reasoningTokenCount.nonNegativeOrNull(),
            )
        }

        internal fun addCounts(first: Int, second: Int): Int =
            (first.toLong() + second.toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()

        private fun addReported(first: Int?, second: Int?): Int? =
            if (first == null || second == null) null else addCounts(first, second)

        private fun Int?.nonNegativeOrNull(): Int? = this?.coerceAtLeast(0)
    }
}

/**
 * Aggregates provider usage without double-counting cumulative stream snapshots.
 *
 * A provider request may report usage multiple times; [observeRequestSnapshot] replaces the
 * current request snapshot. Only [finishRequest] adds that final snapshot to previous tool-loop
 * rounds.
 */
internal class RequestTokenUsageAccumulator {
    private var completedUsage: TokenUsage? = null
    private var currentRequestUsage: TokenUsage? = null
    private var requestActive = false

    fun beginRequest() {
        check(!requestActive) { "A token-usage request is already active" }
        currentRequestUsage = null
        requestActive = true
    }

    fun observeRequestSnapshot(usage: TokenUsage) {
        check(requestActive) { "Token usage arrived outside a provider request" }
        currentRequestUsage = usage
    }

    fun finishRequest() {
        if (!requestActive) return
        currentRequestUsage?.let { requestUsage ->
            completedUsage = completedUsage?.plusRequest(requestUsage) ?: requestUsage
        }
        currentRequestUsage = null
        requestActive = false
    }

    fun snapshot(): TokenUsage? = when {
        completedUsage == null -> currentRequestUsage
        currentRequestUsage == null -> completedUsage
        else -> completedUsage?.plusRequest(checkNotNull(currentRequestUsage))
    }
}
