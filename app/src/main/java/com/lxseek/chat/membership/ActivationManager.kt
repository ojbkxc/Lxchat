package com.lxseek.chat.membership

import android.content.Context

/**
 * 激活管理器：封装 [CloudApi] + 本地验证 + 设备身份证获取。
 *
 * UI 层（如 [com.lxseek.chat.ui.settings.SettingsMembershipPage]）只与本类交互，
 * 不直接接触 [CloudApi] / [SignedCredential] / [DeviceIdCard]。
 *
 * - [activate]：激活码激活（联网，POST /api/activate_by_code）。
 * - [trial]：首次免费三天试用（联网，POST /api/trial）。
 * - [activateByOrder]：订单激活（联网，POST /api/activate_by_order，服务器查订单确认已支付）。
 * - [renew]：续费（联网，POST /api/renew）。
 * - [verifyLocal]：纯离线验证，读本地凭证 → 验证签名 + 设备 ID 匹配 + 未过期。
 * - [verifyRemote]：联网验证（POST /api/verify），服务器确认凭证有效。
 * - [deactivate]：解绑本设备（本地清除凭证）。
 * - [getDeviceIdDisplay]：获取格式化设备身份证，供设置页显示。
 *
 * 默认用 [RemoteCloudApi]（调 activate.lxseek.com）。需要离线兜底时可注入 [LocalCloudApi]。
 */
class ActivationManager(
    private val cloudApi: CloudApi,
    private val context: Context,
) {

    /** 激活码激活（联网：POST /api/activate_by_code）。 */
    suspend fun activate(code: String): ActivationResult {
        val deviceId = DeviceIdCard.getDeviceId(context)
        return cloudApi.activate(code, deviceId)
    }

    /**
     * 首次免费三天试用（联网：POST /api/trial）。
     *
     * 仅 [RemoteCloudApi] 支持；若注入的是 [LocalCloudApi] 则返回 [ActivationResult.NetworkError]。
     * 成功后本地标记已用过试用（[isTrialUsed]），UI 据此隐藏试用按钮。
     */
    suspend fun trial(): ActivationResult {
        val deviceId = DeviceIdCard.getDeviceId(context)
        val result = if (cloudApi is RemoteCloudApi) {
            cloudApi.trial(deviceId)
        } else {
            ActivationResult.NetworkError
        }
        if (result is ActivationResult.Success) {
            markTrialUsed()
        }
        return result
    }

    /**
     * 订单激活（联网：POST /api/activate_by_order）。
     *
     * DeepLink 回调后调用：服务器查订单确认已支付 → 返回签名凭证。
     * 仅 [RemoteCloudApi] 支持；若注入的是 [LocalCloudApi] 则返回 [ActivationResult.NetworkError]。
     */
    suspend fun activateByOrder(outTradeNo: String): ActivationResult {
        val deviceId = DeviceIdCard.getDeviceId(context)
        return if (cloudApi is RemoteCloudApi) {
            cloudApi.activateByOrder(deviceId, outTradeNo)
        } else {
            ActivationResult.NetworkError
        }
    }

    /**
     * 续费（联网：POST /api/renew）。
     *
     * 已激活但快到期时调用：服务器查订单确认已支付 → 返回新的签名凭证。
     * 仅 [RemoteCloudApi] 支持；若注入的是 [LocalCloudApi] 则返回 [ActivationResult.NetworkError]。
     */
    suspend fun renew(outTradeNo: String): ActivationResult {
        val deviceId = DeviceIdCard.getDeviceId(context)
        return if (cloudApi is RemoteCloudApi) {
            cloudApi.renew(deviceId, outTradeNo)
        } else {
            ActivationResult.NetworkError
        }
    }

    /**
     * 离线验证：读取本地凭证 → 验证签名 + 设备 ID 匹配 + 未过期。
     *
     * 不联网，App 启动时快速判定会员状态用。委托给 [LocalCloudApi.verify]，
     * 它只读本地 SharedPreferences 中的凭证并用 HMAC-SHA256 验证签名。
     *
     * 注意：这要求服务器签发凭证时用的 HMAC 密钥与 [LocalCloudApi] 的密钥一致。
     * 当前是过渡方案；后续服务器改用 RSA 非对称签名后，[SignedCredential] 需要
     * 加 RSA 验证支持，届时离线验证改用公钥。
     */
    suspend fun verifyLocal(): VerifyResult {
        val deviceId = DeviceIdCard.getDeviceId(context)
        // 始终用 LocalCloudApi 做离线验证，即使 cloudApi 是 RemoteCloudApi。
        return LocalCloudApi(context).verify(deviceId)
    }

    /**
     * 联网验证（POST /api/verify）：服务器确认凭证有效。
     *
     * 仅 [RemoteCloudApi] 支持；若注入的是 [LocalCloudApi] 则等价于 [verifyLocal]。
     */
    suspend fun verifyRemote(): VerifyResult {
        val deviceId = DeviceIdCard.getDeviceId(context)
        return cloudApi.verify(deviceId)
    }

    /** 解绑本设备（本地清除凭证）。 */
    suspend fun deactivate(): Boolean {
        val deviceId = DeviceIdCard.getDeviceId(context)
        return cloudApi.deactivate(deviceId)
    }

    /** 获取当前设备身份证（完整 32 位 hex），用于激活码绑定。 */
    fun getDeviceId(): String = DeviceIdCard.getDeviceId(context)

    /** 获取格式化设备身份证（XXXX-XXXX-XXXX-XXXX），用于设置页显示。 */
    fun getDeviceIdDisplay(): String = DeviceIdCard.getDeviceIdDisplay(context)

    /** 是否已用过免费试用。本地 SharedPreferences 标记，UI 据此隐藏试用按钮。 */
    fun isTrialUsed(): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TRIAL_USED, false)

    /** 标记已用过试用（trial 成功后调用）。 */
    private fun markTrialUsed() {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TRIAL_USED, true).apply()
    }

    companion object {
        private const val PREF_NAME = "lxchat_activation"
        private const val KEY_TRIAL_USED = "trial_used"
    }
}
