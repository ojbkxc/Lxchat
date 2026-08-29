package com.lxseek.chat.openai

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * ChatGPT(OpenAI) 官方账号 OAuth 客户端。
 *
 * 端点与参数对齐 cc-haha 的 `src/services/openaiAuth/client.ts`:
 *   - 授权端点: https://auth.openai.com/oauth/authorize
 *   - token 端点: https://auth.openai.com/oauth/token
 *   - client_id: app_EMoamEEZ73f0CkXaXp7hrann (Codex CLI 官方客户端)
 *
 * 走标准「PKCE 授权码」流程:登录成功后换到的 access_token 即可作为
 * OpenAI API 的 Bearer API Key 使用。与 Grok OAuth 相比,授权请求额外携带
 * `id_token_add_organizations=true` 与 `codex_cli_simplified_flow=true` 两个
 * OpenAI 专有参数(见 cc-haha-main client.ts)。
 */
internal object OpenAIXOAuthConstants {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE_ENDPOINT = "$ISSUER/oauth/authorize"
    const val TOKEN_ENDPOINT = "$ISSUER/oauth/token"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val SCOPE = "openid profile email offline_access"
    const val CALLBACK_PATH = "/auth/callback"
    const val TOKEN_LIFETIME_DEFAULT_SECONDS = 6 * 60 * 60L
    const val TOKEN_EXPIRY_SKEW_MS = 5 * 60_000L
    const val TIMEOUT_MS = 30_000
}

/** 一次 PKCE 授权码会话所需状态。 */
internal class OpenAIOAuthChallenge {
    val state: String = randomBytesHex(32)
    val nonce: String = randomBytesHex(16)
    val codeVerifier: String = randomBytesBase64Url(32)
    val codeChallenge: String = pkceS256(codeVerifier)
}

internal fun randomBytesHex(bytes: Int): String = //
    SecureRandom().run {
        val b = ByteArray(bytes)
        nextBytes(b)
        b.joinToString("") { "%02x".format(it) }
    }

private fun randomBytesBase64Url(bytes: Int): String {
    val b = ByteArray(bytes)
    SecureRandom().nextBytes(b)
    return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
}

internal fun pkceS256(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return android.util.Base64.encodeToString(digest, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
}

/** 构建授权 URL。 [redirectUri] 形如 `http://127.0.0.1:<port>/auth/callback`,必须与 token 交换一致。 */
internal fun buildOpenAIAuthorizeUrl(
    redirectUri: String,
    challenge: OpenAIOAuthChallenge,
): String {
    val params = buildString {
        append("response_type=code")
        append("&client_id=").append(encode(OpenAIXOAuthConstants.CLIENT_ID))
        append("&redirect_uri=").append(encode(redirectUri))
        append("&scope=").append(encode(OpenAIXOAuthConstants.SCOPE))
        append("&state=").append(encode(challenge.state))
        append("&nonce=").append(encode(challenge.nonce))
        append("&code_challenge=").append(encode(challenge.codeChallenge))
        append("&code_challenge_method=S256")
        // OpenAI 专有授权参数(见 cc-haha-main client.ts),Grok 不需要。
        append("&id_token_add_organizations=true")
        append("&codex_cli_simplified_flow=true")
    }
    return "${OpenAIXOAuthConstants.AUTHORIZE_ENDPOINT}?$params"
}

internal fun encode(v: String): String =
    URLEncoder.encode(v, Charsets.UTF_8.name())

/**
 * 用授权码交换 token,返回原始响应字符串;由调用方 [OpenAIXTokenStore] 解析。
 */
internal fun exchangeOpenAICodeForTokens(
    code: String,
    redirectUri: String,
    challenge: OpenAIOAuthChallenge,
): String {
    val body = buildString {
        append("grant_type=authorization_code")
        append("&client_id=").append(encode(OpenAIXOAuthConstants.CLIENT_ID))
        append("&code=").append(encode(code))
        append("&redirect_uri=").append(encode(redirectUri))
        append("&code_verifier=").append(encode(challenge.codeVerifier))
    }
    return postToken(body)
}

/** 用 refresh_token 换取新的 access token,返回原始响应字符串。 */
internal fun refreshOpenAITokens(refreshToken: String): String {
    val body = buildString {
        append("grant_type=refresh_token")
        append("&client_id=").append(encode(OpenAIXOAuthConstants.CLIENT_ID))
        append("&refresh_token=").append(encode(refreshToken))
    }
    return postToken(body)
}

private fun postToken(formBody: String): String {
    val conn = URL(OpenAIXOAuthConstants.TOKEN_ENDPOINT).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.instanceFollowRedirects = true
        conn.connectTimeout = OpenAIXOAuthConstants.TIMEOUT_MS
        conn.readTimeout = OpenAIXOAuthConstants.TIMEOUT_MS
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
        throw IllegalStateException("OpenAI token request failed ($status): ${errBody?.take(500)}")
    } finally {
        conn.disconnect()
    }
}