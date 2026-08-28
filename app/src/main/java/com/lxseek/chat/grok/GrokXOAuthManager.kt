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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.ServerSocket

/** 登录流程对外暴露的状态。 */
enum class GrokLoginPhase { IDLE, IN_PROGRESS, SUCCESS, FAILED }

data class GrokLoginUiState(
    val phase: GrokLoginPhase = GrokLoginPhase.IDLE,
    val message: String? = null,
)

/**
 * Grok(x.ai) 官方账号登录编排。
 *
 * 与 cc-haha 桌面端 `hahaGrokOAuthService.ts` 对齐的 PKCE 授权码流程,但回调宿主改到
 * Android 上的本地 [ServerSocket]:应用在 127.0.0.1 上开一个随机端口,把授权 URL
 * 交给系统浏览器;用户在 auth.x.ai 完成授权后,浏览器把 `http://127.0.0.1:PORT/callback?code=...&state=...`
 * 发起给本地端口,这里的回调服务收到后完成 code → access_token 交换,并返回一个
 * “登录成功,可关闭本页” 的 HTML 页面。
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

    fun isLoggedIn(): Boolean = tokenStore.load() != null

    fun currentEmail(): String? = tokenStore.load()?.email

    /** 取当前可用 access token(若过期且可刷新,自动刷新并回写)。 */
    fun currentAccessToken(): String? = tokenStore.ensureFresh()?.accessToken

    /**
     * 发起一次登录:绑定本地回调端口、生成 PKCE,返回授权 URL 供 UI 用浏览器打开。
     * 之后通过 [loginState] 观察完成情况。
     */
    suspend fun startLogin(): Uri? = withContext(Dispatchers.IO) {
        if (_loginState.value.phase == GrokLoginPhase.IN_PROGRESS) return@withContext null
        var socket: ServerSocket? = null
        try {
            socket = ServerSocket(0, 1, java.net.Inet4Address.getByName("127.0.0.1"))
            val port = socket.localPort
            val redirectUri = "http://127.0.0.1:$port${GrokXOAuthConstants.CALLBACK_PATH}"
            val challenge = GrokOAuthChallenge()
            _loginState.value = GrokLoginUiState(GrokLoginPhase.IN_PROGRESS)

            val server = socket
            scope.launch(Dispatchers.IO) {
                try {
                    handleCallback(server, redirectUri, challenge)
                } catch (e: Throwable) {
                    DebugLog.e(TAG, "callback handler failed", e)
                    runCatching { server.close() }
                    _loginState.value = GrokLoginUiState(
                        GrokLoginPhase.FAILED,
                        e.message ?: "登录回调失败",
                    )
                }
            }
            Uri.parse(buildGrokAuthorizeUrl(redirectUri, challenge))
        } catch (e: Throwable) {
            DebugLog.e(TAG, "startLogin failed", e)
            runCatching { socket?.close() }
            _loginState.value = GrokLoginUiState(GrokLoginPhase.FAILED, e.message ?: "无法启动登录")
            null
        }
    }

    private fun handleCallback(
        server: ServerSocket,
        redirectUri: String,
        challenge: GrokOAuthChallenge,
    ) {
        val client = server.accept() ?: return
        try {
            val reader = BufferedReader(client.getInputStream().reader(Charsets.UTF_8))
            val requestLine = reader.readLine() ?: throw IllegalStateException("空回调请求")
            // 形如 GET /callback?code=..&state=.. HTTP/1.1
            val target = requestLine.split(' ').getOrNull(1) ?: "/"
            val pathAndQuery = target.substringBefore('?')
            val code = Uri.parse(target).getQueryParameter("code")
            val state = Uri.parse(target).getQueryParameter("state")
            val error = Uri.parse(target).getQueryParameter("error")

            if (error != null || code.isNullOrBlank() || state.isNullOrBlank()) {
                respondHtml(client, renderPage(success = false, message = "授权被拒绝或缺少参数"))
                throw IllegalStateException(error ?: "authorization missing code/state")
            }
            if (state != challenge.state) {
                respondHtml(client, renderPage(success = false, message = "state 校验失败,请重试"))
                throw IllegalStateException("state mismatch")
            }

            val response = exchangeGrokCodeForTokens(code, redirectUri, challenge)
            val tokens = tokenStore.parseTokenResponse(response)
            tokenStore.save(tokens)
            settings.upsertApiKey(
                name = tokens.email?.takeIf { it.isNotBlank() } ?: "Grok 官方账号",
                key = tokens.accessToken,
                provider = Constants.PROVIDER_GROK,
            )
            respondHtml(client, renderPage(success = true, message = tokens.email))
            _loginState.value = GrokLoginUiState(GrokLoginPhase.SUCCESS, tokens.email)
        } finally {
            runCatching { server.close() }
            runCatching { client.close() }
        }
    }

    fun logout() {
        tokenStore.delete()
        _loginState.value = GrokLoginUiState()
    }

    private fun respondHtml(client: java.net.Socket, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val writer = client.getOutputStream().bufferedWriter(Charsets.UTF_8)
        writer.write("HTTP/1.1 200 OK\r\n")
        writer.write("Content-Type: text/html; charset=utf-8\r\n")
        writer.write("Cache-Control: no-store\r\n")
        writer.write("Content-Length: ${body.size}\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.flush()
        client.getOutputStream().write(body)
        client.getOutputStream().flush()
    }

    private fun renderPage(success: Boolean, message: String?): String {
        return if (success) {
            val email = message?.let { " ($it)" } ?: ""
            """<!doctype html><html><head><meta charset="utf-8"><title>Grok Login Success</title>
<style>body{font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#fafafa;color:#333}.card{text-align:center;padding:40px;background:white;border-radius:12px;box-shadow:0 4px 16px rgba(0,0,0,.06)}h1{color:#16a34a;margin:0 0 12px}p{color:#666}</style>
</head><body><div class="card"><h1>✓ Grok 登录成功</h1><p>账号授权完成$email,可关闭本页返回 LxChat。</p></div><script>setTimeout(function(){window.close()},1500)</script></body></html>"""
        } else {
            """<!doctype html><html><head><meta charset="utf-8"><title>Grok Login Failed</title>
<style>body{font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#fafafa;color:#333}.card{text-align:center;padding:40px;background:white;border-radius:12px;box-shadow:0 4px 16px rgba(0,0,0,.06)}h1{color:#dc2626;margin:0 0 12px}pre{color:#666;white-space:pre-wrap;text-align:left;background:#f5f5f5;padding:12px;border-radius:6px}</style>
</head><body><div class="card"><h1>✗ Grok 登录失败</h1><pre>${(message ?: "未知错误").replace("<", "&lt;")}</pre></div></body></html>"""
        }
    }

    private companion object {
        const val TAG = "GrokXOAuth"
    }
}