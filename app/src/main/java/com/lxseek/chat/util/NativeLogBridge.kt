package com.lxseek.chat.util

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Native → [DebugLog] 日志桥接。
 *
 * 把 native（Rust / C++ NDK）层通过 JNI 上抛的日志事件统一转发到 Lxchat 已有的
 * [DebugLog]，避免 native 侧直接调 `android.util.Log` 绕过 [DebugLog] 的开关与
 * 脱敏逻辑。对应 HyX `mobile/src/lib.rs` 中的 `JniLogLayer`：
 *
 * ```
 * tracing::event!
 *   → JniLogLayer::on_event           (tracing-subscriber Layer)
 *   → attach_current_thread()         (JNIEnv for this thread)
 *   → callback.onLog(level, tag, msg) (JNI call_method)
 *   → NativeLogBridge.onNativeLog     (本类，@JvmStatic)
 *   → DebugLog.{e,w,i,d}              (统一出口)
 * ```
 *
 * # 级别映射
 * 沿用 HyX `level_to_int` 的约定（与 `tracing::Level` 的 `Ord` 一致）：
 * - 0 = TRACE
 * - 1 = DEBUG
 * - 2 = INFO
 * - 3 = WARN
 * - 4 = ERROR
 *
 * 若上游使用相反约定（0=error … 4=trace），置 [levelSchemeReversed] = true 翻转。
 *
 * # 线程安全
 * [installed] 与 [listener] 均为原子引用，可在任意线程调用——native 日志事件
 * 通常来自 Tokio worker 线程或 llama.cpp 推理线程，经 JNI 上抛。
 *
 * # NDK 接入
 * Lxchat 已有 NDK 层（`lxchat_llama`，见 `LlamaChatEngine.kt`）。native 侧回调
 * 方式：通过 JNI `FindClass("com/lxseek/chat/util/NativeLogBridge")` +
 * `GetStaticMethodID("onNativeLog", "(ILjava/lang/String;Ljava/lang/String;)V")`
 * 调用本类的 [onNativeLog]（`@JvmStatic`）。
 *
 * 若 native 侧实现了 [nativeInstallLogBridge] / [nativeUninstallLogBridge]，
 * [install] / [uninstall] 会通过 `external fun` 通知 native 注册/注销回调；
 * 若 native 侧未实现（`UnsatisfiedLinkError`），自动降级为纯 Kotlin 模式——
 * Kotlin 侧仍可在 native 调用 [onNativeLog] 前手动 [install]。
 */
object NativeLogBridge {

    /** Rust tracing 级别 → int，与 HyX `level_to_int` 一致。 */
    const val LEVEL_TRACE = 0
    const val LEVEL_DEBUG = 1
    const val LEVEL_INFO = 2
    const val LEVEL_WARN = 3
    const val LEVEL_ERROR = 4

    /**
     * 默认按 HyX 约定（0=trace … 4=error）。置 true 则按相反约定
     * （0=error … 4=trace）解释 [onNativeLog] 的 level 参数。
     */
    @Volatile
    var levelSchemeReversed = false

    /**
     * 是否在 [onNativeLog] 里捕获并吞掉回调过程中抛出的异常。
     * native 侧经 JNI 调用时，Kotlin 异常会变成 pending JNI exception，
     * 若不 clear 会阻塞后续 JNI 调用（HyX 的 `env.exception_clear()` 即此意）。
     * 默认 true，与 HyX 行为一致。
     */
    @Volatile
    var swallowCallbackExceptions = true

    private const val TAG = "NativeLogBridge"

    private val installed = AtomicBoolean(false)

    /**
     * 旁路监听器：在 [DebugLog] 之外把 native 日志事件同步给 UI
     * （类似 HyX 的 `LogCollector`，用于日志面板展示）。null 表示无。
     */
    private val listener = AtomicReference<((level: Int, tag: String, msg: String) -> Unit)?>()

    /** 桥接是否已安装。 */
    fun isInstalled(): Boolean = installed.get()

