package com.lxseek.chat.util

import android.content.Context

object DebugLog {
    @Volatile
    var forceEnabled = false
    @Volatile
    private var enabled = true

    fun init(context: Context) {
        enabled = forceEnabled || (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private val active: Boolean get() = forceEnabled || enabled

    fun d(tag: String, msg: String) { if (active) android.util.Log.d(tag, msg) }
    fun d(tag: String, msg: String, tr: Throwable) {
        if (active) android.util.Log.d(tag, "$msg ${safeThrowableSummary(tr)}")
    }
    fun e(tag: String, msg: String) { if (active) android.util.Log.e(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable) {
        if (active) android.util.Log.e(tag, "$msg ${safeThrowableSummary(tr)}")
    }
    fun w(tag: String, msg: String) { if (active) android.util.Log.w(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable) {
        if (active) android.util.Log.w(tag, "$msg ${safeThrowableSummary(tr)}")
    }

    /**
     * Throwable messages and cause chains frequently contain request URLs, response excerpts,
     * local paths, or user content. Keep the exception type and application stack locations for
     * diagnostics without forwarding those uncontrolled strings to Logcat.
     */
    internal fun safeThrowableSummary(tr: Throwable): String = buildString {
        append("exception=")
        append(tr.javaClass.name)
        tr.stackTrace
            .asSequence()
            .take(MAX_SAFE_STACK_FRAMES)
            .forEach { frame ->
                append("\n\tat ")
                append(frame)
            }
    }

    private const val MAX_SAFE_STACK_FRAMES = 24
}
