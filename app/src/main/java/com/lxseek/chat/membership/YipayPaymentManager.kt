package com.lxseek.chat.membership

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Yipay (鏄撴敮浠? payment manager 鈥?builds the gateway submit URL and parses/verifies
 * the DeepLink callback.
 *
 * **Signature algorithm** (identical to v2board EPay.php `pay()` and to
 * [YipayCallbackVerifier.buildSignString]):
 *  1. Collect the request parameters (money, name, notify_url, return_url,
 *     out_trade_no, pid, type 鈥?blanks dropped), sort by key ascending (ksort).
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
     * @param notifyUrl    server async notify URL; blank to omit (App绔棤鏈嶅姟鍣? 鍙暀绌?
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

    /**
     * Query the gateway for the status of [outTradeNo] 鈥?the fallback path when the
     * DeepLink callback is lost (user closed the browser, redirect failed, 鈥?.
     *
     * Calls `GET {gatewayUrl}/api.php?act=order&pid=...&key=...&out_trade_no=...`.
     * Runs on an IO dispatcher with short timeouts. Returns null on network/parse
     * failure; otherwise a [QueryResult] where `code==1 && status==1` means paid.
     *
     * @deprecated 涓嶅啀鐩存帴璋冩槗鏀粯鏌ヨ API銆傚晢鎴峰瘑閽ワ紙merchantKey锛変笉搴旂暀鍦?App 绔紝
     * 涓?DeepLink 鍥炶皟鍙浼€犮€傛柊娴佺▼鏀逛负璋?[RemoteCloudApi.activateByOrder]锛?     * 鐢辨縺娲绘湇鍔″櫒锛坅ctivate.lxseek.com锛夊悗绔煡璇㈡槗鏀粯璁㈠崟纭鐪熸宸叉敮浠樺悗绛惧彂鍑瘉銆?     * 淇濈暀鏈柟娉曚粎渚涚绾胯皟璇?鏃ц矾寰勫吋瀹癸紝鐢熶骇鐜涓嶅簲璋冪敤銆?     */
    @Deprecated(
        "Use RemoteCloudApi.activateByOrder instead 鈥?merchant key must not live in the App.",
        ReplaceWith("RemoteCloudApi(context).activateByOrder(deviceId, outTradeNo)"),
    )
    suspend fun queryOrderStatus(config: YipayConfig, outTradeNo: String): QueryResult? =
        withContext(Dispatchers.IO) {
            try {
                val base = config.gatewayUrl.trimEnd('/')
                val url = "$base/api.php?act=order" +
                    "&pid=${URLEncoder.encode(config.pid, "UTF-8")}" +
                    "&key=${URLEncoder.encode(config.merchantKey, "UTF-8")}" +
                    "&out_trade_no=${URLEncoder.encode(outTradeNo, "UTF-8")}"
                val request = Request.Builder().url(url).get().build()
                queryClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body.string()
                    val parsed = queryJson.decodeFromString<QueryResponse>(body)
                    QueryResult(
                        code = parsed.code,
                        status = parsed.status ?: 0,
                        money = parsed.money.orEmpty(),
                        tradeNo = parsed.trade_no.orEmpty(),
                        outTradeNo = parsed.out_trade_no.orEmpty(),
                    )
                }
            } catch (_: Exception) {
                null
            }
        }

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }

    companion object {
        /** Dedicated client for order-status queries: short timeouts, no streaming. */
        private val queryClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()

        private val queryJson: Json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Result of processing a Yipay callback DeepLink, surfaced to the UI via a StateFlow
 * (see [com.lxseek.chat.MainActivity]). [Idle] is the resting state; the UI consumes
 * [Success]/[Failed] and resets to [Idle]. [Confirming] is an intermediate state shown
 * while the server confirms the payment (1鈥? s typically).
 */
sealed class YipayCallbackResult {
    /** No callback processed yet / already consumed. */
    object Idle : YipayCallbackResult()
    /** Callback signature verified; server is confirming the payment before activating. */
    object Confirming : YipayCallbackResult()
    /** Callback verified and membership activated. */
    data class Success(val tier: MembershipTier) : YipayCallbackResult()
    /** Signature mismatch, missing params, or non-success trade status. */
    object Failed : YipayCallbackResult()
}

/**
 * Result of the gateway order-status query ([YipayPaymentManager.queryOrderStatus]).
 *
 * - `code == 1`: query succeeded; check `status` (1 = paid, 0 = unpaid).
 * - `code == -1`: order does not exist.
 * - `code == -3`: merchant id/key mismatch.
 *
 * `null` from [YipayPaymentManager.queryOrderStatus] means a network/parse failure.
 */
data class QueryResult(
    val code: Int,
    val status: Int,
    val money: String,
    val tradeNo: String,
    val outTradeNo: String,
)

/** Wire shape of the gateway `act=order` response. Missing fields default to null/0. */
@Serializable
private data class QueryResponse(
    val code: Int = 0,
    val status: Int? = null,
    val money: String? = null,
    val trade_no: String? = null,
    val out_trade_no: String? = null,
)