    /**
     * 安装桥接。幂等：重复调用无副作用。
     *
     * 若 native 侧实现了 [nativeInstallLogBridge]，会一并通知 native 注册回调；
     * 否则静默降级为纯 Kotlin 模式（仍可接收 native 主动调用的 [onNativeLog]）。
     *
     * @return true 表示已处于安装态（本次或之前安装成功）
     */
    fun install(): Boolean {
        if (!installed.compareAndSet(false, true)) {
            DebugLog.d(TAG, "install: already installed")
            return true
        }
        try {
            nativeInstallLogBridge()
        } catch (_: UnsatisfiedLinkError) {
            // native 侧未实现注册入口——纯 Kotlin 模式，仍可工作。
        } catch (e: Throwable) {
            if (swallowCallbackExceptions) DebugLog.w(TAG, "nativeInstallLogBridge failed", e)
            else throw e
        }
        DebugLog.d(TAG, "native log bridge installed")
        return true
    }

    /**
     * 卸载桥接。幂等。卸载后 [onNativeLog] 仍可被 native 调用，但会直接丢弃
     * （避免 native 侧未感知卸载时崩溃）。
     */
    fun uninstall() {
        if (!installed.compareAndSet(true, false)) {
            return
        }
        try {
            nativeUninstallLogBridge()
        } catch (_: UnsatisfiedLinkError) {
            // native 侧未实现——无副作用。
        } catch (e: Throwable) {
            if (swallowCallbackExceptions) DebugLog.w(TAG, "nativeUninstallLogBridge failed", e)
            else throw e
        }
        listener.set(null)
        DebugLog.d(TAG, "native log bridge uninstalled")
    }

    /**
     * 设置旁路监听器（用于 UI 日志面板）。传 null 清除。
     * 监听器在 [onNativeLog] 内同步调用，应快速返回，避免阻塞 native 线程。
     */
    fun setListener(l: ((level: Int, tag: String, msg: String) -> Unit)?) {
        listener.set(l)
    }

    /**
     * Native 日志回调入口。由 native 层通过 JNI 调用（`@JvmStatic`），或由
     * Kotlin 侧的 native 桥接代码调用。
     *
     * @param level Rust tracing 级别（见 [LEVEL_TRACE] 等；若 [levelSchemeReversed]
     *              为 true 则按相反约定解释）
     * @param tag   日志 tag，通常是 Rust 模块路径（如 `hyx_core::network::udp`）
     * @param msg   日志消息，可能含 `key=value` 附加字段（由 native 侧的
     *              `FieldCollector` 拼接，见 HyX `lib.rs`）
     */
    @JvmStatic
    fun onNativeLog(level: Int, tag: String, msg: String) {
        if (!installed.get()) return
        val mapped = if (levelSchemeReversed) LEVEL_ERROR - level else level
        try {
            dispatch(mapped, tag, msg)
        } catch (e: Throwable) {
            if (!swallowCallbackExceptions) throw e
        }
        // 旁路监听器单独 try，避免监听器异常影响主分发。
        listener.get()?.let { l ->
            try {
                l.invoke(level, tag, msg)
            } catch (e: Throwable) {
                if (!swallowCallbackExceptions) throw e
            }
        }
    }

    /**
     * 集中映射到 [DebugLog]，避免在多处散落 if/when。
     * trace 并入 debug（Logcat 无 trace 级别），未知级别带前缀并入 debug。
     */
    private fun dispatch(level: Int, tag: String, msg: String) {
        when (level) {
            LEVEL_ERROR -> DebugLog.e(tag, msg)
            LEVEL_WARN -> DebugLog.w(tag, msg)
            LEVEL_INFO -> DebugLog.i(tag, msg)
            LEVEL_DEBUG -> DebugLog.d(tag, msg)
            LEVEL_TRACE -> DebugLog.d(tag, msg)
            else -> DebugLog.d(tag, "[lvl=$level] $msg")
        }
    }

    /**
     * 通知 native 侧注册日志回调（native → Kotlin 的 JNI 入口已固定为
     * [onNativeLog] 的静态方法签名）。native 侧可选实现；未实现时
     * [install] 会捕获 `UnsatisfiedLinkError` 优雅降级。
     *
     * JNI 符号：`Java_com_lxseek_chat_util_NativeLogBridge_nativeInstallLogBridge`。
     */
    @JvmStatic
    private external fun nativeInstallLogBridge()

    /**
     * 通知 native 侧注销日志回调。与 [nativeInstallLogBridge] 对称。
     *
     * JNI 符号：`Java_com_lxseek_chat_util_NativeLogBridge_nativeUninstallLogBridge`。
     */
    @JvmStatic
    private external fun nativeUninstallLogBridge()
}