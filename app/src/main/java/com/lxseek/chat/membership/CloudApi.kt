package com.lxseek.chat.membership

/**
 * 云端 API 接口（重构 R1：本文件只保留接口与公共类型，实现拆分至
 * [LocalCloudApi.kt] / [RemoteCloudApi.kt]，各类型保持原包名，外部引用不变）。
 *
 * 现在用 [RemoteCloudApi]（联网验证，默认 `https://activate.lxseek.com`），
 * [LocalCloudApi] 用于离线兜底（本地验证 + 预置激活码）。
 *
 * 接口设计为 suspend，网络实现走 IO 调度器；本地实现里 suspend 仅做磁盘读写。
 */
interface CloudApi {
    /** 激活码激活，绑定设备。成功返回签名凭证。 */
    suspend fun activate(code: String, deviceId: String): ActivationResult

    /** 验证设备会员状态（联网）。 */
    suspend fun verify(deviceId: String): VerifyResult

    /** 解绑设备。 */
    suspend fun deactivate(deviceId: String): Boolean

    /**
     * 创建支付订单（云端生成订单 + 支付 URL）。
     *
     * 二元制会员体系下套餐不再有档位之分（全部为付费账户的不同时长买法），
     * 故本接口不再接收 tier 参数；服务端请求仍携带 `tier="Premium"` 以兼容
     * 既有服务器协议（服务器按 [planId] 定价）。
     *
     * @param planId 套餐 ID（[PlanCatalog] 中的 id），空字符串表示回退旧逻辑（不传给服务端）。
     */
    suspend fun createPaymentOrder(
        deviceId: String,
        amount: String,
        planId: String = "",
    ): PaymentOrderResult?
}

/** 激活结果。 */
sealed class ActivationResult {
    /** 激活成功，返回签名凭证。 */
    data class Success(val credential: SignedCredential) : ActivationResult()

    /** 激活码无效（格式错误或不在预置码表中）。 */
    object InvalidCode : ActivationResult()

    /** 已被其他设备使用（一码一机）。 */
    object AlreadyUsed : ActivationResult()

    /** 激活码已过期。 */
    object Expired : ActivationResult()

    /** 网络错误（Local 实现不会返回，Remote 实现会）；也用于"需联网核验"语义。 */
    object NetworkError : ActivationResult()
}

/**
 * 验证结果。
 *
 * 注意语义区分（修复 M8）：网络故障/密钥未配置返回 [NetworkError]（可重试），
 * **不**与 [Invalid]（凭据确凿无效）混淆，避免把网络抖动当成凭据吊销。
 */
sealed class VerifyResult {
    /** 凭证有效：签名正确、设备匹配、未过期。 */
    data class Valid(val credential: SignedCredential) : VerifyResult()

    /** 凭证无效（签名错误、设备不匹配、已过期或设备指纹被篡改）。 */
    object Invalid : VerifyResult()

    /** 本地无凭证（未激活过）。 */
    object NotFound : VerifyResult()

    /** 网络错误；HMAC 密钥未配置（本地验签不可用）或时钟回拨超阈值时也用本值（需联网核验）。 */
    object NetworkError : VerifyResult()
}

/** 支付订单创建结果。 */
data class PaymentOrderResult(
    val outTradeNo: String,
    val paymentUrl: String,
)

/**
 * 设备状态查询结果（用于卸载重装恢复）。
 *
 * App 启动时若本地无凭证，调 [RemoteCloudApi.deviceStatus] 查服务端：
 * - [DeviceStatusResult.Active]：服务端有有效激活，返回重签凭证，恢复到本地。
 * - [DeviceStatusResult.Inactive]：服务端无激活记录或已过期，按未激活处理。
 * - [DeviceStatusResult.NetworkError]：网络错误，本次恢复失败（下次启动再试）。
 */
sealed class DeviceStatusResult {
    /** 设备有有效激活，返回重签凭证。 */
    data class Active(
        val credential: SignedCredential,
        /** 归一化后的档位名（二元制：Free/Premium）。 */
        val tier: String,
        val expireAt: Long,
    ) : DeviceStatusResult()

    /** 设备无激活记录或已过期。 */
    object Inactive : DeviceStatusResult()

    /** 网络错误。 */
    object NetworkError : DeviceStatusResult()
}
