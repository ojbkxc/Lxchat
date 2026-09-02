package com.lxseek.chat.baby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BabyCryDetectorState] 状态机单测：覆盖两个参考项目提炼出的全部判定规则
 * （crywatch 的 sustain/cooldown/RMS 门限 + baby-monitor 的 silence-reset /
 * speech 抑制），不依赖 Android，纯 JVM 可跑。
 */
class BabyCryDetectorStateTest {

    // ── 测试脚手架 ─────────────────────────────────────────────

    /** RMS 幅度充足（约 -20dB，远高于 -60dB 门限）的普通观测。 */
    private val loud = 0.1f

    private fun cry(score: Float = 0.8f, rms: Float = loud) =
        CryObservation(cryScore = score, speechScore = 0f, rms = rms)

    private fun speech(score: Float = 0.8f, rms: Float = loud) =
        CryObservation(cryScore = 0f, speechScore = score, rms = rms)

    /** 可手动拨动的假时钟。 */
    private class FakeClock(var now: Long = 1_000_000L) {
        fun tick(ms: Long) { now += ms }
    }

    private fun detector(
        params: BabyCryParams = BabyCryParams(),
        clock: FakeClock = FakeClock(),
    ) = BabyCryDetectorState(params) { clock.now } to clock

    // ── 1. 连续命中达 sustain → Alert ─────────────────────────

    @Test
    fun sustainedCryHitsTriggerAlert() {
        val params = BabyCryParams(sustainHits = 3, cooldownMs = 60_000L)
        val (state, _) = detector(params)

        assertEquals(CryVerdict.None, state.observe(cry()))
        assertEquals(CryVerdict.None, state.observe(cry()))
        val verdict = state.observe(cry())
        assertTrue(verdict is CryVerdict.Alert)
        assertEquals(3, (verdict as CryVerdict.Alert).streak)
    }

    @Test
    fun shortCryBurstBelowSustainDoesNotAlert() {
        val params = BabyCryParams(sustainHits = 3, silenceReset = 2)
        val (state, _) = detector(params)

        assertEquals(CryVerdict.None, state.observe(cry()))
        assertEquals(CryVerdict.None, state.observe(cry()))
        // streak=2 未达标；连续两帧未命中（silenceReset=2）后 streak 清零。
        assertEquals(CryVerdict.None, state.observe(cry(0.05f)))
        assertEquals(CryVerdict.None, state.observe(cry(0.05f)))
        // streak 已清零：再来一帧命中只到 1，仍不报警。
        assertEquals(CryVerdict.None, state.observe(cry()))
    }

    // ── 2. 静默重置 ───────────────────────────────────────────

    @Test
    fun silenceResetsStreakSoAlertCanFireAgain() {
        val params = BabyCryParams(sustainHits = 2, silenceReset = 3, cooldownMs = 0)
        val (state, _) = detector(params)

        assertEquals(CryVerdict.None, state.observe(cry()))
        assertTrue(state.observe(cry()) is CryVerdict.Alert)

        // 连续未命中达到 silenceReset 后 streak 清零。
        repeat(3) { assertEquals(CryVerdict.None, state.observe(cry(0.05f))) }

        // 冷却为 0，重新累计两次命中应再次报警。
        assertEquals(CryVerdict.None, state.observe(cry()))
        assertTrue(state.observe(cry()) is CryVerdict.Alert)
    }

    // ── 3. 语音抑制（有人在哄） ────────────────────────────────

    @Test
    fun speechObservationSuppressesAndResetsStreak() {
        val params = BabyCryParams(sustainHits = 3)
        val (state, _) = detector(params)

        assertEquals(CryVerdict.None, state.observe(cry()))
        assertEquals(CryVerdict.None, state.observe(cry()))
        // 第三帧变成「有人在说话」：清零并抑制，不报警。
        assertEquals(CryVerdict.Suppressed, state.observe(speech()))
        // 语音结束后从零开始累计，两次命中不报警（需重新达 3）。
        assertEquals(CryVerdict.None, state.observe(cry()))
        assertEquals(CryVerdict.None, state.observe(cry()))
    }

    @Test
    fun speechBelowThresholdIsNonHitNotSuppression() {
        val params = BabyCryParams(sustainHits = 2)
        val (state, _) = detector(params)

        // speech=0.3 未过 0.5 门限 → 走普通未命中分支。
        assertEquals(CryVerdict.None, state.observe(speech(0.3f)))
    }

    // ── 4. 冷却去重 ───────────────────────────────────────────

    @Test
    fun cooldownPreventsDuplicateAlertsAndReArmsAfterExpiry() {
        val clock = FakeClock()
        val params = BabyCryParams(sustainHits = 2, silenceReset = 2, cooldownMs = 60_000L)
        val state = BabyCryDetectorState(params) { clock.now }

        // 第一次报警。
        assertEquals(CryVerdict.None, state.observe(cry()))
        assertTrue(state.observe(cry()) is CryVerdict.Alert)

        // 冷却期内：静默重置后再次持续哭，streak 达标但不重复报警。
        clock.tick(10_000L)
        repeat(2) { assertEquals(CryVerdict.None, state.observe(cry(0.05f))) }
        assertEquals(CryVerdict.None, state.observe(cry()))
        assertEquals(CryVerdict.None, state.observe(cry())) // streak 达标但冷却未过

        // 冷却过期（lastAlertAt 之后 70s）后恢复可报警。
        clock.tick(60_000L)
        repeat(2) { assertEquals(CryVerdict.None, state.observe(cry(0.05f))) } // 先静默清零
        assertEquals(CryVerdict.None, state.observe(cry()))
        assertTrue(state.observe(cry()) is CryVerdict.Alert)
    }

    // ── 5. RMS 安静门限 ───────────────────────────────────────

    @Test
    fun belowRmsGateCountsAsSilenceFrame() {
        val params = BabyCryParams(sustainHits = 2, silenceReset = 8)
        val (state, _) = detector(params)

        // rms 极小（约 -140dB，远低于 -60dB 门限）但 cry 分数很高：
        // 必须当安静帧处理，不能累计命中（否则两次后就会报警）。
        val quiet = 1e-7f
        repeat(10) { assertEquals(CryVerdict.None, state.observe(cry(0.99f, rms = quiet))) }
    }

    @Test
    fun rmsToDbConversionIsSane() {
        // 1.0 → 0dB 附近；每缩小 10 倍 → -20dB。
        assertEquals(0f, BabyCryDetectorState.rmsToDb(1f), 0.01f)
        assertEquals(-20f, BabyCryDetectorState.rmsToDb(0.1f), 0.01f)
        assertEquals(-40f, BabyCryDetectorState.rmsToDb(0.01f), 0.01f)
        // 0 不得产生 -Inf（+1e-9 保护）。
        assertTrue(BabyCryDetectorState.rmsToDb(0f).isFinite())
    }

    // ── 6. 边界：命中阈值恰好等于门限 ──────────────────────────

    @Test
    fun cryScoreExactlyAtThresholdCountsAsHit() {
        val params = BabyCryParams(cryProb = 0.4f, sustainHits = 1)
        val (state, _) = detector(params)

        assertTrue(state.observe(cry(0.4f)) is CryVerdict.Alert)
    }
}
