package com.lxseek.chat.membership

import android.content.Context

/**
 * 会员运行时全局绑定（安全修复 H4 的接线点）。
 *
 * 背景：[LocalMembershipProvider] 的公共构造签名是
 * `LocalMembershipProvider(settingsManager)`（由 AppContainer 调用，不可改动），
 * 拿不到 Context，因此无法自行读取 SharedPreferences 中的签名凭证。
 * 本对象提供进程级、幂等的 Context 绑定：App 入口（[com.lxseek.chat.MainActivity]
 * 的深链分发）调用 [bind] 一次，之后 [LocalMembershipProvider.refresh] 便能通过
 * [credentialTrust] 拿到"以已验签凭证为准"的权威判定源。
 *
 * 线程安全：[bind] 幂等，仅首次调用生效。
 */
object MembershipRuntime {

    @Volatile
    private var appContext: Context? = null

    /**
     * 绑定应用上下文（幂等；后续调用在已绑定后为空操作）。
     * 只保存 applicationContext，避免持有 Activity 引用造成泄漏。
     */
    fun bind(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) {
                    appContext = context.applicationContext
                }
            }
        }
    }

    /**
     * 当前可用的凭证信任源；未绑定时返回 null（付费门退回 DataStore 快照判定，
     * 与历史行为一致，见 [LocalMembershipProvider.refresh] 的降级说明）。
     */
    fun credentialTrust(): CredentialTrust? {
        val ctx = appContext ?: return null
        val cloudApi = LocalCloudApi(ctx)
        return CredentialTrust { cloudApi.verify(DeviceIdCard.getDeviceId(ctx)) }
    }
}

/**
 * 凭证信任源：返回本地（离线）凭证校验结果。
 *
 * 语义（与 [VerifyResult] 对齐）：
 * - [VerifyResult.Valid]：本地存在签名正确、设备匹配、未过期的凭证 → 付费态。
 * - [VerifyResult.Invalid]：凭证被篡改 / 设备不匹配 / 已过期 / 设备指纹不符 → 一律按免费。
 * - [VerifyResult.NotFound]：从未激活过。
 * - [VerifyResult.NetworkError]：HMAC 密钥未配置或时钟回拨超阈值 → 需联网核验。
 */
fun interface CredentialTrust {
    suspend fun verify(): VerifyResult
}