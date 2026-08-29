package com.lxseek.chat.runtime

import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-account quota pool with round-robin rotation, daily usage caps, exponential
 * backoff on failure, and automatic failover across accounts that share the same
 * provider.
 *
 * Lifecycle of an account:
 *  - [acquire] selects the least-recently-used eligible account for a provider.
 *    An account is eligible when it is not temporarily disabled, has not hit its
 *    daily limit, and has fewer than [MAX_FAILURES] consecutive failures.
 *  - [reportSuccess] clears the consecutive-failure counter.
 *  - [reportFailure] increments the failure counter and disables the account for
 *    `2^failures * 1000` ms (exponential backoff). After [MAX_FAILURES] failures
 *    the account stays ineligible until [reportSuccess] or [resetDaily] clears it.
 *  - [reportUsage] adds consumed tokens to [QuotaAccount.dailyUsed].
 *  - [resetDaily] zeroes every account's `dailyUsed` and clears soft-disable
 *    state so a new quota window can begin.
 *
 * State is exposed as a hot [StateFlow] so UI / observability layers can react
 * to account additions, removals and health transitions without polling.
 *
 * Pure Kotlin + kotlinx.coroutines only — zero external dependencies. Designed
 * to be injected as a process-scoped singleton via [com.lxseek.chat.di.AppContainer].
 */
class QuotaPool {

    /** A single API-key backed account for a provider. */
    data class QuotaAccount(
        val id: String,
        val providerId: String,
        val apiKey: String,
        val dailyLimit: Int,
        val dailyUsed: Int = 0,
        /** Epoch millis of the last acquire / usage, used for round-robin ordering. */
        val lastUsed: Long = 0L,
        val consecutiveFailures: Int = 0,
        /** Epoch millis until which the account is considered disabled; null = active. */
        val disabledUntil: Long? = null,
    )

    /** Result of an [acquire] call: either a usable account or a human-readable reason. */
    data class QuotaAcquireResult(
        val account: QuotaAccount?,
        val reason: String,
    ) {
        val isSuccess: Boolean get() = account != null
    }

    /** Maximum consecutive failures before an account is taken out of rotation. */
    private val maxFailures: Int = MAX_FAILURES

    // id -> account (single source of truth)
    private val accountsById = ConcurrentHashMap<String, QuotaAccount>()

    /** Hot, observable snapshot of all accounts. */
    private val _accounts = MutableStateFlow<List<QuotaAccount>>(emptyList())
    val accounts: StateFlow<List<QuotaAccount>> = _accounts.asStateFlow()

    // ── Mutation ───────────────────────────────────────────────

    /** Registers a new account or replaces an existing one with the same id. */
    fun addAccount(account: QuotaAccount) {
        accountsById[account.id] = account
        publishSnapshot()
        DebugLog.d(TAG, "addAccount id=${account.id} provider=${account.providerId} limit=${account.dailyLimit}")
    }

    /** Removes the account with [id], if present. */
    fun removeAccount(id: String) {
        val removed = accountsById.remove(id) != null
        if (removed) {
            publishSnapshot()
            DebugLog.d(TAG, "removeAccount id=$id")
        }
    }

    /** Returns a defensive snapshot of all accounts. */
    fun getAccounts(): List<QuotaAccount> = accountsById.values.toList()

    // ── Acquire ────────────────────────────────────────────────

    /**
     * Selects the best eligible account for [providerId] using least-recently-used
     * round-robin. Returns a failed [QuotaAcquireResult] with a diagnostic reason
     * when no account can serve the request.
     */
    fun acquire(providerId: String): QuotaAcquireResult {
        val now = System.currentTimeMillis()
        val candidates = accountsById.values
            .filter { it.providerId == providerId }

        if (candidates.isEmpty()) {
            return QuotaAcquireResult(null, "No accounts registered for provider '$providerId'")
        }

        // Eligibility filter — collect the first failure reason for a useful diagnostic.
        var firstReason: String? = null
        val eligible = candidates.filter { acct ->
            eligibleReason(acct, now).also { reason ->
                if (reason != null && firstReason == null) firstReason = reason
            } == null
        }

        if (eligible.isEmpty()) {
            return QuotaAcquireResult(null, firstReason ?: "No eligible accounts for provider '$providerId'")
        }

        // Round-robin: least-recently-used first, tie-break by id for determinism.
        val picked = eligible.sortedWith(
            compareBy<QuotaAccount> { it.lastUsed }.thenBy { it.id }
        ).first()

        // Stamp lastUsed so the next acquire rotates to a different account.
        accountsById[picked.id] = picked.copy(lastUsed = now)
        publishSnapshot()

        DebugLog.d(TAG, "acquire provider=$providerId -> account=${picked.id} (used=${picked.dailyUsed}/${picked.dailyLimit})")
        return QuotaAcquireResult(accountsById[picked.id] ?: picked, "OK")
    }

