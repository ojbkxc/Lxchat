package com.lxseek.chat.api.router

import android.os.SystemClock
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
 * @param enableComplexityRouting 是否启用复杂度智能路由（false 时行为完全不变，向后兼容）。
 *   开启后仅在无 fallback 快速路径中按任务复杂度选择模型，避免与 fallback 逻辑冲突
 * @param simpleTaskModel 简单任务用的小模型 ID（null/空 = 未配置，走启发式匹配）
 * @param complexTaskModel 复杂任务用的大模型 ID（null/空 = 未配置，走启发式匹配）
 */
data class RouterConfig(
    val allowlist: ModelAllowlist = ModelAllowlist.PERMISSIVE,
    val rateLimitPerMinute: Map<String, Int> = emptyMap(),
    val maxConcurrentPerProvider: Map<String, Int> = emptyMap(),
    val enableFallback: Boolean = true,
    val enableKeyRotation: Boolean = true,
    val enableComplexityRouting: Boolean = false,
    val simpleTaskModel: String? = null,
    val complexTaskModel: String? = null,
) {
    companion object {
        /** 全部禁用的默认配置：路由器退化为直接转发，保持向后兼容。 */
        val DISABLED = RouterConfig(
            allowlist = ModelAllowlist.PERMISSIVE,
            rateLimitPerMinute = emptyMap(),
            maxConcurrentPerProvider = emptyMap(),
            enableFallback = false,
            enableKeyRotation = false,
            enableComplexityRouting = false,
            simpleTaskModel = null,
            complexTaskModel = null,
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
 * @param scoreFeedbackLoop 结果质量反馈环（进程级共享单例，null = 关闭）。
 *   每次路由尝试结束后按"成功/失败 + 延迟"写入 0–100 的质量分；
 *   兜底链排序时把该分数簿的历史均分与 [AdaptiveFallbackTracker] 的
 *   在线打分混合，让真实结果质量反馈影响后续路由权重（自改进闭环）。
 */
class SmartModelRouter(
    private val delegate: LlmProvider,
    private val routerConfig: RouterConfig = RouterConfig.DISABLED,
    private val fallbackChain: FallbackChain = FallbackChain.EMPTY,
    private val apiKeyRotator: ApiKeyRotator = ApiKeyRotator(),
    private val apiKeySource: ApiKeySource? = null,
    private val scoreFeedbackLoop: com.lxseek.chat.metrics.ScoreFeedbackLoop? = null,
) : LlmProvider by delegate {

    /** 复杂度路由器：按任务复杂度选择小/大模型，配置来自 [routerConfig]。 */
    private val complexityRouter = ComplexityRouter(
        simpleTaskModel = routerConfig.simpleTaskModel,
        complexTaskModel = routerConfig.complexTaskModel,
    )

    /**
     * 用 [ScoreFeedbackLoop] 分数簿的历史均分重排兜底条目。
     *
     * 混合权重各 50%：一半来自 [AdaptiveFallbackTracker] 的在线打分（成功率/延迟），
     * 一半来自分数簿的均分（0–100 归一化到 0–1）。分数簿无记录的条目取中性值 0.5，
     * 因此与在线打分的相对顺序保持一致（[sortedBy] 稳定排序，全程无副作用）。
     */
    private fun reorderWithScoreBook(entries: List<FallbackEntry>): List<FallbackEntry> {
        if (entries.size < 2) return entries
        val loop = scoreFeedbackLoop ?: return entries
        return entries.sortedBy { entry ->
            val onlineScore = fallbackTracker.scoreOf(entry.provider.name, entry.modelId)
            val bookScore = loop.getPerformance(scoreKey(entry.provider.name, entry.modelId))
                ?.avgScore?.div(100.0) ?: 0.5
            0.5 * onlineScore + 0.5 * bookScore
        }
    }

    /**
     * Score book key: "route:providerName/modelId".
     * Prefix route scores to avoid mixing with tool names: the score book is shared
     * with tool-level performance (getTopPerformingTools/getUnderperformingTools),
     * and an unprefixed "providerName/modelId" could be mistaken for a tool name.
     */
    private fun scoreKey(providerName: String, modelId: String): String = "route:$providerName/$modelId"

    /**
     * 每次路由尝试结束后写入质量分（0–100）。
     *
     * 打分规则：失败 = 0；成功 = 100 扣除延迟惩罚（每秒 -5，下限 50），
     * 既奖励稳定成功也奖励快速响应。boosters 记录本次使用的路由策略，
     * 供 [ScoreFeedbackLoop.getRecommendedBoosters] 分析历史最优组合。
     */
    private fun recordRouteScore(
        providerName: String,
        modelId: String,
        success: Boolean,
        latencyMs: Long,
        boosters: List<String>,
    ) {
        val loop = scoreFeedbackLoop ?: return
        val score = if (!success) {
            0
        } else {
            // 每秒延迟 -5 分，延迟惩罚最多扣 50 分（下限 50）。
            val penalty = ((latencyMs / 1000L) * 5L).coerceAtMost(50L)
            (100 - penalty).toInt()
        }
        loop.recordScore(
            toolName = scoreKey(providerName, modelId),
            score = score,
            context = if (success) "ok in ${latencyMs}ms" else "failed after ${latencyMs}ms",
            boostersUsed = boosters,
        )
    }

    /**
     * D3：API Key 列表的短 TTL 缓存，避免每次请求都重新解析持久化配置。
     * TTL 极短（[KEYS_CACHE_TTL_MS]），用户增删/启用 Key 后很快自动失效。
     */
    private data class KeysCacheEntry(
        val keys: List<ApiKeyEntry>,
        val createdAtMs: Long,
    )
    private val keysCache = ConcurrentHashMap<String, KeysCacheEntry>()

    /** D1：兜底模型的自适应反馈追踪器（按历史成功率与延迟在线打分）。 */
    private val fallbackTracker = AdaptiveFallbackTracker()

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
        // D1：启用 fallback 时按历史成功率/延迟在线打分重排兜底模型，无历史则保持原配置顺序。
        // 质量反馈环（ScoreFeedbackLoop）开启时，把分数簿的历史均分与在线打分混合，
        // 让每次请求的真实结果质量持续影响后续路由权重（自改进闭环）。
        val orderedByTracker = if (hasFallback) fallbackTracker.orderByScore(fallbackChain.fallbacks) else emptyList()
        val fallbackEntries = if (hasFallback && scoreFeedbackLoop != null) {
            reorderWithScoreBook(orderedByTracker)
        } else {
            orderedByTracker
        }

        // ── 无 fallback 快速路径：直接流式转发，不缓冲 ──
        // 避免缓冲整个响应导致首 token 延迟；仅应用白名单/Key 轮换/速率限制/并发限制。
        // 复杂度智能路由仅在此路径应用，避免与 fallback 缓冲路径冲突。
        if (!hasFallback) {
            val effectiveModelId = routeByComplexity(messages, config.modelId)
            streamDirect(
                provider = delegate,
                modelId = effectiveModelId,
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
     * 按任务复杂度路由模型（仅在 [RouterConfig.enableComplexityRouting] 开启时生效）。
     *
     * 用 [TaskComplexityEstimator] 评估消息复杂度，再用 [complexityRouter] 选择模型。
     * 关闭时直接返回 [primaryModel]，行为完全不变（向后兼容）。
     *
     * @param messages 聊天消息列表
     * @param primaryModel 当前主模型 ID
     * @return 实际使用的模型 ID
     */
    private fun routeByComplexity(messages: List<ChatMessage>, primaryModel: String): String {
        if (!routerConfig.enableComplexityRouting) return primaryModel
        val complexity = TaskComplexityEstimator.estimate(messages)
        val routed = complexityRouter.routeFor(complexity, primaryModel)
        val effective = routed ?: primaryModel
        DebugLog.i(
            "SmartRouter",
            "复杂度路由: 复杂度=$complexity(weight=${complexity.weight}), " +
                "主模型=$primaryModel, 选中=$effective" +
                if (routed == null) "(未切换)" else "",
        )
        return effective
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
            var sawError = false
            val startMs = SystemClock.elapsedRealtime()
            provider.generateResponse(messages, entryConfig).collect { event ->
                if (event is StreamEvent.Error) sawError = true
                emit(event)
            }
            // 反馈环：主路径（无 fallback 快速路径）也写入质量分，
            // 让分数簿覆盖真实生成流量的每一次路由决策。
            recordRouteScore(
                providerName = provider.name,
                modelId = modelId,
                success = !sawError,
                latencyMs = SystemClock.elapsedRealtime() - startMs,
                boosters = routeBoosters(entryConfig),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            emit(StreamEvent.Error(GenerationError.Unknown(e)))
            // 反馈环：异常同样计为失败（计分与 AdaptiveFallbackTracker 的口径一致）
            recordRouteScore(
                providerName = provider.name,
                modelId = modelId,
                success = false,
                latencyMs = 0L,
                boosters = routeBoosters(entryConfig),
            )
        } finally {
            concurrencySemaphore?.release()
        }
    }

    /** 本次请求实际启用的路由策略标签，随质量分写入反馈环。 */
    private fun routeBoosters(config: ProviderConfig): List<String> = buildList {
        if (routerConfig.enableKeyRotation) add("key-rotation")
        if (routerConfig.enableComplexityRouting) add("complexity-routing")
        if (config.modelId.isNotBlank()) add("model=${config.modelId}")
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
        val startMs = SystemClock.elapsedRealtime()

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

        // D1：记录本条目的成功率与延迟，供自适应 fallback 打分
        fallbackTracker.record(
            provider = provider.name,
            modelId = modelId,
            success = error == null,
            latencyMs = SystemClock.elapsedRealtime() - startMs,
        )
        // 反馈环：兜底缓冲路径同样写入质量分，保持两条路径打分口径一致
        recordRouteScore(
            providerName = provider.name,
            modelId = modelId,
            success = error == null,
            latencyMs = SystemClock.elapsedRealtime() - startMs,
            boosters = routeBoosters(entryConfig),
        )

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

        // 2. Key 轮换（D3：列表经短 TTL 缓存避免每次重新解析）
        if (routerConfig.enableKeyRotation && apiKeySource != null) {
            val keys = cachedKeysFor(providerName)
            if (keys.isNotEmpty()) {
                apiKeyRotator.nextKey(providerName, keys)?.let { return it }
            }
        }

        // 3. 回退到原始配置中的 Key
        return fallbackKey
    }

    /**
     * D3：获取 [provider] 的 API Key 列表，带短 TTL 缓存。
     *
     * [ApiKeySource.keysFor] 通常读取持久化配置（DB/SharedPreferences），
     * 高并发下每次请求都调用会产生不必要的 IO/解析开销。这里缓存 TTL 内的快照，
     * TTL 极短，用户界面改动 Key 后很快失效，不会感知到陈旧数据。
     */
    private suspend fun cachedKeysFor(providerName: String): List<ApiKeyEntry> {
        val now = SystemClock.elapsedRealtime()
        val cached = keysCache[providerName]
        if (cached != null && now - cached.createdAtMs < KEYS_CACHE_TTL_MS) return cached.keys
        // 仅在 enableKeyRotation && apiKeySource != null 时被调用，此处可安全断言非空
        val fresh = apiKeySource!!.keysFor(providerName)
        keysCache[providerName] = KeysCacheEntry(fresh, now)
        return fresh
    }

    private companion object {
        /** D3：API Key 列表缓存时长（毫秒）。 */
        const val KEYS_CACHE_TTL_MS = 5000L
    }
}

/**
 * D1：兜底模型的自适应反馈追踪器。
 *
 * 在线收集每个（Provider, modelId) 的请求成功率与平均延迟，为 fallback
 * 兜底序列计算实时可靠性打分：更稳、更快的模型优先尝试。无历史记录的条目
 * 打分取中性值 1.0，保持用户的原始配置顺序（稳定优先）。
 */
private class AdaptiveFallbackTracker {

    private class Stat {
        val attempts = AtomicLong(0)
        val successes = AtomicLong(0)
        val latencySum = java.util.concurrent.atomic.LongAdder()
    }

    private val stats = ConcurrentHashMap<String, Stat>()

    private fun key(provider: String, modelId: String): String = "$provider\u0001$modelId"

    /** 记录一次请求结果（成功与否 + 总耗时）。线程安全。 */
    fun record(provider: String, modelId: String, success: Boolean, latencyMs: Long) {
        val s = stats.computeIfAbsent(key(provider, modelId)) { Stat() }
        s.attempts.incrementAndGet()
        if (success) s.successes.incrementAndGet()
        s.latencySum.add(latencyMs.coerceAtLeast(0))
    }

    /**
     * 可靠性打分（越高越优先）。
     *
     * - 无历史：返回 1.0（中性，不扰动原配置顺序）。
     * - 有历史：成功率线性项 + 延迟倒数归一化项加权混合。
     *   成功率权重更高（稳定性优先），延迟作为第二信号区分同等可靠性的模型。
     */
    fun score(provider: String, modelId: String): Double {
        val s = stats[key(provider, modelId)] ?: return 1.0
        val attempts = s.attempts.get()
        if (attempts == 0L) return 1.0
        val successRate = s.successes.get().toDouble() / attempts
        val avgLatency = s.latencySum.sum() / attempts.toDouble()
        val latencyScore = LATENCY_REF_MS / (LATENCY_REF_MS + avgLatency)
        return successRate * SUCCESS_WEIGHT + latencyScore * LATENCY_WEIGHT
    }

    /** [score] 的公开只读入口，供路由器把在线打分与反馈环分数簿混合。 */
    fun scoreOf(provider: String, modelId: String): Double = score(provider, modelId)

    /** 按打分降序重排兜底链；打分相同时保持原配置顺序（稳定排序）。 */
    fun orderByScore(entries: List<FallbackEntry>): List<FallbackEntry> {
        if (entries.size < 2) return entries
        return entries.indices
            .sortedWith(
                compareByDescending<Int> { score(entries[it].provider.name, entries[it].modelId) }
                    .thenBy { it },
            )
            .map { entries[it] }
    }

    private companion object {
        /** 延迟打分基准（毫秒）：在该延迟下延迟项贡献 0.5。 */
        const val LATENCY_REF_MS = 3000.0
        const val SUCCESS_WEIGHT = 0.7
        const val LATENCY_WEIGHT = 0.3
    }
}
