package com.lxseek.chat.membership

/**
 * Yipay (易支付) callback verifier.
 *
 * Verifies the MD5 signature returned by the payment gateway. See
 * https://www.yipay.cn/ for the API docs.
 *
 * Signature algorithm (standard 彩虹易支付):
 *  1. Take every callback parameter except `sign` and `sign_type`, dropping
 *     blank values.
 *  2. Sort the remaining parameters by key in ascending ASCII order.
 *  3. Concatenate as `key1=value1&key2=value2&...&keyN=valueN`.
 *  4. Append the merchant key directly (no `&`): `...keyN=valueN<merchantKey>`.
 *  5. MD5 the resulting string and lowercase it to a 32-char hex digest.
 *
 * H2：merchantKey 未配置时本验证器无法给出可信结论（构造前调用方应先检查
 * [YipayConfig.isMerchantKeyConfigured]）。传入空密钥计算的签名必然失配，
 * [verify] 会返回 false——这是"未配置即拒绝"的保守行为。
 */
class YipayCallbackVerifier(
    private val merchantKey: String, // 商户密钥
) {
    /** Parsed callback parameters as received from the gateway. */
    data class CallbackParams(
        val pid: String,         // 商户ID
        val tradeNo: String,     // 易支付订单号
        val outTradeNo: String,  // 商户订单号
        val type: String,        // 支付类型 (alipay/wxpay等)
        val name: String,        // 商品名称
        val money: String,       // 金额
        val tradeStatus: String, // 交易状态 (TRADE_SUCCESS)
        val sign: String,        // 签名
        val signType: String,    // 签名类型 (MD5)
    )

    /** Verify the callback signature. Returns true iff the MD5 sign matches. */
    fun verify(params: CallbackParams): Boolean {
        if (!params.signType.equals("MD5", ignoreCase = true)) {
            return false
        }
        if (params.sign.isBlank()) return false
        if (merchantKey.isBlank()) return false
        val expected = buildSignString(params)
        return CryptoUtils.constantTimeEquals(expected.lowercase(), params.sign.lowercase())
    }

    /**
     * Build the sign string for verification (按参数名排序后拼接).
     * Exposed for debugging/logging; callers should normally use [verify].
     */
    fun buildSignString(params: CallbackParams): String {
        val map = sortedMapOf<String, String>(
            "pid" to params.pid,
            "trade_no" to params.tradeNo,
            "out_trade_no" to params.outTradeNo,
            "type" to params.type,
            "name" to params.name,
            "money" to params.money,
            "trade_status" to params.tradeStatus,
        )
        // Drop blank values per the spec.
        val joined = map.filterValues { it.isNotBlank() }
            .entries.joinToString(separator = "&") { (k, v) -> "$k=$v" }
        val raw = joined + merchantKey
        return CryptoUtils.md5Hex(raw)
    }

    /** True when the trade represents a successful payment. */
    fun isTradeSuccess(params: CallbackParams): Boolean =
        params.tradeStatus.equals("TRADE_SUCCESS", ignoreCase = true)
}
