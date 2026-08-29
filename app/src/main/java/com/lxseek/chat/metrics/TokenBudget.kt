package com.lxseek.chat.metrics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Token budget manager: enforces session-level and daily-level token spend
 * limits so a runaway conversation or a misbehaving sub-agent cannot burn
 * through the user's quota unchecked. This is the global monitoring layer of
 * the Token optimization strategy.
 *
 * The manager exposes the current [TokenBudget] as a hot [StateFlow] so the UI
 * can render a progress bar / warning banner and the generation loop can
 * short-circuit when [isExceeded] is true. Limits themselves are also mutable
 * at runtime (e.g. from a settings page) and propagate through the same flow.
 *
 * Counters are in-memory only — `resetSession` is called when a new
 * conversation starts and `resetDaily` is called by a scheduled job at
 * local midnight. Persistence of the limits themselves is the caller's
 * responsibility (e.g. via [com.lxseek.chat.data.repository.SettingsRepository]).
 *
 * Designed to be injected as a process-scoped singleton via
 * [com.lxseek.chat.di.AppContainer].
 */
class TokenBudgetManager(
    sessionLimit: Int = DEFAULT_SESSION_LIMIT,
    dailyLimit: Int = DEFAULT_DAILY_LIMIT,
    warningThreshold: Float = DEFAULT_WARNING_THRESHOLD,
) {
    /** Immutable snapshot of the budget state at a point in time. */
    data class TokenBudget(
        /** Max tokens allowed in the current session; 0 = unlimited. */
        val sessionLimit: Int,
        /** Max tokens allowed per day; 0 = unlimited. */
        val dailyLimit: Int,
        /** Tokens consumed in the current session. */
        val sessionUsed: Int,
        /** Tokens consumed today. */
        val dailyUsed: Int,
        /** Fraction of the limit at which [isApproachingLimit] flips to true (0..1). */
        val warningThreshold: Float,
    )

    private val _budget = MutableStateFlow(
        TokenBudget(
            sessionLimit = sessionLimit,
            dailyLimit = dailyLimit,
            sessionUsed = 0,
            dailyUsed = 0,
            warningThreshold = warningThreshold.coerceIn(0f, 1f),
        )
    )
    val budget: StateFlow<TokenBudget> = _budget.asStateFlow()

    // ── Read ───────────────────────────────────────────────────

    /** Returns the current budget snapshot. */
    fun getBudget(): TokenBudget = _budget.value

    /**
     * `true` when either the session or daily counter has crossed the
     * [TokenBudget.warningThreshold] fraction of its limit. Limits of 0
     * (unlimited) never approach the limit.
     */
    fun isApproachingLimit(): Boolean {
        val b = _budget.value
        return ratio(b.sessionUsed, b.sessionLimit) >= b.warningThreshold ||
            ratio(b.dailyUsed, b.dailyLimit) >= b.warningThreshold
    }

    /** `true` when either the session or daily counter has reached / exceeded its limit. */
    fun isExceeded(): Boolean {
        val b = _budget.value
        return exceeds(b.sessionUsed, b.sessionLimit) || exceeds(b.dailyUsed, b.dailyLimit)
    }

    /**
     * Remaining tokens before the *binding* limit (the smaller of session /
     * daily remaining) is hit. Returns [Int.MAX_VALUE] when both limits are 0
     * (unlimited). Never negative.
     */
    fun remaining(): Int {
        val b = _budget.value
        val sessionRemaining = if (b.sessionLimit <= 0) Int.MAX_VALUE else (b.sessionLimit - b.sessionUsed)
        val dailyRemaining   = if (b.dailyLimit   <= 0) Int.MAX_VALUE else (b.dailyLimit   - b.dailyUsed)
        return minOf(sessionRemaining, dailyRemaining).coerceAtLeast(0)
    }

    // ── Write ──────────────────────────────────────────────────

    /** Sets the per-session token limit. Use 0 for unlimited. */
    fun setSessionLimit(tokens: Int) {
        update { it.copy(sessionLimit = tokens.coerceAtLeast(0)) }
    }

    /** Sets the per-day token limit. Use 0 for unlimited. */
    fun setDailyLimit(tokens: Int) {
        update { it.copy(dailyLimit = tokens.coerceAtLeast(0)) }
    }

    /** Sets the warning threshold (clamped to 0..1). */
    fun setWarningThreshold(threshold: Float) {
        update { it.copy(warningThreshold = threshold.coerceIn(0f, 1f)) }
    }

    /**
     * Consumes [tokens] against both the session and daily counters. Negative
     * values are clamped to 0 (refunds are not supported; call [resetSession]
     * or [resetDaily] instead).
     */
    fun consume(tokens: Int) {
        if (tokens <= 0) return
        update {
            it.copy(
                sessionUsed = it.sessionUsed + tokens,
                dailyUsed = it.dailyUsed + tokens,
            )
        }
    }

    /** Resets the session counter to 0 (e.g. on new conversation). */
    fun resetSession() {
        update { it.copy(sessionUsed = 0) }
    }

    /** Resets the daily counter to 0 (e.g. scheduled midnight job). */
    fun resetDaily() {
        update { it.copy(dailyUsed = 0) }
    }

    /** Resets both counters (convenience for tests / debug). */
    fun resetAll() {
        update { it.copy(sessionUsed = 0, dailyUsed = 0) }
    }

    // ── Helpers ───────────────────────────────────────────────

    private inline fun update(transform: (TokenBudget) -> TokenBudget) {
        _budget.value = transform(_budget.value)
    }

    private fun ratio(used: Int, limit: Int): Float =
        if (limit <= 0) 0f else used.toFloat() / limit.toFloat()

    private fun exceeds(used: Int, limit: Int): Boolean =
        limit > 0 && used >= limit

    companion object {
        /** Default per-session budget: 200k tokens. */
        const val DEFAULT_SESSION_LIMIT: Int = 200_000

        /** Default per-day budget: 1M tokens. */
        const val DEFAULT_DAILY_LIMIT: Int = 1_000_000

        /** Default warning threshold: 80% of the limit. */
        const val DEFAULT_WARNING_THRESHOLD: Float = 0.8f
    }
}