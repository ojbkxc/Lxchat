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

        // InfantCryNet 双引擎：加载失败不阻断（降级为 YAMNet 单引擎）。
        val infantGate = InfantCryGateClassifier.load(appContext).getOrNull()
        if (infantGate == null) {
            DebugLog.w(TAG, "InfantCryNet gate unavailable; YAMNet-only mode")
        }

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
                    // 重建检测器以应用 sustain / cooldown 变化（enabled=false 时服务会被停掉，
                    // 这里不处理开关本身）。
                    detector = BabyCryDetectorState(
                        BabyCryParams(
                            sustainHits = cfg.sustainHits,
                            cooldownMs = cfg.cooldownMinutes * 60_000L,
                        ),
                    )
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
                                detector.observe(CryObservation(0f, 0f, rms))
                            } else {
                                val (cry, speech) = classifier.classify(samples)
                                    ?: (0f to 0f)
                                // 双引擎融合：InfantCryNet 门控概率可用时与 YAMNet 取几何平均
                                //（任一引擎接近 0 即压低总分，双高才高——保守防误报）。
                                val fused = if (infantGate != null) {
                                    val gate = classifyWithGate(infantGate, samples)
                                    if (gate != null) sqrt(cry * gate).toFloat() else cry
                                } else cry
                                detector.observe(CryObservation(fused, speech, rms))
                            }
                            if (verdict is CryVerdict.Alert) {
                                DebugLog.i(TAG, "cry alert: score=${verdict.cryScore} streak=${verdict.streak}")
                                sendAlerts(verdict)
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
            infantGate?.close()
        }
    }

    /**
     * InfantCryNet 门控推理：16kHz 采样重采样到模型要求的 22050Hz（线性插值），
     * 归一化对齐 Python `librosa.util.normalize`（峰值归一到 ±1）。
     */
    private fun classifyWithGate(
        gate: InfantCryGateClassifier,
        samples16k: FloatArray,
    ): Double? {
        val resampled = resampleTo22050(samples16k)
        val normalized = peakNormalize(resampled)
        val features = InfantCryFeatures.extract(normalized)
        return gate.classify(features)
    }

    private fun resampleTo22050(src: FloatArray): FloatArray {
        val ratio = 22050.0 / SAMPLE_RATE
        val outLen = (src.size * ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i / ratio
            val i0 = pos.toInt()
            val i1 = minOf(i0 + 1, src.size - 1)
            val t = (pos - i0).toFloat()
            out[i] = src[i0] * (1 - t) + src[i1] * t
        }
        return out
    }

    private fun peakNormalize(x: FloatArray): FloatArray {
        var peak = 0f
        for (v in x) peak = maxOf(peak, kotlin.math.abs(v))
        if (peak <= 1e-8f) return x
        val out = FloatArray(x.size)
        for (i in x.indices) out[i] = x[i] / peak
        return out
    }

    private suspend fun buildDetector(store: BabyMonitorStore): BabyCryDetectorState {
        val cfg = store.config.first()
        return BabyCryDetectorState(
            BabyCryParams(
                sustainHits = cfg.sustainHits,
                cooldownMs = cfg.cooldownMinutes * 60_000L,
            ),
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

        val pct = (verdict.cryScore * 100).toInt()
        val message = appContext.getString(R.string.baby_monitor_alert_message, pct)
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
        if (!anySent) showLocalAlert(verdict)
    }

    /** 本地兜底通知（无可用 IM 渠道或全部发送失败时）。 */
    private fun showLocalAlert(verdict: CryVerdict.Alert) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val pct = (verdict.cryScore * 100).toInt()
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.baby_monitor_alert_title))
            .setContentText(getString(R.string.baby_monitor_alert_message, pct))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(ALERT_NOTIFICATION_ID, notification) }
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
