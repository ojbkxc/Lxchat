package com.lxseek.chat.util

import kotlin.math.abs

/**
 * 进度上报节流器：按时间间隔 + 百分比变化触发回调，避免高频 UI 刷新。
 *
 * 灵感来自 HyX `mobile/src/lib.rs` 中 `progress_sink` 的 `Throttle` 结构
 * （200ms 时间窗 + 滑动速率），扩展为"时间 OR 百分比"双条件任一满足即上报：
 *  - 快速突进时由百分比节流兜底，避免每个 chunk 都刷新 UI；
 *  - 长时间小步推进时由时间节流兜底，确保用户能看到进度在动。
 *
 * 节流条件（满足任一即允许上报，**OR** 而非 AND）：
 *  1. 首次上报（状态为空）；
 *  2. 距上次上报 ≥ [minIntervalMs]；
 *  3. 进度相对上次上报变化 ≥ [minPercentDelta]。
 *
 * 之所以选 OR 而非 AND：若要求"时间到 且 进度跨阈值"才上报，长时间小步推进
 * （每秒涨 0.3%）会因百分比永远凑不够 1% 而 UI 长时间不动，与节流初衷相悖。
 *
 * 线程安全：状态字段用 `@Volatile` 标记，"读-判-写"复合操作用一个内部 monitor
 * 锁保护，与同包 [RateLimiter] 风格一致。回调在锁外执行，避免回调内部再次进入
 * 锁导致死锁，也缩短锁持有时间。
 *
 * 纯 Kotlin，无外部依赖；日志复用 [DebugLog]。
 *
 * @param minIntervalMs    两次上报之间的最小时间间隔（毫秒），默认 200ms（≈5Hz），
 *                         与 HyX Rust 侧 `progress_sink` 的 200ms 窗口对齐。
 * @param minPercentDelta  两次上报之间的最小百分比变化，默认 1.0%。
 */
class ProgressThrottle(
    private val minIntervalMs: Long = 200L,
    private val minPercentDelta: Double = 1.0,
) {
    @Volatile
    private var lastEmitNanos: Long = 0L

    @Volatile
    private var lastEmitProgress: Double = Double.NaN

    private val lock = Any()

    /**
     * 判断当前进度是否应该上报，**不修改内部状态**。
     *
     * @param progress 当前进度百分比，建议范围 0.0–100.0。
     * @param nowNanos 当前时间戳（纳秒），默认 [System.nanoTime]；暴露为参数便于测试。
     * @return true 表示应当上报。
     */
    fun shouldEmit(progress: Double, nowNanos: Long = System.nanoTime()): Boolean {
        synchronized(lock) {
            return shouldEmitLocked(progress, nowNanos)
        }
    }

    /**
     * 条件触发回调：当 [shouldEmit] 返回 true 时调用 [callback] 并更新状态。
     *
     * @param progress 当前进度百分比，建议范围 0.0–100.0。
     * @param callback 接收最终被上报的进度值；在锁外执行，异常会被吞掉并记日志。
     * @return true 表示已触发回调，false 表示被节流。
     */
    fun emit(
        progress: Double,
        callback: (Double) -> Unit,
        nowNanos: Long = System.nanoTime(),
    ): Boolean {
        synchronized(lock) {
            if (!shouldEmitLocked(progress, nowNanos)) return false
            lastEmitNanos = nowNanos
            lastEmitProgress = progress
        }
        invokeCallback(progress, callback, "emit")
        return true
    }

    /**
     * 强制触发回调并更新状态，无视节流条件。
     *
     * 用于传输完成、失败、取消等终态事件，确保 UI 一定能收到最终状态。
     */
    fun forceEmit(
        progress: Double,
        callback: (Double) -> Unit,
        nowNanos: Long = System.nanoTime(),
    ) {
        synchronized(lock) {
            lastEmitNanos = nowNanos
            lastEmitProgress = progress
        }
        invokeCallback(progress, callback, "forceEmit")
    }

    /** 重置节流状态，使下一次 [emit] 必然触发（等价于"未曾上报过"）。 */
    fun reset() {
        synchronized(lock) {
            lastEmitNanos = 0L
            lastEmitProgress = Double.NaN
        }
    }

    /** 在已持有 [lock] 的前提下计算节流判定，不修改状态。 */
    private fun shouldEmitLocked(progress: Double, nowNanos: Long): Boolean {
        if (lastEmitNanos == 0L || lastEmitProgress.isNaN()) return true
        val elapsedMs = (nowNanos - lastEmitNanos) / 1_000_000.0
        if (elapsedMs >= minIntervalMs) return true
        val delta = abs(progress - lastEmitProgress)
        return delta >= minPercentDelta
    }

    /** 在锁外执行回调，吞掉异常并记日志，避免回调抛出影响调用方。 */
    private fun invokeCallback(progress: Double, callback: (Double) -> Unit, op: String) {
        try {
            callback(progress)
        } catch (t: Throwable) {
            DebugLog.w(TAG, "$op callback threw", t)
        }
    }

    private companion object {
        private const val TAG = "ProgressThrottle"
    }
}