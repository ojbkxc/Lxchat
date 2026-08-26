package com.lxseek.chat.adb

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
        val result = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            p.waitFor()
            p.exitValue() == 0
        } catch (e: Exception) {
            false
        }
        cached = result
        return result
    }

    /** Clears the cached result so the next call re-checks. Useful after a root toggle. */
    fun invalidate() {
        cached = null
    }
}