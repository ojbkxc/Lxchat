package com.lxseek.chat.membership

import android.content.Context
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * 本地实现（离线兜底；生产主流程走 [RemoteCloudApi]）。
 *
 * - 激活码预置在代码中（仅测试用码，后续从配置/远程获取）
 * - HMAC 签名密钥经 [MembershipSecrets] 从 BuildConfig 注入（修复 H5：
 *   不再随 APK 分发占位密钥；未配置时本地签发/验签均禁用）
 * - 激活后凭证保存在 SharedPreferences（`lxchat_activation`）
 *
 * 一码一机且一次性消费（修复 M4）：激活码 → deviceId 映射记录在 `used_codes`。
 * 历史行为允许同设备重复激活同一码无限续期，现已收紧为绝对一次性——
 * 已被任何设备（含原设备）使用的码再次激活返回 [ActivationResult.AlreadyUsed]。
 * 服务器端激活（/api/activate_by_code）以服务端记录为准，本表是本地兜底。
 *
 * 离线可用：[verify] 只读本地凭证 + 验证签名 + 设备匹配 + 过期 + 设备指纹，不联网。
 *
 * 并发安全（修复 M3）：所有读-改-写序列（凭证保存、激活码表、时钟水位）
 * 由 [ioMutex] 互斥保护，避免并发激活/解绑造成丢失更新。
 *
 * 防时钟回拨（修复 M6）：每次成功验证记录墙钟水位 `last_seen_clock`；
 * 若当前时间比水位倒退超过 [CLOCK_ROLLBACK_THRESHOLD_MILLIS]（1 天），
 * 视为时钟被回拨，本地判定不可信，返回 [VerifyResult.NetworkError]
 * （凭据需联网核验后才恢复本地信任）。
 *
 * 设备指纹快照（修复 M7，缓解性措施）：激活时保存完整设备指纹
 * （[DeviceIdCard.getFullFingerprint]，64 位哈希），验证时比对当前指纹。
 * 不符（如 root 篡改 ANDROID_ID 后克隆到同型号设备）则拒绝本地验证、
 * 要求重新激活。**局限**：root 用户同样可以篡改本快照存储，此措施仅提高
 * 攻击成本，无法彻底防御 root；绝对防御需服务器端设备风控。
 */
