package com.lxseek.chat.membership

import com.lxseek.chat.BuildConfig
import com.lxseek.chat.util.DebugLog

/**
 * 会员/支付密钥的唯一入口（安全修复 H2 / H5）。
 *
 * 设计目标：**真实密钥不随 APK 源码分发，也不打进 git 追踪的任何文件。**
 *
 * 注入链路：`app/build.gradle.kts` 读取 gradle 属性（`-P` 或 gradle.properties）
 * 或 local.properties（已 gitignore），写入 BuildConfig 字段。两处均未配置时
 * BuildConfig 字段为空串，代表"未配置"，App 内对应能力自动禁用并打 WARN：
 *
 * - [hmacSecret] 空 → 离线凭证验签不可用（[LocalCloudApi.verify] 返回
 *   [VerifyResult.NetworkError]，凭据视为需联网核验），本地激活码签发禁用。
 * - [yipayMerchantKey] 空 → 易支付回调本地验签禁用、App 端自行构造支付 URL
 *   的回退路径禁用，支付确认一律走服务器对账（[RemoteCloudApi.activateByOrder]）。
 *
 * 【生产正确做法】客户端持有共享 HMAC 密钥本身可被反编译提取，离线验签只能
 * 提高篡改门槛而非绝对安全。生产环境应由服务器端签名（如 RSA 非对称签名，
 * App 仅内置公钥），本轮的目标是"不再随 APK 分发假密钥/占位密钥"。
 */
object MembershipSecrets {

    private const val TAG = "MembershipSecrets"

    /** HMAC-SHA256 共享密钥（激活服务器与客户端一致）；空串 = 未配置。 */
    val hmacSecret: String
        get() = BuildConfig.LXCHAT_HMAC_SECRET

    /** 易支付商户密钥（MD5 签名用）；空串 = 未配置。 */
    val yipayMerchantKey: String
        get() = BuildConfig.LXCHAT_YIPAY_MERCHANT_KEY

    /** HMAC 密钥是否已配置。 */
    val isHmacSecretConfigured: Boolean
        get() = hmacSecret.isNotBlank()

    /** 易支付商户密钥是否已配置。 */
    val isYipayMerchantKeyConfigured: Boolean
        get() = yipayMerchantKey.isNotBlank()

    /**
     * 首次使用时打一次 WARN 提醒未配置（避免刷屏）。
     * 供 [LocalCloudApi] / [RemoteCloudApi] 启动路径调用。
     */
    fun warnIfHmacNotConfigured() {
        if (!isHmacSecretConfigured && !hmacWarned) {
            hmacWarned = true
            DebugLog.w(
                TAG,
                "LXCHAT_HMAC_SECRET not configured via gradle property/local.properties; " +
                    "offline credential verification is unavailable and credentials require online check.",
            )
        }
    }

    /** 易支付商户密钥未配置的 WARN（一次性）。 */
    fun warnIfYipayKeyNotConfigured() {
        if (!isYipayMerchantKeyConfigured && !yipayWarned) {
            yipayWarned = true
            DebugLog.w(
                TAG,
                "LXCHAT_YIPAY_MERCHANT_KEY not configured; local callback signature verification " +
                    "and client-side payment URL fallback are disabled (server reconciliation only).",
            )
        }
    }

    @Volatile private var hmacWarned = false
    @Volatile private var yipayWarned = false
}