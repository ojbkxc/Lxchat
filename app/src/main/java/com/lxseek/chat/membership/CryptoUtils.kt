package com.lxseek.chat.membership

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 会员域统一加密工具（重构 R3：合并域内三处重复实现）。
 *
 * 原先 [SignedCredential]、[YipayCallbackVerifier]、[RedemptionCodeValidator]、
 * [YipayPaymentManager]、[DeviceIdCard] 各自维护了常量时间比较 / HMAC / MD5 / SHA-256
 * 的私有副本，行为相同但容易在修改时出现漂移。本对象把它们收敛为单一实现。
 *
 * 所有方法均为无状态纯函数，线程安全。
 */
internal object CryptoUtils {

    // ── 常量时间比较 ──────────────────────────────────────────

    /**
     * 常量时间字符串比较，避免时序攻击（长度不同直接返回，长度信息本身不敏感）。
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    /**
     * 常量时间字节数组比较（签名/摘要比对用）。
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    // ── HMAC-SHA256 ───────────────────────────────────────────

    /**
     * HMAC-SHA256 → 小写 hex 字符串（[SignedCredential] 凭证签名用）。
     *
     * @param secretKey 密钥（UTF-8 字节）；调用方须保证非空，否则抛 [IllegalArgumentException]。
     */
    fun hmacSha256Hex(data: ByteArray, secretKey: String): String = toHex(hmacSha256(data, secretKey.toByteArray(Charsets.UTF_8)))

    /**
     * HMAC-SHA256 → 原始字节（[RedemptionCodeValidator] 兑换码验证用）。
     */
    fun hmacSha256(data: ByteArray, secretKey: ByteArray): ByteArray {
        val mac = try {
            Mac.getInstance("HmacSHA256")
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException("HmacSHA256 not available", e)
        }
        mac.init(SecretKeySpec(secretKey, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // ── 摘要 ──────────────────────────────────────────────────

    /** MD5 → 小写 hex（易支付签名算法要求，见 [YipayCallbackVerifier]）。 */
    fun md5Hex(input: String): String = toHex(digest("MD5", input))

    /** SHA-256 → 小写 hex（设备身份证等）。 */
    fun sha256Hex(input: String): String = toHex(digest("SHA-256", input))

    private fun digest(algorithm: String, input: String): ByteArray = try {
        MessageDigest.getInstance(algorithm).digest(input.toByteArray(Charsets.UTF_8))
    } catch (e: NoSuchAlgorithmException) {
        throw IllegalStateException("$algorithm not available", e)
    }

    /** 字节数组 → 小写 hex 字符串。 */
    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_TABLE[v ushr 4])
            sb.append(HEX_TABLE[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX_TABLE = "0123456789abcdef".toCharArray()
}