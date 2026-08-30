package com.lxseek.chat.api.oauth

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject

/**
 * Shared PKCE and form-post helpers for OAuth client implementations.
 *
 * Extracted verbatim from [com.lxseek.chat.grok.GrokXOAuthClient] and
 * [com.lxseek.chat.openai.OpenAIXOAuthClient], which carried identical copies of the
 * PKCE primitives and token endpoint POST. Per-provider differences (endpoints,
 * client ids, scopes, extra authorize params) stay in each client file; only the
 * mechanism is shared here.
 */

/** Cryptographic-random hex string of [bytes] length. */
internal fun pkceRandomHex(bytes: Int): String =
    SecureRandom().run {
        val b = ByteArray(bytes)
        nextBytes(b)
        b.joinToString("") { "%02x".format(it) }
    }

/** Cryptographic-random base64url string (no padding) of [bytes] length. */
internal fun pkceRandomBase64Url(bytes: Int): String {
    val b = ByteArray(bytes)
    SecureRandom().nextBytes(b)
    return android.util.Base64.encodeToString(
        b,
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
    )
}

/** S256 PKCE code challenge for a given [verifier]. */
internal fun pkceS256(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return android.util.Base64.encodeToString(
        digest,
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
    )
}

/** URL-encode a form parameter value using UTF-8. */
internal fun formUrlEncode(v: String): String =
    URLEncoder.encode(v, Charsets.UTF_8.name())

/**
 * POST a x-www-form-urlencoded [formBody] to [endpoint] and return the response body.
 * [errorLabel] is embedded in the exception message on failure so callers can tell
 * which provider failed without inspecting the URL.
 */
internal fun postFormToken(endpoint: String, formBody: String, timeoutMs: Int, errorLabel: String): String {
    val conn = URL(endpoint).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.instanceFollowRedirects = true
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty(
            "Content-Type",
            "application/x-www-form-urlencoded",
        )
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(formBody) }
        val status = conn.responseCode
        val errorStream = conn.errorStream
        if (status == HttpURLConnection.HTTP_OK && errorStream == null) {
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val errBody = errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        throw IllegalStateException("$errorLabel token request failed ($status): ${errBody?.take(500)}")
    } finally {
        conn.disconnect()
    }
}

/**
 * Extract the user email from a token endpoint response.
 *
 * Checks the top-level `email` field first, then [fallbackEmail], then decodes the
 * JWT payload (id_token or access_token) and reads its `email` claim. Returns null
 * when no email can be determined. Shared by Grok and OpenAI token stores.
 */
internal fun parseJwtEmail(json: JSONObject, fallbackEmail: String?): String? {
    json.optString("email").takeIf { it.isNotBlank() }?.let { return it }
    fallbackEmail?.let { return it }
    // id_token / access_token 第二段是 JWT payload,base64url 解码后找 email 字段。
    val jwt = json.optString("id_token").takeIf { it.isNotBlank() }
        ?: json.optString("access_token").takeIf { it.isNotBlank() }
    val payload = jwt?.split('.')?.getOrNull(1) ?: return null
    return runCatching {
        val decoded = android.util.Base64.decode(
            payload.replace('-', '+').replace('_', '/').padEnd((payload.length + 3) / 4 * 4, '='),
            android.util.Base64.DEFAULT,
        )
        val claims = JSONObject(String(decoded, Charsets.UTF_8))
        claims.optString("email").takeIf { it.isNotBlank() }
    }.getOrNull()
}