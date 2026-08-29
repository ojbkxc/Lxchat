package com.lxseek.chat.membership

/**
 * Feature categories grouped by capability scope.
 *
 * The categories form a coarse capability ladder that maps onto [MembershipTier]:
 * - [CORE]         — always available to every user (including Free).
 * - [EXTENDED]     — available starting from Premium.
 * - [ADVANCED]     — available starting from Pro.
 * - [ENTERPRISE]   — available starting from Pro today; reserved for the
 *                     future Enterprise tier (org/seat plans).
 *
 * Keeping the category separate from the tier lets the policy (which tier a
 * category needs) evolve without touching the tool-name registry.
 */
enum class FeatureCategory {
    CORE,
    EXTENDED,
    ADVANCED,
    ENTERPRISE,
}

/**
 * Maps tool names to membership tiers and feature categories.
 *
 * This is the single source of truth for "which feature needs which plan".
 * It encodes four design principles:
 *
 * 1. **Openness** — core capabilities ([FeatureCategory.CORE]) are free; the
 *    gate never blocks basic operation of the agent.
 * 2. **Practical privilege** — paid tiers unlock genuinely useful advanced
 *    features (automation, multi-model, plugin marketplace) rather than
 *    artificial limits on core flows.
 * 3. **Minimal granularity** — each tool maps to exactly one category, and
 *    each category maps to exactly one tier. There are no overlapping or
 *    conditional rules, so reasoning about access is O(1) and deterministic.
 * 4. **Graceful guidance** — locked features return actionable, human-readable
 *    upgrade hints via [getUpgradeHint] instead of silent denials.
 *
 * Unknown tool names default to [FeatureCategory.CORE] (openness: when in
 * doubt, allow) so newly added tools remain usable until explicitly classified.
 */
class FeatureTierMapper {

    /** Stable tool-name → category registry. */
    private val toolToCategory: Map<String, FeatureCategory> = buildMap {
        // CORE (Free) — foundational device and system capabilities.
        listOf(
            "file_read",
            "file_write",
            "file_list",
            "shell",
            "app_launch",
            "app_info",
            "contacts",
            "calendar",
            "notifications",
        ).forEach { put(it, FeatureCategory.CORE) }

        // EXTENDED (Premium) — productivity multipliers.
        listOf(
            "screen_record",
            "usage_stats",
            "automation",
            "workflow",
            "image_generate",
            "vision_analyze",
        ).forEach { put(it, FeatureCategory.EXTENDED) }

        // ADVANCED (Pro) — power-user and integration tooling.
        listOf(
            "meta_tools",
            "skill_market",
            "plugin_install",
            "multi_model",
            "auxiliary_models",
            "quota_pool",
            "credential_vault",
            "config_management",
        ).forEach { put(it, FeatureCategory.ADVANCED) }

        // ENTERPRISE (Pro today, Enterprise tier in the future).
        listOf(
            "multi_agent",
            "tool_sandbox",
            "performance_analytics",
            "score_feedback",
        ).forEach { put(it, FeatureCategory.ENTERPRISE) }
    }

    /** Category → minimum required tier. */
    private val categoryToTier: Map<FeatureCategory, MembershipTier> = mapOf(
        FeatureCategory.CORE to MembershipTier.Free,
        FeatureCategory.EXTENDED to MembershipTier.Premium,
        FeatureCategory.ADVANCED to MembershipTier.Pro,
        FeatureCategory.ENTERPRISE to MembershipTier.Pro,
    )

    /** All registered tool names in insertion order. */
    val allTools: List<String> get() = toolToCategory.keys.toList()

    /**
     * Returns the membership tier required to use [toolName].
     *
     * Unknown tools resolve to [MembershipTier.Free] (openness default).
     */
    fun requiredTier(toolName: String): MembershipTier =
        categoryToTier.getValue(categorize(toolName))

    /** Returns the membership tier required for [category]. */
    fun requiredTier(category: FeatureCategory): MembershipTier =
        categoryToTier.getValue(category)

    /**
     * Classifies [toolName] into a [FeatureCategory].
     *
     * Unknown tool names default to [FeatureCategory.CORE] so new tools stay
     * available until they are explicitly promoted to a paid category.
     */
    fun categorize(toolName: String): FeatureCategory =
        toolToCategory[toolName] ?: FeatureCategory.CORE

    /**
     * Returns true if [toolName] is available for [userTier].
     *
     * Availability is a simple rank comparison: a user tier at or above the
     * required tier can use the feature. This keeps the gate total and
     * transitive — a Pro user can use everything a Premium user can.
     */
    fun isAvailable(toolName: String, userTier: MembershipTier): Boolean =
        userTier.rank() >= requiredTier(toolName).rank()

    /**
     * Returns the list of feature names locked for [userTier], in registry order.
     *
     * Used by the UI to render the "locked features" panel and by the paywall
     * to highlight what the user would gain by upgrading.
     */
    fun getLockedFeatures(userTier: MembershipTier): List<String> =
        toolToCategory.keys.filter { toolName -> !isAvailable(toolName, userTier) }

    /**
     * Returns a human-readable upgrade hint for [toolName].
     *
     * The hint is actionable (names the tier to buy) rather than a bare
     * "locked" message, supporting the graceful-guidance principle.
     */
    fun getUpgradeHint(toolName: String): String {
        val tier = requiredTier(toolName)
        return when (tier) {
            MembershipTier.Free -> "Available on all plans"
            MembershipTier.Premium -> "Upgrade to Premium to unlock '$toolName'"
            MembershipTier.Pro -> "Upgrade to Pro to unlock '$toolName'"
            MembershipTier.Enterprise -> "Upgrade to Enterprise to unlock '$toolName'"
        }
    }

    /**
     * Numeric rank of a tier for comparison.
     *
     * Encoded as an extension on [MembershipTier] so the ordering lives next to
     * the enum without modifying the enum itself (which is persisted by name).
     */
    private fun MembershipTier.rank(): Int = when (this) {
        MembershipTier.Free -> 0
        MembershipTier.Premium -> 1
        MembershipTier.Pro -> 2
        MembershipTier.Enterprise -> 3
    }
}