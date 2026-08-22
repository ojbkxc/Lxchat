package com.lxseek.chat.api.router

import com.lxseek.chat.api.GenerationError

/**
 * LLM 请求重试策略
 *
 * 定义在智能路由层面的重试决策与退避间隔。与 [com.lxseek.chat.api.util.ProviderRetryPolicy]
 * 互补：后者在单个 Provider 内部针对传输错误/HTTP 状态码做即时重试，
 * 本策略在路由层面决定一次失败的 Provider 调用是否应作为可回退错误
 * 触发 Fallback Chain 切换或路由级重试。
 *
 * 设计意图：
 * - 指数退避：base * 2^(attempt-1)，避免在服务恢复期产生请求风暴
 * - 区分可重试与不可重试错误：认证错误、请求格式错误不重试
 * - 最大重试次数有界，防止无限循环
 */
object LlmRetryPolicy {
    /** 路由层面最大重试次数（不含首次请求） */
    const val MAX_RETRY_ATTEMPTS = 3

    /** 退避基准延迟（毫秒） */
    private const val RETRY_BASE_DELAY_MS = 1_000L

    /** 退避上限（毫秒），避免指数增长过大 */
    private const val RETRY_MAX_DELAY_MS = 30_000L

    /**
     * 计算第 [retryAttempt] 次重试前的退避延迟（指数退避，带上限）。
     *
     * @param retryAttempt 重试序号，从 1 开始；<=0 会被归一化为 1
     */
    fun nextDelayMs(retryAttempt: Int): Long {
        val normalized = retryAttempt.coerceAtLeast(1)
        val raw = RETRY_BASE_DELAY_MS * (1L shl (normalized - 1))
        return raw.coerceAtMost(RETRY_MAX_DELAY_MS)
    }

    /**
     * 判断给定错误是否值得重试或作为可回退错误。
     *
     * - 网络错误（含 429 速率限制、5xx 服务端错误）：可重试
     * - 超时：可重试
     * - 不完整流：可重试（可能是中继断连）
     * - 输出截断：不可重试（提高 max_tokens 才能解决）
     * - 认证失败（401）、请求格式错误、配置错误、取消：不可重试
     * - 工具执行/转码/嵌入/本地模型错误：不可重试（非传输层问题）
     */
    fun isRetryable(error: GenerationError): Boolean = when (error) {
        is GenerationError.Network -> when (error.statusCode) {
            401, 403 -> false           // 认证/授权失败，重试无益
            429 -> true                 // 速率限制，退避后可重试
            in 500..599 -> true         // 服务端错误，可重试
            else -> true                // 其他网络错误（0=连接拒绝等），可重试
        }
        is GenerationError.Api -> when (error.code?.lowercase()) {
            "invalid_api_key", "authentication_error" -> false
            "rate_limit_exceeded" -> true
            else -> true                // 默认可重试，避免过度悲观
        }
        GenerationError.Timeout -> true
        is GenerationError.IncompleteStream -> true
        is GenerationError.OutputTruncated -> false
        is GenerationError.SseParse -> false
        is GenerationError.ToolExecution -> false
        is GenerationError.Transcription -> false
        is GenerationError.Embedding -> false
        is GenerationError.LocalModel -> false
        is GenerationError.Configuration -> false
        is GenerationError.RequestFormat -> false
        is GenerationError.Unknown -> true   // 未知异常可能是瞬时传输故障
        GenerationError.Cancelled -> false
    }
}