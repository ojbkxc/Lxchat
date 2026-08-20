package com.lxseek.chat.sandbox

import android.content.Context
import com.lxseek.chat.data.repository.SettingsRepository

class FdroidSandboxManagerFactory(
    private val context: Context,
    private val settings: SettingsRepository,
) : SandboxManagerFactory {
    // Return the SAME ProotSandboxManager on every create(). All three creation sites
    // (ChatViewModel, GenerationManager.shellToolProvider, TaskExecutionEngine) previously
    // got distinct instances sharing the same on-disk Alpine rootfs, so concurrent shell/file
    // operations across conversations could corrupt lib/apk/db/installed and /etc/apk/world.
    // A single shared instance lets ProotSandboxManager's internal mutex serialize mutations.
    private val shared by lazy { ProotSandboxManager(context, settings) }
    override fun create(): SandboxManager = shared
    override fun isAvailable(): Boolean = true
}
