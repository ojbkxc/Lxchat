package com.lxseek.chat.api.oauth

import android.content.Context
import android.net.Uri
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provider 无关的 OAuth 登录编排基类（R1 重构）。
 *
 * 与 cc-haha 桌面端对齐的 PKCE 授权码流程。Android 上系统浏览器无法访问 App
 * 内部的 127.0.0.1 端口，因此不启动 [java.net.ServerSocket]，而是改用 App 内
 * WebView 加载授权 URL，由 [handleCallbackUrl] 处理 WebView 拦截到的回调 URL 完成
 * code → access_token 交换。
 *
 * 子类只需声明 [providerName]/[defaultAccountName]/[tag] 与四种登录 UI 状态的
 * 构造方式（各自的 enum/data class 保持对外不变），端点等差异全部注入
 * [OAuthProviderConfig]。
 *
 * M4 修复：access token 明文的唯一持久层是 SettingsRepository 的活动 API Key
 * （Provider 请求路径从那里取值），[BaseXTokenStore] 只持久化 refresh 元数据；
 * 登录与刷新后由这里负责把最新 access token 同步进 settings，消除「双份落盘
 * 且互不一致」的问题。
 *
 * @param S 该 Provider 对外暴露的登录 UI 状态类型
 */
abstract class BaseXOAuthManager<S>(
    context: Context,
    private val settings: SettingsRepository,
    // 保留构造参数以兼容既有调用方（AppContainer 传入 appScope）；基类不再直接使用。
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
    protected val config: OAuthProviderConfig,
    protected val tokenStore: BaseXTokenStore,
) {
    protected val appContext: Context = context.applicationContext

    /** 日志 TAG，如 "OpenAIXOAuth" / "GrokXOAuth"。 */
    protected abstract val tag: String

    /** Provider 常量：Constants.PROVIDER_CHATGPT / Constants.PROVIDER_GROK。 */
    protected abstract val providerName: String

    /** settings 里 API Key 条目的默认显示名。 */
    protected abstract val defaultAccountName: String

    // ── 登录 UI 状态（四态工厂，子类用各自的 enum/data class 实现） ──

    protected abstract fun idleState(): S
    protected abstract fun inProgressState(): S
    protected abstract fun successState(message: String?): S
    protected abstract fun failedState(message: String?): S

    private val _loginState: MutableStateFlow<S> by lazy { MutableStateFlow(idleState()) }

    /** 供子类以自己的状态类型对外暴露。 */
    protected val baseLoginState: StateFlow<S> get() = _loginState.asStateFlow()

    /** 当前登录会话的 PKCE challenge，由 [startLogin] 写入、[handleCallbackUrl] 读取后清空。 */
    @Volatile
    private var currentChallenge: OAuthPkceChallenge? = null

    /** 当前登录会话使用的 redirect_uri，供 WebView 判断是否拦截回调。 */
    @Volatile
    private var currentRedirectUri: String? = null

    /**
     * M6 修复：登录会话互斥。CAS 原子化「检查-启动」，杜绝并发 startLogin 双开
     * 授权会话导致的 challenge 互相覆盖。
     */
    private val loginInProgress = AtomicBoolean(false)

    /**
     * H2 修复：刷新临界区——把「检查-刷新-落盘」整段串行化。多协程并发请求
     * token 时只有一个真正执行刷新，其余排队后命中快路径，杜绝旋转式
     * refresh token 互相作废（invalid_grant → 永久登出）。
     */
    private val refreshMutex = Mutex()

    fun isLoggedIn(): Boolean = tokenStore.load() != null

    fun currentEmail(): String? = tokenStore.load()?.email

    /** Provider 专属账号标识（ChatGPT 的 chatgpt_account_id；未登录/无则为 null）。 */
    fun currentAccountId(): String? = tokenStore.load()?.accountId

    /**
     * 取当前可用 access token：未过期时直接读 settings 的活动 API Key（唯一
     * 持久层）；临近过期则刷新并把新 token 同步回 settings 与 oauth 元数据文件。
     *
     * H2：落盘失败会让 [BaseXTokenStore.save] 抛异常，这里捕获后返回 null——
     * 令本次刷新结果失效，而不是拿着「内存认为已保存」的旧值继续跑。
     */
    suspend fun currentAccessToken(): String? = try {
        refreshMutex.withLock {
            val meta = tokenStore.load()
            when {
                meta == null -> null
                // 快路径：尚未到期。
                meta.expiresAt == null ||
                    meta.expiresAt - System.currentTimeMillis() > config.tokenExpirySkewMs ->
                    settings.awaitActiveKey(providerName)
                else -> refreshAccessTokenLocked(meta)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        // 协程取消必须继续向上传播，不能当作刷新失败吞掉。
        throw e
    } catch (e: Exception) {
        DebugLog.e(tag, "token refresh failed", e)
        null
    }

    /**
     * 发起一次登录：生成 PKCE 与固定端口的 redirect_uri，返回授权 URL 供 UI 用
     * App 内 WebView 打开。之后通过子类暴露的 loginState 观察完成情况，或由 UI
     * 把 WebView 拦截到的回调 URL 交给 [handleCallbackUrl]。
     */
    suspend fun startLogin(): Uri? = withContext(Dispatchers.IO) {
        // M6：CAS 保证同一时刻只有一个进行中的登录会话。
        if (!loginInProgress.compareAndSet(false, true)) return@withContext null
        try {
            val redirectUri = "http://127.0.0.1:$FIXED_CALLBACK_PORT${config.callbackPath}"
            val challenge = OAuthPkceChallenge()
            currentChallenge = challenge
            currentRedirectUri = redirectUri
            _loginState.value = inProgressState()
            Uri.parse(OAuthTokenClient.buildAuthorizeUrl(config, redirectUri, challenge))
        } catch (e: Throwable) {
            DebugLog.e(tag, "startLogin failed", e)
            currentChallenge = null
            currentRedirectUri = null
            loginInProgress.set(false)
            _loginState.value = failedState(e.message ?: "无法启动登录")
            null
        }
    }

    /** 返回当前回调 URL 前缀，供 WebView 判断是否拦截。 */
    fun getCallbackUrlPrefix(): String? = currentRedirectUri

    /** 处理 WebView 拦截到的回调 URL，完成 token 交换。 */
    suspend fun handleCallbackUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        val challenge = currentChallenge ?: return@withContext false
        val redirectUri = currentRedirectUri ?: return@withContext false
        try {
            val uri = Uri.parse(url)
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")
            val error = uri.getQueryParameter("error")
            if (error != null || code.isNullOrBlank()) {
                _loginState.value = failedState(error ?: "缺少授权码")
                return@withContext false
            }
            // M1 修复：state 必须存在且匹配，缺失或不匹配一律拒绝，防 CSRF。
            if (state != challenge.state) {
                _loginState.value = failedState("state 校验失败,请重试")
                return@withContext false
            }
            val response = OAuthTokenClient.exchangeCodeForTokens(config, code, redirectUri, challenge)
            val tokens = tokenStore.parseTokenResponse(response)
            // M4：oauth 元数据文件只存 refresh 元数据；access token 唯一落盘到
            // settings 活动 API Key。save 失败会抛异常 → 走 FAILED，不会出现
            // 「内存认为登录成功、磁盘实际没写」的不一致。
            tokenStore.save(tokens.toStored())
            settings.upsertApiKey(
                name = tokens.email?.takeIf { it.isNotBlank() } ?: defaultAccountName,
                key = tokens.accessToken,
                provider = providerName,
            )
            _loginState.value = successState(tokens.email)
            true
        } catch (e: Throwable) {
            DebugLog.e(tag, "handleCallbackUrl failed", e)
            _loginState.value = failedState(e.message ?: "token 交换失败")
            false
        } finally {
            currentChallenge = null
            currentRedirectUri = null
            loginInProgress.set(false)
        }
    }

    /**
     * Cancel an in-flight login session (e.g. the user closed the auth WebView).
     *
     * Resets [loginInProgress], clears the pending PKCE challenge / redirect URI
     * and moves the login state back to idle. Without this the CAS flag stays
     * set forever after the dialog is dismissed, permanently disabling the
     * login button until app restart.
     *
     * Idempotent: a no-op when no login session is in progress, so calling it
     * after a completed (successful or failed) login never disturbs the
     * logged-in state.
     */
    fun cancelLogin() {
        // CAS ensures we only tear down when a session is actually in progress;
        // otherwise this is a harmless no-op (idempotent).
        if (!loginInProgress.compareAndSet(true, false)) return
        currentChallenge = null
        currentRedirectUri = null
        _loginState.value = idleState()
    }

    fun logout() {
        tokenStore.delete()
        _loginState.value = idleState()
    }

    /**
     * 刷新临界区内（[refreshMutex] 已持有）：用 refresh token 换新 token 并同步
     * settings 与元数据文件。返回新的 access token，或无 refresh token 时 null。
     */
    private suspend fun refreshAccessTokenLocked(meta: StoredOAuthTokens): String? {
        val refresh = meta.refreshToken ?: return null
        val raw = OAuthTokenClient.refreshTokens(config, refresh)
        val tokens = tokenStore.parseTokenResponse(raw, meta)
        // 先写元数据（失败即抛），成功后再同步 settings，保证两处不出现倒挂。
        tokenStore.save(tokens.toStored())
        settings.upsertApiKey(
            name = tokens.email?.takeIf { it.isNotBlank() } ?: defaultAccountName,
            key = tokens.accessToken,
            provider = providerName,
        )
        return tokens.accessToken
    }

    private companion object {
        /** 固定回调端口，仅用于构造 redirect_uri，实际不监听该端口。 */
        const val FIXED_CALLBACK_PORT = 8765
    }
}