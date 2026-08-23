package com.lxseek.chat.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.util.SecretCrypto
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 设备长期身份：Ed25519 密钥对 + 自签名证书。
 *
 * 首次运行生成密钥对并持久化，后续复用。设备 ID = SHA-256(publicKey) 的 hex，
 * 作为发现信标和握手中的稳定标识（公钥不变则设备 ID 不变，跨重启稳定）。
 * 私钥经 [SecretCrypto]（Android Keystore AES-256-GCM）加密后写入 SharedPreferences。
 *
 * 对应 HyX `core/src/identity.rs`。Rust 用 rcgen 生成 X.509 证书 + SHA-256 指纹；
 * 这里用自签名 [DeviceCertificate]（Ed25519 签名覆盖 deviceId+pubKey+有效期），
 * 语义等价：稳定身份 + 可验证归属。
 *
 * 纯 Kotlin，无外部加密依赖 —— [Ed25519] 是 RFC 8032 自包含实现。
 */
object DeviceIdentityManager {
    private const val TAG = "DeviceIdentity"
    private const val PREFS = "lxchat_device_identity"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_PUBLIC_KEY = "public_key_b64"
    private const val KEY_PRIVATE_KEY = "private_key_enc_b64"
    private const val CERT_VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000 // 10 年

    /** 加载已有身份；不存在则生成并持久化。幂等，跨重启稳定。 */
    fun loadOrGenerate(context: Context): DeviceIdentity {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load(prefs)?.let { return it }
        val generated = generate()
        save(prefs, generated)
        DebugLog.d(TAG, "generated new device identity ${generated.deviceId}")
        return generated
    }

    /** 生成新身份（不持久化）。私钥为 32 字节随机种子。 */
    fun generate(): DeviceIdentity {
        val seed = ByteArray(32)
        SecureRandom().nextBytes(seed)
        val publicKey = Ed25519.publicKey(seed)
        val deviceId = deriveDeviceId(publicKey)
        return DeviceIdentity(deviceId, publicKey, seed)
    }

    /** 设备 ID = SHA-256(publicKey) hex（64 字符）。公钥不变则稳定。 */
    fun deriveDeviceId(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return digest.toHex()
    }

    /** 用设备私钥对 [message] 签名，返回 64 字节签名（R ‖ S）。 */
    fun sign(identity: DeviceIdentity, message: ByteArray): ByteArray =
        Ed25519.sign(identity.privateKey, message)

    /** 用公钥验签。失败返回 false（不抛异常）。 */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        Ed25519.verify(publicKey, message, signature)

    /** 创建自签名设备证书（有效期 10 年）。 */
    fun createCertificate(
        identity: DeviceIdentity,
        now: Long = System.currentTimeMillis()
    ): DeviceCertificate {
        val issuedAt = now
        val notAfter = now + CERT_VALIDITY_MS
        // 先构造无签名证书计算签名载荷，再填入签名。
        val unsigned = DeviceCertificate(
            deviceId = identity.deviceId,
            publicKey = identity.publicKey,
            issuedAt = issuedAt,
            notAfter = notAfter,
            signature = ByteArray(0)
        )
        val sig = Ed25519.sign(identity.privateKey, certSignPayload(unsigned))
        return unsigned.copy(signature = sig)
    }

    /** 验证自签名证书：有效期 + deviceId 与公钥一致 + 签名有效。 */
    fun verifyCertificate(
        cert: DeviceCertificate,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (now < cert.issuedAt || now > cert.notAfter) {
            DebugLog.w(TAG, "certificate expired or not yet valid")
            return false
        }
        if (cert.deviceId != deriveDeviceId(cert.publicKey)) {
            DebugLog.w(TAG, "certificate deviceId does not match publicKey")
            return false
        }
        return Ed25519.verify(cert.publicKey, certSignPayload(cert), cert.signature)
    }

