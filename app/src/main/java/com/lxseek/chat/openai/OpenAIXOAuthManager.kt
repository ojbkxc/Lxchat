package com.lxseek.chat.openai

import android.content.Context
import com.lxseek.chat.api.oauth.BaseXOAuthManager
import com.lxseek.chat.api.oauth.BaseXTokenStore
import com.lxseek.chat.api.oauth.OAuthProviderConfig
import com.lxseek.chat.api.oauth.decodeJwtPayload
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.util.Constants

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** 登录流程对外暴露的状态。 */
enum class OpenAILoginPhase { IDLE, IN_PROGRESS, SUCCESS, FAILED }

data class OpenAILoginUiState(
    val phase: OpenAILoginPhase = OpenAILoginPhase.IDLE,
    val message: String? = null,
)

/**
 * ChatGPT(OpenAI) 官方账号 OAuth 的全部静态差异（端点与参数对齐 cc-haha 的
 * `src/services/openaiAuth/client.ts`）。流程与存储的通用实现见
 * [BaseXOAuthManager] / [BaseXTokenStore]。
 */
private val openAiConfig = OAuthProviderConfig(
    authorizeEndpoint = "https://auth.openai.com/oauth/authorize",
    tokenEndpoint = "https://auth.openai.com/oauth/token",
    clientId = "app_EMoamEEZ73f0CkXaXp7hrann", // Codex CLI 官方客户端
    scope = "openid profile email offline_access",
    callbackPath = "/auth/callback",
    timeoutMs = 30_000,
    errorLabel = "OpenAI",
    extraAuthorizeParams = mapOf(
        "id_token_add_organizations" to "true",
        "codex_cli_simplified_flow" to "true",
    ),
)

/**
 * 从 token 端点响应的 JWT claims 里提取 ChatGPT account id。
 *
 * Mirrors cc-haha-main `extractOpenAIAccountId`: prefers `id_token`, falls back to
 * `access_token`, and reads `chatgpt_account_id` (top-level or nested under
 * `https://api.openai.com/auth`) then `organizations[0].id`。JWT payload 解码统一
 * 走 [decodeJwtPayload]（R2）。
 */
private fun decodeOpenAIAccountId(json: JSONObject): String? {
    val jwt = json.optString("id_token").takeIf { it.isNotBlank() }
        ?: json.optString("access_token").takeIf { it.isNotBlank() }
        ?: return null
    val claims = decodeJwtPayload(jwt) ?: return null
    return claims.optString("chatgpt_account_id").takeIf { it.isNotBlank() }
        // https://api.openai.com/auth -> chatgpt_account_id
        ?: claims.optJSONObject("https://api.openai.com/auth")
            ?.optString("chatgpt_account_id")?.takeIf { it.isNotBlank() }
        // organizations[0].id
        ?: claims.optJSONArray("organizations")
            ?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
}

/** OpenAI 的 token 元数据存储：`filesDir/openai_oauth.json` + accountId 提取。 */
private class OpenAIXTokenStore(context: Context) : BaseXTokenStore(context, openAiConfig) {
    override val fileName: String = "openai_oauth.json"
    override val tag: String = "OpenAIXTokenStore"

    override fun parseProviderClaims(json: JSONObject): String? = decodeOpenAIAccountId(json)
}

/**
 * ChatGPT(OpenAI) 官方账号登录编排（薄壳）。
 *
 * 登录成功后的 access token 通过 [SettingsRepository.upsertApiKey] 写入为
 * [Constants.PROVIDER_CHATGPT] 的活动 API Key，供 Codex Responses API 的
 * [com.lxseek.chat.api.openai.OpenAIXProvider] 使用；refresh 元数据由
 * [OpenAIXTokenStore] 持久化。
 */
class OpenAIXOAuthManager(
    context: Context,
    settings: SettingsRepository,
    scope: CoroutineScope,
) : BaseXOAuthManager<OpenAILoginUiState>(
    context,
    settings,
    scope,
    openAiConfig,
    OpenAIXTokenStore(context),
) {
    override val tag: String = "OpenAIXOAuth"
    override val providerName: String = Constants.PROVIDER_CHATGPT
    override val defaultAccountName: String = "ChatGPT 官方账号"

    val loginState: StateFlow<OpenAILoginUiState> get() = baseLoginState

    override fun idleState(): OpenAILoginUiState = OpenAILoginUiState()
    override fun inProgressState(): OpenAILoginUiState =
        OpenAILoginUiState(OpenAILoginPhase.IN_PROGRESS)
    override fun successState(message: String?): OpenAILoginUiState =
        OpenAILoginUiState(OpenAILoginPhase.SUCCESS, message)
    override fun failedState(message: String?): OpenAILoginUiState =
        OpenAILoginUiState(OpenAILoginPhase.FAILED, message)
}
