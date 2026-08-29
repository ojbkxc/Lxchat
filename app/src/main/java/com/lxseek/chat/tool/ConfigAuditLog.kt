package com.lxseek.chat.tool

/**
 * Single audit-log entry recording a configuration change.
 *
 * Captured by [ConfigAuditLog] whenever a meta tool mutates app configuration
 * (config_set, tool_toggle, set_permission, rollback_config). The log is
 * in-memory and bounded so it does not grow unbounded in long sessions.
 *
 * Fields:
 * - [timestamp]: wall-clock millis when the change happened.
 * - [toolName]: the meta tool that performed the change (e.g. "config_set",
 *   "tool_toggle") or the target tool name for permission changes.
 * - [changeType]: a short classifier ("config_set:model", "tool_toggle",
 *   "set_permission", "rollback") for filtering in queries.
 * - [oldValue] / [newValue]: string projections of the before/after state.
 *   For complex changes these may be a summary or the tool name.
 * - [agentId]: the conversation or agent that triggered the change, taken
 *   from [com.lxseek.chat.viewmodel.GenerationContext.conversationId].
 */
data class AuditLogEntry(
    val timestamp: Long,
    val toolName: String,
    val changeType: String,
    val oldValue: String,
    val newValue: String,
    val agentId: String,
)

/**
 * In-memory bounded audit log of configuration changes.
 *
 * Keeps at most [MAX_ENTRIES] entries (oldest evicted first) and returns query
 * results in reverse-chronological order so the most recent changes appear
 * first — the natural order for "what just happened?" inspection.
 *
 * Thread-safety: same single-threaded convention as [ConfigSnapshotManager].
 * The log is process-scoped and not persisted; it exists to give the agent
 * (and the user via the `get_audit_log` meta tool) a transparent view of
 * every configuration mutation in the current session.
 */
class ConfigAuditLog {
    private val entries = ArrayDeque<AuditLogEntry>()

    /**
     * Append a new entry; evicts the oldest when over capacity.
     *
     * Insertion is O(1) amortized thanks to [ArrayDeque].
     */
    fun log(entry: AuditLogEntry) {
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
        }
    }

    /**
     * Query entries for a specific [toolName], most-recent first.
     *
     * Pass null for [toolName] to query all tools. [limit] caps the result
     * count; passing a limit larger than the log size is safe.
     */
    fun query(toolName: String?, limit: Int): List<AuditLogEntry> {
        val filtered = if (toolName == null) {
            entries.toList()
        } else {
            entries.filter { it.toolName == toolName }
        }
        val safeLimit = limit.coerceAtLeast(0)
        return filtered.takeLast(safeLimit).asReversed()
    }

    /** Query all entries, most-recent first. Equivalent to `query(null, limit)`. */
    fun queryAll(limit: Int): List<AuditLogEntry> = query(null, limit)

    companion object {
        /** Maximum number of audit entries retained in memory. */
        const val MAX_ENTRIES = 100
    }
}