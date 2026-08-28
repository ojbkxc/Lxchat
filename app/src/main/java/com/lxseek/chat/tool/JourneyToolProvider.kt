package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.data.ActivityJournal
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 成长旅程（journey）工具 —— 对应 Hermes 式「Agent 是否在变好」的自我观察能力。
 *
 * 只读工具，把 [ActivityJournal] 里沉淀下来的活动记录（创建了哪些技能、更新过哪些记忆、
 * 跑过哪些定时任务、校验过哪些输出）聚合为 JSON 返回给模型。模型据此可以：
 * - 向用户汇报「这周沉淀了 3 个新技能 / 精简了 2 条过期记忆」；
 * - 决定是否需要进一步 curate（见 SkillLearnToolProvider 的 curate_skills）；
 * - 感知自己的成长轨迹，而不是每次会话都从零开始。
 *
 * 纯读操作：RiskLevel.ReadOnly、ToolTier.Core，无需审批。
 */
class JourneyToolProvider(
    private val journal: ActivityJournal,
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = JOURNEY,
                description = "Show the agent's growth journey: counts and recent activity for memory updates, skill creation, background tasks, and output verifications.",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        ),
    )

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            definition = definitions(ctx).single(),
            riskLevel = RiskLevel.ReadOnly,
            tier = ToolTier.Core,
            requiresApproval = false,
            summary = "Show growth journey (memory/skills/tasks/verifies).",
        ),
    )

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        if (name != JOURNEY) "Error: Unknown tool: $name"
        else journeyJson()

    override fun handles(name: String): Boolean = name == JOURNEY

    private fun journeyJson(): String {
        val summary = journal.journeySummary()
        val counts = summary["counts"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val latest = summary["latest_by_kind"] as? Map<*, *> ?: emptyMap<Any, Any>()
        @Suppress("UNCHECKED_CAST")
        val recent = (summary["recent"] as? List<Map<String, Any>>) ?: emptyList()

        return buildJsonObject {
            put("type", JOURNEY)
            putJsonArray("activity_counts") {
                counts.entries.sortedByDescending { (it.value as? Int) ?: 0 }.forEach { (kind, count) ->
                    add(
                        buildJsonObject {
                            put("kind", kind.toString())
                            put("count", (count as? Int) ?: 0)
                            (latest[kind] as? Long)?.let { put("latest_ts", it) }
                        },
                    )
                }
            }
            putJsonArray("recent") {
                recent.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("ts", (entry["ts"] as? Long) ?: 0L)
                            put("kind", entry["kind"]?.toString() ?: "")
                            put("action", entry["action"]?.toString() ?: "")
                            put("detail", entry["detail"]?.toString() ?: "")
                            put("ref", entry["ref"]?.toString() ?: "")
                        },
                    )
                }
            }
            put("hint", "Use curate_skills to prune unused skills and cleanup_memories to trim stale memories.")
        }.toString()
    }

    private companion object {
        const val JOURNEY = "journey"
    }
}