    /**
     * Returns `null` when [acct] is eligible at [now], otherwise a short reason
     * string explaining why it was skipped.
     */
    private fun eligibleReason(acct: QuotaAccount, now: Long): String? = when {
        acct.disabledUntil != null && acct.disabledUntil > now ->
            "account '${acct.id}' disabled until ${acct.disabledUntil} (now=$now)"
        acct.dailyLimit > 0 && acct.dailyUsed >= acct.dailyLimit ->
            "account '${acct.id}' daily limit reached (${acct.dailyUsed}/${acct.dailyLimit})"
        acct.consecutiveFailures >= maxFailures ->
            "account '${acct.id}' exceeded $maxFailures consecutive failures"
        else -> null
    }

    // ── Feedback ───────────────────────────────────────────────

    /** Marks [accountId] as healthy: clears the failure counter and any disable window. */
    fun reportSuccess(accountId: String) {
        update(accountId) { it.copy(consecutiveFailures = 0, disabledUntil = null) }
        DebugLog.d(TAG, "reportSuccess id=$accountId")
    }

    /**
     * Records a failure on [accountId]: increments the failure counter and applies
     * exponential backoff `disabledUntil = now + 2^failures * 1000` ms.
     */
    fun reportFailure(accountId: String) {
        update(accountId) {
            val failures = it.consecutiveFailures + 1
            val backoffMs = (1L shl failures) * BASE_BACKOFF_MS // 2^failures * 1000
            val until = System.currentTimeMillis() + backoffMs
            it.copy(consecutiveFailures = failures, disabledUntil = until)
        }
        val acct = accountsById[accountId]
        DebugLog.d(TAG, "reportFailure id=$accountId failures=${acct?.consecutiveFailures} disabledUntil=${acct?.disabledUntil}")
    }

    /** Adds [tokens] to [accountId]'s daily usage counter. */
    fun reportUsage(accountId: String, tokens: Int) {
        if (tokens <= 0) return
        update(accountId) {
            it.copy(dailyUsed = it.dailyUsed + tokens, lastUsed = System.currentTimeMillis())
        }
        DebugLog.d(TAG, "reportUsage id=$accountId tokens=$tokens")
    }

    /**
     * Begins a new quota window: zeroes `dailyUsed` for every account and clears
     * soft-disable windows so temporarily backed-off accounts get a fresh chance.
     * Consecutive-failure counters are preserved — only an explicit [reportSuccess]
     * clears those, so a genuinely broken account is not retried on daily reset.
     */
    fun resetDaily() {
        var touched = false
        accountsById.keys.forEach { id ->
            accountsById.computeIfPresent(id) { _, acct ->
                touched = true
                acct.copy(dailyUsed = 0, disabledUntil = null)
            }
        }
        if (touched) {
            publishSnapshot()
            DebugLog.d(TAG, "resetDaily cleared ${accountsById.size} accounts")
        }
    }

    // ── Observability ──────────────────────────────────────────

    /** Returns a snapshot map of `id -> account` for health dashboards. */
    fun getHealthStatus(): Map<String, QuotaAccount> = accountsById.toMap()

    // ── Internals ──────────────────────────────────────────────

    private fun update(id: String, transform: (QuotaAccount) -> QuotaAccount) {
        accountsById.computeIfPresent(id) { _, acct -> transform(acct) }
        publishSnapshot()
    }

    private fun publishSnapshot() {
        _accounts.value = accountsById.values.toList()
    }

    companion object {
        private const val TAG = "QuotaPool"

        /** Consecutive failures before an account is retired from rotation. */
        const val MAX_FAILURES = 5

        /** Base backoff in ms; actual backoff is `2^failures * BASE_BACKOFF_MS`. */
        const val BASE_BACKOFF_MS: Long = 1000L
    }
}