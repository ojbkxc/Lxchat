package com.lxseek.chat.adb

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.lxseek.chat.BuildConfig
import rikka.shizuku.Shizuku

/**
 * Shizuku 后端管理器：替代 LadbManager，为非 root 设备提供 ADB 级别的 shell 访问。
 *
 * Shizuku 通过 Rikka 的 Shizuku app（包名 [SHIZUKU_PACKAGE]）以系统服务方式运行，
 * 本 app 通过 `dev.rikka.shizuku:api` 与之通信。要使本类可用，必须满足三个条件：
 *   1. Shizuku app 已安装（[isShizukuInstalled]）
 *   2. Shizuku 服务已启动（[isShizukuRunning]，由 [Shizuku.pingBinder] 检测）
 *   3. 本 app 已被用户授权（[isPermissionGranted]，由 [Shizuku.checkSelfPermission] 检测）
 *
 * 三者均满足时 [isReady] 返回 true，此时 [executeCommand] 通过 Shizuku 的
 * UserService（[ShellUserService]）在特权进程中执行任意 shell 命令
 * （uid=root 或 shell，取决于 Shizuku 启动方式）。
 *
 * 说明：Shizuku API 13.1.5 中 `Shizuku.newProcess` 已改为 private，官方推荐用
 * UserService 替代。这里通过 [Shizuku.bindUserService] 绑定隔离进程中的
 * [IShellService] 服务来执行命令。
 */
class ShizukuManager(private val context: Context) {

    companion object {
        /** Shizuku app 包名。 */
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /** Shizuku 在 Google Play 的下载链接。 */
        const val SHIZUKU_PLAY_URL =
            "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"

        /** Shizuku 官网（提供文档与其它下载渠道）。 */
        const val SHIZUKU_WEBSITE_URL = "https://shizuku.rikka.app/"

        /** requestPermission 使用的请求码。 */
        private const val PERMISSION_REQUEST_CODE = 1001

        private const val TAG = "ShizukuManager"

        /** 等待 user-service binder 连上的最长时间（毫秒）。 */
        private const val BIND_TIMEOUT_MS = 15_000L

        /** 组装绑定参数：组件指向 [ShellUserService]，非 daemon（进程随宿主结束）。 */
        private fun createArgs(): Shizuku.UserServiceArgs =
            Shizuku.UserServiceArgs(
                ComponentName("com.lxseek.chat", ShellUserService::class.java.name),
            )
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(false)
                .version(BuildConfig.VERSION_CODE.coerceAtLeast(1))
    }

    /** 当前连接的 [IShellService] 代理；为空表示尚未连接。 */
    @Volatile
    private var shellService: IShellService? = null

