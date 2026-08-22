package com.lxseek.chat.api.router

import com.lxseek.chat.api.GenerationError
import com.lxseek.chat.api.LlmProvider
import com.lxseek.chat.api.ProviderConfig
import com.lxseek.chat.api.StreamEvent
import com.lxseek.chat.data.ApiKeyEntry
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * API Key 来源接口
 *
 * 解耦 [SmartModelRouter] 与 Settings 层：路由器通过此接口按 Provider 名
 * 获取可轮换的 API Key 列表，而不直接依赖 [com.lxseek.chat.data.repository.SettingsRepository]。
 *
 * 当返回空列表时，路由器回退到 [ProviderConfig.apiKey] 中的原始 Key。
 */
fun interface ApiKeySource {
    /** 获取 [provider] 的全部 API Key 列表（按启用顺序）。 */
    suspend fun keysFor(provider: String): List<ApiKeyEntry>
}

/**
 * 智能模型路由器配置
 *
 * 控制路由行为的各项策略参数。所有字段均有默认值，默认配置下路由器
 * 退化为"直接转发"——不启用任何限制或 fallback，保持向后兼容。
 *
 * @param allowlist 模型白名单/黑名单过滤器
 * @param rateLimitPerMinute 按 Provider 名的每分钟速率上限；未列出的 Provider 不限速
 * @param maxConcurrentPerProvider 按 Provider 名的最大并发请求数；未列出的 Provider 不限并发
 * @param enableFallback 是否启用故障转移（false 时仅尝试主模型）
 * @param enableKeyRotation 是否启用 API Key 轮换（false 时使用原始 config 中的 Key）
 */
data class RouterConfig(
    val allowlist: ModelAllowlist = ModelAllowlist.PERMISSIVE,
    val rateLimitPerMinute: Map<String, Int> = emptyMap(),
    val maxConcurrentPerProvider: Map<String, Int> = emptyMap(),
    val enableFallback: Boolean = true,
    val enableKeyRotation: Boolean = true,
) {
    companion object {
        /** 全部禁用的默认配置：路由器退化为直接转发，保持向后兼容。 */
        val DISABLED = RouterConfig(
            allowlist = ModelAllowlist.PERMISSIVE,
            rateLimitPerMinute = emptyMap(),
            maxConcurrentPerProvider = emptyMap(),
            enableFallback = false,
            enableKeyRotation = false,
        )
    }
}

/**
 * 智能模型路由器
 *
 * 整合 Fallback Chain、API Key 轮换、滑动窗口速率限制、并发限制、
 * 模型白名单/黑名单与重试策略，为 Lxchat 提供统一的请求路由入口。
 *
 * 设计意图：
 * - **深度融合 Lxchat 架构**：实现 [LlmProvider] 接口，作为 delegate 的装饰器，
 *   `name`/`defaultBaseUrl`/`fetchModels` 直接委托，仅覆盖 [generateResponse]
 * - **向后兼容**：[RouterConfig.DISABLED] 下退化为直接转发；不包装时调用链不变
 * - **不修改 Provider 接口**：路由器作为 [LlmProvider] 实例透明地注入调用链
 * - **Fallback 安全边界**：仅当请求未产出任何内容（连接阶段失败）时
 *   才切换备用模型；一旦开始流式输出，后续错误直接转发，避免重复输出
 * - **纯 Kotlin 实现**：不引入新依赖，不影响 APK 大小
 *
 * 集成方式：在 [com.lxseek.chat.viewmodel.GenerationManager] 中，
 * 当智能路由启用时用 `SmartModelRouter(provider, ...)` 包装原始 provider，
 * 后续调用链（ProviderPassEffectExecutor / ProviderPassRunner）无需任何修改。
 *
 * @param delegate 被装饰的主 [LlmProvider] 实例
 * @param routerConfig 路由策略配置
 * @param fallbackChain 故障转移链（备用模型列表）
 * @param apiKeyRotator API Key 轮换器（进程级共享实例）
 * @param apiKeySource API Key 来源（null 表示不轮换）
 */
