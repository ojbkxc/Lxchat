package com.lxseek.chat.api.oauth

import com.lxseek.chat.api.HttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Shared PKCE and form-post helpers for OAuth client implementations.
 *
 * Extracted verbatim from [com.lxseek.chat.grok.GrokXOAuthClient] and
 * [com.lxseek.chat.openai.OpenAIXOAuthClient], which carried identical copies of the
 * PKCE primitives and token endpoint POST. Per-provider differences (endpoints,
 * client ids, scopes, extra authorize params) live in [OAuthProviderConfig]; only the
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
 *
 * 走共享 OkHttp 客户端（默认 [HttpClient.client]），从而继承全局代理、代理认证与
 * 加密 DNS 设置——HttpURLConnection 会绕过这三者。需要独立行为时可通过 [client]
 * 注入。[timeoutMs] 作为该次调用的总超时（连接+读取）。
 */
internal fun postFormToken(
    endpoint: String,
    formBody: String,
    timeoutMs: Int,
    errorLabel: String,
    client: OkHttpClient = HttpClient.client,
): String {
    require(timeoutMs > 0) { "timeoutMs must be positive" }
    val form = formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType())
    val request = Request.Builder()
        .url(endpoint)
        .header("Accept", "application/json")
        .post(form)
        .build()
    val call = client.newCall(request).apply {
        timeout().timeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    }
    call.execute().use { response ->
        val bodyText = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IllegalStateException(
                "$errorLabel token request failed (${response.code}): ${bodyText.take(500)}"
            )
        }
        return bodyText
    }
}

/**
 * 解码 JWT（`header.payload.signature` 形态）的第二段 payload 为 [JSONObject]。
 *
 * 此前 [parseJwtEmail] 与 ChatGPT accountId 提取各自维护一份相同的
 * base64url 解码逻辑（R2），现统一收敛到这里；格式非法时返回 null 而非抛异常。
 */
internal fun decodeJwtPayload(jwt: String): JSONObject? {
    val payload = jwt.split('.').getOrNull(1) ?: return null
    return runCatching {
        val decoded = android.util.Base64.decode(
            payload.replace('-', '+').replace('_', '/')
                .padEnd((payload.length + 3) / 4 * 4, '='),
            android.util.Base64.DEFAULT,
        )
        JSONObject(String(decoded, Charsets.UTF_8))
    }.getOrNull()
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
        ?: return null
    return decodeJwtPayload(jwt)?.optString("email")?.takeIf { it.isNotBlank() }
}
