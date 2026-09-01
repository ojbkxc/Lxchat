package com.lxseek.chat.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 时钟鲁棒性（S3，借鉴 openclaw clock-rollback 测试思想）：[CronExpression.next]
 * 的 KDoc 声称跨 DST 间隙/重复保持精确、时区显式、8 年视野覆盖 Feb-29 ——
 * 这些是 Android 设备的真实运行条件（时钟手动调整、NTP 校正、时区/DST 切换）。
 * 此处回归会表现为定时任务重复触发或永不触发，用户侧极难定位。
 */
class CronExpressionDstClockTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val newYork = TimeZone.getTimeZone("America/New_York")

    private fun at(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun next(expr: String, from: Long, zone: TimeZone = utc): Long? =
        CronExpression.parse(expr)!!.next(from, zone)

    // ── DST 切换 ──────────────────────────────────────────────

    @Test
    fun dstSpringForward_gapSkipsNonexistentWallTime() {
        // 纽约 2026-03-08 02:00→03:00（春季前跳，02:xx 墙钟时间不存在）。
        // "30 2 * * *" 从当天 01:00 出发：02:30 不存在 → 落到次日 03-09 02:30（EDT）。
        val from = at(newYork, 2026, 3, 8, 1, 0)
        val expected = Calendar.getInstance(newYork).apply {
            clear()
            set(2026, Calendar.MARCH, 9, 2, 30, 0)
        }.timeInMillis
        assertEquals(expected, next("30 2 * * *", from, newYork))
    }

    @Test
    fun dstFallBack_repeatedWallTimeStaysMonotonic() {
        // 纽约 2026-11-01 01:30 出现两次（EDT 与 EST）。从 00:00 出发，
        // 结果必须是墙钟 01:30 且严格大于输入 —— 不回退、不抛异常、不死循环。
        val from = at(newYork, 2026, 11, 1, 0, 0)
        val result = next("30 1 * * *", from, newYork)
        assertNotNull(result)
        assertTrue("result must be strictly after input", result!! > from)
        val cal = Calendar.getInstance(newYork).apply { timeInMillis = result }
        assertEquals(1, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    // ── 时区显式 ───────────────────────────────────────────────

    @Test
    fun explicitZone_changesResultForSameInstant() {
        // 同一时刻（UTC 2026-06-25 01:00 = 上海 09:00 整）：
        // UTC 视角 → 当天 09:00 尚未到 → 当天 09:00 UTC；
        // 上海视角 → 09:00 已到达 → strictly after → 次日 09:00 上海时间。
        val from = at(utc, 2026, 6, 25, 1, 0)
        assertEquals(at(utc, 2026, 6, 25, 9, 0), next("0 9 * * *", from, utc))
        val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
        val expectedShanghai = Calendar.getInstance(shanghai).apply {
            clear()
            set(2026, Calendar.JUNE, 26, 9, 0, 0)
        }.timeInMillis
        assertEquals(expectedShanghai, next("0 9 * * *", from, shanghai))
    }

    // ── Feb-29 与搜索视野 ──────────────────────────────────────

    @Test
    fun feb29Only_resolvesWithinHorizon() {
        // 2026、2027 均无 2/29；下一候选 2028-02-29（8 年视野内），而非返回 null。
        assertEquals(
            at(utc, 2028, 2, 29, 0, 0),
            next("0 0 29 2 *", at(utc, 2025, 3, 1, 0, 0)),
        )
    }

    @Test
    fun neverMatchingExpression_exhaustsHorizonAndReturnsNull() {
        // dom=30 且 month=2：合法表达式但永不匹配 → 视野耗尽返回 null，而非死循环。
        assertNull(next("0 0 30 2 *", at(utc, 2026, 1, 1, 0, 0)))
    }

    // ── 回拨重算幂等（防重复触发的纯函数语义）──────────────────

    @Test
    fun recomputingFromBeforeTheFirePoint_isIdempotent() {
        // 设备时钟回拨到触发点之前再重算，得到的仍是同一个触发点：
        // 调度层据此可用 next(max(now, lastRunAt)) 安全防重
        // （见 CronScheduler 的时钟回拨防护），同一墙钟时段被第二次经历时不重复执行。
        val from = at(utc, 2026, 6, 25, 8, 0)
        val fire = next("0 9 * * *", from)!!
        assertTrue(fire > from)
        assertEquals(fire, next("0 9 * * *", fire - 60_000L))
    }
}