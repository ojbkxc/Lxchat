package com.lxseek.chat.tool

import com.lxseek.chat.viewmodel.GenerationContext

/** Tool tier classification for staged tool delivery to the model. */
enum class ToolTier {
    /** Always-registered core tools the model needs for basic operation. */
    Core,
    /** Useful but non-essential tools; registered when the context allows. */
    Extended,
    /** Destructive or high-impact tools; registered only in permissive modes. */
    Dangerous,
}

/** Policy that maps tool names to tiers and decides which tiers to register. */
object ToolTierPolicy {
    private val CORE_TOOLS = setOf(
        "list_shells", "execute_shell_command",
        "file_read", "file_glob", "file_grep",
        "memory_list", "memory_read", "memory_create", "memory_edit",
        "list_tasks",
    )
    private val EXTENDED_TOOLS = setOf(
        "list_processes", "system_stats", "tail_follow",
        "web_search", "rag_search", "view_image",
        "get_action_trace", "memory_update",
        "get_shell_job", "list_shell_jobs", "wait_for_job",
        "create_plan", "update_plan_item", "edit_plan",
        "ask_user",
        // file_write/file_edit are HighRisk but essential Agent-mode capabilities (code/file
        // editing). Tier classification controls *visibility*, not *safety* — RiskLevel plus
        // the providers' internal confirm gate still guard these tools. Placing them in Extended
        // ensures the "extended" tier retains full editing capability for Agent mode. Plan mode
        // (Core + Extended) still excludes them via filterByAgentMode, which filters HighRisk
        // regardless of tier, so Plan stays read-only as intended.
        "file_write", "file_edit",
    )

    fun tierOf(toolName: String): ToolTier = when {
        toolName in CORE_TOOLS -> ToolTier.Core
        toolName in EXTENDED_TOOLS -> ToolTier.Extended
        else -> ToolTier.Dangerous
    }

    fun allowedTiers(ctx: GenerationContext): Set<ToolTier> = when (ctx.toolTier) {
        "core" -> setOf(ToolTier.Core)
        "extended" -> setOf(ToolTier.Core, ToolTier.Extended)
        "all" -> setOf(ToolTier.Core, ToolTier.Extended, ToolTier.Dangerous)
        else -> when (ctx.agentMode) {
            AgentMode.Plan -> setOf(ToolTier.Core, ToolTier.Extended)
            AgentMode.Agent -> setOf(ToolTier.Core, ToolTier.Extended, ToolTier.Dangerous)
            AgentMode.Auto -> setOf(ToolTier.Core, ToolTier.Extended, ToolTier.Dangerous)
        }
    }
}