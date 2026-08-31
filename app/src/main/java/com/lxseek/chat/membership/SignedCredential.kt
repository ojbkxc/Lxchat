package com.lxseek.chat.membership

import org.json.JSONObject

/**
 * 云端签名的会员凭证。激活成功后保存在本地，离线验证。
 *
 * 防破解原理：
 * - 凭证内容（deviceId + tier + expiry + source）由云端签名。
 * - App 只持有公钥/共享密钥验证签名，无法伪造。
 * - 篡改本地数据 → 签名验证失败 → 视为未激活。
 *
 * 二元制会员体系：[tier] 只有两态 —— `Free`（免费账户）与 `Premium`（付费账户）。
 * 历史上曾存在 `Pro`/`Enterprise` 多档，读取旧凭证时由 [MembershipTier.parse]
 * 统一归一化为 `Premium`（付费）。**注意**：归一化只发生在语义使用处，本类
 * 的序列化/验签始终使用原始字符串（签名基于原始 tier 值，改写会破坏验签）。
 *
 * 当前 [LocalCloudApi] 用 HMAC-SHA256（共享密钥经 [MembershipSecrets] 从
 * BuildConfig 注入；服务器签发使用同一密钥）。生产正确做法是服务器端 RSA
 * 非对称签名（云端私钥，App 公钥），客户端持共享密钥仍可被反编译提取。
 *
 * 序列化使用 Android 自带的 `org.json.JSONObject`，不引入新依赖。
 */
data class SignedCredential(
    /** 绑定的设备身份证（[DeviceIdCard.getDeviceId]）。 */
    val deviceId: String,
    /** 会员等级：Free（免费）/ Premium（付费）。旧凭证可能仍是 Pro/Enterprise，使用前归一化。 */
    val tier: String,
    /** 过期时间（epoch millis）。 */
    val expiryTimestamp: Long,
    /** 来源：`activation_code` / `yipay`。 */
    val source: String,
    /** 签名（HMAC-SHA256 hex）。 */
    val signature: String,
) {
    /** 序列化为 JSON 字符串，便于保存到 SharedPreferences。 */
    fun toJson(): String {
        val json = JSONObject()
        json.put(KEY_DEVICE_ID, deviceId)
        json.put(KEY_TIER, tier)
        json.put(KEY_EXPIRY, expiryTimestamp)
        json.put(KEY_SOURCE, source)
        json.put(KEY_SIGNATURE, signature)
        return json.toString()
    }

    companion object {
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_TIER = "tier"
        private const val KEY_EXPIRY = "expiryTimestamp"
        private const val KEY_SOURCE = "source"
        private const val KEY_SIGNATURE = "signature"

        /** 反序列化。任何字段缺失/类型不符返回 null，调用方按未激活处理。 */
        fun fromJson(json: String): SignedCredential? = try {
            val obj = JSONObject(json)
            SignedCredential(
                deviceId = obj.getString(KEY_DEVICE_ID),
                tier = obj.getString(KEY_TIER),
                expiryTimestamp = obj.getLong(KEY_EXPIRY),
                source = obj.getString(KEY_SOURCE),
                signature = obj.getString(KEY_SIGNATURE),
            )
        } catch (_: Exception) {
            null
        }

        /**
         * 对凭证内容做 HMAC-SHA256 签名，返回 hex 字符串。
         *
         * 签名内容：`deviceId|tier|expiryTimestamp|source`，不含 signature 自身。
         * 调用方传入的 [credential].signature 会被忽略，函数返回的是"应当的"签名。
         */
        fun sign(credential: SignedCredential, secretKey: String): String {
            val payload = signedPayload(
                deviceId = credential.deviceId,
                tier = credential.tier,
                expiryTimestamp = credential.expiryTimestamp,
                source = credential.source,
            )
            return CryptoUtils.hmacSha256Hex(payload.toByteArray(Charsets.UTF_8), secretKey)
        }

        /**
         * 验证凭证签名。常量时间比较，避免时序侧信道。
         *
         * 注意：本函数只验证签名本身，不检查 deviceId 匹配或是否过期，
         * 这些上层语义由 [ActivationManager.verifyLocal] / [LocalCloudApi.verify] 完成。
         */
        fun verify(credential: SignedCredential, secretKey: String): Boolean {
            val expected = sign(credential, secretKey)
            return CryptoUtils.constantTimeEquals(expected, credential.signature)
        }

        /** 拼接签名载荷。固定顺序，避免字段边界歧义。 */
        private fun signedPayload(
            deviceId: String,
            tier: String,
            expiryTimestamp: Long,
            source: String,
        ): String = "$deviceId|$tier|$expiryTimestamp|$source"
    }
}
