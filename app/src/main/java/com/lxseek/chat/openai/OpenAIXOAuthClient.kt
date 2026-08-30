package com.lxseek.chat.openai

import com.lxseek.chat.api.oauth.formUrlEncode
import com.lxseek.chat.api.oauth.pkceRandomBase64Url
import com.lxseek.chat.api.oauth.pkceRandomHex
import com.lxseek.chat.api.oauth.pkceS256
import com.lxseek.chat.api.oauth.postFormToken

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
    val state: String = pkceRandomHex(32)
    val nonce: String = pkceRandomHex(16)
    val codeVerifier: String = pkceRandomBase64Url(32)
    val codeChallenge: String = pkceS256(codeVerifier)
}

/** 构建授权 URL。 [redirectUri] 形如 `http://127.0.0.1:<port>/auth/callback`,必须与 token 交换一致。 */
internal fun buildOpenAIAuthorizeUrl(
    redirectUri: String,
    challenge: OpenAIOAuthChallenge,
): String {
    val params = buildString {
        append("response_type=code")
        append("&client_id=").append(formUrlEncode(OpenAIXOAuthConstants.CLIENT_ID))
        append("&redirect_uri=").append(formUrlEncode(redirectUri))
        append("&scope=").append(formUrlEncode(OpenAIXOAuthConstants.SCOPE))
        append("&state=").append(formUrlEncode(challenge.state))
        append("&nonce=").append(formUrlEncode(challenge.nonce))
        append("&code_challenge=").append(formUrlEncode(challenge.codeChallenge))
        append("&code_challenge_method=S256")
        // OpenAI 专有授权参数(见 cc-haha-main client.ts),Grok 不需要。
        append("&id_token_add_organizations=true")
        append("&codex_cli_simplified_flow=true")
    }
    return "${OpenAIXOAuthConstants.AUTHORIZE_ENDPOINT}?$params"
}

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
        append("&client_id=").append(formUrlEncode(OpenAIXOAuthConstants.CLIENT_ID))
        append("&code=").append(formUrlEncode(code))
        append("&redirect_uri=").append(formUrlEncode(redirectUri))
        append("&code_verifier=").append(formUrlEncode(challenge.codeVerifier))
    }
    return postFormToken(OpenAIXOAuthConstants.TOKEN_ENDPOINT, body, OpenAIXOAuthConstants.TIMEOUT_MS, "OpenAI")
}

/** 用 refresh_token 换取新的 access token,返回原始响应字符串。 */
internal fun refreshOpenAITokens(refreshToken: String): String {
    val body = buildString {
        append("grant_type=refresh_token")
        append("&client_id=").append(formUrlEncode(OpenAIXOAuthConstants.CLIENT_ID))
        append("&refresh_token=").append(formUrlEncode(refreshToken))
    }
    return postFormToken(OpenAIXOAuthConstants.TOKEN_ENDPOINT, body, OpenAIXOAuthConstants.TIMEOUT_MS, "OpenAI")
}
