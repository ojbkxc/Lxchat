package com.lxseek.chat.tool

/**
 * Deterministic importance scorer for memory files (rules-only, no LLM call).
 *
 * Mirrors the reference python bot's memory decay heuristic ("score = importance - recency
 * penalty") without requiring new persistence: it scores on the memory entry's description and
 * content, so older/rarely-updated low-signal entries rank below structured, actionable facts.
 * This powers an optional descending-sort of the memory file list so the agent reads the most
 * relevant files first.
 *
 * The scoring model favors files that look concrete and self-contained (a specific fact or short
 * instruction) and penalizes large, vaguely-described dumps (likely a long transcript or
 * unscoped note that needs summarization).
 */
object MemoryScorer {

    /**
     * Score in [minScore, maxScore]. Higher = more likely to matter to the agent.
     * [content] may be the file body; both arguments are used because list-time scoring often
     * has only the description.
     */
    fun score(name: String, description: String, content: String = ""): Int {
        var s = 10
        val desc = description.trim()
        val body = content.trim()

        // Concrete, actionable descriptions are high value.
        if (desc.isNotEmpty()) s += 12
        val concise = desc.length in 8..80
        if (concise) s += 6
        if (desc.length > 240) s -= 6

        // Favor short, bounded notes over huge raw dumps.
        val bodyLen = body.length
        when {
            bodyLen == 0 -> s -= 2 // metadata-only entry
            bodyLen < 400 -> s += 8 // distilled fact/instruction
            bodyLen < 1500 -> s += 3
            else -> s -= 5 // long transcript; needs summarization
        }

        // Keyword lift for durable, structured memory.
        val lower = "$name $desc".lowercase()
        val strongHints = listOf("user", "prefer", "like", "dislike", "schedule", "reminder",
            "contact", "account", "address", "project", "goal", "todo", "rule", "constraint")
        s += strongHints.count { it in lower } * 2

        // Near-duplicate / vague name penalty.
        if (name.matches(Regex("""(memo|note|untitled|chat|conversation)([-_\d]*)$"""))) s -= 3

        return s.coerceIn(minScore, maxScore)
    }

    /** Sort a list of memory entries by descending score (stable). */
    fun rankOrdered(entries: List<RankableMemory>): List<RankableMemory> =
        entries.sortedWith(compareByDescending<RankableMemory> { score(it.name, it.description, it.content) }
            .thenBy { it.name })

    /**
     * Minimal structural view of one memory entry used by [rankOrdered]. Kept as an interface so
     * callers with their own model (e.g. MemoryFileInfo + body) don't have to adapt types.
     */
    interface RankableMemory {
        val name: String
        val description: String
        val content: String
    }

    /** Convenience adapter over name/description/content strings. */
    data class Entry(
        override val name: String,
        override val description: String,
        override val content: String = "",
    ) : RankableMemory

    private const val minScore = 0
    private const val maxScore = 60
}