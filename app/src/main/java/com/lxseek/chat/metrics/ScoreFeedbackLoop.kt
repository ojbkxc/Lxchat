package com.lxseek.chat.metrics

/**
 * A single tool execution score record.
 *
 * @param toolName The tool that was scored.
 * @param score Quality score 0–100 (higher is better).
 * @param timestamp When the score was recorded (epoch ms).
 * @param context Brief context of the execution (e.g. "image generation, 1024x1024").
 * @param boostersUsed List of booster names applied during this execution.
 */
data class ToolScore(
    val toolName: String,
    val score: Int,
    val timestamp: Long,
    val context: String,
    val boostersUsed: List<String>,
)

/**
 * Aggregated performance summary for a single tool.
 *
 * @param toolName The tool name.
 * @param avgScore Average score across all recorded executions.
 * @param totalUses Number of recorded executions.
 * @param bestScore Highest score ever recorded.
 * @param worstScore Lowest score ever recorded.
 * @param recommendedBoosters Boosters that historically produced the best scores.
 */
data class ToolPerformanceSummary(
    val toolName: String,
    val avgScore: Double,
    val totalUses: Int,
    val bestScore: Int,
    val worstScore: Int,
    val recommendedBoosters: List<String>,
)

/**
 * Score-feedback loop for tool performance optimization.
 *
 * Inspired by dsh-video-studio's score-feedback pattern: every reviewed
 * tool execution writes its score + booster combo back to the scorebook.
 * The optimizer then picks boosters by real historical performance,
 * creating a self-improving system.
 *
 * Thread-safe via synchronized access to the internal map.
 */
class ScoreFeedbackLoop {

    /** Tool name -> list of scores (max 100 per tool, oldest evicted). */
    private val scoreBook = mutableMapOf<String, MutableList<ToolScore>>()

    /**
     * Records a tool execution score.
     * If the tool already has [MAX_SCORES_PER_TOOL] entries, the oldest is removed.
     */
    fun recordScore(
        toolName: String,
        score: Int,
        context: String,
        boostersUsed: List<String>,
    ) {
        val clamped = score.coerceIn(0, 100)
        val entry = ToolScore(
            toolName = toolName,
            score = clamped,
            timestamp = System.currentTimeMillis(),
            context = context,
            boostersUsed = boostersUsed,
        )
        synchronized(scoreBook) {
            val list = scoreBook.getOrPut(toolName) { mutableListOf() }
            list.add(entry)
            if (list.size > MAX_SCORES_PER_TOOL) {
                list.removeAt(0)
            }
        }
    }

    /** Returns the performance summary for [toolName], or null if no scores recorded. */
    fun getPerformance(toolName: String): ToolPerformanceSummary? {
        synchronized(scoreBook) {
            val scores = scoreBook[toolName] ?: return null
            if (scores.isEmpty()) return null
            return summarize(toolName, scores)
        }
    }

    /** Returns performance summaries for all tools that have at least one score. */
    fun getAllPerformance(): List<ToolPerformanceSummary> {
        synchronized(scoreBook) {
            return scoreBook.mapNotNull { (name, scores) ->
                if (scores.isEmpty()) null else summarize(name, scores)
            }
        }
    }

    /**
     * Returns the boosters that historically produced the best scores
     * for [toolName] (top 3 by average score when used).
     */
    fun getRecommendedBoosters(toolName: String): List<String> {
        synchronized(scoreBook) {
            val scores = scoreBook[toolName] ?: return emptyList()
            if (scores.isEmpty()) return emptyList()
            // Group by booster, compute average score per booster
            return scores
                .flatMap { s -> s.boostersUsed.map { b -> b to s.score } }
                .groupBy { it.first }
                .map { (booster, pairs) ->
                    booster to pairs.map { it.second }.average()
                }
                .sortedByDescending { it.second }
                .take(3)
                .map { it.first }
        }
    }

    /** Returns the top [limit] performing tools by average score. */
    fun getTopPerformingTools(limit: Int = 10): List<ToolPerformanceSummary> =
        getAllPerformance()
            .sortedByDescending { it.avgScore }
            .take(limit)

    /** Returns tools whose average score is below [threshold]. */
    fun getUnderperformingTools(threshold: Int = 50): List<ToolPerformanceSummary> =
        getAllPerformance()
            .filter { it.avgScore < threshold }
            .sortedBy { it.avgScore }

    // ── Internal helpers ──────────────────────────────────────

    private fun summarize(toolName: String, scores: List<ToolScore>): ToolPerformanceSummary {
        val avg = scores.map { it.score }.average()
        val best = scores.maxOf { it.score }
        val worst = scores.minOf { it.score }
        // Recommend boosters from the top-scoring execution
        val bestEntry = scores.maxByOrNull { it.score }
        val recommended = bestEntry?.boostersUsed ?: emptyList()
        return ToolPerformanceSummary(
            toolName = toolName,
            avgScore = avg,
            totalUses = scores.size,
            bestScore = best,
            worstScore = worst,
            recommendedBoosters = recommended,
        )
    }

    companion object {
        private const val MAX_SCORES_PER_TOOL = 100
    }
}