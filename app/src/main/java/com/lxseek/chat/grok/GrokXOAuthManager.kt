package com.lxseek.chat.grok

import android.content.Context
import android.net.Uri
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.util.Constants
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 登录流程对外暴露的状态。 */
enum class GrokLoginPhase { IDLE, IN_PROGRESS, SUCCESS, FAILED }

data class GrokLoginUiState(
    val phase: GrokLoginPhase = GrokLoginPhase.IDLE,
    val message: String? = null,
)

/**
 * Grok(x.ai) 官方账号登录编排。
 *
 * 与 cc-haha 桌面端 `hahaGrokOAuthService.ts` 对齐的 PKCE 授权码流程。Android 上系统浏览器无法
 * 访问 App 内部的 127.0.0.1 端口,因此不再启动 [java.net.ServerSocket],而是改用 App 内
 * WebView 加载授权 URL,由 [handleCallbackUrl] 处理 WebView 拦截到的回调 URL 完成
 * code → access_token 交换。
 *
 * 登录成功后的 access_token 通过 [SettingsRepository.upsertApiKey] 写入为
 * [Constants.PROVIDER_GROK] 的活动 API Key(x.ai OpenAI 兼容接口直接用该 Bearer)。
 */
class GrokXOAuthManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val tokenStore = GrokXTokenStore(context.applicationContext)
    private val _loginState = MutableStateFlow(GrokLoginUiState())
    val loginState: StateFlow<GrokLoginUiState> = _loginState.asStateFlow()

    /** 当前登录会话的 PKCE challenge,由 [startLogin] 写入、[handleCallbackUrl] 读取后清空。 */
    @Volatile
    private var currentChallenge: GrokOAuthChallenge? = null

    /** 当前登录会话使用的 redirect_uri,供 WebView 判断是否拦截回调。 */
    @Volatile
    private var currentRedirectUri: String? = null

    fun isLoggedIn(): Boolean = tokenStore.load() != null

    fun currentEmail(): String? = tokenStore.load()?.email

    /** 取当前可用 access token(若过期且可刷新,自动刷新并回写)。 */
    fun currentAccessToken(): String? = tokenStore.ensureFresh()?.accessToken

    /**
     * 发起一次登录:生成 PKCE 与固定端口的 redirect_uri,返回授权 URL 供 UI 用 App 内
     * WebView 打开。之后通过 [loginState] 观察完成情况,或由 UI 把 WebView 拦截到的
     * 回调 URL 交给 [handleCallbackUrl]。
     */
    suspend fun startLogin(): Uri? = withContext(Dispatchers.IO) {
        if (_loginState.value.phase == GrokLoginPhase.IN_PROGRESS) return@withContext null
        try {
            val redirectUri = "http://127.0.0.1:$FIXED_CALLBACK_PORT${GrokXOAuthConstants.CALLBACK_PATH}"
            val challenge = GrokOAuthChallenge()
            currentChallenge = challenge
            currentRedirectUri = redirectUri
            _loginState.value = GrokLoginUiState(GrokLoginPhase.IN_PROGRESS)
            Uri.parse(buildGrokAuthorizeUrl(redirectUri, challenge))
        } catch (e: Throwable) {
            DebugLog.e(TAG, "startLogin failed", e)
            currentChallenge = null
            currentRedirectUri = null
            _loginState.value = GrokLoginUiState(GrokLoginPhase.FAILED, e.message ?: "无法启动登录")
            null
        }
    }

    /** 返回当前回调 URL 前缀,供 WebView 判断是否拦截。 */
    fun getCallbackUrlPrefix(): String? = currentRedirectUri

    /** 处理 WebView 拦截到的回调 URL,完成 token 交换。 */
    suspend fun handleCallbackUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        val challenge = currentChallenge ?: return@withContext false
        val redirectUri = currentRedirectUri ?: return@withContext false
        try {
            val uri = Uri.parse(url)
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")
            val error = uri.getQueryParameter("error")
            if (error != null || code.isNullOrBlank()) {
                _loginState.value = GrokLoginUiState(GrokLoginPhase.FAILED, error ?: "缺少授权码")
                return@withContext false
            }
            if (state != null && state != challenge.state) {
                _loginState.value = GrokLoginUiState(GrokLoginPhase.FAILED, "state 校验失败,请重试")
                return@withContext false
            }
            val response = exchangeGrokCodeForTokens(code, redirectUri, challenge)
            val tokens = tokenStore.parseTokenResponse(response)
            tokenStore.save(tokens)
            settings.upsertApiKey(
                name = tokens.email?.takeIf { it.isNotBlank() } ?: "Grok 官方账号",
                key = tokens.accessToken,
                provider = Constants.PROVIDER_GROK,
            )
            _loginState.value = GrokLoginUiState(GrokLoginPhase.SUCCESS, tokens.email)
            true
        } catch (e: Throwable) {
            DebugLog.e(TAG, "handleCallbackUrl failed", e)
            _loginState.value = GrokLoginUiState(GrokLoginPhase.FAILED, e.message ?: "token 交换失败")
            false
        } finally {
            currentChallenge = null
            currentRedirectUri = null
        }
    }

    fun logout() {
        tokenStore.delete()
        _loginState.value = GrokLoginUiState()
    }

    private companion object {
        const val TAG = "GrokXOAuth"
        /** 固定回调端口,仅用于构造 redirect_uri,实际不监听该端口。 */
        const val FIXED_CALLBACK_PORT = 8765
    }
}
