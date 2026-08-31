package com.lxseek.chat.membership

/**
 * Yipay (易支付 / 彩虹易支付) gateway configuration.
 *
 * Mirrors the v2board EPay.php configuration: a gateway URL, merchant ID (pid),
 * merchant key (used for MD5 signing), and the default payment channel
 * (alipay / wxpay / qqpay).
 *
 * The signature algorithm is shared with [YipayCallbackVerifier] — see
 * [YipayPaymentManager.buildPaymentUrl] for the request-side signing and
 * [YipayCallbackVerifier.verify] for the callback-side verification.
 *
 * 安全修复 H2：商户密钥不再使用占位符随 APK 分发，而是经 [MembershipSecrets]
 * 从 BuildConfig 读取（gradle 属性 `LXCHAT_YIPAY_MERCHANT_KEY` 或
 * local.properties 注入）。**密钥未配置时**（`isMerchantKeyConfigured == false`）：
 * - 回调本地验签不可用（无法证明回调来自网关，而非伪造 DeepLink）；
 * - App 端自行构造支付 URL 的回退路径禁用（请求侧签名同样需要密钥）。
 * 支付确认只能依赖服务器对账（[RemoteCloudApi.activateByOrder]，
 * 服务器查询网关确认订单真实已支付），不留任何假钥路径。
 */
data class YipayConfig(
    /** 易支付网关URL，如 https://pay.lxseek.com (无尾斜杠). */
    val gatewayUrl: String,
    /** 商户ID (pid). */
    val pid: String,
    /** 商户密钥，直接拼接在签名串末尾（不加 &）. 空串表示未配置（见类注释 H2）. */
    val merchantKey: String,
    /** 默认支付类型：alipay / wxpay / qqpay. */
    val payType: String = "wxpay",
) {
    /** 商户密钥是否已配置（H2：未配置时禁用一切依赖本地签名的路径）。 */
    val isMerchantKeyConfigured: Boolean
        get() = merchantKey.isNotBlank()

    companion object {
        /**
         * Default configuration pointing at the Lxseek payment gateway.
         *
         * merchantKey comes from BuildConfig (gradle property
         * `LXCHAT_YIPAY_MERCHANT_KEY` / local.properties, neither is committed);
         * blank means "not configured" — callers must check
         * [isMerchantKeyConfigured] before any local signing/verification path.
         */
        val DEFAULT = YipayConfig(
            gatewayUrl = "https://pay.lxseek.com",
            pid = "10000",
            merchantKey = MembershipSecrets.yipayMerchantKey,
            payType = "wxpay",
        )
    }
}
