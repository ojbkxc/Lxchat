package com.lxseek.chat.tool

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.viewmodel.GenerationContext
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Screen recording tool provider backed by MediaProjection + MediaCodec (H.264) + MediaMuxer (MP4).
 *
 * Three tools are exposed:
 *  - `screen_record_start` : begin recording, returns a `recording_id`
 *  - `screen_record_stop`  : stop a recording, returns the output file path
 *  - `screen_record_status`: query the active recording (is_recording / duration / file_size)
 *
 * # MediaProjection token
 * MediaProjection can only be obtained from an Activity `onActivityResult` callback. ToolProvider
 * only has an Application context, so a static [ScreenRecordTokenHolder] bridges the two halves:
 *   1. The Activity calls [ScreenRecordToolProvider.createScreenCaptureIntent] to get the system
 *      consent intent and launches it with `startActivityForResult`.
 *   2. In `onActivityResult` the Activity calls [ScreenRecordToolProvider.onScreenCaptureResult]
 *      to store the granted token.
 *   3. `screen_record_start` consumes the token. Each token is single-use (Android requires a fresh
 *      consent after a projection is stopped on API 26+), so the user must re-authorize between
 *      recordings.
 *
 * # Foreground service
 * [ScreenRecordService] holds a low-priority ongoing notification to keep the process alive while
 * the capture loop runs. The engine itself lives in a static session map on the provider so that
 * recording continues even if the service cannot be promoted (e.g. when the host manifest has not
 * declared the service yet). For full background survival the host must register the service and
 * the `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission in its AndroidManifest.xml:
 *
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
 *   <service android:name="com.lxseek.chat.tool.ScreenRecordService"
 *            android:foregroundServiceType="mediaProjection"
 *            android:exported="false" />
 *
 * No external dependencies are used — only Android platform APIs.
 */
class ScreenRecordToolProvider(private val app: Application) : ToolProvider {

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> =
        definitions(ctx).map { def ->
            ToolDescriptor(
                definition = def,
                riskLevel = RiskLevel.Moderate,
                tier = ToolTier.Dangerous,
                requiresApproval = true,
            )
        }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun prop(t: String, d: String) = ToolProperty(t, d)
        return listOf(
            tool(
                "screen_record_start",
                "Start recording the device screen to an MP4 (H.264) file. The user must first " +
                    "grant screen capture consent via createScreenCaptureIntent/onScreenCaptureResult. " +
                    "Returns a recording_id used with screen_record_stop/screen_record_status.",
                mapOf(
                    "width" to prop("integer", "Capture width in pixels. Defaults to the screen width."),
                    "height" to prop("integer", "Capture height in pixels. Defaults to the screen height."),
                    "bitrate" to prop("integer", "Video bitrate in bits per second. Default 8000000 (8 Mbps)."),
                    "fps" to prop("integer", "Target frame rate. Default 30."),
                    "output_path" to prop("string", "Optional absolute output file path. Defaults to app external storage."),
                ),
                emptyList(),
            ),
            tool(
                "screen_record_stop",
                "Stop an in-progress screen recording and finalize the MP4 file. Returns the output " +
                    "file path and size. If recording_id is omitted, stops the active recording.",
                mapOf("recording_id" to prop("string", "Optional recording_id returned by screen_record_start.")),
                emptyList(),
            ),
            tool(
                "screen_record_status",
                "Query the current screen recording state: is_recording, duration_ms, file_size, " +
                    "output_path, and error (if any). Returns is_recording=false when idle.",
                mapOf("recording_id" to prop("string", "Optional recording_id; defaults to the active recording.")),
                emptyList(),
            ),
        )
    }

    override fun handles(name: String): Boolean =
        name == "screen_record_start" || name == "screen_record_stop" || name == "screen_record_status"

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "screen_record_start" -> startRecording(arguments)
                "screen_record_stop" -> stopRecording(arguments)
                "screen_record_status" -> statusRecording(arguments)
                else -> err("unknown_tool", "Unknown screen record tool: $name")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "screen_record_$name failed", e)
            err("tool_error", e.message)
        }
    }

    // ── Recording control ─────────────────────────────────────

    private fun startRecording(arguments: String): String {
        // Reject if something is already recording — Android gives one active projection at a time.
        synchronized(sessionLock) {
            sessions.values.firstOrNull { it.engine.isRunning }?.let {
                return err("already_recording", "A recording is already in progress: ${it.id}. Stop it first.")
            }
        }

        // Obtain a fresh MediaProjection from the consent token.
        val mpm = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return err("no_projection_manager", "MediaProjectionManager unavailable on this device.")
        val projection = ScreenRecordTokenHolder.consume(mpm)
            ?: return err(
                "no_consent",
                "Screen capture consent not granted or already consumed. Call " +
                    "ScreenRecordToolProvider.createScreenCaptureIntent() from an Activity and forward " +
                    "the result to onScreenCaptureResult() first.",
            )

        // Resolve parameters with sensible defaults from the real display.
        val dm = app.resources.displayMetrics
        val width = (argInt("width", arguments) ?: dm.widthPixels).coerceIn(1, 4096)
        val height = (argInt("height", arguments) ?: dm.heightPixels).coerceIn(1, 4096)
        val bitrate = (argInt("bitrate", arguments) ?: 8_000_000).coerceIn(100_000, 100_000_000)
        val fps = (argInt("fps", arguments) ?: 30).coerceIn(1, 120)
        val output = resolveOutputFile(argString("output_path", arguments))
        val id = UUID.randomUUID().toString()

        val engine = ScreenRecordEngine(
            mediaProjection = projection,
            width = width,
            height = height,
            bitrate = bitrate,
            fps = fps,
            densityDpi = dm.densityDpi,
            outputFile = output,
        )
        synchronized(sessionLock) { sessions[id] = RecordingSession(id, engine, output) }

        // Try to promote a foreground service for background survival. Failure is non-fatal: the
        // engine still runs in-process; it just may be reaped sooner if the app goes to background.
        startForegroundService(id)
        engine.start()

        if (engine.error != null) {
            synchronized(sessionLock) { sessions.remove(id) }
            stopForegroundService()
            return err("start_failed", engine.error)
        }
        return buildJsonObject {
            put("type", "screen_record_start")
            put("status", "ok")
            put("recording_id", id)
            put("output_path", output.absolutePath)
            put("width", width)
            put("height", height)
            put("bitrate", bitrate)
            put("fps", fps)
        }.toString()
    }

    private fun stopRecording(arguments: String): String {
        val id = argString("recording_id", arguments)
        val session = pickSession(id)
            ?: return err("not_recording", "No active recording to stop.")
        val ok = engineStop(session)
        val out = session.outputFile
        synchronized(sessionLock) { sessions.remove(session.id) }
        stopForegroundService()
        return buildJsonObject {
            put("type", "screen_record_stop")
            put("status", if (ok) "ok" else "partial")
            put("recording_id", session.id)
            put("output_path", out.absolutePath)
            put("file_size", if (out.exists()) out.length() else 0)
            session.engine.error?.let { put("error", it) }
        }.toString()
    }

    private fun statusRecording(arguments: String): String {
        val id = argString("recording_id", arguments)
        val session = pickSession(id)
        if (session == null) {
            return buildJsonObject {
                put("type", "screen_record_status")
                put("is_recording", false)
                put("has_consent", ScreenRecordTokenHolder.hasToken())
            }.toString()
        }
        val st = session.engine.status()
        return buildJsonObject {
            put("type", "screen_record_status")
            put("is_recording", st.isRunning)
            put("recording_id", session.id)
            put("duration_ms", st.durationMs)
            put("file_size", st.fileSize)
            put("output_path", session.outputFile.absolutePath)
            st.error?.let { put("error", it) }
        }.toString()
    }

    private fun pickSession(id: String?): RecordingSession? = synchronized(sessionLock) {
        if (!id.isNullOrBlank()) {
            sessions[id]
        } else {
            // Default to the running session, else the most recent one.
            sessions.values.firstOrNull { it.engine.isRunning } ?: sessions.values.lastOrNull()
        }
    }

    private fun engineStop(session: RecordingSession): Boolean = try {
        session.engine.stop()
    } catch (e: Exception) {
        DebugLog.e(TAG, "stop engine failed", e)
        false
    }

    private fun resolveOutputFile(outputPath: String?): File {
        if (!outputPath.isNullOrBlank()) {
            val f = File(outputPath)
            f.parentFile?.mkdirs()
            return f
        }
        val dir = File(app.getExternalFilesDir(null), "screen_record")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "screen_${System.currentTimeMillis()}.mp4")
    }

    // ── Foreground service glue ───────────────────────────────

    private fun startForegroundService(recordingId: String) {
        val intent = Intent(app, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RECORDING_ID, recordingId)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                app.startService(intent)
            }
        } catch (e: Exception) {
            // Service likely not declared in the manifest yet — recording continues without it.
            DebugLog.w(TAG, "Foreground service not started (manifest not registered?): ${e.message}")
        }
    }

    private fun stopForegroundService() {
        val intent = Intent(app, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        try {
            app.startService(intent)
        } catch (_: Exception) {
            // Ignore — service may never have started.
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, ToolProperty>,
        required: List<String>,
    ) = ToolDefinition(function = ToolFunction(
        name = name,
        description = description,
        parameters = ToolParameters(properties = properties, required = required),
    ))

    private fun argString(key: String, arguments: String): String? {
        val stripped = arguments.ifBlank { "{}" }
        return try {
            val el = Json.decodeFromString<Map<String, JsonPrimitive>>(stripped)[key]
            val v = el?.content ?: return null
            if (v == "null") null else v
        } catch (_: Exception) {
            null
        }
    }

    private fun argInt(key: String, arguments: String): Int? {
        val stripped = arguments.ifBlank { "{}" }
        return try {
            Json.decodeFromString<Map<String, JsonPrimitive>>(stripped)[key]?.content?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "screen_record_error")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()

    /**
     * In-flight recording state. Kept in a static map so the engine survives configuration changes
     * and is reachable from both the provider and the foreground service.
     */
    private data class RecordingSession(
        val id: String,
        val engine: ScreenRecordEngine,
        val outputFile: File,
    )

    companion object {
        private const val TAG = "ScreenRecordTool"
        private val sessionLock = Any()
        private val sessions = mutableMapOf<String, RecordingSession>()

        /**
         * Build the system screen-capture consent intent. The host Activity should launch it with
         * `startActivityForResult` and then forward the result to [onScreenCaptureResult].
         */
        fun createScreenCaptureIntent(context: Context): Intent? {
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as? MediaProjectionManager ?: return null
            return ScreenRecordTokenHolder.createIntent(mgr)
        }

        /**
         * Store the result of the screen-capture consent dialog. Call from the Activity's
         * `onActivityResult` for the request code used when launching [createScreenCaptureIntent].
         */
        fun onScreenCaptureResult(resultCode: Int, data: Intent?) {
            ScreenRecordTokenHolder.store(resultCode, data)
        }

        /** True when a recording is currently active. */
        fun isRecording(): Boolean = synchronized(sessionLock) {
            sessions.values.any { it.engine.isRunning }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Foreground service that keeps the capture loop alive in the background.
// It only owns a notification; the encoder itself runs in ScreenRecordEngine.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Foreground service for screen recording. Promotes a low-priority ongoing notification so the OS
 * does not reap the process while [ScreenRecordEngine] is draining the encoder.
 *
 * Must be declared in AndroidManifest.xml with `foregroundServiceType="mediaProjection"` and the
 * `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission to actually run in the background on API 29+.
 */