    /** 为等待连接唤醒/超时用的监听锁。 */
    private val lock = java.lang.Object()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(lock) {
                shellService = IShellService.Stub.asInterface(binder)
                lock.notifyAll()
            }
            AdbLog.log("ShizukuManager: shell service connected ($name)")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(lock) { shellService = null }
            AdbLog.log("ShizukuManager: shell service disconnected")
        }

        override fun onBindingDied(name: ComponentName) {
            onServiceDisconnected(name)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        AdbLog.log("ShizukuManager: binder received; reconnecting shell service")
        connectIfPossible()
    }

    init {
        // 当 Shizuku binder（重新）就绪时自动（重新）建立 user-service 连接。
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
    }

    /** 条件允许时绑定 user service。 */
    private fun connectIfPossible() {
        if (isReady()) {
            try {
                Shizuku.bindUserService(createArgs(), serviceConnection)
            } catch (e: Exception) {
                AdbLog.log("ShizukuManager: bindUserService failed — ${e.javaClass.name}: ${e.message}")
            }
        }
    }

    /** Shizuku app 是否已安装。 */
    fun isShizukuInstalled(): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Shizuku 服务是否在运行（binder 是否存活）。未安装时返回 false。 */
    fun isShizukuRunning(): Boolean = try {
        isShizukuInstalled() && Shizuku.pingBinder()
    } catch (e: Exception) {
        AdbLog.log("ShizukuManager: pingBinder failed — ${e.javaClass.name}: ${e.message}")
        false
    }

    /** 本 app 是否已被 Shizuku 授权。服务未运行时返回 false。 */
    fun isPermissionGranted(): Boolean = try {
        isShizukuRunning() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        AdbLog.log("ShizukuManager: checkSelfPermission failed — ${e.javaClass.name}: ${e.message}")
        false
    }

    /** 请求 Shizuku 运行时权限。调用后系统会弹出 Shizuku 的授权对话框。 */
    fun requestPermission() {
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            AdbLog.log("ShizukuManager: requestPermission failed — ${e.javaClass.name}: ${e.message}")
        }
    }

    /** Shizuku 是否完全就绪：已安装 + 服务运行 + 已授权。 */
    fun isReady(): Boolean = isShizukuInstalled() && isShizukuRunning() && isPermissionGranted()

    /**
     * 通过 Shizuku 执行一条 shell 命令并返回标准输出（stdout + stderr 合并）。
     *
     * 实现细节：
     *  - 若 [cmd] 以 `adb shell ` 开头，自动剥离该前缀（兼容旧工具调用约定）；
     *  - 通过 [IShellService.exec] 在特权进程中执行 `sh -c <command>`；
     *  - 首次调用会绑定 user service 并最多等待 [BIND_TIMEOUT_MS]；
     *  - 输出解析由远程服务完成并截断，防止 OOM。
     *
     * @param cmd 要执行的 shell 命令字符串。
     * @param timeoutMs 保留参数（绑定等待会取它和 [BIND_TIMEOUT_MS] 较小值）。
     * @return 命令输出文本。
     * @throws IllegalStateException 当 Shizuku 未就绪或服务连接超时时抛出，调用方应先 [isReady] 检查。
     */
    fun executeCommand(cmd: String, timeoutMs: Int = 30_000): String {
        if (!isReady()) {
            throw IllegalStateException("Shizuku not ready (installed=${isShizukuInstalled()}, running=${isShizukuRunning()}, granted=${isPermissionGranted()})")
        }

        // 剥离 "adb shell " 前缀，让上层可以直接传 adb shell xxx 形式的命令。
        val actualCmd = if (cmd.startsWith("adb shell ")) {
            cmd.removePrefix("adb shell ")
        } else if (cmd.startsWith("adb shell\t")) {
            cmd.removePrefix("adb shell\t")
        } else {
            cmd
        }

        AdbLog.log("ShizukuManager: exec → $actualCmd")

        val service = obtainService(timeoutMs.toLong())
            ?: throw IllegalStateException("Shizuku shell service unavailable (bind timeout)")

        return try {
            val output = service.exec(actualCmd) ?: ""
            AdbLog.log("ShizukuManager: exec len=${output.length}")
            output
        } catch (e: Exception) {
            // IPC 失败通常是服务进程被杀，丢弃代理以便下次重新绑定。
            synchronized(lock) { shellService = null }
            AdbLog.log("ShizukuManager: exec threw — ${e.javaClass.name}: ${e.message}")
            throw e
        }
    }

    /** 获取 [IShellService] 代理；为空时发起绑定并等待（最多至超时）。 */
    private fun obtainService(timeoutMs: Long): IShellService? {
        val existing = shellService
        if (existing != null) return existing

        val waitLimit = timeoutMs.coerceIn(1_000, BIND_TIMEOUT_MS)
        synchronized(lock) {
            try {
                Shizuku.bindUserService(createArgs(), serviceConnection)
            } catch (e: Exception) {
                AdbLog.log("ShizukuManager: bindUserService failed — ${e.javaClass.name}: ${e.message}")
            }
            val deadline = System.currentTimeMillis() + waitLimit
            while (shellService == null && System.currentTimeMillis() < deadline) {
                try {
                    (lock as java.lang.Object).wait((deadline - System.currentTimeMillis()).coerceAtLeast(1L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            return shellService
        }
    }
}