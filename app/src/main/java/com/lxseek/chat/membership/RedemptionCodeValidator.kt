package com.lxseek.chat.membership

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import java.security.NoSuchAlgorithmException

/** Redemption code validation result. */
sealed class RedemptionResult {
    /** Code is well-formed, signature matches, and not past its `expiresAt`. */
    data class Valid(
        val tier: MembershipTier,
        val durationDays: Int,
        val nonce: String,
        val issuedAt: Long,
        val expiresAt: Long,
    ) : RedemptionResult()

    /** Code is malformed, signature is wrong, or payload is unreadable. */
    data class Invalid(val reason: String) : RedemptionResult()

    /** Signature is valid but the code's `expiresAt` is in the past. */
    object Expired : RedemptionResult()

    /**
     * Signature is valid and not expired, but the nonce has already been
     * redeemed. This verdict is only produced by [validate] overloads that
     * receive a used-nonce set.
     */
    object AlreadyUsed : RedemptionResult()
}

/** JSON payload carried inside a redemption code. */
@Serializable
private data class RedemptionPayload(
    val tier: String,
    val durationDays: Int,
    val issuedAt: Long,
    val expiresAt: Long,
    val nonce: String,
)

/**
 * Offline redemption code validator.
 *
 * Codes are signed by a secret key known only to the app (later moved to the
 * native layer). Format:
 *
 * ```
 * BASE64(payloadJson) + "." + BASE64(HMAC-SHA256(BASE64(payloadJson), secretKey))
 * ```
 *
 * Signing the *base64* payload (rather than the raw JSON) makes verification
 * deterministic: the verifier never needs to re-serialize JSON, so differing
 * field ordering or whitespace cannot cause spurious failures.
 *
 * The validator is stateless. Replay protection (the [RedemptionResult.AlreadyUsed]
 * verdict) is the caller's responsibility — pass a used-nonce set to
 * [validate] when you want it enforced, or use [LocalMembershipProvider.redeemedNonces]
 * as the source of truth.
 */
@Deprecated(
    "Use cloud-based activation (ActivationManager) instead. " +
        "Redemption codes are superseded by the unified activation-code system.",
)
class RedemptionCodeValidator(
    private val secretKey: ByteArray, // embedded in native layer later
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Validate signature, structure and expiry only. Replay protection is not
     * performed here; use [validate] with a nonce set if you need it.
     */
    fun validate(code: String): RedemptionResult = validate(code, usedNonces = emptySet())

    /**
     * Full validation including replay protection against [usedNonces].
     */
    fun validate(code: String, usedNonces: Set<String>): RedemptionResult {
        val parts = code.trim().split(SEPARATOR)
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return RedemptionResult.Invalid("Malformed code: expected payload.signature")
        }
        val base64Payload = parts[0]
        val base64Signature = parts[1]

        // Verify HMAC signature in constant time.
        val expectedSignature = hmacSha256(base64Payload.toByteArray(Charsets.US_ASCII))
        val providedSignature = try {
            Base64.getDecoder().decode(base64Signature)
        } catch (e: IllegalArgumentException) {
            return RedemptionResult.Invalid("Signature is not valid Base64")
        }
        if (!constantTimeEquals(expectedSignature, providedSignature)) {
            return RedemptionResult.Invalid("Signature mismatch")
        }

        // Decode payload.
        val payloadJson = try {
            String(Base64.getDecoder().decode(base64Payload), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return RedemptionResult.Invalid("Payload is not valid Base64")
        }
        val payload = try {
            json.decodeFromString<RedemptionPayload>(payloadJson)
        } catch (e: Exception) {
            return RedemptionResult.Invalid("Payload is not valid JSON: ${e.message}")
        }

        // Sanity-check payload fields.
        if (payload.durationDays <= 0) {
            return RedemptionResult.Invalid("durationDays must be positive")
        }
        if (payload.nonce.isBlank()) {
            return RedemptionResult.Invalid("nonce must not be blank")
        }
        if (payload.expiresAt <= payload.issuedAt) {
            return RedemptionResult.Invalid("expiresAt must be after issuedAt")
        }
        val tier = MembershipTier.parse(payload.tier)
        if (tier == MembershipTier.Free) {
            return RedemptionResult.Invalid("tier must be Premium or Pro")
        }

        // Expiry check.
        if (System.currentTimeMillis() >= payload.expiresAt) {
            return RedemptionResult.Expired
        }

        // Replay protection.
        if (payload.nonce in usedNonces) {
            return RedemptionResult.AlreadyUsed
        }

        return RedemptionResult.Valid(
            tier = tier,
            durationDays = payload.durationDays,
            nonce = payload.nonce,
            issuedAt = payload.issuedAt,
            expiresAt = payload.expiresAt,
        )
    }

    /**
     * Extract the nonce from a code without verifying the signature. Useful for
     * logging or pre-checking against a used-nonce set before doing the heavier
     * HMAC computation. Returns null if the code is malformed or the payload is
     * unreadable.
     */
    fun extractNonce(code: String): String? {
        val parts = code.trim().split(SEPARATOR)
        if (parts.size != 2) return null
        return try {
            val payloadJson = String(Base64.getDecoder().decode(parts[0]), Charsets.UTF_8)
            json.decodeFromString<RedemptionPayload>(payloadJson).nonce
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Issue a code. Intended for server-side / dev-tooling use; shipped only in
     * tests or the code-generation tool. Not called from the app at runtime.
     */
    fun issue(
        tier: MembershipTier,
        durationDays: Int,
        issuedAt: Long,
        expiresAt: Long,
        nonce: String,
    ): String {
        require(tier != MembershipTier.Free) { "Free tier cannot be issued" }
        require(durationDays > 0) { "durationDays must be positive" }
        require(expiresAt > issuedAt) { "expiresAt must be after issuedAt" }
        require(nonce.isNotBlank()) { "nonce must not be blank" }
        val payload = RedemptionPayload(
            tier = tier.name,
            durationDays = durationDays,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            nonce = nonce,
        )
        val payloadJson = json.encodeToString(RedemptionPayload.serializer(), payload)
        val base64Payload = Base64.getEncoder().encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        val signature = hmacSha256(base64Payload.toByteArray(Charsets.US_ASCII))
        val base64Signature = Base64.getEncoder().encodeToString(signature)
        return "$base64Payload$SEPARATOR$base64Signature"
    }

    private fun hmacSha256(data: ByteArray): ByteArray {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secretKey, "HmacSHA256"))
            mac.doFinal(data)
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException("HmacSHA256 not available", e)
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    companion object {
        private const val SEPARATOR = "."
    }
}