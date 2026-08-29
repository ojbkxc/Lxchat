package com.lxseek.chat.membership

/**
 * Result of a feature access check performed by [FeatureGate].
 *
 * The result is self-describing: it carries enough information for the caller
 * to either proceed (when [allowed] is true) or render a graceful upgrade
 * prompt (when false) without needing a second lookup.
 *
 * @property allowed         Whether the current user may use the feature.
 * @property requiredTier    The minimum tier the feature needs.
 * @property currentTier     The user's effective tier at check time.
 * @property upgradeHint     Human-readable text for the upgrade prompt; empty
 *                           when [allowed] is true.
 * @property lockedFeatureName The feature name that was locked, or null when
 *                           access was granted. Used for analytics/logging.
 */
data class FeatureAccessResult(
    val allowed: Boolean,
    val requiredTier: MembershipTier,
    val currentTier: MembershipTier,
    val upgradeHint: String,
    val lockedFeatureName: String?,
)

/**
 * Feature gate that checks membership permissions before tool execution.
 *
 * Bridges two collaborators:
 * - [MembershipProvider] supplies the current user state (a [StateFlow] of
 *   [MembershipStatus], read synchronously at check time).
 * - [FeatureTierMapper] supplies the policy (which tier a tool/category needs).
 *
 * The gate is intentionally synchronous and side-effect free: it reads the
 * current tier snapshot and returns a [FeatureAccessResult]. Callers in the
 * tool execution pipeline invoke [checkAccess] before dispatching to the
 * provider; UI callers invoke it to decide whether to show a feature or a
 * locked placeholder.
 *
 * Design note: the gate does not throw on denial. Returning a structured
 * result lets the caller decide the response (skip, prompt, log) and keeps
 * the gate composable with other checks (risk, approval, quota).
 */
class FeatureGate(
    private val membershipProvider: MembershipProvider,
    private val tierMapper: FeatureTierMapper,
) {

    /**
     * Checks access for a specific tool by name.
     *
     * This is the primary entry point for the tool execution pipeline. The
     * returned [FeatureAccessResult.lockedFeatureName] is the tool name when
     * access is denied, so callers can attribute the denial precisely.
     */
    fun checkAccess(toolName: String): FeatureAccessResult {
        val required = tierMapper.requiredTier(toolName)
        val current = currentTier()
        val allowed = current.satisfies(required)
        return FeatureAccessResult(
            allowed = allowed,
            requiredTier = required,
            currentTier = current,
            upgradeHint = if (allowed) "" else tierMapper.getUpgradeHint(toolName),
            lockedFeatureName = if (allowed) null else toolName,
        )
    }

    /**
     * Checks access for a [FeatureCategory].
     *
     * Used by UI sections that gate an entire category (e.g. "Advanced tools"
     * panel) rather than a single tool. The [lockedFeatureName] is the
     * category name when access is denied.
     */
    fun checkAccess(category: FeatureCategory): FeatureAccessResult {
        val required = tierMapper.requiredTier(category)
        val current = currentTier()
        val allowed = current.satisfies(required)
        return FeatureAccessResult(
            allowed = allowed,
            requiredTier = required,
            currentTier = current,
            upgradeHint = if (allowed) "" else upgradeHintFor(required),
            lockedFeatureName = if (allowed) null else category.name,
        )
    }

    /**
     * Returns the membership tier required for [toolName], or null if the tool
     * is available to all users (i.e. requires only [MembershipTier.Free]).
     *
     * This is the "do I need to gate at all?" probe: callers can short-circuit
     * the access check entirely when this returns null, avoiding a
     * [FeatureAccessResult] allocation on the hot path for core tools.
     */
    fun requireMembership(toolName: String): MembershipTier? {
        val required = tierMapper.requiredTier(toolName)
        return if (required == MembershipTier.Free) null else required
    }

    /**
     * Reads the user's effective tier from the provider's status snapshot.
     *
     * A membership whose [MembershipStatus.isActive] is false (e.g. expired or
     * revoked) is treated as [MembershipTier.Free] so the gate correctly locks
     * paid features for lapsed users and shows them an upgrade prompt.
     */
    private fun currentTier(): MembershipTier {
        val status = membershipProvider.status.value
        return if (status.isActive) status.tier else MembershipTier.Free
    }

    /** Builds a category-level upgrade hint for [tier]. */
    private fun upgradeHintFor(tier: MembershipTier): String = when (tier) {
        MembershipTier.Free -> "Available on all plans"
        MembershipTier.Premium -> "Upgrade to Premium to unlock this feature"
        MembershipTier.Pro -> "Upgrade to Pro to unlock this feature"
        MembershipTier.Enterprise -> "Upgrade to Enterprise to unlock this feature"
    }

    /**
     * True when [this] tier is at or above [required] in the privilege ladder.
     *
     * Encoded as a private extension so the ordering logic stays co-located
     * with the gate that depends on it, without polluting the enum API.
     */
    private fun MembershipTier.satisfies(required: MembershipTier): Boolean =
        rank() >= required.rank()

    private fun MembershipTier.rank(): Int = when (this) {
        MembershipTier.Free -> 0
        MembershipTier.Premium -> 1
        MembershipTier.Pro -> 2
        MembershipTier.Enterprise -> 3
    }
}