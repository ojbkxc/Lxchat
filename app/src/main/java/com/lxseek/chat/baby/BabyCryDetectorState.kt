package com.lxseek.chat.baby

/**
 * 婴儿哭声检测的判定参数。
 *
 * 融合多个开源参考项目的调优值：
 *  - crywatch `yamnet_detect.py`：CRY_PROB=0.4 / SUSTAIN=2 / COOLDOWN=60s / RMS_GATE=-60dB
 *  - baby-monitor `settings.py`：CRY_COUNT_THRESHOLD=5 / SILENCE_RESET=10 / SPEECH_THRESHOLD=0.5
 *  - Beddington：sustained=1.5s / release=1.0s / cry_clear_grace=30s（合并哭声片段）
 *  - Noctivana：区分 Baby cry(哼唧) 与 Crying sobbing(剧烈大哭)
 *  - CoughLogger：cough/sneeze 短事件低门槛 0.3 + 短去重 700ms
 *  - sound2species：per-class 阈值 / 最短持续 / 短 gap 合并
 *
 * @param cryProb         低声哼唧（Baby cry+Whimper）单帧概率，超过才计入一次命中（0..1）。
 * @param intenseCryProb  剧烈大哭（Crying sobbing）单帧概率门槛，分级用（0..1）。
 * @param sustainHits     连续命中该次数后触发报警（防瞬间噪声误报）。
 * @param silenceReset    连续未命中该次数后清零计数与报警锁（允许下一次报警）。
 * @param speechReset     「语音类概率」超过该值视为有人在哄娃：立即清零计数并抑制报警。
 * @param cooldownMs      两次报警之间的最小间隔（去重，防轰炸）。
 * @param rmsGateDb       RMS 低于该 dB 值视为安静，跳过推理（省电 + 防底噪误判）。
 * @param graceMs         哭声 episode 结束后的宽限期：期内再次触发视为同一次事件
 *                        （合并碎段，Beddington cry_clear_grace）。
 */
data class BabyCryParams(
    val cryProb: Float = 0.4f,
    val intenseCryProb: Float = 0.6f,
    val sustainHits: Int = 3,
    val silenceReset: Int = 8,
    val speechReset: Float = 0.5f,
    val cooldownMs: Long = 60_000L,
    val rmsGateDb: Float = -60f,
    val graceMs: Long = 30_000L,
)

/** 单次推理的原始结果：供 [BabyCryDetectorState.observe] 消费的输入。 */
data class CryObservation(
    /** 低声哼唧类（Baby cry + Whimper）合计概率，0..1。 */
    val cryScore: Float = 0f,
    /** 语音类概率，0..1（Speech 超过 speechReset 视为有人在管）。 */
    val speechScore: Float = 0f,
    /** 本窗 RMS（线性 0..1，非 dB）。 */
    val rms: Float = 0f,
    /** 剧烈大哭（Crying, sobbing）概率，0..1。 */
    val intenseCryScore: Float = 0f,
    /** 咳嗽/清嗓概率，0..1。 */
    val coughScore: Float = 0f,
    /** 打喷嚏概率，0..1。 */
    val sneezeScore: Float = 0f,
    /** 尖叫/高声喊叫概率，0..1。 */
    val screamScore: Float = 0f,
    /** 欢笑概率，0..1。 */
    val laughterScore: Float = 0f,
    /** 牙牙学语概率，0..1。 */
    val childSpeechScore: Float = 0f,
    /** 白噪音来源概率，0..1。 */
    val whiteNoiseScore: Float = 0f,
    /** 门/柜开关概率，0..1。 */
    val doorScore: Float = 0f,
)

/** 单个事件类的判定参数（per-class，吸收 sound2species / CoughLogger）。 */
data class EventParams(
    /** 触发门槛（越低越灵敏）。 */
    val threshold: Float,
    /** 同类事件最小间隔（去重，防同事件连发刷屏）。 */
    val dedupMs: Long,
    /** 持续命中的最少帧数（防瞬时毛刺误报）。 */
    val minHits: Int,
)