class SmartModelRouter(
    private val delegate: LlmProvider,
    private val routerConfig: RouterConfig = RouterConfig.DISABLED,
    private val fallbackChain: FallbackChain = FallbackChain.EMPTY,
    private val apiKeyRotator: ApiKeyRotator = ApiKeyRotator(),
    private val apiKeySource: ApiKeySource? = null,
) : LlmProvider by delegate {

    /**
     * 路由一次生成请求。
     *
     * 先尝试主 Provider（delegate），当且仅当请求未产出任何内容且错误可重试时，
     * 按 [fallbackChain] 顺序回退到备用模型。每个条目应用白名单、Key 轮换、
     * 速率限制与并发限制后调用对应 Provider。
     *
     * @param messages 聊天消息列表
     * @param config Provider 配置；主条目直接使用此配置，备用条目按 entry 覆盖
     * @return 流式响应事件
     */
    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig,
    ): Flow<StreamEvent> = flow {
        // 主条目 + 备用条目（当 enableFallback 时）
        val hasFallback = routerConfig.enableFallback && fallbackChain.hasFallback
        val fallbackEntries = if (hasFallback) fallbackChain.fallbacks else emptyList()

        // ── 无 fallback 快速路径：直接流式转发，不缓冲 ──
        // 避免缓冲整个响应导致首 token 延迟；仅应用白名单/Key 轮换/速率限制/并发限制。
        if (!hasFallback) {
            streamDirect(
                provider = delegate,
                modelId = config.modelId,
                apiKeyOverride = null,
                baseUrlOverride = null,
                messages = messages,
                baseConfig = config,
                emit = ::emit,
            )
            return@flow
        }

        // ── 有 fallback 缓冲路径：先收集主条目事件，失败且未产出内容时回退 ──
        val totalAttempts = 1 + fallbackEntries.size
        var lastError: GenerationError? = null

        // ── 主条目（delegate） ──
        val primaryResult = attemptEntry(
            provider = delegate,
            modelId = config.modelId,
            apiKeyOverride = null,
            baseUrlOverride = null,
            messages = messages,
            baseConfig = config,
        )
        // 转发主条目产出的事件
        for (event in primaryResult.events) emit(event)

        if (primaryResult.success) return@flow

        lastError = primaryResult.error
        val canFallback = hasFallback &&
            !primaryResult.producedContent &&
            primaryResult.error != null &&
            LlmRetryPolicy.isRetryable(primaryResult.error)

        if (!canFallback) {
            // 不可回退：若主条目产出了内容，错误已在 events 中转发；
            // 若未产出内容且不可重试，需补发错误（attemptEntry 未转发未产出内容的错误）
            if (!primaryResult.producedContent && primaryResult.error != null) {
                emit(StreamEvent.Error(primaryResult.error))
            }
            return@flow
        }

        // ── 备用条目 ──
        for ((index, entry) in fallbackEntries.withIndex()) {
            // 白名单检查
            if (!routerConfig.allowlist.isAllowed(entry.modelId)) {
                lastError = GenerationError.Configuration(
                    "备用模型 '${entry.modelId}' 不在白名单中或已被黑名单禁止",
                )
                DebugLog.w("SmartRouter", "备用模型 ${entry.modelId} 被白名单拒绝")
                continue
            }

            // 通知消费者即将回退
            emit(StreamEvent.Retrying(attempt = index + 1, maxAttempts = totalAttempts))

            val result = attemptEntry(
                provider = entry.provider,
                modelId = entry.modelId,
                apiKeyOverride = entry.apiKeyOverride,
                baseUrlOverride = entry.baseUrlOverride,
                messages = messages,
                baseConfig = config,
            )
            for (event in result.events) emit(event)

            if (result.success) return@flow

            lastError = result.error
            val canContinue = index < fallbackEntries.lastIndex &&
                !result.producedContent &&
                result.error != null &&
                LlmRetryPolicy.isRetryable(result.error)

            if (!canContinue) {
                if (!result.producedContent && result.error != null) {
                    emit(StreamEvent.Error(result.error))
                }
                return@flow
            }
        }

        // 所有条目耗尽
        lastError?.let { emit(StreamEvent.Error(it)) }
    }

    /**
     * 无 fallback 快速路径：直接流式转发 Provider 事件，不缓冲。
     *
     * 仍应用白名单检查、API Key 轮换、速率限制与并发限制，但不收集事件——
     * Provider 产出的事件立即 emit 给下游，保证首 token 无延迟。
     * 白名单拒绝或异常归一化为 [StreamEvent.Error] emit。
     */
    private suspend fun streamDirect(
        provider: LlmProvider,
        modelId: String,
        apiKeyOverride: String?,
        baseUrlOverride: String?,
        messages: List<ChatMessage>,
        baseConfig: ProviderConfig,
        emit: suspend (StreamEvent) -> Unit,
    ) {
        // 白名单检查
        if (!routerConfig.allowlist.isAllowed(modelId)) {
            emit(StreamEvent.Error(GenerationError.Configuration(
                "模型 '$modelId' 不在白名单中或已被黑名单禁止",
            )))
            return
        }

        // 解析 API Key 与构建配置
        val resolvedKey = resolveApiKey(provider.name, apiKeyOverride, baseConfig.apiKey)
        val entryConfig = baseConfig.copy(
            modelId = modelId,
            apiKey = resolvedKey,
            baseUrl = baseUrlOverride ?: baseConfig.baseUrl,
        )

        // 速率限制
        val rateLimiter = routerConfig.rateLimitPerMinute[provider.name]?.let { rpm ->
            RateLimiterRegistry.getOrCreateRateLimiter(provider.name, rpm)
        }
        rateLimiter?.acquire()

        // 并发限制
        val concurrencySemaphore = routerConfig.maxConcurrentPerProvider[provider.name]?.let { max ->
            RateLimiterRegistry.getOrCreateConcurrencySemaphore(provider.name, max)
        }
        concurrencySemaphore?.acquire()

        try {
            provider.generateResponse(messages, entryConfig).collect { event ->
                emit(event)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            emit(StreamEvent.Error(GenerationError.Unknown(e)))
        } finally {
            concurrencySemaphore?.release()
        }
    }

    /**
     * 单次条目尝试的结果。
     */
    private data class AttemptResult(
        val events: List<StreamEvent>,
        val success: Boolean,
        val producedContent: Boolean,
        val error: GenerationError?,
    )

    /**
     * 执行单个条目的请求，收集全部事件后返回结果。
     *
     * 注意：此函数会**完整消费** Provider 的 Flow（而非流式转发），因为需要
     * 在结束后判断是否回退。对于主条目，事件列表通常很小（连接失败时为空或仅 Error）；
     * 对于成功的主条目，事件会被缓冲后一次性返回——这意味着首 token 延迟。
     *
     * 为兼顾流式体验，当条目开始产出内容后，后续事件仍被收集但 producedContent
     * 标记为 true，使路由器不会在内容产出后回退。
     *
     * **优化**：对于主条目，若担心首 token 延迟，可改为直接 flow 转发并在 collect
     * 中实时 emit——但这样无法在失败时"吞掉"已 emit 的 Error。当前实现选择安全性
     * 优先：先收集再转发，确保 fallback 不会产生重复 Error。
     */
    private suspend fun attemptEntry(
        provider: LlmProvider,
        modelId: String,
        apiKeyOverride: String?,
        baseUrlOverride: String?,
        messages: List<ChatMessage>,
        baseConfig: ProviderConfig,
    ): AttemptResult {
        // 白名单检查（主条目）
        if (!routerConfig.allowlist.isAllowed(modelId)) {
            return AttemptResult(
                events = emptyList(),
                success = false,
                producedContent = false,
                error = GenerationError.Configuration(
                    "模型 '$modelId' 不在白名单中或已被黑名单禁止",
                ),
            )
        }

        // 解析 API Key
        val resolvedKey = resolveApiKey(provider.name, apiKeyOverride, baseConfig.apiKey)

        // 构建条目配置
        val entryConfig = baseConfig.copy(
            modelId = modelId,
            apiKey = resolvedKey,
            baseUrl = baseUrlOverride ?: baseConfig.baseUrl,
        )

        // 速率限制
        val rateLimiter = routerConfig.rateLimitPerMinute[provider.name]?.let { rpm ->
            RateLimiterRegistry.getOrCreateRateLimiter(provider.name, rpm)
        }
        rateLimiter?.acquire()

        // 并发限制
        val concurrencySemaphore = routerConfig.maxConcurrentPerProvider[provider.name]?.let { max ->
            RateLimiterRegistry.getOrCreateConcurrencySemaphore(provider.name, max)
        }
        concurrencySemaphore?.acquire()

        val events = mutableListOf<StreamEvent>()
        var producedContent = false
        var error: GenerationError? = null

        try {
            provider.generateResponse(messages, entryConfig).collect { event ->
                when (event) {
                    is StreamEvent.TextChunk,
                    is StreamEvent.ThoughtChunk,
                    is StreamEvent.ToolCallUpdate,
                    is StreamEvent.ToolCallRequest,
                    is StreamEvent.ToolCallsRequest -> {
                        producedContent = true
                        events.add(event)
                    }
                    is StreamEvent.Error -> {
                        error = event.error
                        // 仅当已产出内容时转发错误（未产出内容的错误由路由器决定是否转发）
                        if (producedContent) events.add(event)
                    }
                    is StreamEvent.UsageUpdate,
                    is StreamEvent.Retrying -> events.add(event)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            error = GenerationError.Unknown(e)
        } finally {
            concurrencySemaphore?.release()
        }

        return AttemptResult(
            events = events,
            success = error == null,
            producedContent = producedContent,
            error = error,
        )
    }

    /**
     * 解析实际使用的 API Key。
     *
     * 优先级：
     * 1. [apiKeyOverride]：条目显式指定的 Key
     * 2. Key 轮换：从 [apiKeySource] 获取列表，经 [apiKeyRotator] 轮换
     * 3. [fallbackKey]：原始配置中的 Key
     */
    private suspend fun resolveApiKey(
        providerName: String,
        apiKeyOverride: String?,
        fallbackKey: String,
    ): String {
        // 1. 显式覆盖优先
        apiKeyOverride?.takeIf { it.isNotBlank() }?.let { return it }

        // 2. Key 轮换
        if (routerConfig.enableKeyRotation && apiKeySource != null) {
            val keys = apiKeySource.keysFor(providerName)
            if (keys.isNotEmpty()) {
                apiKeyRotator.nextKey(providerName, keys)?.let { return it }
            }
        }

        // 3. 回退到原始配置中的 Key
        return fallbackKey
    }
}
