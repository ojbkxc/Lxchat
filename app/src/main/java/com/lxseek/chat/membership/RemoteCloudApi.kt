package com.lxseek.chat.membership

import android.content.Context
import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * 远程实现（调 activate.lxseek.com，源站 140.245.103.211:443，经 Cloudflare 代理）。
 *
 * - [activate] → POST /api/activate_by_code（激活码激活）
 * - [verify] → POST /api/verify（联网验证凭证）
 * - [deactivate] → 本地清除凭证（远程不需要）
 * - [createPaymentOrder] → POST /api/create_payment（服务端生成订单 + 支付 URL）
 *
 * 特有方法（不在 [CloudApi] 接口里）：
 * - [trial]：首次免费三天试用
 * - [activateByOrder]：订单激活（DeepLink 回调后服务器查订单确认已支付）
 * - [renew]：续费（服务器在剩余时长上累加）
 * - [deviceStatus]：查询设备激活状态（卸载重装恢复用）
 *
 * 网络层用 [HttpClient.activationClient]（带证书锁定 + 10 秒超时）。
 * 凭证保存到 SharedPreferences（和 [LocalCloudApi] 用相同的 prefs），离线时可用
 * [SignedCredential.verify] 本地验证（需 HMAC 密钥已配置，见 [MembershipSecrets]）。
 *
 * 错误语义（修复 M8）：网络故障 / 超时 / HTTP 5xx / JSON 解析失败一律映射为
 * NetworkError 类结果（可重试），绝不与"凭据无效"混淆；仅服务器明确返回
 * 业务失败码时才判定 Invalid。
 *
 * 重试（修复 M9）：瞬时故障（IOException / HTTP 5xx）自动重试一次（间隔 500ms），
 * 避免弱网下单次抖动导致激活/验证失败。
 *
 * @param baseUrl 远程激活服务地址，默认 `https://activate.lxseek.com`
 */
