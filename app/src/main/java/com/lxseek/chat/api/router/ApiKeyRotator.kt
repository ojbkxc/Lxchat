package com.lxseek.chat.api.router

import com.lxseek.chat.data.ApiKeyEntry
import com.lxseek.chat.util.DebugLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * API Key 轮换器
 *
 * 在同一 Provider 的多个 API Key 之间做 round-robin 轮换，实现负载均衡。
 * 当某些 Key 被标记为不可用时，自动跳过并从剩余可用 Key 中选择。
 *
 * 设计意图：
 * - 适配 Lxchat 已有的多 Key 存储（[ApiKeyEntry]），不修改 Settings 层
 * - 线程安全：每个 Provider 一把 [Mutex]，避免并发请求拿到同一索引
 * - 进程级索引缓存：轮换位置存于内存 [ConcurrentHashMap]，进程重启后从头开始
 * - 与 HermesApp 的 MultiApiKeyProvider 思路一致，但不依赖 ModelConfigManager
 *
 * 使用方式：由 [SmartModelRouter] 持有一个共享实例，每次请求前调用 [nextKey]
 * 获取当前应使用的 Key，失败时调用 [markUnavailable] 暂时跳过该 Key。
 */
class ApiKeyRotator {
    /**
     * 每个 Provider 的轮换状态。
     *
     * @property index 下一个要使用的 Key 索引（已对 key 数取模）
     * @property unavailableIds 被标记为不可用的 Key ID 集合
     */
    private data class RotationState(
        @Volatile var index: Int = 0,
        val unavailableIds: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    )

    private val states = ConcurrentHashMap<String, RotationState>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(provider: String): Mutex =
        mutexes.computeIfAbsent(provider) { Mutex() }

    private fun stateFor(provider: String): RotationState =
        states.computeIfAbsent(provider) { RotationState() }

    /**
     * 获取 [provider] 的下一个可用 API Key。
     *
     * 算法：
     * 1. 从 [keys] 中筛除非空且未被标记不可用的 Key
     * 2. 若无可用 Key，返回 null
     * 3. 从当前索引开始 round-robin 选取，并推进索引
     *
     * @param provider Provider 名称
     * @param keys 该 Provider 的全部 API Key 列表（通常来自 Settings）
     * @return 下一个可用的 Key 字符串，或 null 当无可用 Key
     */
    suspend fun nextKey(provider: String, keys: List<ApiKeyEntry>): String? {
        if (keys.isEmpty()) return null

        val state = stateFor(provider)
        return mutexFor(provider).withLock {
            // 筛选可用候选：Key 非空且未被标记不可用
            val candidates = keys.filter { it.key.isNotBlank() && it.id !in state.unavailableIds }
            if (candidates.isEmpty()) {
                DebugLog.w("ApiKeyRotator", "[$provider] 无可用 API Key（共 ${keys.size} 个，${state.unavailableIds.size} 个被标记不可用）")
                return@withLock null
            }

            // 从当前索引开始取，推进索引
            val startIndex = state.index % candidates.size
            val selected = candidates[startIndex]
            state.index = (startIndex + 1) % candidates.size

            DebugLog.d(
                "ApiKeyRotator",
                "[$provider] 选用 Key ${startIndex + 1}/${candidates.size} '${selected.name}'",
            )
            selected.key
        }
    }

    /**
     * 将指定 Key 标记为不可用（例如认证失败后）。
     *
     * 标记在进程生命周期内有效；进程重启后所有 Key 重新视为可用。
     *
     * @param provider Provider 名称
     * @param keyId 不可用的 Key ID（[ApiKeyEntry.id]）
     */
    fun markUnavailable(provider: String, keyId: String) {
        stateFor(provider).unavailableIds.add(keyId)
        DebugLog.w("ApiKeyRotator", "[$provider] Key $keyId 已标记为不可用")
    }

    /**
     * 清除指定 Provider 的不可用标记（例如用户手动重置后）。
     */
    fun clearUnavailable(provider: String) {
        stateFor(provider).unavailableIds.clear()
    }

    /** 清除所有 Provider 的轮换状态与不可用标记（主要用于测试）。 */
    fun clearAll() {
        states.clear()
        mutexes.clear()
    }
}