    /** 持久化身份到 SharedPreferences（私钥经 [SecretCrypto] 加密）。 */
    fun save(context: Context, identity: DeviceIdentity) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        save(prefs, identity)
    }

    /** 从 SharedPreferences 加载身份；不存在或损坏返回 null。 */
    fun load(context: Context): DeviceIdentity? =
        load(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private fun save(prefs: SharedPreferences, identity: DeviceIdentity) {
        val pubB64 = Base64.encodeToString(identity.publicKey, Base64.NO_WRAP)
        val privB64 = Base64.encodeToString(identity.privateKey, Base64.NO_WRAP)
        val privEnc = SecretCrypto.encrypt(privB64)
        prefs.edit()
            .putString(KEY_DEVICE_ID, identity.deviceId)
            .putString(KEY_PUBLIC_KEY, pubB64)
            .putString(KEY_PRIVATE_KEY, privEnc)
            .apply()
    }

    private fun load(prefs: SharedPreferences): DeviceIdentity? {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        val pubB64 = prefs.getString(KEY_PUBLIC_KEY, null) ?: return null
        val privEnc = prefs.getString(KEY_PRIVATE_KEY, null) ?: return null
        return try {
            val publicKey = Base64.decode(pubB64, Base64.NO_WRAP)
            val privB64 = SecretCrypto.decrypt(privEnc)
            val privateKey = Base64.decode(privB64, Base64.NO_WRAP)
            if (publicKey.size != 32 || privateKey.size != 32) {
                DebugLog.e(TAG, "loaded key sizes invalid (pub=${publicKey.size}, priv=${privateKey.size})")
                return null
            }
            DeviceIdentity(deviceId, publicKey, privateKey)
        } catch (e: Exception) {
            DebugLog.e(TAG, "load failed", e)
            null
        }
    }

    /** 证书签名载荷：deviceId ‖ publicKey ‖ issuedAt ‖ notAfter（定长布局，防歧义）。 */
    private fun certSignPayload(cert: DeviceCertificate): ByteArray =
        cert.deviceId.toByteArray(Charsets.UTF_8) +
            cert.publicKey +
            longToBytes(cert.issuedAt) +
            longToBytes(cert.notAfter)

    private fun longToBytes(v: Long): ByteArray = ByteBuffer.allocate(8).putLong(v).array()
}

/**
 * 设备身份。公钥/私钥均为 32 字节。
 *
 * [deviceId] 由公钥派生，[privateKey] 是 Ed25519 种子（不是 clamped 标量）。
 */
data class DeviceIdentity(
    val deviceId: String,
    val publicKey: ByteArray,
    val privateKey: ByteArray
) {
    init {
        require(publicKey.size == 32) { "publicKey must be 32 bytes" }
        require(privateKey.size == 32) { "privateKey must be 32 bytes" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceIdentity) return false
        return deviceId == other.deviceId &&
            publicKey.contentEquals(other.publicKey) &&
            privateKey.contentEquals(other.privateKey)
    }

    override fun hashCode(): Int {
        var r = deviceId.hashCode()
        r = 31 * r + publicKey.contentHashCode()
        r = 31 * r + privateKey.contentHashCode()
        return r
    }

    override fun toString(): String =
        "DeviceIdentity(deviceId=$deviceId, publicKey=<32 bytes>)"
}

/**
 * 自签名设备证书。签名覆盖 deviceId ‖ publicKey ‖ issuedAt ‖ notAfter。
 */
data class DeviceCertificate(
    val deviceId: String,
    val publicKey: ByteArray,
    val issuedAt: Long,
    val notAfter: Long,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceCertificate) return false
        return deviceId == other.deviceId &&
            publicKey.contentEquals(other.publicKey) &&
            issuedAt == other.issuedAt &&
            notAfter == other.notAfter &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var r = deviceId.hashCode()
        r = 31 * r + publicKey.contentHashCode()
        r = 31 * r + issuedAt.hashCode()
        r = 31 * r + notAfter.hashCode()
        r = 31 * r + signature.contentHashCode()
        return r
    }

    override fun toString(): String =
        "DeviceCertificate(deviceId=$deviceId, issuedAt=$issuedAt, notAfter=$notAfter)"
}

