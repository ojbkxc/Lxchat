package com.lxseek.chat.membership

/**
 * A membership plan for UI display.
 *
 * This is a presentation model, not a domain entity: it carries the
 * human-facing strings (display name, price, feature bullets) needed to render
 * the settings page, paywall, and onboarding comparison. Prices are kept as
 * display strings so locale formatting and currency can be swapped without
 * touching the catalog.
 *
 * @property tier          The [MembershipTier] this plan sells.
 * @property displayName   Short label shown on the plan card.
 * @property price         Display string including currency, e.g. "¥19.9".
 * @property durationDays  Billing period in days; 0 means permanent (no expiry).
 * @property features      Bullet-point features shown under the plan name.
 * @property highlighted   Whether the UI should visually emphasize this plan
 *                         (e.g. "Most popular" badge).
 */
data class MembershipPlan(
    val tier: MembershipTier,
    val displayName: String,
    val price: String,
    val durationDays: Int,
    val features: List<String>,
    val highlighted: Boolean,
)

/**
 * Membership benefits catalog for UI presentation.
 *
 * Centralizes plan definitions so the settings page, paywall, and onboarding
 * screens all render the same source of truth. The catalog is an [object]
 * (process-wide singleton) because plan definitions are static; runtime state
 * (active tier, expiry) lives in [MembershipProvider].
 *
 * The [Enterprise] tier intentionally has no plan entry today — it is reserved
 * for future organization/seat-based sales. [getPlan] returns null for it,
 * which the UI interprets as "not purchasable yet".
 */
object MembershipBenefits {

    private val plans: List<MembershipPlan> = listOf(
        MembershipPlan(
            tier = MembershipTier.Free,
            displayName = "Free",
            price = "¥0",
            durationDays = 0,
            features = listOf(
                "Core chat & streaming",
                "BYOK: bring your own API keys",
                "Web search & file tools",
                "Manual memory & active memory",
                "Voice conversation (offline)",
                "1 IM account binding",
                "Plugin marketplace browsing",
                "Basic desktop pet (DADA)",
            ),
            highlighted = false,
        ),
        MembershipPlan(
            tier = MembershipTier.Premium,
            displayName = "Premium",
            price = "¥0.99",
            durationDays = 30,
            features = listOf(
                "Everything in Free, plus:",
                "Multi-model & smart routing",
                "Auto context compression",
                "Image generation & vision",
                "Full automation (cron/workflow/trigger)",
                "Unlimited IM & proactive messages",
                "Auto memory evolution & RAG search",
                "All desktop pets + custom image",
                "Cloud Whisper & provider TTS",
                "Plugin install & marketplace",
                "ADB Shell & SMS command",
                "Runtime engines (Node/Python/ffmpeg)",
                "Auto backup & DNS encryption",
            ),
            highlighted = true,
        ),
    )

    /** Returns all membership plans in tier order (Free → Premium). */
    fun getPlans(): List<MembershipPlan> = plans

    /**
     * Returns the plan for [tier], or null if no plan is defined.
     *
     * Returns null for [MembershipTier.Pro] and [MembershipTier.Enterprise] today;
     * callers should handle the null case by hiding the purchase CTA for that tier.
     */
    fun getPlan(tier: MembershipTier): MembershipPlan? =
        plans.firstOrNull { it.tier == tier }

    /**
     * Generates a human-readable comparison table of all plans.
     *
     * Used by the paywall and the "compare plans" debug screen. The format is
     * plain text so it can be embedded in a Compose [Text] or shared via
     * intent without HTML rendering.
     */
    fun comparePlans(): String = buildString {
        appendLine("Membership Plans Comparison")
        appendLine("=".repeat(SEPARATOR_LENGTH))
        plans.forEach { plan ->
            appendLine()
            appendLine("${plan.displayName} — ${plan.price}${durationSuffix(plan)}")
            if (plan.highlighted) appendLine("  (Most popular)")
            plan.features.forEach { feature -> appendLine("  • $feature") }
        }
    }

    /** Returns the billing-period suffix for [plan], or empty for permanent plans. */
    private fun durationSuffix(plan: MembershipPlan): String =
        if (plan.durationDays > 0) "/${plan.durationDays}d" else "/permanent"

    private const val SEPARATOR_LENGTH = 40
}