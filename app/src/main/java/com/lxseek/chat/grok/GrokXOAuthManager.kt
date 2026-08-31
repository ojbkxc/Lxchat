package com.lxseek.chat.grok

import android.content.Context
import com.lxseek.chat.api.oauth.BaseXOAuthManager
import com.lxseek.chat.api.oauth.BaseXTokenStore
import com.lxseek.chat.api.oauth.OAuthProviderConfig
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** 登录流程对外暴露的状态。 */
enum class GrokLoginPhase { IDLE, IN_PROGRESS, SUCCESS, FAILED }

data class GrokLoginUiState(
    val phase: GrokLoginPhase = GrokLoginPhase.IDLE,
    val message: String? = null,
)

/**
 * Grok(x.ai) 官方账号 OAuth 的全部静态差异（端点与参数对齐 cc-haha 的
 * `src/services/grokAuth/client.ts`）。流程与存储的通用实现见
 * [BaseXOAuthManager] / [BaseXTokenStore]。
 */
private val grokConfig = OAuthProviderConfig(
    authorizeEndpoint = "https://auth.x.ai/oauth2/authorize",
    tokenEndpoint = "https://auth.x.ai/oauth2/token",
    clientId = "b1a00492-073a-47ea-816f-4c329264a828", // Grok CLI 官方客户端
    scope =
        "openid profile email offline_access grok-cli:access api:access conversations:read conversations:write",
    callbackPath = "/callback",
    timeoutMs = 30_000,
    errorLabel = "Grok",
)

/** Grok 的 token 元数据存储：`filesDir/grok_oauth.json`（无 Provider 专属 claim）。 */
private class GrokXTokenStore(context: Context) : BaseXTokenStore(context, grokConfig) {
    override val fileName: String = "grok_oauth.json"
    override val tag: String = "GrokXTokenStore"
}

/**
 * Grok(x.ai) 官方账号登录编排（薄壳）。
 *
 * 登录成功后的 access token 通过 [SettingsRepository.upsertApiKey] 写入为
 * [Constants.PROVIDER_GROK] 的活动 API Key（x.ai OpenAI 兼容接口直接用该
 * Bearer），refresh 元数据由 [GrokXTokenStore] 持久化。
 */
class GrokXOAuthManager(
    context: Context,
    settings: SettingsRepository,
    scope: CoroutineScope,
) : BaseXOAuthManager<GrokLoginUiState>(
    context,
    settings,
    scope,
    grokConfig,
    GrokXTokenStore(context),
) {
    override val tag: String = "GrokXOAuth"
    override val providerName: String = Constants.PROVIDER_GROK
    override val defaultAccountName: String = "Grok 官方账号"

    val loginState: StateFlow<GrokLoginUiState> get() = baseLoginState

    override fun idleState(): GrokLoginUiState = GrokLoginUiState()
    override fun inProgressState(): GrokLoginUiState =
        GrokLoginUiState(GrokLoginPhase.IN_PROGRESS)
    override fun successState(message: String?): GrokLoginUiState =
        GrokLoginUiState(GrokLoginPhase.SUCCESS, message)
    override fun failedState(message: String?): GrokLoginUiState =
        GrokLoginUiState(GrokLoginPhase.FAILED, message)
}