/**
 * 纯 Kotlin Ed25519 实现（RFC 8032）。
 *
 * Android API 33+ 才在 `java.security` 原生支持 Ed25519，本项目 minSdk=24，
 * 因此用 BigInteger 域运算 + Twisted Edwards 扩展齐次坐标 (X:Y:Z:T) 自包含实现。
 * SHA-512 用 [MessageDigest]（API 1+ 即有）。设备身份是低频操作（生成一次、
 * 签名偶尔），单次签名约 1–3 ms，性能足够。
 *
 * 曲线: -x² + y² = 1 + d·x²·y²，d = -121665/121666 mod p，p = 2²⁵⁵ - 19。
 */
internal object Ed25519 {
    private const val TAG = "Ed25519"

    // 域参数
    private val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.valueOf(2).pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    // d = -121665 / 121666 mod p
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    // I = sqrt(-1) = 2^((p-1)/4) = 2^(2^253 - 1)
    private val I: BigInteger = BigInteger.valueOf(2)
        .modPow(BigInteger.valueOf(2).pow(253).subtract(BigInteger.ONE), P)
    // 基点 B 的 y 坐标 = 4/5 mod p
    private val BY: BigInteger = BigInteger.valueOf(4)
        .multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
    // 基点 B 的 x 坐标（偶数根，RFC 8032 规定）
    private val BX: BigInteger = recoverX(BY, wantOdd = false)

    /** 扩展齐次坐标点 X:Y:Z:T，x = X/Z，y = Y/Z，T = X·Y/Z。 */
    private class Point(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger
    )

    private val NEUTRAL = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val B = Point(BX, BY, BigInteger.ONE, BX.multiply(BY).mod(P))

