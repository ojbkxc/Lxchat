package com.lxseek.chat.membership

import android.content.Context

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * 云端 API 接口。现在用 [LocalCloudApi]（本地验证），
 * 以后云端部署后切 `RemoteCloudApi`（联网验证）。
 *
 * 接口设计为 suspend，方便以后 Remote 实现走网络 IO；Local 实现里 suspend 仅做磁盘读写。
 */
interface CloudApi {
    /** 激活码激活，绑定设备。成功返回签名凭证。 */
    suspend fun activate(code: String, deviceId: String): ActivationResult

    /** 验证设备会员状态（联网）。 */
    suspend fun verify(deviceId: String): VerifyResult

    /** 解绑设备。 */
    suspend fun deactivate(deviceId: String): Boolean

    /**
     * 创建支付订单（云端生成订单 + 支付 URL）。
     *
     * @param planId 套餐 ID（monthly/quarterly/half_year/yearly/lifetime），
     *               空字符串表示回退旧逻辑（不传给服务端）。
     */
    suspend fun createPaymentOrder(
        deviceId: String,
        tier: MembershipTier,
        amount: String,
        planId: String = "",
    ): PaymentOrderResult?
}

/** 激活结果。 */
sealed class ActivationResult {
    /** 激活成功，返回签名凭证。 */
    data class Success(val credential: SignedCredential) : ActivationResult()

    /** 激活码无效（格式错误或不在预置码表中）。 */
    object InvalidCode : ActivationResult()

    /** 已被其他设备使用（一码一机）。 */
    object AlreadyUsed : ActivationResult()

    /** 激活码已过期。 */
    object Expired : ActivationResult()

    /** 网络错误（Local 实现不会返回，Remote 实现会）。 */
    object NetworkError : ActivationResult()
}

/** 验证结果。 */
sealed class VerifyResult {
    /** 凭证有效：签名正确、设备匹配、未过期。 */
    data class Valid(val credential: SignedCredential) : VerifyResult()

    /** 凭证无效（签名错误、设备不匹配或已过期）。 */
    object Invalid : VerifyResult()

    /** 本地无凭证（未激活过）。 */
    object NotFound : VerifyResult()

    /** 网络错误（Local 实现不会返回，Remote 实现会）。 */
    object NetworkError : VerifyResult()
}

/** 支付订单创建结果（预留）。 */
data class PaymentOrderResult(
    val outTradeNo: String,
    val paymentUrl: String,
)

/**
 * 设备状态查询结果（用于卸载重装恢复）。
 *
 * App 启动时若本地无凭证，调 [RemoteCloudApi.deviceStatus] 查服务端：
 * - [DeviceStatusResult.Active]：服务端有有效激活，返回重签凭证，恢复到本地。
 * - [DeviceStatusResult.Inactive]：服务端无激活记录或已过期，按未激活处理。
 * - [DeviceStatusResult.NetworkError]：网络错误，本次恢复失败（下次启动再试）。
 */
sealed class DeviceStatusResult {
    /** 设备有有效激活，返回重签凭证。 */
    data class Active(
        val credential: SignedCredential,
        val tier: String,
        val expireAt: Long,
    ) : DeviceStatusResult()

    /** 设备无激活记录或已过期。 */
    object Inactive : DeviceStatusResult()

    /** 网络错误。 */
    object NetworkError : DeviceStatusResult()
}

/**
 * 本地实现（现在用）。
 *
 * - 激活码预置在代码中（后续从配置/远程获取）
 * - HMAC 签名密钥预置（后续移 NDK）
 * - 激活后凭证保存在 SharedPreferences
 *
 * 一码一机：激活码 → deviceId 映射记录在 `used_codes`，同一码换设备激活返回 [ActivationResult.AlreadyUsed]。
 * 离线可用：[verify] 只读本地凭证 + 验证签名 + 设备匹配 + 过期，不联网。
 */
