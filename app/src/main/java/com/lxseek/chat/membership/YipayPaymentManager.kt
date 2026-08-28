package com.lxseek.chat.membership

import android.net.Uri
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Yipay (易支付) payment manager — builds the gateway submit URL and parses/verifies
 * the DeepLink callback.
 *
 * **Signature algorithm** (identical to v2board EPay.php `pay()` and to
 * [YipayCallbackVerifier.buildSignString]):
 *  1. Collect the request parameters (money, name, notify_url, return_url,
 *     out_trade_no, pid, type — blanks dropped), sort by key ascending (ksort).
 *  2. Concatenate as `key1=value1&key2=value2&...` using the **raw** values.
 *     (PHP does `http_build_query` then `stripslashes(urldecode(...))` which
 *     round-trips back to the raw values, so we skip the encode/decode dance.)
 *  3. Append the merchant key directly (no `&`): `...keyN=valueN<merchantKey>`.
 *  4. MD5 the result and lowercase to a 32-char hex digest.
 *
 * **Submit URL**: `{gatewayUrl}/submit.php?{urlEncodedQuery}&sign={md5}&sign_type=MD5`
 *
 * The callback side is delegated to [YipayCallbackVerifier] which shares the same
 * signing algorithm.
 */
class YipayPaymentManager {

    /**
     * Build the payment submit URL for the gateway.
     *
     * @param config       gateway + credentials
     * @param outTradeNo   merchant order number (also used as the product name by v2board)
     * @param amount       payment amount in **yuan** (e.g. "0.30"); v2board uses cents but
     *                     yipay takes yuan directly
     * @param productName  product name; defaults to [outTradeNo] to match v2board
     * @param notifyUrl    server async notify URL; blank to omit (App端无服务器, 可留空)
     * @param returnUrl    sync return URL (DeepLink), e.g. `lxchat://yipay-callback`
     * @return fully-qualified submit URL to open in the browser
     */
    fun buildPaymentUrl(
        config: YipayConfig,
        outTradeNo: String,
        amount: String,
        productName: String = outTradeNo,
        notifyUrl: String = "",
        returnUrl: String = "",
    ): String {
        // 1. Collect parameters (raw values), drop blanks.
        val params = sortedMapOf<String, String>(
            "pid" to config.pid,
            "type" to config.payType,
            "out_trade_no" to outTradeNo,
            "notify_url" to notifyUrl,
            "return_url" to returnUrl,
            "name" to productName,
            "money" to amount,
        ).filterValues { it.isNotBlank() }

        // 2. Build the raw sign string (ksort already applied by sortedMapOf).
        val signString = params.entries.joinToString(separator = "&") { (k, v) -> "$k=$v" }

        // 3. Append merchant key + MD5.
        val sign = md5Hex(signString + config.merchantKey)

        // 4. Build the submit URL with URL-encoded values for transport.
        val encodedQuery = params.entries.joinToString(separator = "&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        val base = config.gatewayUrl.trimEnd('/')
        return "$base/submit.php?$encodedQuery&sign=$sign&sign_type=MD5"
    }

    /**
     * Parse the DeepLink callback URI into [YipayCallbackVerifier.CallbackParams].
     *
     * The gateway appends query parameters to the return_url:
     * `lxchat://yipay-callback?pid=...&trade_no=...&out_trade_no=...&type=...&
     * name=...&money=...&trade_status=...&sign=...&sign_type=MD5`
     *
     * Returns null if any required parameter is missing.
     */
    fun parseCallback(uri: Uri): YipayCallbackVerifier.CallbackParams? {
        if (uri.scheme != "lxchat" || uri.host != "yipay-callback") return null
        val pid = uri.getQueryParameter("pid") ?: return null
        val tradeNo = uri.getQueryParameter("trade_no") ?: return null
        val outTradeNo = uri.getQueryParameter("out_trade_no") ?: return null
        val type = uri.getQueryParameter("type") ?: return null
        val name = uri.getQueryParameter("name") ?: return null
        val money = uri.getQueryParameter("money") ?: return null
        val tradeStatus = uri.getQueryParameter("trade_status") ?: return null
        val sign = uri.getQueryParameter("sign") ?: return null
        val signType = uri.getQueryParameter("sign_type") ?: "MD5"
        return YipayCallbackVerifier.CallbackParams(
            pid = pid,
            tradeNo = tradeNo,
            outTradeNo = outTradeNo,
            type = type,
            name = name,
            money = money,
            tradeStatus = tradeStatus,
            sign = sign,
            signType = signType,
        )
    }

    /**
     * Verify the callback signature. Delegates to [YipayCallbackVerifier].
     * Returns true iff the MD5 sign matches the recomputed signature.
     */
    fun verifyCallback(config: YipayConfig, params: YipayCallbackVerifier.CallbackParams): Boolean {
        val verifier = YipayCallbackVerifier(config.merchantKey)
        return verifier.verify(params)
    }

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }
}

/**
 * Result of processing a Yipay callback DeepLink, surfaced to the UI via a StateFlow
 * (see [com.lxseek.chat.MainActivity]). [Idle] is the resting state; the UI consumes
 * [Success]/[Failed] and resets to [Idle].
 */
sealed class YipayCallbackResult {
    /** No callback processed yet / already consumed. */
    object Idle : YipayCallbackResult()
    /** Callback verified and membership activated. */
    data class Success(val tier: MembershipTier) : YipayCallbackResult()
    /** Signature mismatch, missing params, or non-success trade status. */
    object Failed : YipayCallbackResult()
}