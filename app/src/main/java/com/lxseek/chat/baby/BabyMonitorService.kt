package com.lxseek.chat.baby

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lxseek.chat.MainActivity
import com.lxseek.chat.R
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * 婴儿哭声监护前台服务（foregroundServiceType=dataSync）。
 *
 * 流程（对齐 baby-monitor 的主循环 + crywatch 的判定门限）：
 *  1. 后台持续录音：自建 16kHz / mono / PCM16 `AudioRecord`（VOICE_RECOGNITION 源，
 *     复用 AEC/NS，避免 TTS/媒体回声误触发）；
 *  2. 每凑满一个 0.975s 推理窗（15600 样本）送 [YamnetCryClassifier]；
 *     先过 RMS 门限（安静窗跳过推理省电，对齐 crywatch 的 RMS_GATE）；
 *  3. 判定走 [BabyCryDetectorState]（sustain / silence-reset / speech 抑制 / cooldown）；
 *  4. 触发时通过 [com.lxseek.chat.im.ImBridgeService.channels()] 向选中渠道发报警：
 *     空选集 = 所有已启用渠道（用户需求默认），非空 = 精确复选集合；
 *     每个渠道选最近一个会话发送，全部失败时发本地通知兜底。
 *
 * 不用 AudioCaptureManager：它会往 cacheDir 落盘 PCM/WAV 且无法脱离开关式会话模型；
 * 监护场景需要数小时不间断录音，自持轻量 AudioRecord 循环更合适。
 *
 * 持有 PARTIAL_WAKE_LOCK 保证息屏后麦克风与推理不中断（监护是核心场景）。
 */
class BabyMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    /** 当前生效配置（由 [monitorLoop] 收集器维护），供免打扰时段判定共享。 */
    @Volatile private var activeConfig = BabyMonitorStore.Config()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.baby_monitor_notification_text)),
            foregroundServiceType(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasRecordPermission()) {
            DebugLog.w(TAG, "RECORD_AUDIO missing; baby monitor stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        if (scopeIsActive) return START_STICKY
        scopeIsActive = true
        scope.launch { monitorLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scopeIsActive = false
        scope.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        super.onDestroy()
    }

    // ── 主循环 ────────────────────────────────────────────────

    private suspend fun monitorLoop() {
        val appContext = applicationContext

        // 模型缺失则直接停（开关 UI 应在下载完成后才允许开启）。
        val manager = BabyModelManager.getInstance(appContext)
        if (!manager.isDownloaded()) {
            DebugLog.w(TAG, "YAMNet model missing; baby monitor stopping")
            stopSelf()
            return
        }

        val classifier = try {
            YamnetCryClassifier(appContext, manager.modelFile)
        } catch (t: Throwable) {
            // Throwable 而非 Exception：模型损坏 / 原生层初始化失败（含部分 TFLite 版本的
            // abort）一律优雅停服，绝不让整个 App 闪退。
            DebugLog.e(TAG, "YAMNet init failed", t)
            stopSelf()
            return
        }

        // 哭声监护使用 YAMNet 单引擎。InfantCryNet ONNX 双引擎此前因
        // onnxruntime-android 对动态批次输出的 JNI 原生 abort（导致闪退）已停用，
        // 相关类与 onnxruntime 依赖现已从构建中彻底移除。

        acquireWakeLock()

        val store = BabyMonitorStore(appContext)
        val sampleRate = SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = (minBuffer * 2).coerceAtLeast(YamnetCryClassifier.INPUT_SAMPLES * 2)

        var audioRecord: AudioRecord? = null
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                DebugLog.e(TAG, "AudioRecord init failed (state=${audioRecord.state})")
                stopSelf()
                return
            }
            audioRecord.startRecording()
            DebugLog.i(TAG, "baby monitor loop started")

            // 滑动窗口：凑满一个推理窗就推理并滑动（每次滑动半窗，重叠检测防漏）。
            val window = ShortArray(YamnetCryClassifier.INPUT_SAMPLES)
            val readBuf = ShortArray(bufferSize / 2)
            var filled = 0
            var detector = buildDetector(store)

            // 配置热更新：单独协程读最新配置。
            val configJob = scope.launch {
                store.config.collect { cfg ->
                    activeConfig = cfg
                    // 重建检测器以应用灵敏度档位 / sustain / cooldown 变化（enabled=false
                    // 时服务会被停掉，这里不处理开关本身）。
                    detector = BabyCryDetectorState(buildParams(cfg))
                }
            }

            try {
                while (currentCoroutineContext().isActive) {
                    val n = audioRecord.read(readBuf, 0, readBuf.size)
                    if (n <= 0) continue
                    var offset = 0
                    while (offset < n) {
                        val toCopy = minOf(YamnetCryClassifier.INPUT_SAMPLES - filled, n - offset)
                        System.arraycopy(readBuf, offset, window, filled, toCopy)
                        filled += toCopy
                        offset += toCopy
                        if (filled >= YamnetCryClassifier.INPUT_SAMPLES) {
                            val samples = FloatArray(filled) { window[it] / 32768f }
                            val rms = rmsOf(samples)
                            val verdict = if (BabyCryDetectorState.rmsToDb(rms) < RMS_GATE_DB) {
                                detector.observe(CryObservation(rms = rms))
                            } else {
                                val s = classifier.classify(samples)
                                    ?: YamnetScores()
                                // 单引擎（YAMNet）：InfantCryNet 双引擎因 onnxruntime-android
                                // 原生 abort 已停用，直接用 YAMNet 概率作为最终的哭声/事件分数。
                                detector.observe(
                                    CryObservation(
                                        cryScore = s.cry,
                                        speechScore = s.speech,
                                        rms = rms,
                                        intenseCryScore = s.intenseCry,
                                        coughScore = s.cough,
                                        sneezeScore = s.sneeze,
                                        screamScore = s.scream,
                                        laughterScore = s.laughter,
                                        childSpeechScore = s.childSpeech,
                                        whiteNoiseScore = s.whiteNoise,
                                        doorScore = s.door,
                                        dogBarkScore = s.dogBark,
                                        catScore = s.cat,
                                        birdScore = s.bird,
                                        glassBreakScore = s.glassBreak,
                                        sirenScore = s.siren,
                                        phoneRingScore = s.phoneRing,
                                        clapScore = s.clap,
                                        whistleScore = s.whistle,
                                        footstepsScore = s.footsteps,
                                        waterScore = s.water,
                                        musicScore = s.music,
                                        pigScore = s.pig,
                                        cowScore = s.cow,
                                        chickenScore = s.chicken,
                                        horseScore = s.horse,
                                        sheepScore = s.sheep,
                                    ),
                                )
                            }
                            when (verdict) {
                                is CryVerdict.Alert -> {
                                    BabyEventHistory.append(
                                        BabyEventEntry(
                                            id = System.currentTimeMillis(),
                                            typeName = BabyEventEntry.TYPE_CRY_ALERT,
                                            score = verdict.cryScore,
                                            timeMs = System.currentTimeMillis(),
                                            kind = BabyEventEntry.Kind.CRY_ALERT,
                                        ),
                                    )
                                    DebugLog.i(TAG, "cry alert: score=${verdict.cryScore} streak=${verdict.streak}")
                                    sendAlerts(verdict)
                                }
                                is CryVerdict.Ended -> {
                                    BabyEventHistory.append(
                                        BabyEventEntry(
                                            id = System.currentTimeMillis(),
                                            typeName = BabyEventEntry.TYPE_CRY_ENDED,
                                            score = verdict.peakScore,
                                            timeMs = System.currentTimeMillis(),
                                            kind = BabyEventEntry.Kind.CRY_ENDED,
                                        ),
                                    )
                                    DebugLog.i(
                                        TAG,
                                        "cry episode ended: durMs=${verdict.durationMs} peak=${verdict.peakScore}",
                                    )
                                }
                                is CryVerdict.Event -> {
                                    BabyEventHistory.append(
                                        BabyEventEntry(
                                            id = System.currentTimeMillis(),
                                            typeName = verdict.type.name,
                                            score = verdict.score,
                                            timeMs = System.currentTimeMillis(),
                                            kind = BabyEventEntry.Kind.EVENT,
                                        ),
                                    )
                                    // 剧烈大哭始终报警（安全优先）；其余事件仅记录不打扰，
                                    // 免打扰时段内静默（仅 DebugLog，不触发任何外部动作）。
                                    val nowMin = System.currentTimeMillis() /
                                        (1000L * 60) % 1440
                                    val quiet = activeConfig.inQuietHours(nowMin.toInt())
                                    if (verdict.type == EventType.INTENSE_CRY) {
                                        DebugLog.i(
                                            TAG,
                                            "intense cry: score=${verdict.score} durMs=${verdict.durationMs}",
                                        )
                                        sendIntenseCryAlert(verdict)
                                    } else if (!quiet) {
                                        DebugLog.i(
                                            TAG,
                                            "baby event: ${verdict.type} score=${verdict.score} " +
                                                "durMs=${verdict.durationMs}",
                                        )
                                        // 其它事件当前仅记录（不打扰），留作触发时间轴扩展点。
                                    } else {
                                        DebugLog.i(
                                            TAG,
                                            "baby event (quiet hours): ${verdict.type} " +
                                                "score=${verdict.score}",
                                        )
                                    }
                                }
                                else -> Unit
                            }
                            // 半窗滑动。
                            val half = YamnetCryClassifier.INPUT_SAMPLES / 2
                            System.arraycopy(window, half, window, 0, YamnetCryClassifier.INPUT_SAMPLES - half)
                            filled = YamnetCryClassifier.INPUT_SAMPLES - half
                        }
                    }
                }
            } finally {
                configJob.cancel()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "monitor loop crashed", e)
            stopSelf()
        } catch (t: Throwable) {
            // 致命错误（如 OOM / 原生层 abort）也优雅降级：停掉监护服务并释放资源，
            // 避免整个 App 闪退。
            DebugLog.e(TAG, "monitor loop fatal", t)
            stopSelf()
        } finally {
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            classifier.close()
        }
    }

    private suspend fun buildDetector(store: BabyMonitorStore): BabyCryDetectorState {
        val cfg = store.config.first()
        return BabyCryDetectorState(buildParams(cfg))
    }

    /**
     * 把配置映射为一整套状态机参数。灵敏度档位一键替换核心门槛（吸收 android-vad
     * NORMAL/AGGRESSIVE/VERY_AGGRESSIVE 分级思路）；用户手动调过的 sustain/cooldown 优先。
     */
    private fun buildParams(cfg: BabyMonitorStore.Config): BabyCryParams {
        // 灵敏度三档：门槛越低越灵敏（召回↑、误报↑）；STABLE 反之。
        val base = when (cfg.sensitivity) {
            BabySensitivity.SENSITIVE -> BabyCryParams(
                cryProb = 0.32f, intenseCryProb = 0.5f, sustainHits = 2,
                silenceReset = 6, speechReset = 0.55f,
            )
            BabySensitivity.STABLE -> BabyCryParams(
                cryProb = 0.48f, intenseCryProb = 0.7f, sustainHits = 4,
                silenceReset = 12, speechReset = 0.45f,
            )
            BabySensitivity.NORMAL -> BabyCryParams()
        }
        return base.copy(
            sustainHits = cfg.sustainHits,
            cooldownMs = cfg.cooldownMinutes * 60_000L,
            autoTuneEnabled = cfg.autoTuneEnabled,
            eventOverrides = cfg.eventOverrides
                .entries
                .mapNotNull { (name, p) ->
                    runCatching { EventType.valueOf(name) }.getOrNull()?.let { it to p }
                }
                .toMap(),
        )
    }

    // ── 报警发送 ──────────────────────────────────────────────

    /**
     * 遍历选中渠道发报警。空选集 = 所有已启用渠道。每渠道取最近会话发送；
     * 全部渠道都失败时发一条本地高优先级通知兜底（离线也能看见）。
     */
    private suspend fun sendAlerts(verdict: CryVerdict.Alert) {
        val appContext = applicationContext
        val container = (appContext as? com.lxseek.chat.LxChatApplication)?.container
        if (container == null) {
            DebugLog.w(TAG, "AppContainer unavailable; local notify only")
            showLocalAlert(verdict)
            return
        }
        val bridge = container.imBridgeService
        val cfg = BabyMonitorStore(appContext).config.first()
        val channels = bridge.channels()
        val targets = if (cfg.selectedChannels.isEmpty()) {
            channels
        } else {
            channels.filterKeys { it in cfg.selectedChannels }
        }
        if (targets.isEmpty()) {
            DebugLog.w(TAG, "no IM channel selected; local notify only")
            showLocalAlert(verdict)
            return
        }

        val message = buildAlertText(verdict)
        var anySent = false
        for ((channelId, channel) in targets) {
            try {
                val conversation = channel.listConversations().firstOrNull() ?: continue
                val result = channel.sendMessage(conversation.id, message)
                if (result is com.lxseek.chat.im.ImSendResult.Success) {
                    anySent = true
                    DebugLog.i(TAG, "alert sent via $channelId -> ${conversation.title}")
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "alert via $channelId failed", e)
            }
        }
        if (!anySent) showLocalNotification(getString(R.string.baby_monitor_alert_title), buildAlertText(verdict))
    }

    /**
     * 剧烈大哭（INTENSE_CRY）分级报警：复用选中 IM 渠道发送，文案带「剧烈」标识，
     * 本地兜底通知也使用同渠道的高优先级通知。事件分数过低时不打扰——仅当分数
     * 达到 [thresholdOf]（默认 0.6）才会产生 [EventType.INTENSE_CRY] 事件。
     */
    private suspend fun sendIntenseCryAlert(verdict: CryVerdict.Event) {
        val appContext = applicationContext
        val container = (appContext as? com.lxseek.chat.LxChatApplication)?.container
        if (container == null) {
            showLocalNotification(getString(R.string.baby_monitor_intense_cry_title), buildIntenseCryText(verdict))
            return
        }
        val bridge = container.imBridgeService
        val cfg = BabyMonitorStore(appContext).config.first()
        val channels = bridge.channels()
        val targets = if (cfg.selectedChannels.isEmpty()) {
            channels
        } else {
            channels.filterKeys { it in cfg.selectedChannels }
        }
        if (targets.isEmpty()) {
            showLocalNotification(getString(R.string.baby_monitor_intense_cry_title), buildIntenseCryText(verdict))
            return
        }
        val message = buildIntenseCryText(verdict)
        var anySent = false
        for ((channelId, channel) in targets) {
            try {
                val conversation = channel.listConversations().firstOrNull() ?: continue
                val result = channel.sendMessage(conversation.id, message)
                if (result is com.lxseek.chat.im.ImSendResult.Success) {
                    anySent = true
                    DebugLog.i(TAG, "intense-cry alert sent via $channelId -> ${conversation.title}")
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "intense-cry alert via $channelId failed", e)
            }
        }
        if (!anySent) showLocalNotification(getString(R.string.baby_monitor_intense_cry_title), message)
    }

    /** 本地兜底通知（无可用 IM 渠道或全部发送失败时），文案可定制。 */
    private fun showLocalNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(ALERT_NOTIFICATION_ID, notification) }
    }

    /** 旧签名兼容：普通哭声报警走默认标题的本地兜底。 */
    private fun showLocalAlert(verdict: CryVerdict.Alert) {
        showLocalNotification(getString(R.string.baby_monitor_alert_title), buildAlertText(verdict))
    }

    /**
     * 组装报警文案：带触发时间、哭声置信度与连续命中次数，方便家长实时掌控报警依据。
     * IM 渠道与本地兜底通知共用同一份文案。
     */
    private fun buildAlertText(verdict: CryVerdict.Alert): String {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val pct = (verdict.cryScore * 100).toInt()
        return getString(R.string.baby_monitor_alert_message, time, pct, verdict.streak)
    }

    /** 剧烈大哭分级报警文案（带剧烈标识 + 分数 + 持续时长）。 */
    private fun buildIntenseCryText(verdict: CryVerdict.Event): String {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val pct = (verdict.score * 100).toInt()
        val secs = verdict.durationMs / 1000
        return getString(R.string.baby_monitor_intense_cry_message, time, pct, secs)
    }

    // ── 前台通知与权限 ───────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.baby_monitor_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_MS)
        }
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun rmsOf(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += (s * s).toDouble()
        return sqrt(sum / samples.size).toFloat()
    }

    companion object {
        private const val TAG = "BabyMonitorService"
        private const val CHANNEL_ID = "lxchat_baby_monitor"
        private const val NOTIFICATION_ID = 3
        private const val ALERT_CHANNEL_ID = "lxchat_baby_monitor_alert"
        private const val ALERT_NOTIFICATION_ID = 3001
        private const val WAKE_LOCK_TAG = "lxchat:baby-monitor"
        private const val MAX_WAKE_LOCK_MS = 12 * 60 * 60 * 1000L
        private const val SAMPLE_RATE = 16000

        /** 安静门限 dBFS（对齐 crywatch CRY_RMS_GATE_DB=-60）。 */
        private const val RMS_GATE_DB = -60f

        @Volatile private var scopeIsActive = false

        fun start(context: Context) {
            val app = context.applicationContext
            try {
                val intent = Intent(app, BabyMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (e: RuntimeException) {
                DebugLog.e(TAG, "Failed to start baby monitor service", e)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, BabyMonitorService::class.java),
                )
            }
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            try {
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.baby_monitor_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.baby_monitor_channel_desc)
                    setShowBadge(false)
                    setSound(null, null)
                }.also { manager.createNotificationChannel(it) }
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    context.getString(R.string.baby_monitor_alert_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.baby_monitor_alert_channel_desc)
                    setShowBadge(true)
                }.also { manager.createNotificationChannel(it) }
            } catch (e: Throwable) {
                DebugLog.w(TAG, "Failed to create baby monitor channels", e)
            }
        }
    }
}
