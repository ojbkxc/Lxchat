package com.lxseek.chat.grok

import com.lxseek.chat.api.oauth.formUrlEncode
import com.lxseek.chat.api.oauth.pkceRandomBase64Url
import com.lxseek.chat.api.oauth.pkceRandomHex
import com.lxseek.chat.api.oauth.pkceS256
import com.lxseek.chat.api.oauth.postFormToken

/**
 * Grok(x.ai) 官方账号 OAuth 客户端。
 *
 * 端点与参数对齐 cc-haha 的 `src/services/grokAuth/client.ts`:
 *   - 授权端点: https://auth.x.ai/oauth2/authorize
 *   - token 端点: https://auth.x.ai/oauth2/token
 *   - client_id: b1a00492-073a-47ea-816f-4c329264a828 (Grok CLI 官方客户端)
 *
 * 走标准「PKCE 授权码」流程:登录成功后换到的 access_token 即可作为
 * x.ai OpenAI 兼容接口的 Bearer API Key 使用。
 */
internal object GrokXOAuthConstants {
    const val ISSUER = "https://auth.x.ai"
    const val AUTHORIZE_ENDPOINT = "$ISSUER/oauth2/authorize"
    const val TOKEN_ENDPOINT = "$ISSUER/oauth2/token"
    const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    const val SCOPE =
        "openid profile email offline_access grok-cli:access api:access conversations:read conversations:write"
    const val CALLBACK_PATH = "/callback"
    const val TOKEN_LIFETIME_DEFAULT_SECONDS = 6 * 60 * 60L
    const val TOKEN_EXPIRY_SKEW_MS = 5 * 60_000L
    const val TIMEOUT_MS = 30_000
}

/** 一次 PKCE 授权码会话所需状态。 */
internal class GrokOAuthChallenge {
    val state: String = pkceRandomHex(32)
    val nonce: String = pkceRandomHex(16)
    val codeVerifier: String = pkceRandomBase64Url(32)
    val codeChallenge: String = pkceS256(codeVerifier)
}

/** 构建授权 URL。 [redirectUri] 形如 `http://127.0.0.1:<port>/callback`,必须与 token 交换一致。 */
internal fun buildGrokAuthorizeUrl(
    redirectUri: String,
    challenge: GrokOAuthChallenge,
): String {
    val params = buildString {
        append("response_type=code")
        append("&client_id=").append(formUrlEncode(GrokXOAuthConstants.CLIENT_ID))
        append("&redirect_uri=").append(formUrlEncode(redirectUri))
        append("&scope=").append(formUrlEncode(GrokXOAuthConstants.SCOPE))
        append("&state=").append(formUrlEncode(challenge.state))
        append("&nonce=").append(formUrlEncode(challenge.nonce))
        append("&code_challenge=").append(formUrlEncode(challenge.codeChallenge))
        append("&code_challenge_method=S256")
    }
    return "${GrokXOAuthConstants.AUTHORIZE_ENDPOINT}?$params"
}

/**
 * 用授权码交换 token,返回原始响应字符串;由调用方 [GrokXTokenStore] 解析。
 */
internal fun exchangeGrokCodeForTokens(
    code: String,
    redirectUri: String,
    challenge: GrokOAuthChallenge,
): String {
    val body = buildString {
        append("grant_type=authorization_code")
        append("&client_id=").append(formUrlEncode(GrokXOAuthConstants.CLIENT_ID))
        append("&code=").append(formUrlEncode(code))
        append("&redirect_uri=").append(formUrlEncode(redirectUri))
        append("&code_verifier=").append(formUrlEncode(challenge.codeVerifier))
    }
    return postFormToken(GrokXOAuthConstants.TOKEN_ENDPOINT, body, GrokXOAuthConstants.TIMEOUT_MS, "Grok")
}

/** 用 refresh_token 换取新的 access token,返回原始响应字符串。 */
internal fun refreshGrokTokens(refreshToken: String): String {
    val body = buildString {
        append("grant_type=refresh_token")
        append("&client_id=").append(formUrlEncode(GrokXOAuthConstants.CLIENT_ID))
        append("&refresh_token=").append(formUrlEncode(refreshToken))
    }
    return postFormToken(GrokXOAuthConstants.TOKEN_ENDPOINT, body, GrokXOAuthConstants.TIMEOUT_MS, "Grok")
}