/** 院内事件类型（不打断哭声主状态机，独立判定并记录 / 可选推送）。 */
enum class EventType(val params: EventParams) {
    /** 剧烈大哭（Crying sobbing）——对齐 Noctivana 的升级告警：高门槛、短去重。 */
    INTENSE_CRY(EventParams(threshold = 0.6f, dedupMs = 5_000L, minHits = 2)),
    /** 咳嗽 —— 短音，低门槛 + 短去重（CoughLogger 700ms）。 */
    COUGH(EventParams(threshold = 0.3f, dedupMs = 700L, minHits = 1)),
    /** 打喷嚏 —— 短音，低门槛 + 短去重。 */
    SNEEZE(EventParams(threshold = 0.3f, dedupMs = 700L, minHits = 1)),
    /** 尖叫 —— 连哭的极端信号，高门槛低误报（对齐枪声检测 0.64）。 */
    SCREAM(EventParams(threshold = 0.64f, dedupMs = 5_000L, minHits = 2)),
    /** 欢笑 —— 状态良好信号。 */
    LAUGHTER(EventParams(threshold = 0.35f, dedupMs = 5_000L, minHits = 2)),
    /** 牙牙学语 —— 「醒着且在互动」。 */
    CHILD_SPEECH(EventParams(threshold = 0.4f, dedupMs = 5_000L, minHits = 2)),
    /** 白噪音 —— 安抚/环境音（Vacuum/Hair dryer/Fan/White noise 组内最大）。 */
    WHITE_NOISE(EventParams(threshold = 0.2f, dedupMs = 30_000L, minHits = 2)),
    /** 门/柜开关 —— 可能有人进出。 */
    DOOR(EventParams(threshold = 0.25f, dedupMs = 5_000L, minHits = 1)),
}

/** 观测后的状态转移结果。 */
sealed interface CryVerdict {
    /** 无事发生（安静 / 未达持续阈值 / 冷却中）。 */
    data object None : CryVerdict

    /** 命中持续阈值且冷却已过——应发出一次报警，并标记本次哭声周期的开始。 */
    data class Alert(
        val cryScore: Float,
        val streak: Int,
        /** 本次哭声周期（cry_started）的开始时间戳毫秒。 */
        val startedAt: Long,
        /** 自周期开始到本次报警的持续毫秒。 */
        val durationMs: Long,
    ) : CryVerdict

    /** 有人声介入，抑制并清零（不报警，直到语音消失后重新累计）。 */
    data object Suppressed : CryVerdict

    /**
     * 哭声周期结束（cry_ended）：在已产生过 [Alert] 后，一旦再次进入静默清零或
     * 语音抑制，即发出一次该事件，携带开始时间与峰值得分，供历史记录/日志展示。
     */
    data class Ended(
        /** 本次哭声周期的开始时间戳毫秒。 */
        val startedAt: Long,
        /** 哭声周期的持续毫秒（结束时间 - 开始时间）。 */
        val durationMs: Long,
        /** 周期内观察到的最大哭声得分。 */
        val peakScore: Float,
    ) : CryVerdict

    /**
     * 非哭声的附加事件（per-class 独立判定），携带类别、得分、首次命中和持续时长。
     * INTENSE_CRY 为高优先级事件（可走报警），其余事件仅记录不打扰。
     */
    data class Event(
        val type: EventType,
        val score: Float,
        /** 该事件首次命中的时间戳毫秒。 */
        val startedAt: Long,
        /** 自首次命中以来的持续毫秒。 */
        val durationMs: Long,
    ) : CryVerdict
}

