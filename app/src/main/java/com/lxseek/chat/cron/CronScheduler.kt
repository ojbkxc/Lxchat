package com.lxseek.chat.cron

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 解析并匹配 5 字段 Cron 表达式：`minute hour day-of-month month day-of-week`。
 *
 * 支持的每字段语法（与经典 cron 一致，不支持 L/W/# 等高级修饰符）：
 * - `*`      通配符
 * - `n`      单个值
 * - `a,b,c`  列表
 * - `a-b`    范围
* - 步长：在字段（含「任意」通配）后追加 /n 生效，从字段最小值开始
* - `a-b/n`  范围内步长
 *
 * day-of-week：0 与 7 均表示周日（POSIX cron 习惯）。
 * 当 day-of-month 与 day-of-week 同时被限制（都不是 `*`）时，按 POSIX 规则取「或」关系；
 * 否则取「与」关系。这与系统 `crontab` 行为一致。
 *
 * 该类是不可变且线程安全的。
 */
class CronExpression private constructor(
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>,
    private val domRestricted: Boolean,
    private val dowRestricted: Boolean,
) {
    /** 判断给定 [calendar]（已设置到某一分钟起点）是否匹配本表达式。 */
    fun matches(calendar: Calendar): Boolean {
        val minute = calendar.get(Calendar.MINUTE)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val dom = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.get(Calendar.MONTH) + 1 // Calendar.MONTH: 0=Jan
        val dow = (calendar.get(Calendar.DAY_OF_WEEK) - 1).let { if (it == 0) 7 else it }
        // POSIX: 两个 day 字段都被限制时取「或」，否则取「与」。
        val domMatched = dom in daysOfMonth
        val dowMatched = dow in daysOfWeek
        val dayMatched = if (domRestricted && dowRestricted) domMatched || dowMatched
                         else domMatched && dowMatched
        return minute in minutes && hour in hours && month in months && dayMatched
    }

    /**
     * 计算从 [fromMillis]（不含）之后第一个匹配本表达式的分钟起点的时间戳。
     * 最多向前搜索 4 年（约 2.1M 分钟），超过则返回 null（例如 `2 2 2 2 2` 在闰年边界附近仍能在 4 年内命中）。
     */
    fun nextRunAfter(fromMillis: Long): Long? {
        val cal = Calendar.getInstance().apply {
            timeInMillis = fromMillis
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, 1) // 从下一分钟开始
        }
        val maxIterations = 366 * 4 * 24 * 60 // ~4 年
        repeat(maxIterations) {
            if (matches(cal)) return cal.timeInMillis
            cal.add(Calendar.MINUTE, 1)
        }
        return null
    }

    companion object {
        /** 解析 [expr]，失败时抛 [IllegalArgumentException]（含字段名与原始片段）。 */
        fun parse(expr: String): CronExpression {
            val fields = expr.trim().split(Regex("\\s+"))
            require(fields.size == 5) {
                "Cron expression must have 5 fields (minute hour day month weekday): '$expr'"
            }
            val (minField, hourField, domField, monthField, dowField) = fields
            val minutes = parseField(minField, 0, 59, "minute")
            val hours = parseField(hourField, 0, 23, "hour")
            val doms = parseField(domField, 1, 31, "day-of-month")
            val months = parseField(monthField, 1, 12, "month")
            // day-of-week: 0-7（0 与 7 都是周日），归一化为 1-7（1=周日）以便与 Calendar 对齐
            val dows = parseField(dowField, 0, 7, "day-of-week").let { raw ->
                raw.flatMap { if (it == 0) listOf(7) else listOf(it) }.toSet()
            }
            return CronExpression(
                minutes = minutes,
                hours = hours,
                daysOfMonth = doms,
                months = months,
                daysOfWeek = dows,
                domRestricted = domField.trim() != "*",
                dowRestricted = dowField.trim() != "*",
            )
        }

        /** 仅解析不抛异常：成功返回 [CronExpression]，失败返回 null。便于 UI 即时校验。 */
        fun tryParse(expr: String): CronExpression? = runCatching { parse(expr) }.getOrNull()

        /**
         * 解析单个字段。
         * @param field 原始字段文本
         * @param min   字段最小值
         * @param max   字段最大值
         * @param name  字段名（仅用于错误信息）
         */
        private fun parseField(field: String, min: Int, max: Int, name: String): Set<Int> {
            if (field == "*") return (min..max).toSet()
            val result = mutableSetOf<Int>()
            for (part in field.split(",")) {
                val segment = part.trim()
                require(segment.isNotEmpty()) { "Cron field '$name' has empty list element: '$field'" }
                val (rangeSpec, step) = if (segment.contains("/")) {
                    val slashIdx = segment.indexOf("/")
                    val r = segment.substring(0, slashIdx)
                    val s = segment.substring(slashIdx + 1).toIntOrNull()
                        ?: throw IllegalArgumentException("Cron field '$name' has invalid step: '$segment'")
                    require(s > 0) { "Cron field '$name' step must be > 0: '$segment'" }
                    r to s
                } else {
                    segment to 1
                }
                val (start, end) = if (rangeSpec == "*") {
                    min to max
                } else if (rangeSpec.contains("-")) {
                    val dashIdx = rangeSpec.indexOf("-")
                    val a = rangeSpec.substring(0, dashIdx).toIntOrNull()
                        ?: throw IllegalArgumentException("Cron field '$name' has invalid range start: '$rangeSpec'")
                    val b = rangeSpec.substring(dashIdx + 1).toIntOrNull()
                        ?: throw IllegalArgumentException("Cron field '$name' has invalid range end: '$rangeSpec'")
                    a to b
                } else {
                    val v = rangeSpec.toIntOrNull()
                        ?: throw IllegalArgumentException("Cron field '$name' has invalid value: '$rangeSpec'")
                    v to v
                }
                require(start in min..max && end in min..max && start <= end) {
                    "Cron field '$name' range [$start-$end] out of bounds [$min-$max]: '$segment'"
                }
                var i = start
                while (i <= end) {
                    result.add(i)
                    i += step
                }
            }
            return result
        }
    }
}

