package com.lxseek.chat.membership

/**
 * Thin JNI bridge to the native redemption code validator (`libredemption_native.so`).
 *
 * The native layer performs the cryptographic work (HMAC-SHA256 over the
 * base64 payload, constant-time signature compare) and the expiry check,
 * keeping the secret-key handling off the JVM heap and harder to tamper with
 * via Frida-style hooks. Kotlin callers should prefer [RedemptionCodeValidator]
 * for full structural/replay validation; this bridge is the low-level entry
 * point used when only signature + expiry are needed.
 *
 * Result codes are kept in sync with `redemption_native.cpp`:
 *  - [RESULT_VALID]     — signature matches and `expiresAt` is in the future
 *  - [RESULT_INVALID]   — malformed code, base64 error, or signature mismatch
 *  - [RESULT_EXPIRED]   — signature valid but `expiresAt <= now`
 */
@Deprecated(
    "Cloud-based activation does not use native validation. " +
        "Retained for backward compatibility with legacy redemption codes.",
)
object RedemptionNativeBridge {
    init { System.loadLibrary("redemption_native") }

    /** Signature valid and not expired. */
    const val RESULT_VALID = 0

    /** Malformed code, base64 decode failure, or signature mismatch. */
    const val RESULT_INVALID = 1

    /** Signature valid but the code's `expiresAt` is in the past. */
    const val RESULT_EXPIRED = 2

    /**
     * Validate a redemption code against [secretKey].
     *
     * @param code       `BASE64(payload) + "." + BASE64(HMAC-SHA256(BASE64(payload), secretKey))`
     * @param secretKey  HMAC secret key bytes
     * @return one of [RESULT_VALID], [RESULT_INVALID], [RESULT_EXPIRED]
     */
    external fun validateCode(code: String, secretKey: ByteArray): Int

    /**
     * Retrieve the HMAC secret key from the native layer.
     *
     * The key is XOR-obfuscated inside `libredemption_native.so` and deobfuscated
     * at runtime, so it never appears in plain text in the APK's Kotlin/Java code
     * or in the .rodata section of the .so (resistant to `strings` and simple
     * static analysis). Use this to supply [RedemptionCodeValidator] without
     * hard-coding the key in Kotlin.
     */
    external fun getHmacSecret(): ByteArray
}