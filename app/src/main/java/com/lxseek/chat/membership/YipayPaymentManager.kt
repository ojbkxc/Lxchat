package com.lxseek.chat.membership

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Yipay（易支付）支付管理器 —— 构建网关提交 URL 并解析/校验 DeepLink 回调。
 *
 * **签名算法**（与 v2board EPay.php `pay()` 及 [YipayCallbackVerifier.buildSignString] 一致）：
 *  1. 收集请求参数（money、name、notify_url、return_url、out_trade_no、pid、type —— 空值剔除），
 *     按参数名升序排序（ksort）。
 *  2. 用**原始值**拼接为 `key1=value1&key2=value2&...`。
 *     （PHP 的 `http_build_query` + `stripslashes(urldecode(...))` 会还原回原始值，
 *     这里直接跳过编码往返。）
 *  3. 商户密钥直接拼接在末尾（不加 `&`）：`...keyN=valueN<merchantKey>`。
 *  4. MD5 后转小写 32 位 hex。
 *
 * **提交 URL**：`{gatewayUrl}/submit.php?{urlEncodedQuery}&sign={md5}&sign_type=MD5`
 *
 * 回调侧委托给 [YipayCallbackVerifier]（同一签名算法）。
 *
 * 安全（H2）：构造支付 URL / 校验回调签名均要求商户密钥已配置
 * （[YipayConfig.isMerchantKeyConfigured]）；未配置时调用方应禁用本地路径，
 * 仅走服务器对账（[RemoteCloudApi.activateByOrder]）。
 */
class YipayPaymentManager {

    /**
     * Build the payment submit URL for the gateway.
     *
     * @param config       gateway + credentials（须已配置 merchantKey）
     * @param outTradeNo   merchant order number (also used as the product name by v2board)
     * @param amount       payment amount in **yuan** (e.g. "0.30"); v2board uses cents but
     *                     yipay takes yuan directly
     * @param productName  product name; defaults to [outTradeNo] to match v2board
     * @param notifyUrl    server async notify URL; blank to omit（App 端无服务器，可留空）
     * @param returnUrl    sync return URL (DeepLink), e.g. `lxchat://yipay-callback`
     * @return fully-qualified submit URL to open in the browser
     * @throws IllegalStateException [config] 的 merchantKey 未配置（H2：禁止用空/假密钥构造 URL）
     */
    fun buildPaymentUrl(
        config: YipayConfig,
        outTradeNo: String,
        amount: String,
        productName: String = outTradeNo,
        notifyUrl: String = "",
        returnUrl: String = "",
    ): String {
        check(config.isMerchantKeyConfigured) {
            "merchant key not configured; client-side payment URL is disabled (H2)"
        }
        MembershipSecrets.warnIfYipayKeyNotConfigured()

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
        val sign = CryptoUtils.md5Hex(signString + config.merchantKey)

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
     *
     * H3(c)：调用方在 [YipayConfig.isMerchantKeyConfigured] 为 true 时**必须**
     * 调用本方法并拒绝验签失败的回调，不可跳过。未配置时本方法恒返回 false，
     * 由调用方决定是否仅依赖服务器对账。
     */
    fun verifyCallback(config: YipayConfig, params: YipayCallbackVerifier.CallbackParams): Boolean {
        val verifier = YipayCallbackVerifier(config.merchantKey)
        return verifier.verify(params)
    }

    /**
     * Query the gateway for the status of [outTradeNo] —— the fallback path when the
     * DeepLink callback is lost (user closed the browser, redirect failed, …).
     *
     * Calls `GET {gatewayUrl}/api.php?act=order&pid=...&key=...&out_trade_no=...`.
     * Runs on an IO dispatcher with short timeouts. Returns null on network/parse
     * failure; otherwise a [QueryResult] where `code==1 && status==1` means paid.
     *
     * @deprecated 不再直接调易支付查询 API。商户密钥（merchantKey）不应留在 App 端，
     * 且 DeepLink 回调可被伪造。新流程改为调 [RemoteCloudApi.activateByOrder]，
     * 由激活服务器（activate.lxseek.com）后端查询易支付订单确认真正已支付后签发凭证。
     * 保留本方法仅供离线调试与旧路径兼容，生产环境不应调用。
     */
    @Deprecated(
        "Use RemoteCloudApi.activateByOrder instead — merchant key must not live in the App.",
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

    companion object {
        /** Dedicated client for order-status queries: short timeouts, no streaming.
         *  Certificate pinning prevents MITM packet sniffing on pay.lxseek.com. */
        private val queryClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .certificatePinner(
                okhttp3.CertificatePinner.Builder()
                    .add("pay.lxseek.com", "sha256/yZ1amwQO/r0SSBhz48UcPsaNPElxwEZvQaCP/8iRAxE=")
                    .build()
            )
            .build()

        private val queryJson: Json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Result of processing a Yipay callback DeepLink, surfaced to the UI via a StateFlow
 * (see [com.lxseek.chat.MainActivity]). [Idle] is the resting state; the UI consumes
 * [Success]/[Failed] and resets to [Idle]. [Confirming] is an intermediate state shown
 * while the server confirms the payment (1–5 s typically).
 *
 * 二元制：[Success] 携带的 tier 恒为 [MembershipTier.Premium]（付费账户）。
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
