package com.lxseek.chat.adb

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shizuku 后端管理器：替代 LadbManager，为非 root 设备提供 ADB 级别的 shell 访问。
 *
 * Shizuku 通过 Rikka 的 Shizuku app（包名 [SHIZUKU_PACKAGE]）以系统服务方式运行，
 * 本 app 通过 `dev.rikka.shizuku:api` 与之通信。要使本类可用，必须满足三个条件：
 *   1. Shizuku app 已安装（[isShizukuInstalled]）
 *   2. Shizuku 服务已启动（[isShizukuRunning]，由 [Shizuku.pingBinder] 检测）
 *   3. 本 app 已被用户授权（[isPermissionGranted]，由 [Shizuku.checkSelfPermission] 检测）
 *
 * 三者均满足时 [isReady] 返回 true，此时 [executeCommand] 可直接通过
 * [Shizuku.newProcess] 执行任意 shell 命令（uid=root 或 shell，取决于 Shizuku 启动方式）。
 *
 * 与 LadbManager 的差异：
 *  - 无需下载/打包 adb binary，无需无线调试配对；
 *  - 无需 WRITE_SECURE_SETTINGS；
 *  - 单次 [executeCommand] 启动一个短生命进程，不复用长 shell 管道（更稳，避免僵尸进程）。
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
     *  - 使用 `sh -c <cmd>` 形式调用 [Shizuku.newProcess]，与 `Runtime.exec` 行为一致；
     *  - 进程在 [timeoutMs] 内未结束则强制销毁，避免 hang 死调用线程；
     *  - stdout/stderr 合并读取，超出 64KB 截断，防止 OOM。
     *
     * @param cmd 要执行的 shell 命令字符串。
     * @param timeoutMs 超时毫秒，默认 30s。
     * @return 命令输出文本（trim 后）。
     * @throws IllegalStateException 当 Shizuku 未就绪时抛出，调用方应先 [isReady] 检查。
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

        val process: Process = Shizuku.newProcess(arrayOf("sh", "-c", actualCmd), null, null)
        try {
            // 合并 stdout + stderr
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))

            val maxBytes = 64 * 1024
            val outThread = Thread {
                try {
                    var ch: Int
                    val buf = CharArray(2048)
                    while (reader.read(buf).also { ch = it } > 0) {
                        if (output.length + ch > maxBytes) {
                            output.append(buf, 0, maxBytes - output.length)
                            break
                        }
                        output.append(buf, 0, ch)
                    }
                } catch (_: Exception) {
                    // 读流异常不致命，已读到的内容仍可返回
                }
            }
            val errThread = Thread {
                try {
                    var ch: Int
                    val buf = CharArray(2048)
                    while (errReader.read(buf).also { ch = it } > 0) {
                        if (output.length + ch > maxBytes) {
                            output.append(buf, 0, maxBytes - output.length)
                            break
                        }
                        output.append(buf, 0, ch)
                    }
                } catch (_: Exception) {
                    // 同上
                }
            }
            outThread.start()
            errThread.start()

            val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                outThread.interrupt()
                errThread.interrupt()
                AdbLog.log("ShizukuManager: exec timeout after ${timeoutMs}ms — forcibly destroyed")
                throw IllegalStateException("Shizuku command timed out after ${timeoutMs}ms")
            }
            outThread.join(500)
            errThread.join(500)

            val result = output.toString().trim()
            AdbLog.log("ShizukuManager: exec exit=${process.exitValue()} len=${result.length}")
            return result
        } finally {
            try {
                process.destroy()
            } catch (_: Exception) {
                // ignore
            }
        }
    }
}