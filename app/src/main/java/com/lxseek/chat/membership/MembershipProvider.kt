package com.lxseek.chat.membership

import com.lxseek.chat.data.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Membership tier levels（二元制会员体系）。
 *
 * 只有两种有效账户状态：
 * - [Free] —— 免费账户。
 * - [Premium] —— 付费账户（**PAID**）。
 *
 * 历史上还存在 [Pro] / [Enterprise] 两档，现已废除。它们仅作为 @Deprecated
 * 兼容壳保留：外部文件（如 SettingsScreen）仍引用这些枚举常量，直接删除
 * 会破坏编译；本包内**没有任何代码路径会产生这两个值**，[parse] 读到旧值
 * 时统一归一化为 [Premium]（付费）。
 */
enum class MembershipTier {
    Free,
    Premium,

    /** @deprecated 兼容壳（2026 二元制简化）：等价于 [Premium]，勿在新代码中使用。 */
    @Deprecated("Binary membership: use Premium (paid) instead", level = DeprecationLevel.WARNING)
    Pro,

    /** @deprecated 兼容壳（2026 二元制简化）：等价于 [Premium]，勿在新代码中使用。 */
    @Deprecated("Binary membership: use Premium (paid) instead", level = DeprecationLevel.WARNING)
    Enterprise,
    ;

    companion object {
        /**
         * Parse a tier name defensively; unknown values fall back to [Free].
         * 二元制归一化：旧档位名（Premium/Pro/Enterprise）与 "Paid" 一律映射为
         * [Premium]，保证旧凭证 / 旧 DataStore / 旧订单记录平滑迁移。
         */
        fun parse(name: String?): MembershipTier {
            val raw = name?.trim().orEmpty()
            if (raw.isEmpty()) return Free
            return when (raw.lowercase()) {
                "free" -> Free
                "premium", "paid", "pro", "enterprise" -> Premium
                else -> Free
            }
        }
    }
}

/** Membership status snapshot. */
data class MembershipStatus(
    val tier: MembershipTier = MembershipTier.Free,
    val expiryTimestamp: Long? = null,
    val source: String = "", // "yipay" or "redemption_code" / "activation_code"
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
 * 判定层级（安全修复 H4）：
 * 1. **权威源 = 已验签凭证**。若能获得凭证信任源（构造注入或 [MembershipRuntime]），
 *    [refresh] 先做本地凭证校验（[LocalCloudApi.verify]：签名 + 设备匹配 + 未过期
 *    + 设备指纹 + 时钟水位）。凭据无效（被篡改/设备不符/过期）时**一律按免费**，
 *    DataStore 中的付费态只作显示缓存，不再决定付费门。
 * 2. **降级源 = DataStore 快照**。信任源不可用（App 未绑定 Context、HMAC 密钥
 *    未配置、时钟回拨）时退回 DataStore 判定（与历史行为一致），此时凭据视为
 *    需联网核验。密钥未配置是构建配置问题（生产发布必配），见 [MembershipSecrets]。
 */
class LocalMembershipProvider(
    private val settingsManager: SettingsManager,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** 可选凭证信任源（H4）；为空时尝试 [MembershipRuntime] 全局绑定。 */
    private val credentialTrust: CredentialTrust? = null,
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
        // ── 1. DataStore 快照（显示缓存）──────────────────────────
        val tier = settingsManager.membership.tier.first()
        val expiry = settingsManager.membership.expiryTimestamp.first()
        val source = settingsManager.membership.source.first()
        val persistedActive = settingsManager.membership.isActive.first()
        val effectiveActive = persistedActive && !isExpired(expiry)
        var status = MembershipStatus(
            tier = MembershipTier.parse(tier),
            expiryTimestamp = expiry,
            source = source,
            isActive = effectiveActive,
        )

        // ── 2. 权威判定：已验签凭证（H4）───────────────────────────
        // 付费门以签名凭证为准；DataStore 可能被 root 直接改写（如
        // membership_is_active=true 绕过付费墙），签名保护使其不可伪造。
        val trust = credentialTrust ?: MembershipRuntime.credentialTrust()
        if (trust != null) {
            when (val verified = trust.verify()) {
                is VerifyResult.Valid -> {
                    status = MembershipStatus(
                        tier = MembershipTier.parse(verified.credential.tier),
                        expiryTimestamp = verified.credential.expiryTimestamp,
                        source = verified.credential.source,
                        isActive = true,
                    )
                }
                is VerifyResult.Invalid -> {
                    // 凭据无效（篡改/设备不符/过期/指纹不符）：一律按免费，
                    // 并清除 DataStore 中的付费残留，防 UI 显示与判定不一致。
                    status = MembershipStatus()
                }
                is VerifyResult.NotFound,
                is VerifyResult.NetworkError,
                -> {
                    // 未激活过 / 需联网核验（密钥未配置、时钟回拨）：保留 DataStore 快照。
                }
            }
            // DataStore 与权威判定漂移时回写（reconcile），保证后续读取一致。
            if (status.tier.name != tier || status.expiryTimestamp != expiry ||
                status.source != source || status.isActive != persistedActive
            ) {
                settingsManager.membership.saveStatus(
                    tier = status.tier.name,
                    expiryTimestamp = status.expiryTimestamp,
                    source = status.source,
                    isActive = status.isActive,
                )
            }
            _status.value = status
            return
        }

        // ── 3. 降级路径：无信任源，维持 DataStore 判定（历史行为）──
        _status.value = status
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
     *
     * M5：剩余时长累加——当前会员尚未过期时，新时长在剩余到期点之上叠加，
     * 而非从现在清零重算。
     */
    suspend fun applyRedemption(
        tier: MembershipTier,
        durationDays: Int,
        nonce: String,
    ) {
        val base = currentEffectiveExpiry()
        val expiry = base + durationDays.toLong() * MILLIS_PER_DAY
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
     *
     * M5：同 [applyRedemption]，在剩余时长上累加。
     */
    suspend fun applyYipayPurchase(
        tier: MembershipTier,
        durationDays: Int,
    ) {
        val base = currentEffectiveExpiry()
        val expiry = base + durationDays.toLong() * MILLIS_PER_DAY
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
     * 不重新计算过期时间，确保与服务器返回的凭证完全一致（服务器端 renew 已做累加）。
     *
     * 解决 activateByOrder 把凭证存到 SharedPreferences 而 [refresh] 从 DataStore 读取
     * 导致两者不同步的问题：激活成功后调用本方法把凭证的 tier/expiry 写入 DataStore，
     * 这样 [refresh] 能读到新的付费状态。
     */
    suspend fun applyCredential(credential: SignedCredential) {
        settingsManager.membership.saveStatus(
            tier = MembershipTier.parse(credential.tier).name,
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

    /**
     * M5：当前有效的到期时间；无会员或已过期则返回 now()（新时长从现在起算）。
     */
    private suspend fun currentEffectiveExpiry(): Long {
        val expiry = settingsManager.membership.expiryTimestamp.first()
        val active = settingsManager.membership.isActive.first()
        return if (active && expiry != null && expiry > now()) expiry else now()
    }

    private fun isExpired(expiry: Long?): Boolean =
        expiry != null && now() >= expiry

    companion object {
        const val SOURCE_YIPAY = "yipay"
        const val SOURCE_REDEMPTION_CODE = "redemption_code"
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