class ScreenRecordService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundCompat()
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        ensureChannel()
        val notification = buildNotification("Screen recording in progress")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException or missing manifest permission/type.
            DebugLog.w(TAG, "startForeground failed: ${e.message}")
        }
    }

    private fun stopForegroundCompat() {
        try {
            @Suppress("DEPRECATION")
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "Screen Recording", NotificationManager.IMPORTANCE_LOW)
                ch.description = "Ongoing notification while the screen is being recorded"
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val pi = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = pi?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return builder
            .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_camera)
            .setContentTitle("LxChat Screen Record")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
    }

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val CHANNEL_ID = "lxchat_screen_record"
        private const val NOTIFICATION_ID = 0x5C52 // 'SR'

        const val ACTION_START = "lxchat.screen_record.START"
        const val ACTION_STOP = "lxchat.screen_record.STOP"
        const val EXTRA_RECORDING_ID = "recording_id"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MediaProjection consent token bridge between the Activity and the provider.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Static holder for the MediaProjection consent result. The Activity stores the (resultCode, data)
 * pair here after the user accepts the system dialog; the provider consumes it when starting a
 * recording. A token is single-use: once consumed, the user must re-authorize for the next
 * recording (Android invalidates the token after the projection is stopped on API 26+).
 */
internal object ScreenRecordTokenHolder {
    @Volatile private var resultCode: Int = 0
    @Volatile private var resultData: Intent? = null
    @Volatile private var consumed: Boolean = true

