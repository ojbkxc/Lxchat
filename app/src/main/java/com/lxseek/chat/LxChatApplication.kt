package com.lxseek.chat

import android.app.Application
import com.lxseek.chat.di.AppContainer
import com.lxseek.chat.util.CrashReporter
import com.lxseek.chat.util.DebugLog

/**
 * Application entry point. Installs the crash reporter before any other component runs so
 * that crashes occurring during startup are captured as well.
 *
 * Owns the process-scoped [AppContainer] so that shared singletons (data layer, providers,
 * generation infrastructure) outlive any single Activity/ViewModel and are reachable from
 * background components (Workers, scheduled task execution) — not just the UI.
 */
class LxChatApplication : Application() {
    /** Process-lifetime dependency container. The single source of shared singletons. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // TEMP: force-enable DebugLog for release-build diagnostics
        DebugLog.forceEnabled = true
        DebugLog.init(this)
        try {
            CrashReporter.install(this)
        } catch (e: Throwable) {
            // CrashReporter itself must never crash onCreate. Fall back to platform handler.
            android.util.Log.e("LxChatApplication", "CrashReporter.install failed", e)
        }
        // Orphaned Run recovery is the startup barrier for every generator and scheduler.
        // Background initialization must never kill the process — the UI can still launch
        // even if recovery/scheduling fails (they self-heal on next interaction).
        try {
            container.startProcessServices()
        } catch (e: Throwable) {
            android.util.Log.e("LxChatApplication", "startProcessServices failed", e)
            CrashReporter.note("LxChatApplication.startProcessServices threw ${e.javaClass.simpleName}")
        }
    }
}