/**
 * 把 [CronTask] 调度到 WorkManager，并在任务变更时自动重排。
 *
 * 调度策略：每个任务用一条 **OneTimeWorkRequest 链**，按 Cron 表达式精确计算下次执行时间，
 * 用 `setInitialDelay` 把首次触发对齐到该时间点。任务执行完后 [CronWorker] 调用 [reschedule]
 * 排下下一次，形成自驱动的链。这避免了 `PeriodicWorkRequest` 15 分钟最小间隔的限制，
 * 也避免了周期任务「固定间隔」与 Cron「固定时间点」语义不一致的问题。
 *
 * - [start] 监听 [CronTaskStore.tasks]，对新增/修改的 enabled 任务调用 [schedule]（KEEP，
 *   不打断正在执行的同名 work），对禁用或删除的任务调用 [cancel]。
 * - [schedule] 用 KEEP：首次调度或启动恢复时入队，已存在则保留。
 * - [reschedule] 用 REPLACE：任务参数变更或 Worker 执行完后强制重排下一次。
 * - [cancel] 取消该任务的所有挂起 work。
 *
 * unique work name: `cron_${taskId}`；tag: `cron_${taskId}`（cancel 用）。
 */
class CronScheduler(
    private val appContext: Context,
    private val store: CronTaskStore,
    private val scope: CoroutineScope,
) {
    /** 监听 tasks Flow 的协程，[start] 启动、[stop] 取消。 */
    private var observerJob: Job? = null

    /** 启动时扫描并持续跟踪所有 Cron 任务。重复调用安全（已运行则跳过）。 */
    fun start() {
        if (observerJob?.isActive == true) return
        observerJob = store.tasks
            .onEach { tasks ->
                val enabledIds = tasks.filter { it.enabled }.map { it.id }.toSet()
                // 1. 调度所有 enabled 任务（KEEP：已调度的不会被打断）。
                tasks.filter { it.enabled }.forEach { task ->
                    runCatching { schedule(task) }
                        .onFailure { DebugLog.e(TAG, "schedule failed for ${task.id}", it) }
                }
                // 2. 取消所有 disabled / 已删除任务的挂起 work。
                //    通过遍历当前已知的全部任务 id（含 disabled），对不在 enabledIds 中的取消。
                tasks.forEach { task ->
                    if (task.id !in enabledIds) {
                        runCatching { cancel(task.id) }
                            .onFailure { DebugLog.e(TAG, "cancel failed for ${task.id}", it) }
                    }
                }
            }
            .launchIn(scope)
    }

    /** 停止监听任务变更（不取消已调度的 work）。通常仅在测试中调用。 */
    fun stop() {
        observerJob?.cancel()
        observerJob = null
    }

    /**
     * 调度一次 [task]：计算下次执行时间，用 OneTimeWorkRequest + 初始延迟入队。
     * 用 [ExistingWorkPolicy.KEEP]：若已有同名 work（ENQUEUED/RUNNING/SUCCEEDED），保留现有的。
     * 这保证启动时不会重复入队，也不会打断正在执行的任务。
     */
    fun schedule(task: CronTask) {
        val expr = CronExpression.tryParse(task.cronExpression) ?: run {
            DebugLog.w(TAG, "Skip scheduling task ${task.id}: invalid cron '${task.cronExpression}'")
            return
        }
        val now = System.currentTimeMillis()
        // 时钟回拨防护（S3）：设备墙钟被拨回上次执行之前（NTP 校正/手动调整）时，
        // 以 lastRunAt 为下界重算 —— 同一墙钟时段被第二次经历时不会重复触发。
        // 语义只收紧不放宽：lastRunAt <= now 时 anchor == now，行为与原先完全一致。
        val anchor = maxOf(now, task.lastRunAt)
        val nextRun = expr.nextRunAfter(anchor) ?: run {
            DebugLog.w(TAG, "Skip scheduling task ${task.id}: no next run within 4 years")
            return
        }
        val delayMs = (nextRun - now).coerceAtLeast(0)
        enqueue(task, delayMs, ExistingWorkPolicy.KEEP)
    }

    /**
     * 重新调度 [task]：用 [ExistingWorkPolicy.REPLACE] 取消旧 work 并按新参数重排。
     * 用于任务参数变更（Cron 表达式 / prompt / modelId 改变）或 Worker 执行完后排下一次。
     */
    fun reschedule(task: CronTask) {
        val expr = CronExpression.tryParse(task.cronExpression) ?: return
        val now = System.currentTimeMillis()
        // 同 schedule()：REPLACE 重排在墙钟回拨后以 lastRunAt 为下界，
        // 防止把触发点拉回已执行过的墙钟时段（防重复触发，见 schedule() 注释）。
        val anchor = maxOf(now, task.lastRunAt)
        val nextRun = expr.nextRunAfter(anchor) ?: return
        val delayMs = (nextRun - now).coerceAtLeast(0)
        enqueue(task, delayMs, ExistingWorkPolicy.REPLACE)
    }

    /** 取消 [taskId] 对应的所有挂起 work（按 tag）。 */
    fun cancel(taskId: String) {
        WorkManager.getInstance(appContext).cancelAllWorkByTag(tag(taskId))
    }

    private fun enqueue(task: CronTask, delayMs: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<CronWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_TASK_ID to task.id))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(tag(task.id))
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(uniqueName(task.id), policy, request)
    }

    private fun uniqueName(taskId: String) = "cron_$taskId"
    private fun tag(taskId: String) = "cron_$taskId"

    companion object {
        const val KEY_TASK_ID = "task_id"
        private const val TAG = "CronScheduler"
    }
}