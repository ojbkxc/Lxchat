package com.lxseek.chat.api.router

import com.lxseek.chat.api.LlmProvider

/**
 * 智能模型路由器工厂
 *
 * 解耦 [com.lxseek.chat.viewmodel.GenerationManager] 与 [SmartModelRouter] 的具体构造：
 * GenerationManager 通过此接口按需将原始 Provider 包装为智能路由 Provider，
 * 而不直接依赖 router 包的配置类（[RouterConfig]、[FallbackChain] 等）。
 *
 * 设计意图：
 * - **向后兼容**：返回 null 表示不包装，调用链退化为原始 Provider
 * - **依赖倒置**：GenerationManager 依赖此接口而非具体路由实现
 * - **配置集中**：路由配置（白名单、速率限制、fallback 链等）在工厂实现处统一管理
 *
 * 使用方式：在 [com.lxseek.chat.di.AppContainer] 中构造实现并注入
 * `ChatViewModel` / `TaskExecutionEngine`，由它们传给 `GenerationManager`。
 */
fun interface SmartModelRouterFactory {
    /**
     * 将 [delegate] 包装为智能路由 Provider。
     *
     * @param delegate 原始 Provider 实例
     * @param providerName Provider 名称（用于查找 fallback provider、速率限制键等）
     * @param modelId 模型 ID（用于白名单检查、fallback 主条目）
     * @return 包装后的 Provider，或 null 表示不包装（保持向后兼容）
     */
    fun create(delegate: LlmProvider, providerName: String, modelId: String): LlmProvider?
}