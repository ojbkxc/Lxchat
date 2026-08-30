package com.lxseek.chat.membership

import com.lxseek.chat.data.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Membership tier levels.
 *
 * Tiers are ordered by privilege: [Free] ⊂ [Premium] ⊂ [Pro] ⊂ [Enterprise].
 * The [Enterprise] tier is reserved for future organization/seat-based plans;
 * today no feature category requires it, but it is a first-class enum value so
 * persistence, parsing, and UI comparisons stay exhaustive and forward-compatible.
 */
enum class MembershipTier {
    Free,
    Premium,
    Pro,
    Enterprise,
    ;

    companion object {
        /** Parse a tier name defensively; unknown values fall back to [Free]. */
        fun parse(name: String?): MembershipTier =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Free
    }
}

/** Membership status snapshot. */
data class MembershipStatus(
    val tier: MembershipTier = MembershipTier.Free,
    val expiryTimestamp: Long? = null,
    val source: String = "", // "yipay" or "redemption_code"
    val isActive: Boolean = false,
) {
    /**
     * 永久会员：已激活且无过期时间，或过期时间在 [LIFETIME_THRESHOLD_DAYS] 天以后。
     *
     * 服务端永久套餐签发 36500 天（约 100 年），激活码等途径也可能签发
     * 2099 年哨兵日期等不同长度的"永久"。此处用 10 年阈值统一覆盖各种永久语义，
     * 同时不会误伤叠加购买的年度套餐（连续买 10 年年度才会到阈值）。
     */
    val isLifetime: Boolean
        get() = isActive && (expiryTimestamp == null ||
            expiryTimestamp >= System.currentTimeMillis() + LIFETIME_THRESHOLD_DAYS * MILLIS_PER_DAY)

    private companion object {
        const val LIFETIME_THRESHOLD_DAYS = 3650L
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}

/** Interface for membership status sources. */
interface MembershipProvider {
    val status: StateFlow<MembershipStatus>
    fun hasMembership(): Boolean
    suspend fun refresh()
}

/**
 * Local offline membership provider backed by DataStore via [SettingsManager].
 *
 * The status is held in a [MutableStateFlow] that is hydrated from DataStore on
 * construction and reloaded on [refresh]. Active state is derived from the
 * persisted flag AND the expiry timestamp (a membership whose expiry has passed
 * is reported as inactive even if the persisted flag is still true).
 */
class LocalMembershipProvider(
    private val settingsManager: SettingsManager,
    private val now: () -> Long = { System.currentTimeMillis() },
) : MembershipProvider {

    private val _status = MutableStateFlow(MembershipStatus())
    override val status: StateFlow<MembershipStatus> = _status.asStateFlow()

    init {
        // Best-effort synchronous hydration is not possible with DataStore (suspending),
        // so callers should invoke refresh() once after construction (typically in a
        // coroutine scope at app start). The StateFlow starts at the Free default.
    }

    override fun hasMembership(): Boolean = _status.value.isActive

    override suspend fun refresh() {
        val tier = settingsManager.membership.tier.first()
        val expiry = settingsManager.membership.expiryTimestamp.first()
        val source = settingsManager.membership.source.first()
        val persistedActive = settingsManager.membership.isActive.first()
        val effectiveActive = persistedActive && !isExpired(expiry)
        _status.value = MembershipStatus(
            tier = MembershipTier.parse(tier),
            expiryTimestamp = expiry,
            source = source,
            isActive = effectiveActive,
        )
        // If the persisted flag says active but the membership has actually expired,
        // reconcile the persisted state so future reads are consistent.
        if (persistedActive && !effectiveActive) {
            settingsManager.membership.saveStatus(
                tier = tier,
                expiryTimestamp = expiry,
                source = source,
                isActive = false,
            )
        }
    }

    /**
     * Apply a successfully validated redemption: persist the new tier/expiry and
     * record the code nonce so it cannot be redeemed again.
     */
    suspend fun applyRedemption(
        tier: MembershipTier,
        durationDays: Int,
        nonce: String,
    ) {
        val start = now()
        val expiry = start + durationDays.toLong() * MILLIS_PER_DAY
        settingsManager.membership.saveStatus(
            tier = tier.name,
            expiryTimestamp = expiry,
            source = SOURCE_REDEMPTION_CODE,
            isActive = true,
        )
        settingsManager.membership.addRedeemedNonce(nonce)
        refresh()
    }

    /**
     * Apply a successfully verified yipay callback: persist the new tier/expiry.
     * The duration is derived from the purchased product (caller decides).
     */
    suspend fun applyYipayPurchase(
        tier: MembershipTier,
        durationDays: Int,
    ) {
        val start = now()
        val expiry = start + durationDays.toLong() * MILLIS_PER_DAY
        settingsManager.membership.saveStatus(
            tier = tier.name,
            expiryTimestamp = expiry,
            source = SOURCE_YIPAY,
            isActive = true,
        )
        refresh()
    }

    /**
     * 直接用云端凭证更新会员状态（激活/恢复后调用）。
     *
     * 与 [applyYipayPurchase] 不同，此方法直接用凭证中的 tier 和 expiryTimestamp，
     * 不重新计算过期时间，确保与服务器返回的凭证完全一致。
     *
     * 解决 activateByOrder 把凭证存到 SharedPreferences 而 [refresh] 从 DataStore 读取
     * 导致两者不同步的问题：激活成功后调用本方法把凭证的 tier/expiry 写入 DataStore，
     * 这样 [refresh] 能读到新的付费状态。
     */
    suspend fun applyCredential(credential: SignedCredential) {
        settingsManager.membership.saveStatus(
            tier = credential.tier,
            expiryTimestamp = credential.expiryTimestamp,
            source = credential.source,
            isActive = true,
        )
        refresh()
    }

    /** Revoke membership and clear all persisted state. */
    suspend fun revoke() {
        settingsManager.membership.clear()
        refresh()
    }

    /** Access the redeemed-nonce set for replay protection checks. */
    suspend fun redeemedNonces(): Set<String> =
        settingsManager.membership.redeemedNonces.first()

    private fun isExpired(expiry: Long?): Boolean =
        expiry != null && now() >= expiry

    companion object {
        const val SOURCE_YIPAY = "yipay"
        const val SOURCE_REDEMPTION_CODE = "redemption_code"
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}