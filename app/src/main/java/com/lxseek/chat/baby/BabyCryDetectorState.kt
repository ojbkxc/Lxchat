package com.lxseek.chat.baby

/**
 * 婴儿哭声检测的判定参数（融合 crywatch 与 baby-monitor 两个参考项目的调优值）。
 *
 * 参考：
 *  - crywatch `yamnet_detect.py`：CRY_PROB=0.4 / SUSTAIN=2 / COOLDOWN=60s / RMS_GATE=-60dB
 *  - baby-monitor `settings.py`：CRY_COUNT_THRESHOLD=5 / SILENCE_RESET=10 / SPEECH_THRESHOLD=0.5
 *
 * @param cryProb         单帧「哭声类合计概率」超过该值才计入一次命中（0..1）。
 * @param sustainHits     连续命中该次数后触发报警（防瞬间噪声误报）。
 * @param silenceReset    连续未命中该次数后清零计数与报警锁（允许下一次报警）。
 * @param speechReset     「语音类概率」超过该值视为有人在哄娃：立即清零计数并抑制报警。
 * @param cooldownMs      两次报警之间的最小间隔（去重，防轰炸）。
 * @param rmsGateDb       RMS 低于该 dB 值视为安静，跳过推理（省电 + 防底噪误判）。
 * @param minWindowMs     送入推理的音频窗长下限（YAMNet 单帧约 0.975s）。
 */
data class BabyCryParams(
    val cryProb: Float = 0.4f,
    val sustainHits: Int = 3,
    val silenceReset: Int = 8,
    val speechReset: Float = 0.5f,
    val cooldownMs: Long = 60_000L,
    val rmsGateDb: Float = -60f,
)

/** 单次推理的原始结果：供 [BabyCryDetectorState.observe] 消费的输入。 */
data class CryObservation(
    /** 哭声类（Baby cry / Crying, sobbing / Whimper）的合计概率，0..1。 */
    val cryScore: Float,
    /** 语音类概率，0..1（Speech 超过 speechReset 视为有人在管）。 */
    val speechScore: Float,
    /** 本窗 RMS（线性 0..1，非 dB）。 */
    val rms: Float,
)

/** 观测后的状态转移结果。 */
sealed interface CryVerdict {
    /** 无事发生（安静 / 未达持续阈值 / 冷却中）。 */
    data object None : CryVerdict

    /** 命中持续阈值且冷却已过——应发出一次报警。 */
    data class Alert(val cryScore: Float, val streak: Int) : CryVerdict

    /** 有人声介入，抑制并清零（不报警，直到语音消失后重新累计）。 */
    data object Suppressed : CryVerdict
}

/**
 * 纯状态机：把 [CryObservation] 流折叠成 [CryVerdict] 事件流。无 Android 依赖，
 * 可直接在 JVM 单元测试里验证全部转移逻辑。
 *
 * 状态：连续命中数 [streak]、连续未命中数 [silence]、上次报警时间。
 * 转移规则（对齐 baby_monitor.py:41-104 主循环）：
 *  1. RMS 低于门限 dB → 视为安静帧：silence+1，命中阈值后清零（不重置报警锁，
 *     报警锁由 cooldown 自然过期）。
 *  2. speech > speechReset → 全量清零 + 显式抑制一次（有人哄娃，不需要报警）。
 *  3. cry >= cryProb → streak+1、silence=0；streak 达到 sustainHits 且冷却已过 → Alert。
 *  4. 其余 → silence+1；连续 silence 达到 silenceReset → 清零 streak。
 */
class BabyCryDetectorState(
    private val params: BabyCryParams = BabyCryParams(),
    /** 注入时钟，测试用。 */
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var streak = 0
    private var silence = 0
    private var lastAlertAt = 0L

    /** 处理一次观测，返回判定结果（见类注释的转移规则）。 */
    fun observe(o: CryObservation): CryVerdict {
        val t = now()

        // 1) 安静门限：RMS 太低直接当无声处理（省推理也在上游做，这里兜底状态转移）。
        if (rmsToDb(o.rms) < params.rmsGateDb) {
            silence += 1
            if (silence >= params.silenceReset) streak = 0
            return CryVerdict.None
        }

        // 2) 有人声：视为大人在哄，清零并抑制。
        if (o.speechScore > params.speechReset) {
            streak = 0
            silence = 0
            return CryVerdict.Suppressed
        }

        // 3) 哭声命中。
        if (o.cryScore >= params.cryProb) {
            streak += 1
            silence = 0
            if (streak >= params.sustainHits && t - lastAlertAt >= params.cooldownMs) {
                lastAlertAt = t
                return CryVerdict.Alert(cryScore = o.cryScore, streak = streak)
            }
            return CryVerdict.None
        }

        // 4) 未命中：累计静默，足够久后清零。
        silence += 1
        if (silence >= params.silenceReset) streak = 0
        return CryVerdict.None
    }

    companion object {
        /** 线性 RMS → dBFS。 */
        fun rmsToDb(rms: Float): Float =
            (20.0 * kotlin.math.log10(rms.toDouble() + 1e-9)).toFloat()
    }
}