    /** 由 32 字节种子派生 32 字节公钥。 */
    fun publicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val h = sha512(seed)
        val s = littleEndianLoad(clamp(h.copyOfRange(0, 32)))
        return pointToBytes(scalarMult(s, B))
    }

    /** 签名：返回 64 字节 R ‖ S。 */
    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val h = sha512(seed)
        val a = clamp(h.copyOfRange(0, 32))
        val prefix = h.copyOfRange(32, 64)
        val s = littleEndianLoad(a)
        val pub = pointToBytes(scalarMult(s, B))
        // r = SHA-512(prefix ‖ M) mod L
        val r = littleEndianLoad(sha512(prefix + message)).mod(L)
        val rBytes = pointToBytes(scalarMult(r, B))
        // S = (r + s · SHA-512(R ‖ A ‖ M)) mod L
        val k = littleEndianLoad(sha512(rBytes + pub + message)).mod(L)
        val bigS = r.add(k.multiply(s)).mod(L)
        return rBytes + littleEndian(bigS, 32)
    }

    /** 验签：S·B == R + SHA-512(R‖A‖M)·A 且 S < L。失败返回 false。 */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            val rBytes = signature.copyOfRange(0, 32)
            val bigS = littleEndianLoad(signature.copyOfRange(32, 64))
            if (bigS.compareTo(L) >= 0) return false
            val a = bytesToPoint(publicKey)
            val r = bytesToPoint(rBytes)
            val k = littleEndianLoad(sha512(rBytes + publicKey + message)).mod(L)
            val lhs = scalarMult(bigS, B)
            val rhs = add(r, scalarMult(k, a))
            pointToBytes(lhs).contentEquals(pointToBytes(rhs))
        } catch (e: Exception) {
            DebugLog.w(TAG, "verify failed", e)
            false
        }
    }

    /**
     * 内部自检：sign/verify 闭环 + 篡改检测。仅用于测试，不用于生产路径。
     * @return true 若实现内部一致。
     */
    fun selfTest(): Boolean {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pub = publicKey(seed)
        val msg = "lxchat-ed25519-selftest".toByteArray()
        val sig = sign(seed, msg)
        val ok = verify(pub, msg, sig)
        val tampered = verify(pub, "tampered".toByteArray(), sig)
        val wrongKey = verify(ByteArray(32), msg, sig)
        return ok && !tampered && !wrongKey
    }

    /** 由 y 坐标恢复 x（曲线方程 -x²+y²=1+d·x²·y² → x²=(y²-1)/(d·y²+1)）。 */
    private fun recoverX(y: BigInteger, wantOdd: Boolean): BigInteger {
        if (y.signum() == 0) return BigInteger.ZERO
        val yy = y.multiply(y).mod(P)
        val num = yy.subtract(BigInteger.ONE).mod(P)
        val den = D.multiply(yy).add(BigInteger.ONE).mod(P)
        val xx = num.multiply(den.modInverse(P)).mod(P)
        // x = xx^((p+3)/8) = xx^(2^252 - 2)
        var x = xx.modPow(BigInteger.valueOf(2).pow(252).subtract(BigInteger.valueOf(2)), P)
        if (!x.multiply(x).mod(P).equals(xx)) {
            x = x.multiply(I).mod(P)
        }
        if (!x.multiply(x).mod(P).equals(xx)) {
            throw IllegalArgumentException("point not on curve")
        }
        // 选择符号：偶数根（wantOdd=false）或奇数根（wantOdd=true）
        if (x.testBit(0) != wantOdd) {
            x = P.subtract(x)
        }
        return x
    }

    /** 点加（a = -1 的 Twisted Edwards 扩展齐次坐标，无 exceptional case）。 */
    private fun add(p1: Point, p2: Point): Point {
        val a = p1.y.subtract(p2.y).multiply(p1.x.subtract(p2.x)).mod(P)
        val b = p1.y.add(p2.y).multiply(p1.x.add(p2.x)).mod(P)
        val c = p1.t.multiply(BigInteger.valueOf(2)).multiply(D).multiply(p2.t).mod(P)
        val d = p1.z.multiply(BigInteger.valueOf(2)).multiply(p2.z).mod(P)
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = b.add(a)
        return Point(
            e.multiply(f).mod(P),
            g.multiply(h).mod(P),
            f.multiply(g).mod(P),
            e.multiply(h).mod(P)
        )
    }

    /** 标量乘：double-and-add。 */
    private fun scalarMult(k: BigInteger, p: Point): Point {
        var result = NEUTRAL
        var addend = p
        var n = k
        while (n.signum() > 0) {
            if (n.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            n = n.shiftRight(1)
        }
        return result
    }

    /** 点编码为 32 字节：y 的 little-endian，最高位存 x 的最低位（符号）。 */
    private fun pointToBytes(p: Point): ByteArray {
        val zInv = p.z.modInverse(P)
        val x = p.x.multiply(zInv).mod(P)
        val y = p.y.multiply(zInv).mod(P)
        val out = littleEndian(y, 32)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** 32 字节解码为点：最高位是 x 符号，其余是 y。 */
    private fun bytesToPoint(bytes: ByteArray): Point {
        require(bytes.size == 32) { "point must be 32 bytes" }
        val copy = bytes.copyOf()
        val sign = (copy[31].toInt() ushr 7) and 1
        copy[31] = (copy[31].toInt() and 0x7F).toByte()
        val y = littleEndianLoad(copy)
        require(y.compareTo(P) < 0) { "y not in field" }
        val x = recoverX(y, wantOdd = sign == 1)
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    /** 标量 clamp：清除低 3 位、清除最高位、设置次高位。 */
    private fun clamp(a: ByteArray): ByteArray {
        a[0] = (a[0].toInt() and 0xF8).toByte()
        a[31] = (a[31].toInt() and 0x7F).toByte()
        a[31] = (a[31].toInt() or 0x40).toByte()
        return a
    }

    private fun littleEndian(n: BigInteger, len: Int): ByteArray {
        val out = ByteArray(len)
        var v = n
        for (i in 0 until len) {
            out[i] = v.and(BigInteger.valueOf(0xFF)).toByte()
            v = v.shiftRight(8)
        }
        return out
    }

    private fun littleEndianLoad(bytes: ByteArray): BigInteger {
        var result = BigInteger.ZERO
        for (i in bytes.size - 1 downTo 0) {
            result = result.shiftLeft(8).or(BigInteger.valueOf(bytes[i].toLong() and 0xFF))
        }
        return result
    }

    private fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()

private fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
    }
    return sb.toString()
}