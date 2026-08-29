package com.lxseek.chat.tool

/**
 * Immutable snapshot of a configuration map captured at a point in time.
 *
 * Used by [ConfigSnapshotManager] to support safe rollback: before any mutating
 * meta tool applies a new config, a snapshot of the current state is recorded
 * so the agent (or user) can revert to it if the change turns out wrong.
 *
 * The [configMap] is a flat string-to-string projection of the whitelisted
 * configuration keys (model, temperature, max_tokens, top_p, pet_enabled,
 * pet_character, system_prompt_addon). Complex settings that cannot be
 * represented as a single string are skipped — rollback is best-effort for
 * the common "model/temperature switch" path, not a full app-state restore.
 */
data class ConfigSnapshot(
    val snapshotId: String,
    val timestamp: Long,
    val configMap: Map<String, String>,
    val reason: String,
)

/**
 * In-memory ring of the most recent [ConfigSnapshot]s.
 *
 * Keeps at most [MAX_SNAPSHOTS] entries (oldest evicted first) so the agent can
 * roll back to a known-good configuration without persisted state. No disk
 * storage is used — snapshots are process-scoped and lost on restart, which is
 * acceptable because they exist to undo in-session mistakes, not to survive
 * crashes.
 *
 * Thread-safety: access is single-threaded by convention (the generation
 * pipeline serializes meta-tool execution per conversation). If concurrent
 * access is ever needed, wrap the deque in a synchronized block.
 */
class ConfigSnapshotManager {
    private val snapshots = ArrayDeque<ConfigSnapshot>()
    private var counter = 0L

    /**
     * Capture a new snapshot and return it.
     *
     * The [configMap] is copied defensively so later mutations by the caller
     * do not alter the stored snapshot.
     */
    fun createSnapshot(configMap: Map<String, String>, reason: String): ConfigSnapshot {
        counter += 1
        val now = System.currentTimeMillis()
        val snapshot = ConfigSnapshot(
            snapshotId = "snap-$counter-$now",
            timestamp = now,
            configMap = configMap.toMap(),
            reason = reason,
        )
        snapshots.addLast(snapshot)
        while (snapshots.size > MAX_SNAPSHOTS) {
            snapshots.removeFirst()
        }
        return snapshot
    }

    /**
     * Look up a snapshot by id for rollback.
     *
     * Returns the matching [ConfigSnapshot] or null when the id is unknown or
     * the snapshot has been evicted. The snapshot is not removed from history —
     * rolling back to an older state does not discard newer snapshots, so the
     * agent can still inspect the failed state if needed.
     */
    fun rollback(snapshotId: String): ConfigSnapshot? =
        snapshots.firstOrNull { it.snapshotId == snapshotId }

    /** List all snapshots (oldest first), at most [MAX_SNAPSHOTS]. */
    fun listSnapshots(): List<ConfigSnapshot> = snapshots.toList()

    /** Most recent snapshot, or null when none exists. */
    fun getLatestSnapshot(): ConfigSnapshot? = snapshots.lastOrNull()

    companion object {
        /** Maximum number of snapshots retained in memory. */
        const val MAX_SNAPSHOTS = 10
    }
}