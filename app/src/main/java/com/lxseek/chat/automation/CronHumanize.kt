package com.lxseek.chat.automation

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 将 5 字段 cron 表达式转成人话描述（移植自 cc-haha 的 `cronToHuman`）。
 *
 * 只覆盖常见模式；不匹配时原样返回 cron 字符串。用途：
 * 1. `create_task` 创建后生成「确认摘要」，让模型向用户复述"这个任务会在什么时候跑"，
 *    而不是丢一个没人能一眼读懂的 `0 9 * * 1-5`。
 * 2. `list_tasks` 里展示每个任务的调度描述。
 *
 * 纯 JVM 实现，无 Android 依赖，可单测。
 */
object CronHumanize {

    private val DAY_NAMES = arrayOf(
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
    )

    /**
     * 返回 cron 的 human-readable 描述。无法识别/非法时返回原字符串。
     * 所有时间按本机时区解释（与 [CronExpression] 一致）。
     */
    fun describe(cron: String): String {
        val parts = cron.trim().split(Regex("\\s+"))
        if (parts.size != 5) return cron
        val (minute, hour, dom, month, dow) = parts

        // Every N minutes: step/N * * * *
        val everyMin = Regex("""\*/(\d+)""").find(minute)
        if (everyMin != null && hour == "*" && dom == "*" && month == "*" && dow == "*") {
            val n = everyMin.groupValues[1].toIntOrNull() ?: return cron
            return if (n == 1) "Every minute" else "Every $n minutes"
        }

        // Every hour: 0 * * * *  /  M * * * *
        if (minute.matches(Regex("""\d+""")) && hour == "*" && dom == "*" && month == "*" && dow == "*") {
            val m = minute.toIntOrNull() ?: return cron
            return if (m == 0) "Every hour" else "Every hour at :${m.toString().padStart(2, '0')}"
        }

        // Every N hours: 0 step/N * * *
        val everyHour = Regex("""\*/(\d+)""").find(hour)
        if (minute.matches(Regex("""\d+""")) && everyHour != null && dom == "*" && month == "*" && dow == "*") {
            val n = everyHour.groupValues[1].toIntOrNull() ?: return cron
            val m = minute.toIntOrNull() ?: return cron
            val suffix = if (m == 0) "" else " at :${m.toString().padStart(2, '0')}"
            return if (n == 1) "Every hour$suffix" else "Every $n hours$suffix"
        }

        // Remaining cases need a fixed hour + minute.
        if (!minute.matches(Regex("""\d+""")) || !hour.matches(Regex("""\d+"""))) return cron
        val m = minute.toIntOrNull() ?: return cron
        val h = hour.toIntOrNull() ?: return cron

        // Daily at specific time: M H * * *
        if (dom == "*" && month == "*" && dow == "*") {
            return "Every day at ${formatTime(h, m)}"
        }

        // Specific day of week: M H * * D
        if (dom == "*" && month == "*" && dow.matches(Regex("""\d"""))) {
            val dayIndex = (dow.toIntOrNull() ?: return cron) % 7
            return "Every ${DAY_NAMES[dayIndex]} at ${formatTime(h, m)}"
        }

        // Weekdays: M H * * 1-5
        if (dom == "*" && month == "*" && dow == "1-5") {
            return "Weekdays at ${formatTime(h, m)}"
        }

        return cron
    }

    private fun formatTime(hour: Int, minute: Int): String {
        // 用 2000-01-01（无 DST）构造，避免今天恰好是春令时切换日导致 2am→3am 被折叠。
        val calendar = java.util.Calendar.getInstance(Locale.US).apply {
            clear()
            set(2000, 0, 1, hour, minute, 0)
        }
        return SimpleDateFormat("h:mm a", Locale.US).format(Date(calendar.timeInMillis))
    }

    /** 为新建的定时任务生成「确认摘要」文本，模型应原样展示给用户核对。 */
    fun confirmationSummary(name: String, cron: String): String {
        val human = describe(cron)
        val nextRun = CronExpression.parse(cron)?.next(System.currentTimeMillis())
        val nextText = nextRun?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
        } ?: "unavailable"
        return buildString {
            appendLine("Task confirmation:")
            appendLine("  name: $name")
            appendLine("  schedule: $human ($cron)")
            appendLine("  next run: $nextText")
            appendLine("Please show this summary to the user and ask them to confirm before it runs.")
        }
    }
}
