package com.lxseek.chat.adb

import android.content.Context
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

/**
 * Shizuku user service that runs in the privileged (root/shell) process.
 *
 * Shizuku's UserService mechanism lets this app bounce a shell command over a
 * Binder to an isolated process that runs with the Shizuku server's UID. This
 * replaces the private `Shizuku.newProcess()` path (private since API 13.1.5).
 *
 * The class is instantiated reflectively by the Shizuku server using the fully
 * qualified class name from [rikka.shizuku.Shizuku.UserServiceArgs], so it MUST
 * stay public, expose a public no-arg constructor (plus a v13+ [Context]
 * constructor), (`R`obfuscation keep) and not be stripped by R8 — see
 * proguard-rules.pro. Note the process is not a normal Android app process, so
 * only plain Runtime/OS APIs are used here.
 */
@Keep
class ShellUserService : IShellService.Stub() {

    /** v13+ constructor; the Context is created with createPackageContextAsUser. */
    @Keep
    @Suppress("unused")
    constructor(context: Context) : this()

    /** Reserved by Shizuku: called when the user service is removed. */
    override fun destroy() {
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun exit() {
        destroy()
    }

    @Throws(android.os.RemoteException::class)
    override fun exec(cmd: String?): String {
        val command = cmd ?: return ""
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = StringBuilder()
            val err = StringBuilder()
            Thread {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) out.appendLine(line)
                    }
                } catch (_: Exception) {
                    // ignore read failures
                }
            }.start()
            Thread {
                try {
                    process.errorStream.bufferedReader().useLines { lines ->
                        for (line in lines) err.appendLine(line)
                    }
                } catch (_: Exception) {
                    // ignore read failures
                }
            }.start()

            val finished = process.waitFor(20, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val exit = if (finished) process.exitValue() else -1
            buildString {
                append("exit=").append(exit).append('\n')
                if (out.isNotBlank()) append(out)
                if (err.isNotBlank()) {
                    if (out.isNotBlank()) append('\n')
                    append(err)
                }
            }.trim().take(65536)
        } catch (e: Exception) {
            "shell error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}