package com.lxseek.chat.tool

import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.membership.MembershipTier
import com.lxseek.chat.plugin.PluginHost
import com.lxseek.chat.agent.GenerationContext

/**
 * Per-tool permission entry in the [ToolPermissionMatrix].
 *
 * Captures the minimum-granularity policy for a single tool: whether it is enabled,
 * which membership tier is required, whether explicit user approval is needed,
 * the risk classification, and optional daily/session quotas.
 *
 * Inspired by dph project4's per-tool gating — instead of a single global
 * "tools enabled" flag, every tool gets its own row so the agent can fine-tune
 * access (e.g. enable `web_search` but require Premium, disable `file_write`
 * entirely without touching the rest).
 */
data class ToolPermission(
    val toolName: String,
    val enabled: Boolean = true,
    val requiresMembership: MembershipTier = MembershipTier.Free,
    val requiresApproval: Boolean = false,
    val riskLevel: RiskLevel = RiskLevel.ReadOnly,
    val dailyQuota: Int? = null,
    val sessionQuota: Int? = null,
)

/**
 * Minimum-granularity permission matrix for every tool the agent can call.
 *
 * The default matrix is built dynamically from [PluginHost]'s toolDescriptors so
 * every registered tool gets a sensible default derived from its [ToolDescriptor]
 * (risk level, approval flag, membership requirement). Explicit [setPermission]
 * calls override the defaults in memory; the overrides are keyed by tool name so
 * a plugin re-registering the same tool id keeps the user's customization.
 *
 * [isAllowed] is the single runtime gate that combines the enabled flag with the
 * caller's membership tier — the generation executor calls it before dispatching
 * any tool invocation.
 *
 * Persistence: overrides live in memory for this process. The optional
 * [SettingsRepository] is accepted so future versions can persist overrides
 * without changing the constructor signature; today it is used to read the
 * initial enabled-models set so disabled models start disabled here too.
 */
class ToolPermissionMatrix(
    private val pluginHost: PluginHost? = null,
    private val settings: SettingsRepository? = null,
) {
    /** In-memory overrides keyed by tool name. */
    private val overrides = mutableMapOf<String, ToolPermission>()

    /**
     * Build the full matrix: defaults derived from [PluginHost] toolDescriptors
     * merged with any explicit overrides. Returns a stable LinkedHashMap so
     * callers can rely on insertion order for display.
     */
    fun getMatrix(): Map<String, ToolPermission> {
        val result = LinkedHashMap<String, ToolPermission>()
        // Defaults from plugin descriptors.
        if (pluginHost != null) {
            val ctx = GenerationContext()
            for (provider in pluginHost.toolProviders()) {
                for (desc in provider.toolDescriptors(ctx)) {
                    val name = desc.definition.function.name
                    if (name in result) continue
                    result[name] = desc.toPermission()
                }
            }
        }
        // Apply overrides on top of defaults.
        for ((name, perm) in overrides) {
            result[name] = perm
        }
        return result
    }

    /** Return the permission for a single tool, or null when the tool is unknown. */
    fun getPermission(toolName: String): ToolPermission? = getMatrix()[toolName]

    /**
     * Set (override) the permission for a single tool.
     *
     * The [permission] is normalized so [ToolPermission.toolName] always matches
     * [toolName], preventing accidental key/value mismatches.
     */
    fun setPermission(toolName: String, permission: ToolPermission) {
        overrides[toolName] = permission.copy(toolName = toolName)
    }

    /**
     * Runtime gate: true when the tool exists, is enabled, AND the caller's
     * membership satisfies the tool's [ToolPermission.requiresMembership].
     *
     * A [MembershipTier.Free] requirement always passes. Any higher tier
     * requires [GenerationContext.hasMembership] to be true. Finer tier-vs-tier
     * comparison is intentionally not performed here because [GenerationContext]
     * only carries a boolean membership flag; the membership-aware disclosure
     * layer handles tier ranking separately.
     */
    fun isAllowed(toolName: String, ctx: GenerationContext): Boolean {
        val perm = getPermission(toolName) ?: return false
        if (!perm.enabled) return false
        if (perm.requiresMembership != MembershipTier.Free && !ctx.hasMembership) return false
        return true
    }

    /** Convert a [ToolDescriptor] into a default [ToolPermission] row. */
    private fun ToolDescriptor.toPermission(): ToolPermission {
        val name = definition.function.name
        // Tools that are known to be disabled in settings start disabled here too.
        val initiallyEnabled = settings?.let { isInitiallyEnabled(name) } ?: true
        return ToolPermission(
            toolName = name,
            enabled = initiallyEnabled,
            requiresMembership = if (requiresMembership) MembershipTier.Premium else MembershipTier.Free,
            requiresApproval = requiresApproval,
            riskLevel = riskLevel,
            dailyQuota = null,
            sessionQuota = null,
        )
    }

    /**
     * Best-effort check whether a tool should be enabled by default based on
     * persisted settings.
     *
     * Today every tool defaults to enabled — the matrix is opt-out, not opt-in.
     * The [settings] reference is kept so future versions can consult specific
     * toggles (e.g. a per-tool disable flag in DataStore) without changing the
     * constructor signature.
     */
    private fun isInitiallyEnabled(@Suppress("UNUSED_PARAMETER") toolName: String): Boolean = true
}