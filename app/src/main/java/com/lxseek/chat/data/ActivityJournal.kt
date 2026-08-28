package com.lxseek.chat.data

import android.content.Context
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 活动日志（journey 的数据底座）—— 对应 Hermes 式「Agent 是否在变好」的成长记录。
 *
 * 把「记忆/技能/Cron 的变更、技能被调用、输出被校验」等事件追加进一个 JSONL 文件，
 * 供 [com.lxseek.chat.tool.JourneyToolProvider] 查询展示：创建过哪些技能、更新过哪些记忆、
 * 有哪些定时任务在跑、最近都沉淀了什么。全部 @Synchronized，并发安全。
 */
class ActivityJournal(context: Context) {

    /** 事件类别。`kind` 用于 journey 分组统计。 */
    object Kind {
        const val MEMORY = "memory"
        const val SKILL = "skill"
        const val TASK = "task"
        const val VERIFY = "verify"
    }

    @Serializable
    data class ActivityEntry(
        val ts: Long,
        val kind: String,
        val action: String,
        val detail: String = "",
        val ref: String = "",
    )

    private val journalFile: File =
        File(context.filesDir, "activity_journal.jsonl")

    private val json = Json { ignoreUnknownKeys = true }

    /** 追加一条活动记录（后台线程写盘）。 */
    suspend fun record(kind: String, action: String, detail: String = "", ref: String = "") =
        withContext(Dispatchers.IO) {
            runCatching {
                val entry = ActivityEntry(
                    ts = System.currentTimeMillis(),
                    kind = kind,
                    action = action,
                    detail = detail.take(500),
                    ref = ref.take(200),
                )
                journalFile.appendText(json.encodeToString(entry) + "\n")
            }.onFailure {
                DebugLog.e("ActivityJournal", "Failed to record $kind/$action", it)
            }
        }

    /** 最近 [limit] 条活动（按时间倒序）。 */
    fun recent(limit: Int = 50): List<ActivityEntry> =
        if (!journalFile.exists()) emptyList()
        else journalFile.readLines()
            .mapNotNull { line ->
                runCatching { json.decodeFromString<ActivityEntry>(line) }.getOrNull()
            }
            .sortedByDescending { it.ts }
            .take(limit)

    /** 全量（正序），供 journey 聚合统计。 */
    fun all(): List<ActivityEntry> =
        if (!journalFile.exists()) emptyList()
        else journalFile.readLines()
            .mapNotNull { line ->
                runCatching { json.decodeFromString<ActivityEntry>(line) }.getOrNull()
            }

    /**
     * 生成 journey 摘要：按 kind 分组统计数量，并给出最近的动作时间线。
     * 纯内存计算，供 [com.lxseek.chat.tool.JourneyToolProvider.journey] 返回 JSON。
     */
    fun journeySummary(): Map<String, Any> {
        val entries = all()
        val byKind = entries.groupBy { it.kind }
        val counts = mutableMapOf<String, Int>()
        byKind.forEach { (kind, list) -> counts[kind] = list.size }
        val latestByKind = byKind.mapValues { (_, list) ->
            list.maxByOrNull { it.ts }?.ts ?: 0L
        }
        val recent = entries.sortedByDescending { it.ts }.take(30).map {
            mapOf(
                "ts" to it.ts,
                "kind" to it.kind,
                "action" to it.action,
                "detail" to it.detail,
                "ref" to it.ref,
            )
        }
        return mapOf(
            "counts" to counts,
            "latest_by_kind" to latestByKind,
            "recent" to recent,
        )
    }
}
