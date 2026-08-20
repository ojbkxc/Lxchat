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

/** Policy that decides which tiers to register based on context. */
object ToolTierPolicy {
    /**
     * Look up a tool's tier from its [ToolDescriptor]. Providers that override
     * [ToolProvider.toolDescriptors] supply their own tier; the legacy path
     * (this method) only exists as fallback for providers that still return
     * [ToolProvider.definitions] without descriptors.
     *
     * When a tool name is not found the result is [ToolTier.Dangerous] — the
     * safest default.
     */
    fun tierOf(toolName: String): ToolTier {
        // Legacy fallback for providers that have not migrated to toolDescriptors.
        // New tools should declare their tier inside ToolDescriptor instead.
        return LEGACY_TIERS[toolName] ?: ToolTier.Dangerous
    }

    /** Derive tier from a [ToolProvider]'s [ToolDescriptor] list for the given tool name. */
    fun tierOf(name: String, descriptors: List<ToolDescriptor>): ToolTier =
        descriptors.firstOrNull { it.definition.function.name == name }?.tier
            ?: tierOf(name)

    /**
     * Collect a flat name → descriptor map from a list of providers, preferring the
     * first provider that handles each tool.
     */
    fun descriptorMap(
        providers: List<ToolProvider>,
        ctx: com.lxseek.chat.viewmodel.GenerationContext,
    ): Map<String, ToolDescriptor> {
        val map = LinkedHashMap<String, ToolDescriptor>()
        for (provider in providers) {
            for (desc in provider.toolDescriptors(ctx)) {
                map.putIfAbsent(desc.definition.function.name, desc)
            }
        }
        return map
    }

    fun allowedTiers(ctx: com.lxseek.chat.viewmodel.GenerationContext): Set<ToolTier> =
        when (ctx.toolTier) {
            "core" -> setOf(ToolTier.Core)
            "extended" -> setOf(ToolTier.Core, ToolTier.Extended)
            "all" -> setOf(ToolTier.Core, ToolTier.Extended, ToolTier.Dangerous)
            else -> when (ctx.agentMode) {
                AgentMode.Plan -> setOf(ToolTier.Core, ToolTier.Extended)
                AgentMode.Agent -> setOf(ToolTier.Core, ToolTier.Extended, ToolTier.Dangerous)
                AgentMode.Auto -> setOf(ToolTier.Core, ToolTier.Extended, ToolTier.Dangerous)
            }
        }

    // ── Legacy lookup kept for providers that haven't migrated to ToolDescriptor ──
    // Once every provider overrides toolDescriptors() these entries can be deleted.
    private val LEGACY_TIERS: Map<String, ToolTier> = mapOf(
        // Core
        "list_shells" to ToolTier.Core,
        "execute_shell_command" to ToolTier.Core,
        "file_read" to ToolTier.Core,
        "file_glob" to ToolTier.Core,
        "file_grep" to ToolTier.Core,
        "memory_list" to ToolTier.Core,
        "memory_read" to ToolTier.Core,
        "memory_create" to ToolTier.Core,
        "memory_edit" to ToolTier.Core,
        "list_tasks" to ToolTier.Core,
        // Extended
        "list_processes" to ToolTier.Extended,
        "system_stats" to ToolTier.Extended,
        "tail_follow" to ToolTier.Extended,
        "web_search" to ToolTier.Extended,
        "rag_search" to ToolTier.Extended,
        "view_image" to ToolTier.Extended,
        "get_action_trace" to ToolTier.Extended,
        "memory_update" to ToolTier.Extended,
        "get_shell_job" to ToolTier.Extended,
        "list_shell_jobs" to ToolTier.Extended,
        "wait_for_job" to ToolTier.Extended,
        "create_plan" to ToolTier.Extended,
        "update_plan_item" to ToolTier.Extended,
        "edit_plan" to ToolTier.Extended,
        "ask_user" to ToolTier.Extended,
        "file_write" to ToolTier.Extended,
        "file_edit" to ToolTier.Extended,
    )
}