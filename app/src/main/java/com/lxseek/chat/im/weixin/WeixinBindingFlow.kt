package com.lxseek.chat.im.weixin

import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
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
     *
     * P1-4: [onNeedVerifyCode] 在服务端返回 `need_verifycode` 状态时被调用，调用方
     * （UI 层 Compose Dialog）通过它弹窗让用户输入配对码并返回；返回非空时带 verifyCode
     * 继续轮询，返回 null（UI 未实现或用户取消）时保持兼容继续轮询等下次。
     * 参考weixin-ClawBot-API bot.py:1114-1121 终端读取配对码。
     */
    suspend fun pollUntilConfirmed(
        qrcode: String,
        baseUrl: String = WeixinIlinkApi.WEIXIN_QR_BASE_URL,
        onStatus: (String) -> Unit = {},
        onNeedVerifyCode: suspend () -> String? = { null },
    ): WeixinIlinkApi.LoginStatus {
        // P2-5: baseUrl 可变，scaned_but_redirect 时切换到新 baseUrl 继续轮询
        var currentBaseUrl = baseUrl
        // P1-4: 用户输入的配对码；非空时下次 pollLogin 带上 verify_code 提交。
        var pendingVerifyCode: String? = null
        while (true) {
            coroutineContext.ensureActive()
            val status = if (pendingVerifyCode != null) {
                api.pollLogin(qrcode, currentBaseUrl, verifyCode = pendingVerifyCode)
            } else {
                api.pollLogin(qrcode, currentBaseUrl)
            }
            onStatus(status.status)
            when (status.status) {
                "confirmed" -> return status
                "expired" -> throw WeixinApiError("login-expired", "二维码已过期，请重新扫码。")
                "verify_code_blocked" ->
                    throw WeixinApiError("verify-blocked", "验证码输入过多，请稍后再试。")
                // P1-4: 服务端要求配对验证码——调回调拿用户输入，非空则下次带上提交，
                // null 则继续轮询等下次（UI 未实现时保持兼容，不会卡死）。
                "need_verifycode" -> {
                    val code = try {
                        onNeedVerifyCode()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DebugLog.w("WeixinBindingFlow", "onNeedVerifyCode failed: ${e.message}")
                        null
                    }
                    pendingVerifyCode = code?.takeIf { it.isNotBlank() }
                    if (pendingVerifyCode == null) {
                        DebugLog.d("WeixinBindingFlow", "need_verifycode: no code provided, keep polling")
                    }
                }
                // P2-5: scaned_but_redirect 切换到服务端返回的新 baseUrl 继续轮询
                // （参考 weixin-ClawBot-API bot.py:1051-1057,1104-1107）
                "scaned_but_redirect" -> {
                    status.baseUrl?.takeIf { it.isNotBlank() }?.let { newUrl ->
                        runCatching { WeixinIlinkApi.normalizeWeixinApiBaseUrl(newUrl) }
                            .getOrNull()?.let { currentBaseUrl = it }
                    }
                }
                // P2-5: binded_redirect 视为已完成，复用本地 token
                // （参考 weixin-ClawBot-API bot.py:1166-1177）
                "binded_redirect" -> {
                    if (!status.token.isNullOrBlank()) return status
                    // token 为空时无法复用，继续轮询等服务端返回 token
                }
                // wait / scaned → 继续
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
        DebugLog.d("WeixinBindingFlow", "开始扫码绑定，调用 beginLogin...")
        val begin = api.beginLogin()
        DebugLog.d(
            "WeixinBindingFlow",
            "beginLogin 成功: qrcode=${begin.qrcode.take(10)}... url=${begin.qrcodeUrl}",
        )
        onEvent(Event.QrcodeReady(begin.qrcodeUrl))
        val confirmed = pollUntilConfirmed(
            begin.qrcode,
            onStatus = { status -> onEvent(Event.StatusChanged(status)) },
        )
        val token = confirmed.token?.takeIf { it.isNotBlank() }
            ?: throw WeixinApiError("no-token", "扫码成功但微信服务未返回 token。")
        val baseUrl = confirmed.baseUrl?.takeIf { it.isNotBlank() }
            ?.let { WeixinIlinkApi.normalizeWeixinApiBaseUrl(it) }
            ?: WeixinIlinkApi.WEIXIN_QR_BASE_URL
        DebugLog.d("WeixinBindingFlow", "扫码确认成功: token=${token.take(10)}... baseUrl=$baseUrl")
        onEvent(Event.Success(token, baseUrl))
        BindingResult(token, baseUrl)
    } catch (e: WeixinApiError) {
        DebugLog.e("WeixinBindingFlow", "扫码绑定失败(WeixinApiError): ${e.code} - ${e.message}", e)
        onEvent(Event.Failure(e))
        null
    } catch (e: Exception) {
        DebugLog.e("WeixinBindingFlow", "扫码绑定失败(Exception): ${e.message}", e)
        onEvent(Event.Failure(WeixinApiError("bind-failed", e.message ?: "扫码绑定失败。", e)))
        null
    }
}