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

        // 连续未命中达到 silenceReset：第一次达阈值时补发 Ended 结束哭声周期，此后返回 None。
        assertTrue(state.observe(cry(0.05f)) is CryVerdict.Ended)
        repeat(2) { assertEquals(CryVerdict.None, state.observe(cry(0.05f))) }

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
    fun cryEndedEmittedOnceWhenSilenceResets() {
        val clock = FakeClock()
        val params = BabyCryParams(sustainHits = 2, silenceReset = 3, cooldownMs = 0)
        val state = BabyCryDetectorState(params) { clock.now }

        assertEquals(CryVerdict.None, state.observe(cry()))
        assertTrue(state.observe(cry()) is CryVerdict.Alert)

        // 未命中未达阈值不结束；只有连续未命中达到 silenceReset 才补发一次 Ended。
        clock.tick(1_000L)
        assertTrue(state.observe(cry(0.05f)) is CryVerdict.Ended)
        val ended = state.observe(cry(0.05f))
        assertEquals(CryVerdict.None, ended) // 周期已结束，不再补发

        // 周期的 startedAt 为首次 Alert 时刻，durationMs 为从 Alert 到结束的毫秒差。
    }

    @Test
    fun speechSuppressionAfterAlertEmitsEnded() {
        val clock = FakeClock()
        val params = BabyCryParams(sustainHits = 2, cooldownMs = 0)
        val state = BabyCryDetectorState(params) { clock.now }

        clock.tick(0L)
        assertEquals(CryVerdict.None, state.observe(cry()))
        assertTrue(state.observe(cry()) is CryVerdict.Alert)
        // 有人声介入终止周期：先补发 Ended，而非 Suppressed。
        val verdict = state.observe(speech())
        assertTrue(verdict is CryVerdict.Ended)
        assertTrue((verdict as CryVerdict.Ended).durationMs >= 0)
    }

    @Test
    fun cooldownPreventsDuplicateAlertsAndReArmsAfterExpiry() {
        val clock = FakeClock()
        val params = BabyCryParams(sustainHits = 2, silenceReset = 2, cooldownMs = 60_000L)
        val state = BabyCryDetectorState(params) { clock.now }

        // 第一次报警。
        assertEquals(CryVerdict.None, state.observe(cry()))
        assertTrue(state.observe(cry()) is CryVerdict.Alert)

        // 冷却期内：静默重置（第二帧达 silenceReset 时补发 Ended），随后再累计不重复报警。
        clock.tick(10_000L)
        assertEquals(CryVerdict.None, state.observe(cry(0.05f)))
        assertTrue(state.observe(cry(0.05f)) is CryVerdict.Ended)
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

    // ── 7. 附加事件：per-class 触发 ────────────────────────────

    @Test
    fun coughScoresTriggerEvent() {
        val (state, _) = detector()
        // COUGH 门槛 0.3、minHits=1：一次命中即触发。
        val v = state.observe(
            CryObservation(coughScore = 0.5f, rms = loud),
        )
        assertTrue(v is CryVerdict.Event)
        assertEquals(EventType.COUGH, (v as CryVerdict.Event).type)
    }

    @Test
    fun sneezeBelowThresholdDoesNotTrigger() {
        val (state, _) = detector()
        val v = state.observe(
            CryObservation(sneezeScore = 0.1f, rms = loud),
        )
        assertEquals(CryVerdict.None, v)
    }

    @Test
    fun intenseCryNeedsHighThresholdAndMinHits() {
        // 默认 intenseCryProb=0.6、minHits=2。
        val (state, _) = detector()
        // 0.5 低于门槛 0.6，不触发。
        assertEquals(CryVerdict.None, state.observe(CryObservation(intenseCryScore = 0.5f, rms = loud)))
        // 0.7 命中一次，但 minHits=2 仍未到。
        assertEquals(CryVerdict.None, state.observe(CryObservation(intenseCryScore = 0.7f, rms = loud)))
        // 第二次 0.7 → 触发 INTENSE_CRY。
        val v = state.observe(CryObservation(intenseCryScore = 0.7f, rms = loud))
        assertTrue(v is CryVerdict.Event)
        assertEquals(EventType.INTENSE_CRY, (v as CryVerdict.Event).type)
    }

    @Test
    fun eventDedupSuppressesRepeatedFires() {
        val clock = FakeClock()
        val state = BabyCryDetectorState(BabyCryParams()) { clock.now }
        // 连续两帧 DOOR（minHits=1）触发，之后 dedupMs 期内不再重复。
        assertTrue(state.observe(CryObservation(doorScore = 0.6f, rms = loud)) is CryVerdict.Event)
        assertEquals(CryVerdict.None, state.observe(CryObservation(doorScore = 0.6f, rms = loud)))
        // 冷却过期后恢复。
        clock.tick(6_000L)
        assertEquals(CryVerdict.None, state.observe(CryObservation(doorScore = 0f, rms = loud)))
        assertTrue(state.observe(CryObservation(doorScore = 0.6f, rms = loud)) is CryVerdict.Event)
    }

    @Test
    fun quietFrameDoesNotTriggerWhiteNoiseEvent() {
        val (state, _) = detector()
        val quiet = 1e-7f
        // 安静帧（低 RMS）即使白噪音高也不产生事件，避免整窗高分误报。
        assertEquals(CryVerdict.None, state.observe(CryObservation(whiteNoiseScore = 0.9f, rms = quiet)))
    }

    @Test
    fun speechSuppressionSkipsEvents() {
        val (state, _) = detector()
        // 有一帧大人说话 → 抑制；此时即便笑声分数高也不产出事件。
        val v = state.observe(CryObservation(speechScore = 0.9f, laughterScore = 0.8f, rms = loud))
        assertEquals(CryVerdict.Suppressed, v)
    }

    // ── 8. 哭声周期：grace 合并碎段 ───────────────────────────

    @Test
    fun gracePeriodMergesFragmentedCryEpisodes() {
        val clock = FakeClock()
        val params = BabyCryParams(sustainHits = 2, silenceReset = 2, graceMs = 30_000L, cooldownMs = 0)
        val state = BabyCryDetectorState(params) { clock.now }

        // 第一次哭 → Alert（startedAt = T0）。
        assertEquals(CryVerdict.None, state.observe(cry()))
        val first = state.observe(cry())
        assertTrue(first is CryVerdict.Alert)

        // 静默重置结束周期（补发 Ended），随后进入 grace 内：再哭应合并同一起点。
        clock.tick(1_000L)
        assertTrue(state.observe(cry(0.05f)) is CryVerdict.Ended)
        assertEquals(CryVerdict.None, state.observe(cry(0.05f)))

        clock.tick(20_000L) // 仍在 30s grace 内
        assertEquals(CryVerdict.None, state.observe(cry()))
        val merged = state.observe(cry())
        assertTrue(merged is CryVerdict.Alert)
        // 合并后 startedAt 应沿用第一次 episode 的起点（< 当前时间）。
        assertTrue((merged as CryVerdict.Alert).startedAt < clock.now)
        assertEquals((first as CryVerdict.Alert).startedAt, merged.startedAt)
    }

    @Test
    fun graceExpiryCreatesNewEpisode() {
        val clock = FakeClock()
        val params = BabyCryParams(sustainHits = 2, silenceReset = 2, graceMs = 30_000L, cooldownMs = 0)
        val state = BabyCryDetectorState(params) { clock.now }

        assertEquals(CryVerdict.None, state.observe(cry()))
        val first = state.observe(cry()) as CryVerdict.Alert
        clock.tick(1_000L)
        assertTrue(state.observe(cry(0.05f)) is CryVerdict.Ended)
        assertEquals(CryVerdict.None, state.observe(cry(0.05f)))

        // 超过 grace 后才再哭 → 新 episode，起点重置为当前时刻。
        clock.tick(60_000L)
        assertEquals(CryVerdict.None, state.observe(cry()))
        val next = state.observe(cry())
        assertTrue(next is CryVerdict.Alert)
        assertEquals(clock.now, (next as CryVerdict.Alert).startedAt)
        assertTrue(next.startedAt > first.startedAt)
    }
}
