package com.lxseek.chat.membership

import android.content.Context

/**
 * 激活管理器：封装 [CloudApi] + 本地验证 + 设备身份证获取。
 *
 * UI 层（如 [com.lxseek.chat.ui.settings.SettingsMembershipPage]）只与本类交互，
 * 不直接接触 [CloudApi] / [SignedCredential] / [DeviceIdCard]。
 *
 * - [activate]：联网激活（当前 Local 实现是本地验证，切 Remote 后走网络）。
 * - [verifyLocal]：纯离线验证，读本地凭证 → 验证签名 + 设备 ID 匹配 + 未过期。
 * - [deactivate]：解绑本设备。
 * - [getDeviceIdDisplay]：获取格式化设备身份证，供设置页显示。
 */
class ActivationManager(
    private val cloudApi: CloudApi,
    private val context: Context,
) {

    /** 激活码激活（联网；当前 Local 实现为本地验证）。 */
    suspend fun activate(code: String): ActivationResult {
        val deviceId = DeviceIdCard.getDeviceId(context)
        return cloudApi.activate(code, deviceId)
    }

    /**
     * 离线验证：读取本地凭证 → 验证签名 + 设备 ID 匹配 + 未过期。
     *
     * 当前直接委托给 [CloudApi.verify]（Local 实现就是离线的）。
     * 切 RemoteCloudApi 后，本方法仍应走本地凭证验证（不联网），
     * 届时在此读取本地保存的 [SignedCredential] 并用 [SignedCredential.verify] 校验。
     */
    suspend fun verifyLocal(): VerifyResult {
        val deviceId = DeviceIdCard.getDeviceId(context)
        return cloudApi.verify(deviceId)
    }

    /** 解绑本设备。 */
    suspend fun deactivate(): Boolean {
        val deviceId = DeviceIdCard.getDeviceId(context)
        return cloudApi.deactivate(deviceId)
    }

    /** 获取当前设备身份证（完整 32 位 hex），用于激活码绑定。 */
    fun getDeviceId(): String = DeviceIdCard.getDeviceId(context)

    /** 获取格式化设备身份证（XXXX-XXXX-XXXX-XXXX），用于设置页显示。 */
    fun getDeviceIdDisplay(): String = DeviceIdCard.getDeviceIdDisplay(context)
}