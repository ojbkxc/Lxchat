package com.lxseek.chat.membership

import com.lxseek.chat.util.DebugLog

/**
 * Feature categories grouped by capability scope（二元制会员体系）。
 *
 * 类别只映射到两档：**免费可用**（[CORE]）与**付费可用**（[PAID]）。
 * 历史上 EXTENDED / ADVANCED / ENTERPRISE 分别对应 Premium / Pro / Enterprise
 * 三级阶梯，二元制简化后全部合并为 [PAID] 一档（旧注册表中的付费工具
 * 一并并入 [PAID]，对用户无可见变化——之前它们就要求 Premium 及以上）。
 */
enum class FeatureCategory {
    /** 免费可用：基础能力，所有用户（含免费账户）始终可用。 */
    CORE,

    /** 付费可用：扩展生产力 / 开发者 / 集成 / 企业向工具，需要付费账户。 */
    PAID,
}

/**
 * Maps tool names to membership tiers and feature categories.
 *
 * This is the single source of truth for "which feature needs payment"
 * （二元制：免费可用 / 付费可用）。
 *
 * It encodes four design principles:
 *
 * 1. **Openness** — core capabilities ([FeatureCategory.CORE]) are free; the
 *    gate never blocks basic operation of the agent. Since Lxchat is a BYOK
 *    terminal (users bring their own API keys), the gate never charges for
 *    "API cost" — only for feature value (automation, multi-model, etc.).
 * 2. **Practical privilege** — payment unlocks genuinely useful advanced
 *    features (automation, multi-model, plugin marketplace) rather than
 *    artificial limits on core flows.
 * 3. **Minimal granularity** — each tool maps to exactly one category, and
 *    each category maps to exactly one tier (Free or Premium). There are no
 *    overlapping or conditional rules, so reasoning about access is O(1)
 *    and deterministic.
 * 4. **Graceful guidance** — locked features return actionable, human-readable
 *    upgrade hints via [getUpgradeHint] instead of silent denials.
 *
 * Unknown tool names default to [FeatureCategory.CORE]（保持现状默认：未知即免费，
 * 宁可放行不可误锁），并打一条 DEBUG 日志便于把新工具显式归类——
 * 新增工具在归类前保持可用，直到被明确提升到付费类别。
 */
class FeatureTierMapper {

    private companion object {
        const val TAG = "FeatureTierMapper"
    }

    /** Stable tool-name → category registry. */
    private val toolToCategory: Map<String, FeatureCategory> = buildMap {
        // 免费可用（CORE）— 基础能力，始终可用。
        listOf(
            // Device & system
            "file_read", "file_write", "file_list",
            "shell", "app_launch", "app_info",
            "contacts", "calendar", "notifications",
            // Web
            "web_search", "web_fetch",
            // Memory (manual)
            "memory_read", "memory_manual", "active_memory",
            // Voice (offline)
            "voice_conversation", "offline_asr", "offline_tts",
            // Basic pet
            "basic_pet",
            // IM (1 binding)
            "im_bind_one",
            // Plugin browse only
            "plugin_browse",
            // Data rights
            "data_export", "data_import",
            // Network basic
            "proxy",
            // Misc
            "grok_login", "slash_commands", "system_clean",
        ).forEach { put(it, FeatureCategory.CORE) }

        // 付费可用（PAID）— 生产力倍增、自动进化、开发者与企业向工具。
        // （原 EXTENDED / ADVANCED / ENTERPRISE 三组在二元制下合并为一组。）
        listOf(
            // From original EXTENDED
            "screen_record", "usage_stats",
            "automation", "workflow",
            "image_generate", "vision_analyze",
            // Moved from ADVANCED to EXTENDED
            "multi_model", "auxiliary_models",
            // New: advanced generation
            "thinking_budget", "context_compact",
            // New: advanced tools
            "local_sandbox", "device_control",
            // New: IM multi-binding
            "im_multi_bind", "im_proactive", "im_multi_channel",
            // New: cloud voice
            "cloud_whisper", "provider_tts",
            // New: pet premium
            "pet_all_characters", "pet_custom",
            // New: auto-evolution
            "auto_memory", "rag_search", "auto_cache",
            // New: data & network
            "auto_backup", "dns_encrypt",
            // From original ADVANCED — power-user, developer & integration tooling.
            "meta_tools", "quota_pool", "credential_vault", "config_management",
            // Plugin install (browse is free, install is paid)
            "skill_market", "plugin_install",
            // New: advanced routing
            "smart_routing",
            // New: developer tools
            "adb_shell", "runtime_engine",
            // New: SMS command
            "sms_command",
            // New: IM unlimited & management
            "im_unlimited", "im_management",
            // New: enterprise integration
            "remote_device", "ai_office",
            // From original ENTERPRISE
            "multi_agent",
            "tool_sandbox",
            "performance_analytics",
            "score_feedback",
        ).forEach { put(it, FeatureCategory.PAID) }
    }

    /** Category → minimum required tier. 二元制：只有 Free / Premium（付费）两档。 */
    private val categoryToTier: Map<FeatureCategory, MembershipTier> = mapOf(
        FeatureCategory.CORE to MembershipTier.Free,
        FeatureCategory.PAID to MembershipTier.Premium,
    )

    /** All registered tool names in insertion order. */
    val allTools: List<String> get() = toolToCategory.keys.toList()

    /**
     * Returns the membership tier required to use [toolName].
     *
     * Unknown tools resolve to [MembershipTier.Free]（保持现状默认：开放优先）。
     */
    fun requiredTier(toolName: String): MembershipTier =
        categoryToTier.getValue(categorize(toolName))

    /** Returns the membership tier required for [category]. */
    fun requiredTier(category: FeatureCategory): MembershipTier =
        categoryToTier.getValue(category)

    /**
     * Classifies [toolName] into a [FeatureCategory].
     *
     * Unknown tool names default to [FeatureCategory.CORE]（免费）并打 DEBUG 日志
     * （保持现状默认 + 可观测），新工具在被显式归类前保持可用。
     */
    fun categorize(toolName: String): FeatureCategory =
        toolToCategory[toolName] ?: run {
            DebugLog.d(TAG, "unclassified tool defaults to CORE (free): $toolName")
            FeatureCategory.CORE
        }

    /**
     * Returns true if [toolName] is available for [userTier].
     *
     * 二元制：付费档（Premium）可用全部工具；免费档（Free）仅可用免费类别。
     */
    fun isAvailable(toolName: String, userTier: MembershipTier): Boolean =
        requiredTier(toolName).let { required ->
            if (required == MembershipTier.Free) true else userTier != MembershipTier.Free
        }

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
     * The hint is actionable (说明需升级为付费账户) rather than a bare
     * "locked" message, supporting the graceful-guidance principle.
     */
    fun getUpgradeHint(toolName: String): String {
        val tier = requiredTier(toolName)
        return if (tier == MembershipTier.Free) {
            "Available on all plans"
        } else {
            "Upgrade to a paid membership to unlock '$toolName'"
        }
    }
}