class LocalCloudApi(
    private val context: Context,
    private val secretKey: String = DEFAULT_SECRET_KEY,
) : CloudApi {

    override suspend fun activate(code: String, deviceId: String): ActivationResult {
        val normalized = code.trim().uppercase()

        // 1. 格式校验：XXXX-XXXX-XXXX-XXXX
        if (!CODE_FORMAT.matches(normalized)) {
            return ActivationResult.InvalidCode
        }

        // 2. 查预置码表
        val entry = VALID_CODES[normalized] ?: return ActivationResult.InvalidCode
        val tier = entry.first
        val durationDays = entry.second

        // 3. 一码一机：检查是否已被其他设备使用
        val usedCodes = readUsedCodes()
        val boundDevice = usedCodes[normalized]
        if (boundDevice != null && boundDevice != deviceId) {
            return ActivationResult.AlreadyUsed
        }

        // 4. 生成签名凭证
        val now = System.currentTimeMillis()
        val expiry = now + durationDays.toLong() * MILLIS_PER_DAY
        val unsigned = SignedCredential(
            deviceId = deviceId,
            tier = tier.name,
            expiryTimestamp = expiry,
            source = SOURCE_ACTIVATION_CODE,
            signature = "",
        )
        val signature = SignedCredential.sign(unsigned, secretKey)
        val credential = unsigned.copy(signature = signature)

        // 5. 持久化：保存凭证 + 记录激活码已使用
        saveCredential(credential)
        saveUsedCodes(usedCodes.apply { put(normalized, deviceId) })

        return ActivationResult.Success(credential)
    }

    override suspend fun verify(deviceId: String): VerifyResult {
        val credential = readCredential() ?: return VerifyResult.NotFound

        // 签名验证
        if (!SignedCredential.verify(credential, secretKey)) {
            return VerifyResult.Invalid
        }
        // 设备匹配
        if (credential.deviceId != deviceId) {
            return VerifyResult.Invalid
        }
        // 过期检查
        if (System.currentTimeMillis() >= credential.expiryTimestamp) {
            return VerifyResult.Invalid
        }
        return VerifyResult.Valid(credential)
    }

    override suspend fun deactivate(deviceId: String): Boolean {
        val credential = readCredential() ?: return false
        // 只允许解绑本设备的凭证
        if (credential.deviceId != deviceId) return false

        clearCredential()

        // 清除该 deviceId 绑定的激活码使用记录
        val usedCodes = readUsedCodes()
        val toRemove = usedCodes.keys().asSequence().filter { usedCodes.optString(it) == deviceId }.toList()
        toRemove.forEach { usedCodes.remove(it) }
        saveUsedCodes(usedCodes)

        return true
    }

    override suspend fun createPaymentOrder(
        deviceId: String,
        tier: MembershipTier,
        amount: String,
        planId: String,
    ): PaymentOrderResult? {
        // 预留：现在返回 null（App 端自己构造支付 URL，见 SettingsMembershipPage 的 Yipay 升级区）。
        // 以后云端实现：云端生成订单 + 签名 + 返回支付 URL。
        return null
    }

    // ── 持久化 helpers ──────────────────────────────────────────

    private fun prefs() = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun saveCredential(credential: SignedCredential) {
        prefs().edit().putString(KEY_CREDENTIAL, credential.toJson()).apply()
    }

    private fun readCredential(): SignedCredential? {
        val json = prefs().getString(KEY_CREDENTIAL, null) ?: return null
        return SignedCredential.fromJson(json)
    }

    private fun clearCredential() {
        prefs().edit().remove(KEY_CREDENTIAL).apply()
    }

    /** 读取 `code -> deviceId` 映射。 */
    private fun readUsedCodes(): JSONObject {
        val json = prefs().getString(KEY_USED_CODES, null) ?: return JSONObject()
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveUsedCodes(map: JSONObject) {
        prefs().edit().putString(KEY_USED_CODES, map.toString()).apply()
    }

    companion object {
        /** 来源标识：激活码激活。 */
        const val SOURCE_ACTIVATION_CODE = "activation_code"

        /**
         * 预置 HMAC 密钥占位符。**仅用于本地验证**，后续移到 NDK/混淆。
         * 切到 RemoteCloudApi 后改用 RSA 公钥验证云端签名。
         */
        private const val DEFAULT_SECRET_KEY = "LXCHAT_HMAC_SECRET_PLACEHOLDER"

        /** 激活码格式：4 组 4 位大写字母/数字，用 `-` 连接。 */
        private val CODE_FORMAT = Regex("^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")

        /**
         * 预置激活码表：`code -> (tier, durationDays)`。
         * 后续从远程配置获取，本地仅作离线兜底/测试用。
         */
        private val VALID_CODES: Map<String, Pair<MembershipTier, Int>> = mapOf(
            "LXCH-TEST-PREM-IUM0" to (MembershipTier.Premium to 30),
            "LXCH-TEST-PRO0-0000" to (MembershipTier.Pro to 30),
        )

        private const val PREF_NAME = "lxchat_activation"
        private const val KEY_CREDENTIAL = "credential"
        private const val KEY_USED_CODES = "used_codes"

        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}

/**
 * 远程实现（调 activate.lxseek.com）。
 *
 * - [activate] → POST /api/activate_by_code（激活码激活）
 * - [verify] → POST /api/verify（联网验证凭证）
 * - [deactivate] → 本地清除凭证（远程不需要）
 * - [createPaymentOrder] → 仍然返回 null（App 端自己构造支付 URL）
 *
 * 特有方法（不在 [CloudApi] 接口里）：
 * - [trial]：首次免费三天试用
 * - [activateByOrder]：订单激活（DeepLink 回调后服务器查订单确认已支付）
 * - [renew]：续费（同 activateByOrder）
 *
 * 网络层用 [HttpClient.activationClient]（带证书锁定 + 10 秒超时）。
 * 凭证保存到 SharedPreferences（和 [LocalCloudApi] 用相同的 prefs），离线时可用 [SignedCredential.verify] 本地验证。
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
                if (resp == null) return@withContext VerifyResult.NetworkError
                val code = resp.optInt(KEY_CODE, -1)
                if (code != 0) return@withContext VerifyResult.Invalid
                val valid = resp.optBoolean(KEY_VALID, false)
                if (!valid) return@withContext VerifyResult.Invalid
                // 服务器可能返回更新后的 expire_at，更新本地凭证的过期时间。
                val expireAt = resp.optLong(KEY_EXPIRE_AT, 0L)
                if (expireAt > 0L && expireAt != credential.expiryTimestamp) {
                    val updated = credential.copy(expiryTimestamp = expireAt)
                    saveCredential(updated)
                }
                VerifyResult.Valid(credential)
            } catch (_: IOException) {
                VerifyResult.NetworkError
            } catch (e: Exception) {
                DebugLog.e(TAG, "verify failed", e)
                VerifyResult.Invalid
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
     */
    override suspend fun createPaymentOrder(
        deviceId: String,
        tier: MembershipTier,
        amount: String,
        planId: String,
    ): PaymentOrderResult? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put(KEY_DEVICE_ID, deviceId)
                if (planId.isNotEmpty()) put(KEY_PLAN_ID, planId)
                put(KEY_TIER, tier.name)
                put(KEY_AMOUNT, amount)
            }
            val resp = doPost(PATH_CREATE_PAYMENT, body) ?: return@withContext null
            val code = resp.optInt(KEY_CODE, -1)
            if (code != 0) return@withContext null
            val paymentUrl = resp.optString(KEY_PAYMENT_URL, "")
            val outTradeNo = resp.optString(KEY_OUT_TRADE_NO, "")
            if (paymentUrl.isEmpty()) null
            else PaymentOrderResult(outTradeNo = outTradeNo, paymentUrl = paymentUrl)
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

    /** 续费 → POST /api/renew。同 [activateByOrder]。 */
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
     */
    suspend fun deviceStatus(deviceId: String): DeviceStatusResult {
        val body = JSONObject().apply { put(KEY_DEVICE_ID, deviceId) }
        return withContext(Dispatchers.IO) {
            try {
                val resp = doPost(PATH_DEVICE_STATUS, body)
                    ?: return@withContext DeviceStatusResult.Inactive
                val code = resp.optInt(KEY_CODE, -1)
                if (code != 0) return@withContext DeviceStatusResult.Inactive
                val active = resp.optBoolean(KEY_ACTIVE, false)
                if (!active) return@withContext DeviceStatusResult.Inactive
                val credJson = resp.optJSONObject(KEY_CREDENTIAL)
                    ?: return@withContext DeviceStatusResult.Inactive
                val credential = SignedCredential.fromJson(credJson.toString())
                    ?: return@withContext DeviceStatusResult.Inactive
                val tier = resp.optString(KEY_TIER, "Premium")
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

    /** 发 POST 请求，返回解析后的 JSONObject，null 表示非 2xx 或网络错误。 */
    private fun doPost(path: String, body: JSONObject): JSONObject? {
        val url = baseUrl.trimEnd('/') + path
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        HttpClient.activationClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                DebugLog.e(TAG, "HTTP ${response.code} for $url")
                return null
            }
            val raw = response.body.string()
            return try {
                JSONObject(raw)
            } catch (e: Exception) {
                DebugLog.e(TAG, "invalid JSON response for $url: $raw", e)
                null
            }
        }
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

        /** 远程激活服务默认地址。 */
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

        // 与 LocalCloudApi 共用同一 SharedPreferences，离线时本地验证仍可用
        private const val PREF_NAME = "lxchat_activation"
        private const val KEY_CREDENTIAL_PREF = "credential"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}