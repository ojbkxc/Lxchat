package com.lxseek.chat.data.local

import androidx.room.Query

/** Read-only aggregate projections backing the usage statistics dashboard. */
data class UsageStatistics(
    val conversationCount: Int,
    val messageCount: Int,
    val userMessageCount: Int,
    val modelMessageCount: Int,
    val inputTokenCount: Long,
    val outputTokenCount: Long,
    val reasoningTokenCount: Long,
    val runCount: Int,
    val taskCount: Int,
)

data class ModelUsageRow(
    val modelName: String?,
    val messageCount: Int,
    val outputTokenCount: Long,
)

data class DailyUsageRow(
    val dayStart: Long,
    val messageCount: Int,
    val outputTokenCount: Long,
)

/**
 * Statistics queries inherited by [ChatDao] (mirrors the [ChatAutomationDao] pattern). These are
 * read-only aggregate projections over existing tables, so they never require a schema migration.
 */
interface ChatStatisticsDao {
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM conversations WHERE taskId IS NULL) AS conversationCount,
            (SELECT COUNT(*) FROM messages m INNER JOIN conversations c ON m.conversationId = c.id
                WHERE c.taskId IS NULL AND m.participant IN ('USER','MODEL')) AS messageCount,
            (SELECT COUNT(*) FROM messages m INNER JOIN conversations c ON m.conversationId = c.id
                WHERE c.taskId IS NULL AND m.participant = 'USER') AS userMessageCount,
            (SELECT COUNT(*) FROM messages m INNER JOIN conversations c ON m.conversationId = c.id
                WHERE c.taskId IS NULL AND m.participant = 'MODEL') AS modelMessageCount,
            (SELECT COALESCE(SUM(m.inputTokenCount), 0) FROM messages m INNER JOIN conversations c ON m.conversationId = c.id
                WHERE c.taskId IS NULL AND m.participant IN ('USER','MODEL')) AS inputTokenCount,
            (SELECT COALESCE(SUM(m.outputTokenCount), 0) FROM messages m INNER JOIN conversations c ON m.conversationId = c.id
                WHERE c.taskId IS NULL AND m.participant IN ('USER','MODEL')) AS outputTokenCount,
            (SELECT COALESCE(SUM(m.reasoningTokenCount), 0) FROM messages m INNER JOIN conversations c ON m.conversationId = c.id
                WHERE c.taskId IS NULL AND m.participant IN ('USER','MODEL')) AS reasoningTokenCount,
            (SELECT COUNT(*) FROM runs r INNER JOIN conversations c ON r.conversationId = c.id
                WHERE c.taskId IS NULL) AS runCount,
            (SELECT COUNT(*) FROM tasks) AS taskCount
        """
    )
    suspend fun getUsageStatistics(): UsageStatistics

    @Query(
        """
        SELECT m.modelName AS modelName,
               COUNT(*) AS messageCount,
               COALESCE(SUM(m.outputTokenCount), 0) AS outputTokenCount
        FROM messages m
        INNER JOIN conversations c ON m.conversationId = c.id
        WHERE c.taskId IS NULL
          AND m.participant = 'MODEL'
          AND m.modelName IS NOT NULL
          AND m.modelName != ''
        GROUP BY m.modelName
        ORDER BY outputTokenCount DESC
        """
    )
    suspend fun getUsageByModel(): List<ModelUsageRow>

    @Query(
        """
        SELECT ((m.timestamp / 86400000) * 86400000) AS dayStart,
               COUNT(*) AS messageCount,
               COALESCE(SUM(m.outputTokenCount), 0) AS outputTokenCount
        FROM messages m
        INNER JOIN conversations c ON m.conversationId = c.id
        WHERE c.taskId IS NULL
          AND m.participant IN ('USER', 'MODEL')
          AND m.timestamp >= :sinceMs
        GROUP BY ((m.timestamp / 86400000) * 86400000)
        ORDER BY dayStart ASC
        """
    )
    suspend fun getUsageByDay(sinceMs: Long): List<DailyUsageRow>
}