    /** Store a freshly granted consent result. */
    fun store(code: Int, data: Intent?) {
        resultCode = code
        resultData = data
        consumed = data == null || code == 0
    }

    /** Build the system consent intent to launch from an Activity. */
    fun createIntent(manager: MediaProjectionManager): Intent = manager.createScreenCaptureIntent()

    /** True when a usable, not-yet-consumed consent token is available. */
    fun hasToken(): Boolean = !consumed && resultData != null && resultCode != 0

    /**
     * Consume the stored token and produce a [MediaProjection]. Returns null when no token is
     * available or it has already been used.
     */
    fun consume(manager: MediaProjectionManager): MediaProjection? {
        if (consumed) return null
        val data = resultData ?: return null
        if (resultCode == 0) return null
        synchronized(this) {
            if (consumed) return null
            consumed = true
        }
        return try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            DebugLog.e("ScreenRecordToken", "getMediaProjection failed", e)
            null
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// The encoder engine: MediaProjection -> VirtualDisplay -> MediaCodec -> MediaMuxer.
// ─────────────────────────────────────────────────────────────────────────────

/** Snapshot of an engine's current state, surfaced by `screen_record_status`. */
internal data class RecordingStatus(
    val isRunning: Boolean,
    val durationMs: Long,
    val fileSize: Long,
    val error: String?,
)

/**
 * Single-recording engine that wires [MediaProjection] into an H.264 [MediaCodec] whose encoded
 * samples are written into an MP4 [MediaMuxer].
 *
 * The capture pipeline is:
 *  1. Configure the encoder for Surface input (`COLOR_FormatSurface`).
 *  2. Create the encoder input Surface and start the codec.
 *  3. Create a [VirtualDisplay] that mirrors the screen onto that Surface.
 *  4. A background thread drains the codec output buffers into the muxer until end-of-stream.
 *
 * Call [start] to begin and [stop] to finalize. [stop] blocks until the muxer is closed so the
 * output file is complete when it returns.
 */
internal class ScreenRecordEngine(
    private val mediaProjection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val densityDpi: Int,
    private val outputFile: File,
) {
    @Volatile private var running: Boolean = false
    @Volatile private var stopRequested: Boolean = false
    @Volatile var error: String? = null
        private set

    private var codec: MediaCodec? = null
    private var inputSurface: android.view.Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex: Int = -1
    @Volatile private var muxerStarted: Boolean = false

    private val bufferInfo = MediaCodec.BufferInfo()
    private val releaseLatch = CountDownLatch(1)
    private val startMs = System.currentTimeMillis()
    private var drainThread: Thread? = null

    val isRunning: Boolean get() = running

    /** Configure and start the capture pipeline. Sets [error] on failure. */
    fun start() {
        try {
            // 1. H.264 encoder configured for Surface input.
            val format = MediaFormat.createVideoFormat(MIME_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }
            val c = MediaCodec.createEncoderByType(MIME_AVC)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = c.createInputSurface()
            c.start()
            codec = c
            inputSurface = surface

            // 2. MP4 muxer (track added once the encoder emits its output format).
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 3. VirtualDisplay mirrors the real display onto the encoder input surface.
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "LxChatScreenRecord",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            )

            running = true
            drainThread = Thread({ drainLoop() }, "lxchat-screen-record").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            DebugLog.e(TAG, "start failed", e)
            releaseInternal()
        }
    }

    /**
     * Signal end-of-stream and block until the muxer is finalized. Returns true when the file was
     * closed cleanly within the timeout, false on timeout or when no recording was running.
     */
    fun stop(): Boolean {
        if (!running && releaseLatch.count == 0L) return true
        stopRequested = true
        try {
            codec?.let { c ->
                val index = c.dequeueInputBuffer(0)
                if (index >= 0) {
                    c.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "signalEndOfStream failed", e)
        }
        return releaseLatch.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** Current status snapshot for `screen_record_status`. */
    fun status(): RecordingStatus = RecordingStatus(
        isRunning = running,
        durationMs = System.currentTimeMillis() - startMs,
        fileSize = if (outputFile.exists()) outputFile.length() else 0,
        error = error,
    )

    // ── Encoder drain loop ────────────────────────────────────

    private fun drainLoop() {
        val c = codec ?: run { releaseInternal(); return }
        try {
            while (running) {
                val index = c.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output available yet. Exit once stop has been requested and the
                        // codec has nothing left.
                        if (stopRequested && !hasPendingOutput(c)) running = false
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) throw IllegalStateException("Output format changed twice.")
                        val newFormat = c.outputFormat
                        val m = muxer ?: throw IllegalStateException("Muxer missing.")
                        trackIndex = m.addTrack(newFormat)
                        m.start()
                        muxerStarted = true
                    }
                    index < 0 -> {
                        // Other negative codes are unexpected; just continue.
                    }
                    else -> {
                        val encoded = c.getOutputBuffer(index)
                        if (encoded != null && bufferInfo.size != 0 && muxerStarted) {
                            muxer?.writeSampleData(trackIndex, encoded, bufferInfo)
                        }
                        c.releaseOutputBuffer(index, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            running = false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            DebugLog.e(TAG, "drain loop failed", e)
        } finally {
            releaseInternal()
        }
    }

    /** Heuristic: poll once with a zero-timeout to see if more output is pending. */
    private fun hasPendingOutput(c: MediaCodec): Boolean = try {
        c.dequeueOutputBuffer(bufferInfo, 0) >= 0
    } catch (_: Exception) {
        false
    }

    /** Release every resource in the correct order. Idempotent. */
    private fun releaseInternal() {
        running = false
        // Codec
        codec?.let { c ->
            try { c.stop() } catch (_: Exception) {}
            try { c.release() } catch (_: Exception) {}
        }
        codec = null
        // Surface owned by the codec; just drop the reference.
        inputSurface = null
        // VirtualDisplay
        virtualDisplay?.let { v ->
            try { v.release() } catch (_: Exception) {}
        }
        virtualDisplay = null
        // Muxer
        muxer?.let { m ->
            if (muxerStarted) {
                try { m.stop() } catch (e: Exception) { DebugLog.w(TAG, "muxer stop failed", e) }
            }
            try { m.release() } catch (_: Exception) {}
        }
        muxer = null
        muxerStarted = false
        // MediaProjection
        try { mediaProjection.stop() } catch (_: Exception) {}
        // Unblock stop()
        releaseLatch.countDown()
    }

    companion object {
        private const val TAG = "ScreenRecordEngine"
        private const val MIME_AVC = "video/avc"
        private const val I_FRAME_INTERVAL = 1
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}