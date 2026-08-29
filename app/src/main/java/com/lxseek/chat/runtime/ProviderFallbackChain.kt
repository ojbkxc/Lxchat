package com.lxseek.chat.runtime

import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider fallback chain: executes a suspend [action] against an ordered list of
 * API providers and automatically retries on the next provider when an earlier
 * one throws.
 *
 * Ordering: providers are tried in ascending [ProviderConfig.priority] (lower
 * number = higher priority), and only [ProviderConfig.enabled] providers
 * participate. Ties on priority are broken by insertion id for determinism.
 *
 * Execution contract of [execute]:
 *  - The [action] lambda is invoked once per provider in priority order.
 *  - If [action] returns normally, the result is wrapped in a successful
 *    [FallbackResult] and remaining providers are skipped.
 *  - If [action] throws for a provider, the exception message is recorded in
 *    [FallbackResult.attemptedProviders] and the next provider is tried.
 *  - If every provider throws, a failed [FallbackResult] is returned with the
 *    last error and the full list of attempted providers.
 *  - If no enabled provider is configured, a failed [FallbackResult] is returned
 *    immediately without invoking [action].
 *
 * The chain never throws from [execute] — all failures are surfaced through
 * [FallbackResult], so callers can use a simple `when` instead of try/catch.
 *
 * State is exposed as a hot [StateFlow] so UI layers can render the live
 * provider list and toggle switches without polling.
 *
 * Pure Kotlin + kotlinx.coroutines only — zero external dependencies. Designed
 * to be injected as a process-scoped singleton via [com.lxseek.chat.di.AppContainer].
 */
class ProviderFallbackChain {

    /** Configuration for a single upstream provider. */
    data class ProviderConfig(
        val id: String,
        val name: String,
        val apiBaseUrl: String,
        val apiKey: String,
        val enabled: Boolean = true,
        /** Lower number = tried first. */
        val priority: Int = 0,
    )

    /** Outcome of an [execute] call. */
    data class FallbackResult(
        val success: Boolean,
        val usedProviderId: String?,
        val result: String?,
        val error: String?,
        /** `providerId -> outcome` for every provider that was tried. */
        val attemptedProviders: List<Attempt>,
    ) {
        /** Per-provider attempt record. */
        data class Attempt(
            val providerId: String,
            val providerName: String,
            val ok: Boolean,
            val message: String,
        )
    }

    // id -> config (single source of truth)
    private val providersById = ConcurrentHashMap<String, ProviderConfig>()

    /** Hot, observable snapshot of all providers (unsorted). */
    private val _providers = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val providers: StateFlow<List<ProviderConfig>> = _providers.asStateFlow()

    // ── Mutation ───────────────────────────────────────────────

    /** Registers a new provider or replaces an existing one with the same id. */
    fun addProvider(config: ProviderConfig) {
        providersById[config.id] = config
        publishSnapshot()
        DebugLog.d(TAG, "addProvider id=${config.id} name=${config.name} priority=${config.priority}")
    }

    /** Removes the provider with [id], if present. */
    fun removeProvider(id: String) {
        val removed = providersById.remove(id) != null
        if (removed) {
            publishSnapshot()
            DebugLog.d(TAG, "removeProvider id=$id")
        }
    }

    /** Enables or disables the provider with [id]. No-op when the id is unknown. */
    fun setEnabled(id: String, enabled: Boolean) {
        providersById.computeIfPresent(id) { _, cfg -> cfg.copy(enabled = enabled) }
        publishSnapshot()
        DebugLog.d(TAG, "setEnabled id=$id enabled=$enabled")
    }

    // ── Read ───────────────────────────────────────────────────

    /** Returns all providers sorted by ascending [ProviderConfig.priority]. */
    fun getProviders(): List<ProviderConfig> =
        providersById.values.sortedWith(
            compareBy<ProviderConfig> { it.priority }.thenBy { it.id }
        )

    // ── Execution ──────────────────────────────────────────────

    /**
     * Runs [action] against each enabled provider in priority order until one
     * succeeds. Never throws — all failures are reported through [FallbackResult].
     *
     * The action receives the [ProviderConfig] it should target and must return a
     * [String] result (e.g. an HTTP body or a parsed response id). Any exception
     * thrown by [action] is treated as a provider failure and triggers fallback.
     */
    suspend fun execute(action: suspend (ProviderConfig) -> String): FallbackResult {
        val ordered = getProviders().filter { it.enabled }

        if (ordered.isEmpty()) {
            DebugLog.w(TAG, "execute: no enabled providers configured")
            return FallbackResult(
                success = false,
                usedProviderId = null,
                result = null,
                error = "No enabled providers configured",
                attemptedProviders = emptyList(),
            )
        }

        val attempts = mutableListOf<FallbackResult.Attempt>()
        var lastError: String? = null

        for (cfg in ordered) {
            try {
                DebugLog.d(TAG, "execute: trying provider=${cfg.id} (${cfg.name})")
                val out = action(cfg)
                attempts += FallbackResult.Attempt(
                    providerId = cfg.id,
                    providerName = cfg.name,
                    ok = true,
                    message = "OK",
                )
                DebugLog.d(TAG, "execute: success on provider=${cfg.id}")
                return FallbackResult(
                    success = true,
                    usedProviderId = cfg.id,
                    result = out,
                    error = null,
                    attemptedProviders = attempts,
                )
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                lastError = msg
                attempts += FallbackResult.Attempt(
                    providerId = cfg.id,
                    providerName = cfg.name,
                    ok = false,
                    message = msg,
                )
                DebugLog.w(TAG, "execute: provider=${cfg.id} failed: $msg")
                // continue to next provider
            }
        }

        return FallbackResult(
            success = false,
            usedProviderId = null,
            result = null,
            error = lastError ?: "All providers failed",
            attemptedProviders = attempts,
        )
    }

    // ── Internals ──────────────────────────────────────────────

    private fun publishSnapshot() {
        _providers.value = providersById.values.toList()
    }

    companion object {
        private const val TAG = "ProviderFallbackChain"
    }
}