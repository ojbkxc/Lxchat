package com.lxseek.chat.tool

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** One recorded tool invocation entry in the action trace bus. */
data class ActionTraceEntry(
    val toolName: String,
    val argumentsSummary: String,
    val resultSummary: String,
    val isError: Boolean,
    val server: String?,
    val conversationId: String?,
    val runId: String?,
    val timestampMs: Long,
    val durationMs: Long,
)

/** Process-scoped ring buffer recording the most recent tool invocations. */
object ActionTraceBus {
    private const val CAPACITY = 256
    private val mutex = Mutex()
    private val buffer = ArrayDeque<ActionTraceEntry>(CAPACITY)

    suspend fun record(entry: ActionTraceEntry) {
        mutex.withLock {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
        }
    }

    suspend fun snapshot(limit: Int = 50): List<ActionTraceEntry> {
        val safeLimit = limit.coerceIn(1, CAPACITY)
        return mutex.withLock {
            buffer.takeLast(safeLimit).reversed()
        }
    }

    suspend fun clear() {
        mutex.withLock { buffer.clear() }
    }

    /** JSON serialization for the get_action_trace tool result. */
    suspend fun toJson(limit: Int = 50): String {
        val entries = snapshot(limit)
        return buildJsonObject {
            put("type", "action_trace")
            put("count", entries.size)
            putJsonArray("entries") {
                entries.forEach { e ->
                    add(buildJsonObject {
                        put("tool", e.toolName)
                        put("arguments", e.argumentsSummary)
                        put("result", e.resultSummary)
                        put("is_error", e.isError)
                        e.server?.let { put("server", it) }
                        e.conversationId?.let { put("conversation_id", it) }
                        e.runId?.let { put("run_id", it) }
                        put("timestamp_ms", e.timestampMs)
                        put("duration_ms", e.durationMs)
                    })
                }
            }
        }.toString()
    }
}