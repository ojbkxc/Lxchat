package com.lxseek.chat.api.oauth

/**
 * Provider 无关的 PKCE「授权码」OAuth 客户端（R1 重构）。
 *
 * OpenAI(ChatGPT) 与 Grok(x.ai) 的 OAuth 流程 95% 同构，差异只有端点、client_id、
 * scope、回调路径与个别专有授权参数——统一收敛到这里，各 Provider 只声明一份
 * [OAuthProviderConfig]。
 *
 * 端点与参数对齐 cc-haha 的 `src/services/openaiAuth/client.ts` 与
 * `src/services/grokAuth/client.ts`：登录成功后换到的 access_token 即可作为
 * 相应 OpenAI 兼容接口的 Bearer API Key 使用。
 */

/** 一个 OAuth 提供商的全部静态差异配置。 */
class OAuthProviderConfig(
    val authorizeEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val scope: String,
    /** 回调路径（拼在 `http://127.0.0.1:<port>` 后构成 redirect_uri）。 */
    val callbackPath: String,
    /** token 端点表单请求超时（毫秒）。 */
    val timeoutMs: Int,
    /** 错误信息里标识提供商用（如 "OpenAI"/"Grok"）。 */
    val errorLabel: String,
    /** 授权 URL 的专有附加参数（如 OpenAI 的 codex_cli_simplified_flow）。 */
    val extraAuthorizeParams: Map<String, String> = emptyMap(),
    /** access token 过期前的提前刷新量（毫秒）。 */
    val tokenExpirySkewMs: Long = DEFAULT_TOKEN_EXPIRY_SKEW_MS,
    /** 响应缺 `expires_in` 时假定的默认有效期（秒）。 */
    val tokenLifetimeDefaultSeconds: Long = DEFAULT_TOKEN_LIFETIME_SECONDS,
) {
    companion object {
        const val DEFAULT_TOKEN_EXPIRY_SKEW_MS = 5 * 60_000L
        const val DEFAULT_TOKEN_LIFETIME_SECONDS = 6 * 60 * 60L
    }
}

/** 一次 PKCE 授权码会话所需状态。 */
internal class OAuthPkceChallenge {
    val state: String = pkceRandomHex(32)
    val nonce: String = pkceRandomHex(16)
    val codeVerifier: String = pkceRandomBase64Url(32)
    val codeChallenge: String = pkceS256(codeVerifier)
}

/** 基于 [OAuthProviderConfig] 的授权 URL 构建与 token 端点请求。 */
internal object OAuthTokenClient {

    /** 构建授权 URL。[redirectUri] 形如 `http://127.0.0.1:<port><callbackPath>`，必须与 token 交换一致。 */
    fun buildAuthorizeUrl(
        config: OAuthProviderConfig,
        redirectUri: String,
        challenge: OAuthPkceChallenge,
    ): String {
        val params = buildString {
            append("response_type=code")
            append("&client_id=").append(formUrlEncode(config.clientId))
            append("&redirect_uri=").append(formUrlEncode(redirectUri))
            append("&scope=").append(formUrlEncode(config.scope))
            append("&state=").append(formUrlEncode(challenge.state))
            append("&nonce=").append(formUrlEncode(challenge.nonce))
            append("&code_challenge=").append(formUrlEncode(challenge.codeChallenge))
            append("&code_challenge_method=S256")
            // 提供商专有授权参数（OpenAI 需要，Grok 为空）。
            config.extraAuthorizeParams.forEach { (k, v) ->
                append('&').append(formUrlEncode(k)).append('=').append(formUrlEncode(v))
            }
        }
        return "${config.authorizeEndpoint}?$params"
    }

    /** 用授权码交换 token，返回原始响应字符串，由 [BaseXTokenStore.parseTokenResponse] 解析。 */
    fun exchangeCodeForTokens(
        config: OAuthProviderConfig,
        code: String,
        redirectUri: String,
        challenge: OAuthPkceChallenge,
    ): String {
        val body = buildString {
            append("grant_type=authorization_code")
            append("&client_id=").append(formUrlEncode(config.clientId))
            append("&code=").append(formUrlEncode(code))
            append("&redirect_uri=").append(formUrlEncode(redirectUri))
            append("&code_verifier=").append(formUrlEncode(challenge.codeVerifier))
        }
        return postFormToken(config.tokenEndpoint, body, config.timeoutMs, config.errorLabel)
    }

    /** 用 refresh_token 换取新的 access token，返回原始响应字符串。 */
    fun refreshTokens(
        config: OAuthProviderConfig,
        refreshToken: String,
    ): String {
        val body = buildString {
            append("grant_type=refresh_token")
            append("&client_id=").append(formUrlEncode(config.clientId))
            append("&refresh_token=").append(formUrlEncode(refreshToken))
        }
        return postFormToken(config.tokenEndpoint, body, config.timeoutMs, config.errorLabel)
    }
}