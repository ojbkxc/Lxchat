package com.lxseek.chat.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLEncoder
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Anonymous, opt-in crash reporting.
 *
 * On an uncaught exception we persist a single pending report to disk and then let the
 * platform's default handler terminate the process normally. On the next launch the UI
 * detects the pending report and offers the user a one-tap, opt-in way to file a
 * pre-filled GitHub issue on the project's repository; nothing is ever sent without
 * that explicit action.
 *
 * The report carries only a stack trace plus coarse, non-identifying environment data
 * (app version, Android API level, device model) — no user content, no device IDs.
 */
object CrashReporter {

    private const val ISSUE_URL = "https://github.com/ojbkxc/lxchat/issues/new"
    private const val DIR = "crash"
    private const val FILE = "pending.json"
    private const val MAX_TRACE_CHARS = 60_000

    /** Coarse, non-identifying app identity captured once at install time. */
    private data class AppInfo(val versionName: String, val versionCode: Long)

    @Volatile private var appInfo: AppInfo = AppInfo("?", 0)

    /** Rolling diagnostic trail attached to crash reports. Helps pin down crashes we can't
     *  reproduce locally (e.g. the foreground-service start-in-time timeout) by recording
     *  what happened just before, with timestamps. No user content — only coarse event tags. */
    private const val MAX_BREADCRUMBS = 60
    private val breadcrumbs = ConcurrentLinkedDeque<String>()

    /** Append a timestamped breadcrumb to the diagnostic trail (thread-safe, bounded). */
    fun note(message: String) {
        breadcrumbs.addLast("${System.currentTimeMillis()} $message")
        while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.pollFirst()
    }

    /**
     * Registers the global uncaught-exception handler. Call once, as early as possible
     * (Application.onCreate), so crashes during startup are captured too.
     */
    fun install(context: Context) {
        appInfo = readAppInfo(context)
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(appContext, throwable) }
            // Always chain to the platform handler so the process dies as it normally would.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Returns the pending crash report JSON, or null if none is waiting. */
    fun pendingReport(context: Context): String? {
        val f = reportFile(context)
        if (!f.exists() || f.length() == 0L) return null
        return runCatching { f.readText() }.getOrNull()
    }

    /** Discards the pending report (after the user submits or dismisses it). */
    fun clear(context: Context) {
        runCatching { reportFile(context).delete() }
    }

    /**
     * Builds a pre-filled GitHub issue URL for the given crash report so the user can
     * review and submit it manually in their browser. No network access required.
     */
    fun issueUrl(reportJson: String): String {
        val obj = runCatching { JSONObject(reportJson) }.getOrNull() ?: JSONObject()
        val trace = obj.optString("trace", "")
        val version = obj.optString("appVersion", "?")
        val code = obj.optLong("versionCode", 0)
        val api = obj.optInt("androidApi", 0)
        val release = obj.optString("androidRelease", "?")
        val device = obj.optString("device", "?")
        val ts = obj.optLong("ts", 0)
        val crumbs = runCatching {
            obj.optJSONArray("breadcrumbs")?.let { arr ->
                buildString {
                    for (i in 0 until arr.length()) {
                        if (isNotEmpty()) append('\n')
                        append("- ").append(arr.optString(i))
                    }
                }
            }
        }.getOrNull().orEmpty()

        val title = "Crash report — v$version"
        val body = buildString {
            append("## Crash report\n\n")
            append("| Field | Value |\n|---|---|\n")
            append("| App version | $version ($code) |\n")
            append("| Android API | $api |\n")
            append("| Android release | $release |\n")
            append("| Device | $device |\n")
            append("| Timestamp | $ts |\n\n")
            if (crumbs.isNotEmpty()) {
                append("### Breadcrumbs\n\n")
                append(crumbs).append("\n\n")
            }
            append("### Stack trace\n\n")
            append("```\n")
            append(trace)
            append("\n```\n")
        }

        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedBody = URLEncoder.encode(body, "UTF-8")
        return "$ISSUE_URL?title=$encodedTitle&body=$encodedBody"
    }

    private fun writeReport(context: Context, throwable: Throwable) {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
            .toString().take(MAX_TRACE_CHARS)
        val json = JSONObject().apply {
            put("trace", trace)
            put("appVersion", appInfo.versionName)
            put("versionCode", appInfo.versionCode)
            put("androidApi", Build.VERSION.SDK_INT)
            put("androidRelease", Build.VERSION.RELEASE)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("ts", System.currentTimeMillis())
            put("breadcrumbs", JSONArray(breadcrumbs.toList()))
        }
        val jsonText = json.toString()
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        File(dir, FILE).writeText(jsonText)
        runCatching { mirrorToDownloads(context, jsonText) }
    }

    /**
     * Mirrors the crash report into the public Downloads/LxChat directory via MediaStore so the
     * user can retrieve it from any file manager without root. Best-effort: failures are swallowed
     * by the caller (never crashes the crash handler itself). On API 29+ scoped storage writes to
     * Downloads need no permission; on API 24-28 the WRITE_EXTERNAL_STORAGE permission (maxSdk 28)
     * covers legacy writes.
     */
    private fun mirrorToDownloads(context: Context, jsonText: String) {
        val ts = System.currentTimeMillis()
        val name = "lxchat-crash-$ts.json"
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LxChat")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            try {
                resolver.openOutputStream(uri)?.use { it.write(jsonText.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (_: Exception) {
                runCatching { resolver.delete(uri, null, null) }
            }
        } else {
            val downloads = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "LxChat"
            ).apply { mkdirs() }
            File(downloads, name).writeText(jsonText)
        }
    }

    private fun reportFile(context: Context): File = File(File(context.filesDir, DIR), FILE)

    /**
     * Proactively exports a diagnostic snapshot (breadcrumbs + TTS diagnostic text) to the public
     * Downloads/LxChat directory. Intended for non-crash issues (e.g. TTS not working) where the
     * user wants to share logs with developers. Returns the display name on success, null on failure.
     */
    fun exportDiagnostics(context: Context, extra: String = ""): String? {
        val ts = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("=== LxChat Diagnostics ===\n")
        sb.append("Timestamp: $ts\n")
        sb.append("App version: ${appInfo.versionName} (${appInfo.versionCode})\n")
        sb.append("Android API: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n\n")
        sb.append("=== Breadcrumbs ===\n")
        for (crumb in breadcrumbs) sb.append(crumb).append('\n')
        if (extra.isNotEmpty()) {
            sb.append("\n=== Extra ===\n")
            sb.append(extra).append('\n')
        }
        val text = sb.toString()
        val name = "lxchat-diagnostics-$ts.txt"
        return runCatching {
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LxChat")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                try {
                    resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    name
                } catch (_: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    null
                }
            } else {
                val downloads = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "LxChat"
                ).apply { mkdirs() }
                File(downloads, name).writeText(text)
                name
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun readAppInfo(context: Context): AppInfo = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else pi.versionCode.toLong()
        AppInfo(pi.versionName ?: "?", code)
    }.getOrDefault(AppInfo("?", 0))
}
