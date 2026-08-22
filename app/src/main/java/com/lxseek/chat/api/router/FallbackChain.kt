package com.lxseek.chat.api.router

import com.lxseek.chat.api.LlmProvider

/**
 * 模型故障转移链
 *
 * 定义备用模型的优先级序列。当主模型（由 [SmartModelRouter] 装饰的 delegate）
 * 请求失败且错误可重试时，路由器会按序尝试备用模型，直到其中一个成功或全部耗尽。
 *
 * 设计意图：
 * - 仅包含备用条目；主模型由 [SmartModelRouter] 的 delegate 承担，modelId 取自请求配置
 * - 每个 [FallbackEntry] 携带一个 [LlmProvider] 实例与可选的配置覆盖
 * - 支持覆盖 modelId / apiKey / baseUrl，使备用模型可指向不同端点或凭证
 * - 不可变数据结构，可安全共享
 * - 与 HermesApp 的 fallback 思路一致，但适配 Lxchat 的 [LlmProvider] 接口
 *
 * @param fallbacks 备用模型条目列表（按优先级降序排列）
 */
data class FallbackChain(
    val fallbacks: List<FallbackEntry> = emptyList(),
) {
    /** 备用模型数量。 */
    val size: Int get() = fallbacks.size

    /** 是否存在备用模型。 */
    val hasFallback: Boolean get() = fallbacks.isNotEmpty()

    companion object {
        /** 空链：无备用模型，仅尝试主模型。 */
        val EMPTY = FallbackChain(fallbacks = emptyList())
    }
}

/**
 * 故障转移链中的一个条目。
 *
 * @param provider 该条目使用的 [LlmProvider] 实例
 * @param modelId 该条目使用的模型 ID（覆盖请求中的 modelId）
 * @param apiKeyOverride 可选的 API Key 覆盖；null 表示使用路由器轮换出的 Key 或原始配置
 * @param baseUrlOverride 可选的 Base URL 覆盖；null 表示使用 Provider 默认或原始配置
 */
data class FallbackEntry(
    val provider: LlmProvider,
    val modelId: String,
    val apiKeyOverride: String? = null,
    val baseUrlOverride: String? = null,
)
