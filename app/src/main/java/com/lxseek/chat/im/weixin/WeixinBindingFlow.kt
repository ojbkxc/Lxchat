package com.lxseek.chat.im.weixin

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 微信 iLink 扫码绑定流程：[begin] 拿二维码 → UI 显示 → [pollUntilConfirmed] 轮询状态
 * → confirmed 后拿 token + baseUrl → 交给 UI/ViewModel 存入 [com.lxseek.chat.im.ImGatewayStore]。
 *
 * 不持有配置存储：绑定成功后返回 [BindingResult]，由调用方持久化。这样本类保持纯协议层，
 * 测试时不需要 DataStore。
 *
 * 典型用法（协程内）：
 * ```
 * val flow = WeixinBindingFlow()
 * val result = flow.bind { event ->
 *     when (event) {
 *         is WeixinBindingFlow.Event.QrcodeReady -> showQr(event.qrcodeUrl)
 *         is WeixinBindingFlow.Event.StatusChanged -> updateStatus(event.status)
 *         is WeixinBindingFlow.Event.Success -> saveConfig(event.token, event.baseUrl)
 *         is WeixinBindingFlow.Event.Failure -> showError(event.error)
 *     }
 * }
 * ```
 */
class WeixinBindingFlow(
    private val api: WeixinIlinkApi = WeixinIlinkApi(),
) {
    /** 绑定成功后的凭据。 */
    data class BindingResult(
        val token: String,
        val baseUrl: String,
    )

    /** 绑定流程事件（[bind] 的回调参数）。 */
    sealed interface Event {
        /** 二维码图片 URL 就绪，UI 应显示二维码。 */
        data class QrcodeReady(val qrcodeUrl: String) : Event
        /** 扫码状态变化（wait / scaned / need_verifycode ...）。 */
        data class StatusChanged(val status: String) : Event
        /** 绑定成功，token + baseUrl 可用。 */
        data class Success(val token: String, val baseUrl: String) : Event
        /** 绑定失败（过期 / 被拒 / 网络错误）。 */
        data class Failure(val error: WeixinApiError) : Event
    }

    /** 第一步：申请扫码二维码。返回二维码令牌 + 图片 URL。 */
    suspend fun begin(
        localTokens: List<String> = emptyList(),
        botType: String = WeixinIlinkApi.DEFAULT_BOT_TYPE,
    ): WeixinIlinkApi.BeginLoginResult = api.beginLogin(localTokens, botType)

    /** 单次轮询扫码状态（长轮询 35s）。 */
    suspend fun pollOnce(
        qrcode: String,
        baseUrl: String = WeixinIlinkApi.WEIXIN_QR_BASE_URL,
        verifyCode: String? = null,
    ): WeixinIlinkApi.LoginStatus = api.pollLogin(qrcode, baseUrl, verifyCode)

    /**
     * 循环轮询直到 confirmed / expired / 错误。[onStatus] 在每次状态变化时回调。
     *
     * pollLogin 本身是 35s 长轮询，正常情况下服务器有状态变化才返回；这里额外加 1s 间隔
     * 作为保险，防止服务器立即返回时 busy loop。
     */
    suspend fun pollUntilConfirmed(
        qrcode: String,
        baseUrl: String = WeixinIlinkApi.WEIXIN_QR_BASE_URL,
        onStatus: (String) -> Unit = {},
    ): WeixinIlinkApi.LoginStatus {
        while (true) {
            coroutineContext.ensureActive()
            val status = api.pollLogin(qrcode, baseUrl)
            onStatus(status.status)
            when (status.status) {
                "confirmed" -> return status
                "expired" -> throw WeixinApiError("login-expired", "二维码已过期，请重新扫码。")
                "verify_code_blocked" ->
                    throw WeixinApiError("verify-blocked", "验证码输入过多，请稍后再试。")
                // wait / scaned / need_verifycode / scaned_but_redirect / binded_redirect → 继续
            }
            delay(1_000L)
        }
    }

    /**
     * 一站式绑定：begin → 显示二维码 → pollUntilConfirmed → 返回 token + baseUrl。
     * 全程通过 [onEvent] 回调通知 UI；异常被捕获并转为 [Event.Failure]。
     *
     * 返回 [BindingResult]（成功）或 null（失败，失败已通过 [Event.Failure] 通知）。
     */
    suspend fun bind(onEvent: (Event) -> Unit): BindingResult? = try {
        val begin = api.beginLogin()
        onEvent(Event.QrcodeReady(begin.qrcodeUrl))
        val confirmed = pollUntilConfirmed(begin.qrcode) { status ->
            onEvent(Event.StatusChanged(status))
        }
        val token = confirmed.token?.takeIf { it.isNotBlank() }
            ?: throw WeixinApiError("no-token", "扫码成功但微信服务未返回 token。")
        val baseUrl = confirmed.baseUrl?.takeIf { it.isNotBlank() }
            ?.let { WeixinIlinkApi.normalizeWeixinApiBaseUrl(it) }
            ?: WeixinIlinkApi.WEIXIN_QR_BASE_URL
        onEvent(Event.Success(token, baseUrl))
        BindingResult(token, baseUrl)
    } catch (e: WeixinApiError) {
        onEvent(Event.Failure(e))
        null
    } catch (e: Exception) {
        onEvent(Event.Failure(WeixinApiError("bind-failed", e.message ?: "扫码绑定失败。", e)))
        null
    }
}