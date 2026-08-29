package com.lxseek.chat.metrics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks token usage across LLM calls for cost analysis and optimization.
 *
 * Captures input/output/cache token counts per call, per session, and cumulative.
 * Designed to be injected as a process-scoped singleton via [com.lxseek.chat.di.AppContainer].
 * The generation loop calls [record] after each Provider pass; the UI observes
 * [cumulative] or [records] for real-time cost display.
 */
class TokenUsageTracker {

    /** Single LLM call usage record. */
    data class UsageRecord(
        val timestamp: Long,
        val provider: String,
        val model: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val cachedTokens: Int = 0,
        val totalTokens: Int = inputTokens + outputTokens + cachedTokens,
        val sessionId: String? = null,
    )

    /** Aggregated session snapshot for cost reporting. */
    data class SessionSnapshot(
        val sessionId: String,
        val totalInput: Long,
        val totalOutput: Long,
        val totalCached: Long,
        val totalAll: Long,
        val callCount: Int,
        val byProvider: Map<String, Long>,
    )

    /** Aggregated token stats for a single tool, accumulated across calls. */
    data class ToolTokenStats(
        val toolName: String,
        val totalInputTokens: Long,
        val totalOutputTokens: Long,
        val callCount: Int,
        val avgTokensPerCall: Long = if (callCount == 0) 0L else (totalInputTokens + totalOutputTokens) / callCount,
    )

    private val _records = MutableStateFlow<List<UsageRecord>>(emptyList())
    val records: StateFlow<List<UsageRecord>> = _records.asStateFlow()

    private val _cumulative = MutableStateFlow(0L)
    val cumulative: StateFlow<Long> = _cumulative.asStateFlow()

    private val _sessionTotals = MutableStateFlow<Map<String, Long>>(emptyMap())
    val sessionTotals: StateFlow<Map<String, Long>> = _sessionTotals.asStateFlow()

    // Per-tool token accumulation: toolName -> (inputTokens, outputTokens, callCount).
    private val _toolStats = MutableStateFlow<Map<String, ToolTokenStats>>(emptyMap())
    /** Live per-tool token stats, keyed by tool name. */
    val toolStats: StateFlow<Map<String, ToolTokenStats>> = _toolStats.asStateFlow()

    /** Record a single LLM call's token usage. */
    fun record(record: UsageRecord) {
        _records.value = _records.value + record
        _cumulative.value += record.totalTokens.toLong()
        if (record.sessionId != null) {
            _sessionTotals.value = _sessionTotals.value.toMutableMap().apply {
                put(record.sessionId, (get(record.sessionId) ?: 0L) + record.totalTokens.toLong())
            }
        }
    }

    /** Convenience overload for common call sites. */
    fun record(
        provider: String,
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        cachedTokens: Int = 0,
        sessionId: String? = null,
    ) {
        record(
            UsageRecord(
                timestamp = System.currentTimeMillis(),
                provider = provider,
                model = model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cachedTokens = cachedTokens,
                sessionId = sessionId,
            )
        )
    }

    /** Get aggregated snapshot for a session. */
    fun sessionSnapshot(sessionId: String): SessionSnapshot {
        val sessionRecords = _records.value.filter { it.sessionId == sessionId }
        return SessionSnapshot(
            sessionId = sessionId,
            totalInput = sessionRecords.sumOf { it.inputTokens.toLong() },
            totalOutput = sessionRecords.sumOf { it.outputTokens.toLong() },
            totalCached = sessionRecords.sumOf { it.cachedTokens.toLong() },
            totalAll = sessionRecords.sumOf { it.totalTokens.toLong() },
            callCount = sessionRecords.size,
            byProvider = sessionRecords.groupBy { it.provider }
                .mapValues { (_, recs) -> recs.sumOf { it.totalTokens.toLong() } }
        )
    }

    /** Reset tracker (e.g. on new session or memory pressure). */
    fun reset() {
        _records.value = emptyList()
        _cumulative.value = 0L
        _sessionTotals.value = emptyMap()
        _toolStats.value = emptyMap()
    }

    /**
     * Records token usage attributed to a single tool invocation. Accumulates
     * per-tool stats exposed via [getToolUsageStats] / [toolStats]; does NOT
     * emit an [UsageRecord] (tool calls are not LLM calls). Use the regular
     * [record] overload for the LLM call that triggered the tool.
     */
    fun recordToolUsage(toolName: String, inputTokens: Int, outputTokens: Int) {
        if (toolName.isBlank()) return
        val input  = inputTokens.coerceAtLeast(0).toLong()
        val output = outputTokens.coerceAtLeast(0).toLong()
        _toolStats.value = _toolStats.value.toMutableMap().apply {
            val prev = get(toolName)
            put(
                toolName,
                if (prev == null) {
                    ToolTokenStats(
                        toolName = toolName,
                        totalInputTokens = input,
                        totalOutputTokens = output,
                        callCount = 1,
                    )
                } else {
                    ToolTokenStats(
                        toolName = toolName,
                        totalInputTokens = prev.totalInputTokens + input,
                        totalOutputTokens = prev.totalOutputTokens + output,
                        callCount = prev.callCount + 1,
                    )
                }
            )
        }
    }

    /** Returns a snapshot of per-tool token stats keyed by tool name. */
    fun getToolUsageStats(): Map<String, ToolTokenStats> = _toolStats.value

    /** Estimate tokens from character count when the API does not return usage info.
     *  Uses the common ~4 chars per token heuristic. */
    fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)
}