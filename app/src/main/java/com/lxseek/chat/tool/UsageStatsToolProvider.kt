package com.lxseek.chat.tool

import android.app.Application
import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.agent.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * On-device application usage statistics tools backed by [UsageStatsManager].
 *
 * Exposes four read-only tools:
 *  - usage_stats_today    : per-app foreground duration for today (midnight → now)
 *  - usage_stats_range    : per-app foreground duration for an arbitrary [start_time, end_time]
 *  - usage_stats_app      : detailed usage for a single package, with a daily breakdown
 *  - usage_stats_summary  : aggregate screen time, top apps, and app count
 *
 * All tools require the PACKAGE_USAGE_STATS special permission. Unlike runtime permissions,
 * this is granted by the user in Settings → Apps → Special access → Usage access. When the
 * grant is missing, every tool returns a structured JSON error with a hint to open the grant
 * page instead of crashing. No external dependencies are used — only Android system APIs.
 *
 * This provider is intentionally self-contained: it is NOT registered in NativeToolsPlugin or
 * the manifest. Wire it up explicitly when the product decides to expose usage stats to the
 * model.
 */
class UsageStatsToolProvider(private val app: Application) : ToolProvider {

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> =
        definitions(ctx).map { def ->
            ToolDescriptor(
                definition = def,
                riskLevel = RiskLevel.ReadOnly,
                tier = ToolTier.Extended,
                requiresApproval = false,
            )
        }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun prop(t: String, d: String) = ToolProperty(t, d)
        return listOf(
            tool(
                "usage_stats_today",
                "Return per-app foreground usage duration for today (midnight to now). Each entry " +
                    "has package_name, app_name, foreground_duration_ms, foreground_duration_human, " +
                    "and last_used_ms. Requires the PACKAGE_USAGE_STATS special permission.",
                emptyMap(), emptyList(),
            ),
            tool(
                "usage_stats_range",
                "Return per-app foreground usage duration for a custom time range. Both bounds are " +
                    "epoch milliseconds. Requires the PACKAGE_USAGE_STATS special permission.",
                mapOf(
                    "start_time" to prop("integer", "Range start in epoch milliseconds (inclusive)."),
                    "end_time" to prop("integer", "Range end in epoch milliseconds (inclusive). Defaults to now."),
                ),
                listOf("start_time"),
            ),
            tool(
                "usage_stats_app",
                "Return detailed usage for one application: total foreground time, last time used, " +
                    "and a daily breakdown across the requested range. Requires the PACKAGE_USAGE_STATS " +
                    "special permission.",
                mapOf(
                    "package_name" to prop("string", "Android package id, e.g. com.tencent.mm."),
                    "time_range" to prop("string", "Range window: 'today' (default), '7d', '30d', 'all', or N days as a number."),
                ),
                listOf("package_name"),
            ),
            tool(
                "usage_stats_summary",
                "Return a usage summary: total screen time, top N most-used apps, and per-app " +
                    "foreground totals for the requested window. Requires the PACKAGE_USAGE_STATS " +
                    "special permission.",
                mapOf(
                    "time_range" to prop("string", "Range window: 'today' (default), '7d', '30d', 'all', or N days as a number."),
                    "top_n" to prop("integer", "How many top apps to return (1..50), default 10."),
                ),
                emptyList(),
            ),
        )
    }

    override fun handles(name: String): Boolean = name.startsWith("usage_stats_")

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        try {
            if (!hasUsageAccess()) return@withContext err("permission_denied", permissionHint())
            when (name) {
                "usage_stats_today" -> todayJson()
                "usage_stats_range" -> rangeJson(arguments)
                "usage_stats_app" -> appJson(arguments)
                "usage_stats_summary" -> summaryJson(arguments)
                else -> err("unknown_tool", "Unknown usage_stats tool: $name")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("UsageStatsTool", "$name failed", e)
            err("tool_error", e.message)
        }
    }

    // ── Permission handling ────────────────────────────────────

    /**
     * PACKAGE_USAGE_STATS is a special/privileged op validated through AppOps, not a normal
     * runtime permission. We probe [AppOpsManager.OPSTR_GET_USAGE_STATS] (API 23+). On Q+
     * we use [AppOpsManager.unsafeCheckOpNoThrow]; on M–P we fall back to the deprecated
     * [AppOpsManager.checkOpNoThrow] overload that still accepts the string op. Devices below
     * M cannot expose this op, so we report false there.
     */
    private fun hasUsageAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                app.applicationInfo.uid,
                app.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                app.applicationInfo.uid,
                app.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Human-readable hint plus the Settings deep link the UI can open. */
    private fun permissionHint(): String =
        "Usage access not granted. Open Settings → Apps → Special access → Usage access and " +
            "enable ${app.packageName}. Intent: android.settings.USAGE_ACCESS_SETTINGS"

    // ── Tools ──────────────────────────────────────────────────

    private fun todayJson(): String {
        val start = midnightToday()
        val end = System.currentTimeMillis()
        return rangeToJson("usage_stats_today", start, end)
    }

    private fun rangeJson(arguments: String): String {
        val start = argLong("start_time", arguments)
            ?: return err("no_start", "Missing start_time (epoch ms).")
        val end = argLong("end_time", arguments) ?: System.currentTimeMillis()
        if (end <= start) return err("bad_range", "end_time must be greater than start_time.")
        return rangeToJson("usage_stats_range", start, end)
    }

    private fun appJson(arguments: String): String {
        val pkg = argString("package_name", arguments)?.trim()
            ?: return err("no_package", "Missing package_name.")
        val range = argString("time_range", arguments)?.trim()?.lowercase() ?: "today"
        val (start, end) = resolveRange(range)
        val pm = app.packageManager
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            return err("not_found", "Unknown package '$pkg'")
        }
        val stats = queryStats(start, end).firstOrNull { it.packageName == pkg }
        // Daily breakdown across the requested range — one bucket per calendar day.
        val daily = buildJsonArray {
            val dayCal = Calendar.getInstance().apply { timeInMillis = start }
            while (dayCal.timeInMillis < end) {
                val dayStart = dayCal.timeInMillis
                dayCal.add(Calendar.DAY_OF_MONTH, 1)
                val dayEnd = minOf(dayCal.timeInMillis, end)
                val dayStat = queryStats(dayStart, dayEnd).firstOrNull { it.packageName == pkg }
                add(buildJsonObject {
                    put("day", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dayStart)))
                    put("foreground_duration_ms", dayStat?.totalTimeInForeground ?: 0L)
                })
            }
        }
        return buildJsonObject {
            put("type", "usage_stats_app")
            put("package_name", pkg)
            put("app_name", label)
            put("foreground_duration_ms", stats?.totalTimeInForeground ?: 0L)
            put("foreground_duration_human", humanDuration(stats?.totalTimeInForeground ?: 0L))
            put("last_used_ms", stats?.lastTimeUsed ?: 0L)
            put("range_start_ms", start)
            put("range_end_ms", end)
            put("daily", daily)
        }.toString()
    }

    private fun summaryJson(arguments: String): String {
        val range = argString("time_range", arguments)?.trim()?.lowercase() ?: "today"
        val topN = (argInt("top_n", arguments) ?: 10).coerceIn(1, 50)
        val (start, end) = resolveRange(range)
        val pm = app.packageManager
        val stats = queryStats(start, end)
        val totalScreenTime = stats.sumOf { it.totalTimeInForeground }
        return buildJsonObject {
            put("type", "usage_stats_summary")
            put("range_start_ms", start)
            put("range_end_ms", end)
            put("total_screen_time_ms", totalScreenTime)
            put("total_screen_time_human", humanDuration(totalScreenTime))
            put("app_count", stats.size)
            put("top_apps", buildJsonArray {
                stats.take(topN).forEach { add(it.toEntryJson(pm)) }
            })
        }.toString()
    }

    // ── Core query + rendering ─────────────────────────────────

    private fun rangeToJson(type: String, start: Long, end: Long): String {
        val pm = app.packageManager
        val stats = queryStats(start, end)
        return buildJsonObject {
            put("type", type)
            put("range_start_ms", start)
            put("range_end_ms", end)
            put("count", stats.size)
            put("apps", buildJsonArray {
                stats.forEach { add(it.toEntryJson(pm)) }
            })
        }.toString()
    }

    private fun UsageStats.toEntryJson(pm: PackageManager) = buildJsonObject {
        put("package_name", packageName)
        put("app_name", appLabel(pm, packageName))
        put("foreground_duration_ms", totalTimeInForeground)
        put("foreground_duration_human", humanDuration(totalTimeInForeground))
        put("last_used_ms", lastTimeUsed)
    }

    /**
     * Aggregate stats across the range. [UsageStatsManager.queryAndAggregateUsageStats] merges
     * multi-instance usage (e.g. an app launched several times) into one [UsageStats] per
     * package, which is what we want for duration reporting. Entries with zero foreground
     * time are dropped to keep the payload compact.
     */
    private fun queryStats(start: Long, end: Long): List<UsageStats> {
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val aggregated = usm.queryAndAggregateUsageStats(start, end)
        return aggregated.values
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
    }

    // ── Range parsing ──────────────────────────────────────────

    /** Resolve a human-friendly range token to a [start, end] pair (epoch ms). */
    private fun resolveRange(token: String): Pair<Long, Long> {
        val end = System.currentTimeMillis()
        val start: Long = when (token) {
            "today" -> midnightToday()
            "7d" -> end - 7L * DAY_MS
            "30d" -> end - 30L * DAY_MS
            "all" -> end - 365L * DAY_MS // Heuristic: system usually retains ~1 year.
            else -> {
                // Numeric token interpreted as "last N days"; anything else falls back to today.
                val days = token.toIntOrNull()
                if (days != null && days > 0) end - days.toLong() * DAY_MS else midnightToday()
            }
        }
        return start to end
    }

    private fun midnightToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ── Helpers ────────────────────────────────────────────────

    private fun appLabel(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    /** Compact human-readable duration like "1h23m", "5m12s", or "7s". */
    private fun humanDuration(ms: Long): String {
        if (ms <= 0) return "0s"
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> "${h}h${m}m"
            m > 0 -> "${m}m${s}s"
            else -> "${s}s"
        }
    }

    private fun err(code: String, message: String?): String = toolError("usage_stats_error", code, message)

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}