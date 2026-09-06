package com.lxseek.chat.api.openai

import android.util.Base64
import com.lxseek.chat.util.Constants

/**
 * 内置隐藏网关供应商 lxchat。
 *
 * 设计约束（勿破坏）：
 * - 凭证以 XOR + Base64 形式编译进代码，明文密钥不出现在源码/APK strings 表中，
 *   防止静态提取；运行时按需解码，不缓存到任何持久化存储。
 * - 密钥不写入 DataStore（apiKeys）、不进入备份导出（PortableSettingsArchive）、
 *   不出现在任何 UI 页面与日志输出。
 * - 该供应商不展示在设置 → 供应商列表（SettingsProviderPage 的 builtInNames 硬编码
 *   白名单不含 lxchat）；模型列表与生成请求走与其它 OpenAI 兼容供应商完全相同的通道，
 *   在设置 → 模型页可正常拉取与勾选。
 * - 名称被 [com.lxseek.chat.data.CustomProviderNamePolicy] 保留，用户无法创建同名
 *   自定义供应商来劫持解析路径。
 */
class LxChatProvider : BaseOpenAiProvider() {

    override val name: String = Constants.PROVIDER_LXCHAT

    /** 内置网关端点。供应商无持久化 Base URL 时由基类回退到该默认值。 */
    override val defaultBaseUrl: String = "https://lxchatapi.1232333.xyz/v1"

    override val retryableStatusCodes: Set<Int> = setOf(401, 429, 502, 503, 504)

    companion object {
        // XOR 掩码与密文均为代码内常量；两者单独出现都无法还原密钥。
        private val OBF_MASK = "lXc7kQ2vZ9pR4tW6".toByteArray(Charsets.UTF_8)
        private const val OBF_SECRET_B64 =
            "HzNOQAp8BEU7DxNhVhdvVwptVwcIMFMXYlwVYgxENAdbbFFSU2g="

        /**
         * 解出内置网关密钥。每次调用重新解码、不落字段缓存，避免密钥长期驻留
         * 可被 dump 的实例状态。仅供 SettingsRepository/ProviderRegistry 的
         * 密钥解析特例使用，禁止在 UI 或日志层调用。
         */
        fun builtInApiKey(): String {
            val cipher = Base64.decode(OBF_SECRET_B64, Base64.NO_WRAP)
            val mask = OBF_MASK
            val plain = ByteArray(cipher.size)
            for (i in cipher.indices) {
                plain[i] = (cipher[i].toInt() xor mask[i % mask.size].toInt()).toByte()
            }
            return String(plain, Charsets.UTF_8)
        }
    }
}