/**
 * 纯状态机：把 [CryObservation] 流折叠成 [CryVerdict] 事件流。无 Android 依赖，
 * 可直接在 JVM 单元测试里验证全部转移逻辑。
 *
 * 主状态（哭声周期，保持原 crywatch+baby-monitor 语义）：
 *  - 连续命中数 [streak]、连续未命中数 [silence]、上次报警时间、当前哭声周期
 *    开始时间 [activeSince]。
 *  - Begin/End 对齐算法参考 Beddington CryEventTracker 的 cry_started/cry_ended
 *    double-event 模型，额外保留本项目更强的语音抑制与 RMS 门限。
 *  - 哭声周期结束后提供 [params.graceMs] 宽限期：期内再哭合并为同一次事件（抗碎段）。
 *
 * 附加事件（per-class，独立于哭声周期）：在【非安静、未带语音抑制】的真实事件帧上，
 * 对每个 [EventType] 单独累计命中次数、按 dedup 去重，命中即产出 [CryVerdict.Event]。
 * 安静帧 / 语音抑制帧不产生附加事件（避免白噪音因整窗高分误报、或笑声被当成哭声压制）。
 *
 * 转移规则（对齐 baby_monitor.py:41-104 主循环 + Beddington 事件模型）：
 *  1. RMS 低于门限 dB → 视为安静帧：silence+1，命中阈值后清零；若处于哭声周期则补发 Ended。
 *  2. speech > speechReset → 全量清零 + 显式抑制一次（有人哄娃）；若处于哭声周期则先补发 Ended。
 *  3. cry >= cryProb → streak+1、silence=0；streak 达到 sustainHits 且冷却已过 → 开启周期并发 Alert。
 *  4. 其余 → silence+1；连续 silence 达到 silenceReset → 清零 streak，并结束哭声周期。
 *  5. 非安静且未被杀声抑制时，对 INTENSE_CRY / COUGH / SNEEZE / SCREAM / LAUGHTER /
 *     CHILD_SPEECH / WHITE_NOISE / DOOR 做 per-class 检测，命中产出 [CryVerdict.Event]。
 */