class LocalCloudApi(
    private val context: Context,
    private val secretKey: String = MembershipSecrets.hmacSecret,
) : CloudApi {

    /** 串行化读-改-写序列（M3）。SharedPreferences 单次读写自身线程安全。 */
    private val ioMutex = Mutex()

    override suspend fun activate(code: String, deviceId: String): ActivationResult = ioMutex.withLock {
        val normalized = code.trim().uppercase()

        // 1. 格式校验：XXXX-XXXX-XXXX-XXXX
        if (!CODE_FORMAT.matches(normalized)) {
            return@withLock ActivationResult.InvalidCode
        }

        // 2. 查预置码表
        val entry = VALID_CODES[normalized] ?: return@withLock ActivationResult.InvalidCode
        val durationDays = entry

        // 3. 一码一次性消费（M4）：已被任何设备（含本设备）使用过的码直接拒绝。
        val usedCodes = readUsedCodes()
        if (usedCodes.has(normalized)) {
            return@withLock ActivationResult.AlreadyUsed
        }

        // H5：本地签发需要 HMAC 密钥；未配置时禁用本地签发（提示走联网激活）。
        if (!MembershipSecrets.isHmacSecretConfigured) {
            MembershipSecrets.warnIfHmacNotConfigured()
            return@withLock ActivationResult.NetworkError
        }

        // 4. 生成签名凭证。续费语义（M5）：当前凭证尚未过期时在剩余时长上累加，
        //    已过期（或无凭证）则从现在起算。
        val now = System.currentTimeMillis()
        val existing = readCredential()
        val base = existing?.expiryTimestamp?.takeIf { it > now } ?: now
        val expiry = base + durationDays.toLong() * MILLIS_PER_DAY
        val unsigned = SignedCredential(
            deviceId = deviceId,
            tier = MembershipTier.Premium.name,
            expiryTimestamp = expiry,
            source = SOURCE_ACTIVATION_CODE,
            signature = "",
        )
        val signature = SignedCredential.sign(unsigned, secretKey)
        val credential = unsigned.copy(signature = signature)

        // 5. 持久化：保存凭证 + 记录激活码已使用 + 设备指纹快照（M7）+ 时钟水位（M6）
        saveCredential(credential)
        saveUsedCodes(usedCodes.put(normalized, deviceId))
        saveDeviceFingerprint(DeviceIdCard.getFullFingerprint(context))
        saveLastSeenClock(now)

        ActivationResult.Success(credential)
    }

    override suspend fun verify(deviceId: String): VerifyResult = ioMutex.withLock {
        val credential = readCredential() ?: return@withLock VerifyResult.NotFound

        // H5：未配置 HMAC 密钥时离线验签不可用，凭据视为需联网核验。
        if (!MembershipSecrets.isHmacSecretConfigured) {
            MembershipSecrets.warnIfHmacNotConfigured()
            return@withLock VerifyResult.NetworkError
        }

        // 签名验证
        if (!SignedCredential.verify(credential, secretKey)) {
            return@withLock VerifyResult.Invalid
        }
        // 设备匹配
        if (credential.deviceId != deviceId) {
            return@withLock VerifyResult.Invalid
        }
        // M7：设备指纹快照比对（激活后系统特征被篡改 → 要求重新激活）
        val expectedFingerprint = readDeviceFingerprint()
        if (expectedFingerprint != null &&
            expectedFingerprint != DeviceIdCard.getFullFingerprint(context)
        ) {
            DebugLog.w(TAG, "device fingerprint mismatch; re-activation required")
            return@withLock VerifyResult.Invalid
        }
        // M6：时钟回拨检测。当前墙钟比上次成功验证的水位倒退超过阈值 → 不可信。
        val now = System.currentTimeMillis()
        val lastSeen = readLastSeenClock()
        if (lastSeen > 0L && now < lastSeen - CLOCK_ROLLBACK_THRESHOLD_MILLIS) {
            DebugLog.w(
                TAG,
                "wall clock rolled back ${(lastSeen - now) / MILLIS_PER_DAY}d; " +
                    "online verification required",
            )
            return@withLock VerifyResult.NetworkError
        }
        // 过期检查
        if (now >= credential.expiryTimestamp) {
            return@withLock VerifyResult.Invalid
        }
        // 验证通过：推进时钟水位（只前进不后退）
        if (now > lastSeen) saveLastSeenClock(now)
        VerifyResult.Valid(credential)
    }

    override suspend fun deactivate(deviceId: String): Boolean = ioMutex.withLock {
        val credential = readCredential() ?: return@withLock false
        // 只允许解绑本设备的凭证
        if (credential.deviceId != deviceId) return@withLock false

        clearCredential()

        // 清除该 deviceId 绑定的激活码使用记录与设备指纹快照
        val usedCodes = readUsedCodes()
        val toRemove = usedCodes.keys().asSequence().filter { usedCodes.optString(it) == deviceId }.toList()
        toRemove.forEach { usedCodes.remove(it) }
        saveUsedCodes(usedCodes)
        clearDeviceFingerprint()

        true
    }

    override suspend fun createPaymentOrder(
        deviceId: String,
        amount: String,
        planId: String,
    ): PaymentOrderResult? {
        // 本地实现不支持云端下单；调用方（设置页）会回退到 App 端构造支付 URL（需配置商户密钥）。
        return null
    }

    // ── 持久化 helpers（均要求已持有 [ioMutex]）────────────────

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

    /** 读取 `code -> deviceId` 映射。损坏的 JSON 回退为空表并记录日志（R4：不再静默吞异常）。 */
    private fun readUsedCodes(): JSONObject {
        val json = prefs().getString(KEY_USED_CODES, null) ?: return JSONObject()
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            DebugLog.w(TAG, "used_codes corrupted, resetting: ${e.message}")
            JSONObject()
        }
    }

    private fun saveUsedCodes(map: JSONObject) {
        prefs().edit().putString(KEY_USED_CODES, map.toString()).apply()
    }

    /** M7：激活时保存的完整设备指纹快照。 */
    private fun saveDeviceFingerprint(fingerprint: String) {
        prefs().edit().putString(KEY_DEVICE_FINGERPRINT, fingerprint).apply()
    }

    private fun readDeviceFingerprint(): String? =
        prefs().getString(KEY_DEVICE_FINGERPRINT, null)

    private fun clearDeviceFingerprint() {
        prefs().edit().remove(KEY_DEVICE_FINGERPRINT).apply()
    }

    /** M6：上次成功验证的墙钟水位（epoch millis）。 */
    private fun saveLastSeenClock(timestamp: Long) {
        prefs().edit().putLong(KEY_LAST_SEEN_CLOCK, timestamp).apply()
    }

    private fun readLastSeenClock(): Long =
        prefs().getLong(KEY_LAST_SEEN_CLOCK, 0L)

    companion object {
        private const val TAG = "LocalCloudApi"

        /** 来源标识：激活码激活。 */
        const val SOURCE_ACTIVATION_CODE = "activation_code"

        /** 激活码格式：4 组 4 位大写字母/数字，用 `-` 连接。 */
        private val CODE_FORMAT = Regex("^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")

        /**
         * 预置激活码表：`code -> durationDays`（二元制：全部授予付费账户）。
         * 后续从远程配置获取，本地仅作离线兜底/测试用。
         */
        private val VALID_CODES: Map<String, Int> = mapOf(
            "LXCH-TEST-PREM-IUM0" to 30,
            "LXCH-TEST-PRO0-0000" to 30,
        )

        /** M6：时钟回拨容忍阈值（1 天）。 */
        private const val CLOCK_ROLLBACK_THRESHOLD_MILLIS = 24L * 60L * 60L * 1000L

        private const val PREF_NAME = "lxchat_activation"
        private const val KEY_CREDENTIAL = "credential"
        private const val KEY_USED_CODES = "used_codes"
        private const val KEY_DEVICE_FINGERPRINT = "device_fingerprint"
        private const val KEY_LAST_SEEN_CLOCK = "last_seen_clock"

        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}