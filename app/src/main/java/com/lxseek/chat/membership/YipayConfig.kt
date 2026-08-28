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
 * Production builds should inject [pid] and [merchantKey] via BuildConfig or the
 * native layer so the secret never ships in plain text in the APK. For now the
 * DEFAULT companion holds placeholder values pointing at pay.lxseek.com.
 */
data class YipayConfig(
    /** 易支付网关URL，如 https://pay.lxseek.com (无尾斜杠). */
    val gatewayUrl: String,
    /** 商户ID (pid). */
    val pid: String,
    /** 商户密钥，直接拼接在签名串末尾（不加 &）. */
    val merchantKey: String,
    /** 默认支付类型：alipay / wxpay / qqpay. */
    val payType: String = "wxpay",
) {
    companion object {
        /**
         * Default configuration pointing at the Lxseek payment gateway.
         *
         * PID and merchant key are placeholders — replace with real credentials
         * sourced from BuildConfig / native config before release.
         */
        val DEFAULT = YipayConfig(
            gatewayUrl = "https://pay.lxseek.com",
            pid = "10000",
            merchantKey = "REPLACE_WITH_REAL_MERCHANT_KEY",
            payType = "wxpay",
        )
    }
}