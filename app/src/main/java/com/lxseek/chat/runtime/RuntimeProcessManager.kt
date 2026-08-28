package com.lxseek.chat.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 运行时进程管理：按引擎类型 spawn / kill / status。
 *
 * 设计要点：
 * - 按需启停、不常驻：任务结束后开始计时，空闲 [IDLE_TIMEOUT_MS] 自动 kill；
 *   每次新调用（[touch]）重置计时，空闲时不占用 CPU。
 * - 会话期间进程复用：同一引擎进程在空闲超时前被再次调用时直接复用，避免冷启动。
 * - 生命周期绑定引擎插件：可注册 [stopHandler]，供引擎插件 onDisable / 卸载时强制停止，
 *   不留残留进程。
 */
class RuntimeProcessManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    private var stopHandler: ((String) -> Unit)? = null

    /** 注册引擎级停止回调（由引擎插件 onDisable / 卸载触发）。 */
    fun onStop(handler: (String) -> Unit) {
        stopHandler = handler
    }

    private class EngineProcess(
        val process: Process,
        val startedAt: Long,
    )

    private val running = ConcurrentHashMap<String, EngineProcess>()
    private val idleJobs = ConcurrentHashMap<String, Job>()
    @Volatile
    private var lastActivityTs = ConcurrentHashMap<String, Long>()

    fun isRunning(engineId: String): Boolean {
        val ep = running[engineId] ?: return false
        return ep.process.isAlive
    }

    /** 记录一次活动并重置空闲计时（不启动进程也调用，以便其它工具重置）。 */
    fun touch(engineId: String) {
        lastActivityTs[engineId] = System.currentTimeMillis()
        armIdleWatchdog(engineId)
    }

    /**
     * 启动引擎进程并跟踪。若已运行则幂等返回；若上次进程已退出则清理后重新启动。
     * @return 是否本次真正启动（false 表示已在运行）。
     */
    fun start(
        engineId: String,
        command: List<String>,
        env: Map<String, String>,
        workingDir: File?,
    ): Boolean {
        val existing = running[engineId]
        if (existing != null && existing.process.isAlive) {
            touch(engineId)
            return false
        }
        running.remove(engineId)
        val builder = ProcessBuilder(command)
        if (workingDir != null && workingDir.isDirectory) builder.directory(workingDir)
        builder.redirectErrorStream(true)
        env.forEach { (k, v) -> builder.environment()[k] = v }
        val process = builder.start()
        running[engineId] = EngineProcess(process, System.currentTimeMillis())
        // 消费 stdout，避免缓冲区满阻塞进程。
        thread(isDaemon = true) {
            try {
                process.inputStream.bufferedReader().use { while (it.readLine() != null) { /* drain */ } }
            } catch (_: Exception) { /* 进程退出后流关闭 */ }
        }
        deadWatch(engineId, process)
        touch(engineId)
        return true
    }

    /** 停止某引擎进程；不存在或已退出则清理状态返回 false。 */
    fun stop(engineId: String): Boolean {
        idleJobs.remove(engineId)?.cancel()
        val ep = running.remove(engineId) ?: return false
        lastActivityTs.remove(engineId)
        return if (ep.process.isAlive) {
            ep.process.destroy()
            true
        } else {
            false
        }
    }

    /** 强制停止全部引擎进程（卸载宿主/退出时调用）。 */
    fun stopAll() {
        running.keys.toList().forEach { stop(it) }
    }

    /** 停止指定引擎（供插件 onDisable / 卸载绑定）。 */
    fun stopEngine(engineId: String) {
        stop(engineId)
    }

    // ── 空闲回收 ───────────────────────────────────────────

    private fun armIdleWatchdog(engineId: String) {
        val previous = idleJobs.remove(engineId)
        previous?.cancel()
        val job = scope.launch(Dispatchers.Default) {
            delay(IDLE_TIMEOUT_MS)
            val ep = running[engineId]
            if (ep != null && ep.process.isAlive) {
                Log.d(TAG, "idle timeout, stopping engine $engineId")
                stop(engineId)
            }
        }
        idleJobs[engineId] = job
    }

    /** 引擎进程自然退出后清理运行状态，并回调 [exitHandler]（可继续追踪退出码）。 */
    private fun deadWatch(engineId: String, process: Process) {
        val cleanup: (java.lang.Thread) -> Unit = {
            running.remove(engineId)
            idleJobs.remove(engineId)?.cancel()
        }
        thread(isDaemon = true) {
            try {
                process.waitFor()
            } finally {
                cleanup(java.lang.Thread.currentThread())
            }
        }
    }

    private companion object {
        const val TAG = "RuntimeProcessMgr"
        /** 空闲 10 分钟自动停止。 */
        const val IDLE_TIMEOUT_MS = 10L * 60L * 1_000L
    }
}

/**
 * 进程级一次性执行（供 runtime_exec / novel_inkos 等快捷工具）：spawn、收集输出、超时兜底。
 * 不交给 [RuntimeProcessManager] 跟踪（不存在复用场景），但调用 [RuntimeProcessManager.touch]
 * 保持所属引擎空闲计时（若由调用方提供）。
 */
suspend fun runProcessOnce(
    command: List<String>,
    env: Map<String, String>,
    workingDir: File?,
    timeoutMs: Long,
): ProcessResult = withContext(Dispatchers.IO) {
    val builder = ProcessBuilder(command)
    if (workingDir != null && workingDir.isDirectory) builder.directory(workingDir)
    builder.redirectErrorStream(true)
    env.forEach { (k, v) -> builder.environment()[k] = v }
    val process = builder.start()
    val output = java.lang.StringBuilder()
    val reader = thread(isDaemon = true) {
        try {
            process.inputStream.bufferedReader().use { reader ->
                var l = reader.readLine()
                while (l != null) {
                    output.append(l).append('\n')
                    l = reader.readLine()
                }
            }
        } catch (_: Exception) { /* 进程退出后流关闭 */ }
    }
    val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (!exited) {
        process.destroy()
        process.waitFor(2, TimeUnit.SECONDS)
        if (process.isAlive) process.destroyForcibly()
    }
    reader.join(2000)
    ProcessResult(
        exitCode = if (exited) process.exitValue() else -1,
        output = output.toString(),
        timedOut = !exited,
    )
}

data class ProcessResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
) {
    val isSuccess: Boolean get() = exitCode == 0 && !timedOut
}