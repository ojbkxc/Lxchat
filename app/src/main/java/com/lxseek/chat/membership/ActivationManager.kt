package com.lxseek.chat.membership

import android.content.Context

/**
 * 激活管理器：封装 [CloudApi] + 本地验证 + 设备身份获取。
 *
 * UI 层（如 [com.lxseek.chat.ui.settings.SettingsMembershipPage]）只与本类交互，
 * 不直接接触 [CloudApi] / [SignedCredential] / [DeviceIdCard]。
 *
 * - [activate]：激活码激活（联网，POST /api/activate_by_code）。
 * - [trial]：首次免费三天试用（联网，POST /api/trial）。
 * - [activateByOrder]：订单激活（联网，POST /api/activate_by_order，服务器查订单确认已支付）。
 * - [renew]：续费（联网，POST /api/renew，服务器在剩余时长上累加）。
 * - [verifyLocal]：纯离线验证，读本地凭证 → 验证签名 + 设备 ID 匹配 + 未过期。
 * - [verifyRemote]：联网验证（POST /api/verify），服务器确认凭证有效。
 * - [deactivate]：解绑本设备（本地清除凭证）。
 * - [getDeviceIdDisplay]：获取格式化设备身份，供设置页显示。
 *
 * 默认用 [RemoteCloudApi]（调 activate.lxseek.com）。需要离线兜底时可注入 [LocalCloudApi]。
 *
 * 二元制说明：会员只有免费/付费两档，本类所有激活途径（激活码/试用/订单/续费）
 * 签发的都是同一档付费凭证（tier = Premium），不再有档位参数。
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
     * 已激活但快到期时调用：服务器查订单确认已支付 → 在剩余时长上累加，返回新签名凭证。
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
     * 创建支付订单（云端生成订单 + 支付 URL）。
     *
     * @param amount 金额（元，字符串保留两位小数；服务端按 [planId] 定价时此字段仅作参考）
     * @param planId 套餐 ID（[PlanCatalog] 中的 id），空字符串回退旧逻辑
     * @return 订单结果（含支付 URL 和订单号），失败返回 null
     */
    suspend fun createPaymentOrder(
        amount: String,
        planId: String = "",
    ): PaymentOrderResult? {
        val deviceId = DeviceIdCard.getDeviceId(context)
        return cloudApi.createPaymentOrder(deviceId, amount, planId)
    }

    /**
     * 重装恢复：查询服务端设备激活状态，有有效凭证则恢复到本地。
     *
     * 卸载重装后本地 SharedPreferences 被清空，但服务端仍记录该 deviceId 的激活。
     * App 启动时若本地无凭证，调本方法 → POST /api/device_status →
     * 服务端返回重签凭证 → 保存到本地完成恢复。
     *
     * @param deviceId 设备 ID（[DeviceIdCard.getDeviceId]）
     * @return 恢复成功时返回凭证，否则返回 null
     */
    suspend fun restoreActivation(deviceId: String): SignedCredential? {
        // 只有 RemoteCloudApi 支持 deviceStatus
        val remote = cloudApi as? RemoteCloudApi ?: return null
        return when (val result = remote.deviceStatus(deviceId)) {
            is DeviceStatusResult.Active -> {
                // 保存恢复的凭证到本地（与 LocalCloudApi/RemoteCloudApi 共用同一 prefs）
                saveCredential(result.credential)
                result.credential
            }
            else -> null
        }
    }

    /**
     * 本地是否已有激活凭证（不验证签名/过期，仅检查是否存在）。
     *
     * 用于 App 启动时判断是否需要调 [restoreActivation] 重装恢复。
     */
    fun hasActiveCredential(): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CREDENTIAL, null) != null
    }

    /** 保存凭证到本地 SharedPreferences（与 LocalCloudApi/RemoteCloudApi 共用同一 prefs）。 */
    private fun saveCredential(credential: SignedCredential) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CREDENTIAL, credential.toJson())
            .apply()
    }

    /**
     * 离线验证：读取本地凭证 → 验证签名 + 设备 ID 匹配 + 未过期。
     *
     * 不联网，App 启动时快速判定会员状态。委托给 [LocalCloudApi.verify]：
     * 它只读本地 SharedPreferences 中的凭证并用 HMAC-SHA256 验证签名。
     *
     * 注意：这要求服务器签发凭证时用的 HMAC 密钥与本地一致（密钥经
     * [MembershipSecrets] 从 BuildConfig 注入，见 H5）。这是过渡方案；
     * 后续服务器改用 RSA 非对称签名后，离线验证改用公钥。
     */
    suspend fun verifyLocal(): VerifyResult {
        val deviceId = DeviceIdCard.getDeviceId(context)
        // Always use LocalCloudApi for offline verification, even if cloudApi is RemoteCloudApi.
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

    /** 获取当前设备身份（完整 32 位 hex），用于激活码绑定。 */
    fun getDeviceId(): String = DeviceIdCard.getDeviceId(context)

    /** 获取格式化设备身份（XXXX-XXXX-XXXX-XXXX），用于设置页显示。 */
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
        private const val KEY_CREDENTIAL = "credential"
    }
}