class RemoteCloudApi(
    private val context: Context,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : CloudApi {

    /** 激活码激活 → POST /api/activate_by_code。 */
    override suspend fun activate(code: String, deviceId: String): ActivationResult {
        val body = JSONObject().apply {
            put(KEY_DEVICE_ID, deviceId)
            put(KEY_CODE, code)
        }
        return postActivation(PATH_ACTIVATE_BY_CODE, body)
    }

    /** 联网验证 → POST /api/verify。 */
    override suspend fun verify(deviceId: String): VerifyResult {
        val credential = readCredential() ?: return VerifyResult.NotFound
        val body = JSONObject().apply {
            put(KEY_DEVICE_ID, deviceId)
            put(KEY_CREDENTIAL, JSONObject(credential.toJson()))
        }
        return withContext(Dispatchers.IO) {
            try {
                val resp = doPost(PATH_VERIFY, body)
                    ?: return@withContext VerifyResult.NetworkError
                val code = resp.optInt(KEY_CODE, -1)
                if (code != 0) return@withContext VerifyResult.Invalid
                val valid = resp.optBoolean(KEY_VALID, false)
                if (!valid) return@withContext VerifyResult.Invalid

                var effective = credential
                // 服务器可能返回更新后的 expire_at。
                val expireAt = resp.optLong(KEY_EXPIRE_AT, 0L)
                if (expireAt > 0L && expireAt != credential.expiryTimestamp) {
                    val updated = credential.copy(expiryTimestamp = expireAt)
                    if (MembershipSecrets.isHmacSecretConfigured) {
                        // 修复 H1：过期时间变化后旧签名不再覆盖新字段，落盘前必须
                        // 用共享密钥对新凭据完整重签（含 expireAt），否则下次离线
                        // verifyLocal 必失败（自毁）。
                        val resigned = updated.copy(
                            signature = SignedCredential.sign(updated, MembershipSecrets.hmacSecret),
                        )
                        saveCredential(resigned)
                        effective = resigned
                    } else {
                        // HMAC 密钥未配置（H5）：无法本地重签。不落盘半签名凭据，
                        // 本轮会话内使用服务器确认的有效期，落盘仍保留旧凭据。
                        MembershipSecrets.warnIfHmacNotConfigured()
                        effective = updated
                    }
                }
                VerifyResult.Valid(effective)
            } catch (_: IOException) {
                VerifyResult.NetworkError
            } catch (e: Exception) {
                // M8：解析等非预期异常按网络错误处理（可重试），不误判凭据无效。
                DebugLog.e(TAG, "verify failed", e)
                VerifyResult.NetworkError
            }
        }
    }

    /** 解绑：本地清除凭证（远程不需要）。 */
    override suspend fun deactivate(deviceId: String): Boolean {
        val credential = readCredential() ?: return false
        if (credential.deviceId != deviceId) return false
        clearCredential()
        return true
    }

    /**
     * 创建支付订单 → POST /api/create_payment。
     *
     * 服务端生成订单 + 支付 URL，返回 `{"code":0,"payment_url":"...","out_trade_no":"..."}`。
     * [planId] 非空时传给服务端，服务端按套餐定价；为空时回退旧逻辑（仅传 amount）。
     * 二元制下无档位参数，请求仍携带 `tier="Premium"` 以兼容既有服务器协议。
     */
    override suspend fun createPaymentOrder(
        deviceId: String,
        amount: String,
        planId: String,
    ): PaymentOrderResult? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put(KEY_DEVICE_ID, deviceId)
                if (planId.isNotEmpty()) put(KEY_PLAN_ID, planId)
                put(KEY_TIER, MembershipTier.Premium.name)
                put(KEY_AMOUNT, amount)
            }
            val resp = doPost(PATH_CREATE_PAYMENT, body) ?: return@withContext null
            val code = resp.optInt(KEY_CODE, -1)
            if (code != 0) {
                DebugLog.w(TAG, "createPaymentOrder rejected: code=$code resp=$resp")
                return@withContext null
            }
            val paymentUrl = resp.optString(KEY_PAYMENT_URL, "")
            val outTradeNo = resp.optString(KEY_OUT_TRADE_NO, "")
            if (paymentUrl.isEmpty()) null
            else PaymentOrderResult(outTradeNo = outTradeNo, paymentUrl = paymentUrl)
        } catch (_: IOException) {
            // M8/M9：网络故障已由 doPost 内部重试一次，仍失败则返回 null（下单失败）。
            null
        } catch (e: Exception) {
            DebugLog.e(TAG, "createPaymentOrder failed", e)
            null
        }
    }

    // ── RemoteCloudApi 特有方法 ─────────────────────────────────

    /** 首次免费三天试用 → POST /api/trial。 */
    suspend fun trial(deviceId: String): ActivationResult {
        val body = JSONObject().apply { put(KEY_DEVICE_ID, deviceId) }
        return postActivation(PATH_TRIAL, body)
    }

    /** 订单激活 → POST /api/activate_by_order。服务器查订单确认已支付后激活。 */
    suspend fun activateByOrder(deviceId: String, outTradeNo: String): ActivationResult {
        val body = JSONObject().apply {
            put(KEY_DEVICE_ID, deviceId)
            put(KEY_OUT_TRADE_NO, outTradeNo)
        }
        return postActivation(PATH_ACTIVATE_BY_ORDER, body)
    }

    /** 续费 → POST /api/renew。同 [activateByOrder]，服务器在剩余时长上累加。 */
    suspend fun renew(deviceId: String, outTradeNo: String): ActivationResult {
        val body = JSONObject().apply {
            put(KEY_DEVICE_ID, deviceId)
            put(KEY_OUT_TRADE_NO, outTradeNo)
        }
        return postActivation(PATH_RENEW, body)
    }

    /**
     * 查询设备激活状态（用于卸载重装恢复）。
     *
     * → POST /api/device_status
     * 响应：`{"code":0,"active":true,"tier":"Premium","expire_at":123,"credential":{...}}`
     * 或：`{"code":0,"active":false}`
     *
     * App 启动时若本地无凭证，调本方法查服务端：有有效激活则返回重签凭证，
     * 调用方保存到本地完成恢复。
     *
     * M8：网络故障返回 [DeviceStatusResult.NetworkError]（下次启动再试），
     * 不当作 Inactive。
     */
    suspend fun deviceStatus(deviceId: String): DeviceStatusResult {
        val body = JSONObject().apply { put(KEY_DEVICE_ID, deviceId) }
        return withContext(Dispatchers.IO) {
            try {
                val resp = doPost(PATH_DEVICE_STATUS, body)
                    ?: return@withContext DeviceStatusResult.NetworkError
                val code = resp.optInt(KEY_CODE, -1)
                if (code != 0) return@withContext DeviceStatusResult.Inactive
                val active = resp.optBoolean(KEY_ACTIVE, false)
                if (!active) return@withContext DeviceStatusResult.Inactive
                val credJson = resp.optJSONObject(KEY_CREDENTIAL)
                    ?: return@withContext DeviceStatusResult.Inactive
                val credential = SignedCredential.fromJson(credJson.toString())
                    ?: return@withContext DeviceStatusResult.Inactive
                val tier = resp.optString(KEY_TIER, MembershipTier.Premium.name)
                val expireAt = resp.optLong(KEY_EXPIRE_AT, 0)
                DeviceStatusResult.Active(credential, tier, expireAt)
            } catch (_: IOException) {
                DeviceStatusResult.NetworkError
            } catch (e: Exception) {
                DebugLog.e(TAG, "deviceStatus failed", e)
                DeviceStatusResult.NetworkError
            }
        }
    }

    // ── 内部 helpers ─────────────────────────────────────────────

    /**
     * 通用 POST 激活端点（trial / activate_by_code / activate_by_order / renew）。
     * 响应格式：`{"code":0,"credential":{...},"expire_at":123}`
     */
    private suspend fun postActivation(path: String, body: JSONObject): ActivationResult =
        withContext(Dispatchers.IO) {
            try {
                val resp = doPost(path, body) ?: return@withContext ActivationResult.NetworkError
                val code = resp.optInt(KEY_CODE, -1)
                when (code) {
                    0 -> {
                        val credJson = resp.optJSONObject(KEY_CREDENTIAL)
                            ?: return@withContext ActivationResult.InvalidCode
                        val credential = SignedCredential.fromJson(credJson.toString())
                            ?: return@withContext ActivationResult.InvalidCode
                        // 校验设备 ID 匹配（防止服务器返回其他设备的凭证）
                        val requestDeviceId = body.optString(KEY_DEVICE_ID, "")
                        if (requestDeviceId.isNotBlank() && credential.deviceId != requestDeviceId) {
                            DebugLog.e(TAG, "credential deviceId mismatch: expected=$requestDeviceId got=${credential.deviceId}")
                            return@withContext ActivationResult.InvalidCode
                        }
                        saveCredential(credential)
                        ActivationResult.Success(credential)
                    }
                    CODE_INVALID_CODE -> ActivationResult.InvalidCode
                    CODE_ALREADY_USED -> ActivationResult.AlreadyUsed
                    CODE_EXPIRED -> ActivationResult.Expired
                    else -> {
                        DebugLog.e(TAG, "activation failed: code=$code resp=$resp")
                        ActivationResult.InvalidCode
                    }
                }
            } catch (_: IOException) {
                ActivationResult.NetworkError
            } catch (e: Exception) {
                DebugLog.e(TAG, "postActivation failed: path=$path", e)
                ActivationResult.NetworkError
            }
        }

    /**
     * 发 POST 请求，返回解析后的 JSONObject，null 表示非 2xx 或网络错误。
     *
     * M9：瞬时故障（IOException / HTTP 5xx）自动重试一次（[RETRY_DELAY_MILLIS] 间隔）；
     * 4xx 等确定性失败不重试。
     */
    private suspend fun doPost(path: String, body: JSONObject): JSONObject? {
        val url = baseUrl.trimEnd('/') + path
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        var lastError: IOException? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0) delay(RETRY_DELAY_MILLIS)
            try {
                HttpClient.activationClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val raw = response.body.string()
                        return try {
                            JSONObject(raw)
                        } catch (e: Exception) {
                            DebugLog.e(TAG, "invalid JSON response: $raw", e)
                            null
                        }
                    }
                    // M9：5xx 视为瞬时故障可重试；4xx 为确定性失败直接放弃。
                    if (response.code >= 500 && attempt < MAX_ATTEMPTS - 1) {
                        DebugLog.w(TAG, "HTTP ${response.code} (attempt ${attempt + 1}), retrying")
                    } else {
                        DebugLog.e(TAG, "HTTP ${response.code}")
                        return null
                    }
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) {
                    DebugLog.w(TAG, "IO error (attempt ${attempt + 1}), retrying")
                }
            }
        }
        lastError?.let { DebugLog.e(TAG, "POST failed after retries", it) }
        return null
    }

    // ── 持久化 helpers（与 LocalCloudApi 共用同一 prefs）─────────

    private fun prefs() = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun saveCredential(credential: SignedCredential) {
        prefs().edit().putString(KEY_CREDENTIAL_PREF, credential.toJson()).apply()
    }

    private fun readCredential(): SignedCredential? {
        val json = prefs().getString(KEY_CREDENTIAL_PREF, null) ?: return null
        return SignedCredential.fromJson(json)
    }

    private fun clearCredential() {
        prefs().edit().remove(KEY_CREDENTIAL_PREF).apply()
    }

    companion object {
        private const val TAG = "RemoteCloudApi"

        /** 远程激活服务默认地址（Cloudflare 代理，源站 140.245.103.211:443）。 */
        const val DEFAULT_BASE_URL = "https://activate.lxseek.com"

        // API 路径
        private const val PATH_TRIAL = "/api/trial"
        private const val PATH_ACTIVATE_BY_CODE = "/api/activate_by_code"
        private const val PATH_ACTIVATE_BY_ORDER = "/api/activate_by_order"
        private const val PATH_VERIFY = "/api/verify"
        private const val PATH_RENEW = "/api/renew"
        private const val PATH_CREATE_PAYMENT = "/api/create_payment"
        private const val PATH_DEVICE_STATUS = "/api/device_status"

        // 请求/响应字段名（与服务器端 Go 实现一致）
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CODE = "code"
        private const val KEY_OUT_TRADE_NO = "out_trade_no"
        private const val KEY_CREDENTIAL = "credential"
        private const val KEY_PLAN_ID = "plan_id"
        private const val KEY_TIER = "tier"
        private const val KEY_AMOUNT = "amount"
        private const val KEY_PAYMENT_URL = "payment_url"
        private const val KEY_ACTIVE = "active"

        private const val KEY_VALID = "valid"
        private const val KEY_EXPIRE_AT = "expire_at"

        // 服务器返回 code 值（与服务器端约定）
        private const val CODE_INVALID_CODE = 1
        private const val CODE_ALREADY_USED = 2
        private const val CODE_EXPIRED = 3

        // M9：重试策略（1 次重试 = 共 2 次尝试）
        private const val MAX_ATTEMPTS = 2
        private const val RETRY_DELAY_MILLIS = 500L

        // 与 LocalCloudApi 共用同一 SharedPreferences，离线时本地验证仍可用
        private const val PREF_NAME = "lxchat_activation"
        private const val KEY_CREDENTIAL_PREF = "credential"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}