class BabyCryDetectorState(
    private val params: BabyCryParams = BabyCryParams(),
    /** 注入时钟，测试用。 */
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var streak = 0
    private var silence = 0
    private var lastAlertAt = 0L
    /** 当前哭声周期开始时间，null 表示不在周期内（只有真正 Alert 过才非空）。 */
    private var activeSince: Long? = null
    /** 当前周期内峰值哭声得分。 */
    private var activePeak = 0f
    /** 最近一次哭声周期开始时间（用于 grace 合并回同一起点）。 */
    private var lastEpisodeStartAt = 0L
    /** 最近一次哭声周期结束时间（用于 grace 宽限期判断）。 */
    private var lastEpisodeEndAt = 0L

    /** 各类附加事件的运行状态（命中数 / 最近触发时间 / 首次命中时间）。 */
    private class EventTracker(val type: EventType) {
        var hits = 0
        var lastFiredAt = 0L
        var firstHitAt = 0L
    }

    private val trackers: Map<EventType, EventTracker> =
        EventType.entries.associateWith { EventTracker(it) }

    /** 读取指定事件类在当前观测上的分数。 */
    private fun scoreOf(type: EventType, o: CryObservation): Float = when (type) {
        EventType.INTENSE_CRY -> o.intenseCryScore
        EventType.COUGH -> o.coughScore
        EventType.SNEEZE -> o.sneezeScore
        EventType.SCREAM -> o.screamScore
        EventType.LAUGHTER -> o.laughterScore
        EventType.CHILD_SPEECH -> o.childSpeechScore
        EventType.WHITE_NOISE -> o.whiteNoiseScore
        EventType.DOOR -> o.doorScore
    }

    /** INTENSE_CRY 的门槛跟随 [params.intenseCryProb]（灵敏度可调），其余用枚举内置阈值。 */
    private fun thresholdOf(type: EventType): Float =
        if (type == EventType.INTENSE_CRY) params.intenseCryProb else type.params.threshold

    /** 结束当前哭声周期（若在周期内），返回 Ended 事件，否则 null。 */
    private fun endActiveIfAny(): CryVerdict.Ended? {
        val start = activeSince ?: return null
        activeSince = null
        val peak = activePeak
        activePeak = 0f
        lastEpisodeStartAt = start
        lastEpisodeEndAt = now()
        return CryVerdict.Ended(
            startedAt = start,
            durationMs = (now() - start).coerceAtLeast(0L),
            peakScore = peak,
        )
    }

    /** 处理一次观测，返回判定结果（见类注释的转移规则）。 */
    fun observe(o: CryObservation): CryVerdict {
        val t = now()
        val quiet = rmsToDb(o.rms) < params.rmsGateDb

        // 1) 安静门限：RMS 太低直接当无声处理。
        if (quiet) {
            silence += 1
            if (silence >= params.silenceReset) {
                streak = 0
                val ended = endActiveIfAny()
                return ended ?: CryVerdict.None
            }
            return CryVerdict.None
        }

        // 2) 有人声：视为大人在哄，清零并抑制（若正在哭，则先结束这个周期）。
        if (o.speechScore > params.speechReset) {
            streak = 0
            silence = 0
            return endActiveIfAny() ?: CryVerdict.Suppressed
        }

        // 3) 哭声命中（低声哼唧 → 走周静 Alert 全套）。
        if (o.cryScore >= params.cryProb) {
            streak += 1
            silence = 0
            if (streak >= params.sustainHits && t - lastAlertAt >= params.cooldownMs) {
                lastAlertAt = t
                if (activeSince == null) {
                    // grace：若上次 episode 刚结束不久，沿用其起点合并（抗碎段）。
                    activeSince = if (t - lastEpisodeEndAt <= params.graceMs) {
                        lastEpisodeStartAt
                    } else {
                        t
                    }
                    activePeak = o.cryScore
                } else {
                    activePeak = maxOf(activePeak, o.cryScore)
                }
                return CryVerdict.Alert(
                    cryScore = o.cryScore,
                    streak = streak,
                    startedAt = activeSince!!,
                    durationMs = (t - activeSince!!).coerceAtLeast(0L),
                )
            }
            return checkEvents(t, o) ?: CryVerdict.None
        }

        // 4) 未命中：累计静默，足够久后清零并结束周期。
        silence += 1
        if (silence >= params.silenceReset) {
            streak = 0
            val ended = endActiveIfAny()
            return ended ?: checkEvents(t, o) ?: CryVerdict.None
        }
        return checkEvents(t, o) ?: CryVerdict.None
    }

    /**
     * 附加事件检测：非安静、已通过语音抑制外的真实事件帧才检查。
     * 每类独立累计命中 + 去重，命中即返回首个事件。
     */
    private fun checkEvents(t: Long, o: CryObservation): CryVerdict.Event? {
        for (type in EventType.entries) {
            val tracker = trackers.getValue(type)
            val score = scoreOf(type, o)
            val threshold = thresholdOf(type)
            if (score >= threshold) {
                if (tracker.hits == 0) tracker.firstHitAt = t
                tracker.hits += 1
                if (tracker.hits >= type.params.minHits &&
                    t - tracker.lastFiredAt >= type.params.dedupMs
                ) {
                    tracker.lastFiredAt = t
                    tracker.hits = 0
                    return CryVerdict.Event(
                        type = type,
                        score = score,
                        startedAt = tracker.firstHitAt,
                        durationMs = (t - tracker.firstHitAt).coerceAtLeast(0L),
                    )
                }
            } else {
                tracker.hits = 0
            }
        }
        return null
    }

    companion object {
        /** 线性 RMS → dBFS。 */
        fun rmsToDb(rms: Float): Float =
            (20.0 * kotlin.math.log10(rms.toDouble() + 1e-9)).toFloat()
    }
}