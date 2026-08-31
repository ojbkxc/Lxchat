package com.lxseek.chat.adb

import com.lxseek.chat.util.DebugLog

/**
 * Detects whether root access (su binary) is available on the device.
 *
 * Result is cached after the first check for the lifetime of the process, since the
 * availability of `su` does not change without a reboot or Magisk toggle (which itself
 * requires a process restart to take effect for new exec calls).
 */
object RootDetector {
    private var cached: Boolean? = null

    /**
     * Returns true if the `su` binary is present and can elevate privileges.
     * The check runs `su -c id` and verifies a zero exit code.
     */
    fun isRootAvailable(): Boolean {
        cached?.let { return it }
        var result = false
        var detail = ""
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val waitOk = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val err = p.errorStream.bufferedReader().use { it.readText() }
            result = waitOk && p.exitValue() == 0
            detail = "waitOk=$waitOk exit=${if (waitOk) p.exitValue() else -1} out=${out.trim()} err=${err.trim()}"
        } catch (e: Exception) {
            detail = "exception=${e.javaClass.name}: ${e.message}"
        }
        // 镜像到进程内日志源，让用户看到 su 检测的具体结果（root 未生效时原因一目了然）。
        AdbLog.log("RootDetector: ${if (result) "root available" else "root NOT available"} — $detail")
        DebugLog.d("RootDetector", "isRootAvailable=$result detail=$detail")
        cached = result
        return result
    }

    /** Clears the cached result so the next call re-checks. Useful after a root toggle. */
    fun invalidate() {
        cached = null
    }
}