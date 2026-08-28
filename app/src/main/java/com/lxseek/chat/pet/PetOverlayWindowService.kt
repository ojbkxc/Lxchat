package com.lxseek.chat.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lxseek.chat.MainActivity
import com.lxseek.chat.R
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground service that hosts the draggable pet bubble as a [WindowManager]
 * `TYPE_APPLICATION_OVERLAY` layer, i.e. visible above every other app.
 *
 * It is intentionally inert unless the user has granted the `SYSTEM_ALERT_WINDOW` permission —
 * [onStartCommand] refuses to add the view and self-stops when that permission is missing, so a
 * stale "enabled" preference can never silently hang a ghost bubble in the status bar.
 *
 * The bubble already has a foreground-service notification (the system requirement), and tapping
 * it launches [MainActivity]. Toggle / permission-granting is handled by [PetOverlayController]
 * and surfaced through the Quick Settings tile and the Settings page.
 *
 * In addition to the default Canvas bubble, the service can render a user-supplied image
 * (transparent PNG) stored on disk. The path is read from [SettingsManager.petOverlayImagePath]
 * on start and whenever [refreshImage] is invoked. The bitmap is downscaled to the bubble size to
 * bound memory use.
 */
class PetOverlayWindowService : Service() {

    private var floatingView: PetFloatingView? = null
    private var windowManager: WindowManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        val notification = buildNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundServiceType(),
        )
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If the app was restarted with the pref already on but the user revoked overlay access
        // in the meantime, show nothing rather than crash on addView.
        if (!Settings.canDrawOverlays(this)) {
            DebugLog.w(TAG, "Overlay permission missing; pet service stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        if (floatingView == null) {
            // Read the persisted size scale (0.5~1.0) and sprite asynchronously, then build the
            // window on the main thread. scope uses Dispatchers.Main, so addFloatingView is safe
            // here.
            scope.launch {
                val sizeScale = runCatching {
                    PetOverlayController.getSizeScale(this@PetOverlayWindowService)
                }.getOrDefault(1.0f)
                addFloatingView(sizeScale)
                applyCharacterAsync()
                loadCustomImageAsync()
            }
        } else {
            // Always (re)load the custom image so a path change while the service is running is picked
            // up on the next start command (e.g. after PetOverlayController.refreshImage).
            loadCustomImageAsync()
            applyCharacterAsync()
        }
        return START_STICKY
    }

    private fun addFloatingView(sizeScale: Float) {
        // Guard against a duplicate add if multiple onStartCommand launches race before the first
        // one sets floatingView (scope is single-threaded Main, but two launches can queue up).
        if (floatingView != null) return
        val wm = windowManager ?: return
        val density = resources.displayMetrics.density
        // SIZE_DP is the maximum (100%); scale it down per the user preference (0.5~1.0).
        val effectiveSizeDp = SIZE_DP * sizeScale.coerceIn(0.5f, 1.0f)
        val sizePx = (effectiveSizeDp * density).toInt()
        // Reserve headroom above the bubble for the status-tip capsule (PetFloatingView reads it
        // as h - w and draws the transient message capsule there). The empty strip is touch-pass
        // through, so it never blocks the app underneath.
        val windowH = sizePx + (TIP_HEADROOM_DP * density).toInt()
        val params = WindowManager.LayoutParams(
            sizePx,
            windowH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Start pinned to the top-right with a comfortable margin.
            x = resources.displayMetrics.widthPixels - sizePx - (MARGIN_DP * density).toInt()
            y = topMarginPx()
        }
        val view = PetFloatingView(this).apply {
            bindWindowParams(params)
        }
        try {
            wm.addView(view, params)
            floatingView = view
            DebugLog.d(TAG, "Pet floating view added")
        } catch (e: RuntimeException) {
            DebugLog.e(TAG, "Failed to add pet overlay", e)
        }
    }

    /**
     * Asynchronously loads the user-configured custom image (if any) and pushes it into the
     * floating view. Falls back to the default Canvas bubble when the path is empty, the file is
     * missing, or decoding fails. Runs on the main scope because [PetFloatingView.setCustomBitmap]
     * touches the view.
     */
    private fun loadCustomImageAsync() {
        val view = floatingView ?: return
        val targetW = view.width
        val targetH = view.height
        scope.launch {
            val path = PetOverlayController.getImagePath(this@PetOverlayWindowService)
            // Decoding is disk/IO-bound — push it off the main thread.
            val bitmap = if (path.isNotBlank()) {
                withContext(Dispatchers.IO) { decodeScaledBitmap(path, targetW, targetH) }
            } else null
            view.setCustomBitmap(bitmap)
        }
    }

    /**
     * Asynchronously applies the persisted built-in sprite to the floating view (no-op when the
     * service has no view yet). Runs on the main-thread scope because
     * [PetFloatingView.setCharacter] touches the view.
     */
    private fun applyCharacterAsync() {
        val view = floatingView ?: return
        scope.launch {
            val character = runCatching {
                PetOverlayController.getCharacter(this@PetOverlayWindowService)
            }.getOrDefault(PetCharacter.CLASSIC)
            view.setCharacter(character)
        }
    }

    private fun topMarginPx(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else (24 * resources.displayMetrics.density).toInt()
        return statusBar + (MARGIN_DP * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        val view = floatingView
        if (view != null) {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeViewImmediate(view)
            } catch (_: Exception) {
                // View already removed by a system teardown pass.
            }
            floatingView = null
        }
        DebugLog.d(TAG, "Pet overlay service destroyed")
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.pet_overlay_notification_title))
            .setContentText(getString(R.string.pet_overlay_notification_text))
            .setSmallIcon(R.drawable.ic_pet)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PetOverlayWindowService"
        private const val CHANNEL_ID = "lxchat_pet_overlay"
        private const val NOTIFICATION_ID = 2
        private const val SIZE_DP = 64f
        private const val MARGIN_DP = 12f
        // Vertical padding held above the bubble so PetFloatingView has room for the status-tip capsule.
        private const val TIP_HEADROOM_DP = 36f

        fun start(context: Context) {
            val app = context.applicationContext
            if (!Settings.canDrawOverlays(app)) return
            try {
                val intent = Intent(app, PetOverlayWindowService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (e: RuntimeException) {
                DebugLog.e(TAG, "Failed to start pet overlay service", e)
            }
        }

        fun stop(context: Context) {
            kotlin.runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, PetOverlayWindowService::class.java)
                )
            }
        }

        /**
         * Reloads the custom image into a running overlay. If the service is not running this is a
         * no-op (the next [start] will pick the path up). Safe to call from any thread.
         */
        fun refreshImage(context: Context) {
            val app = context.applicationContext
            // Re-deliver a start command so onStartCommand runs loadCustomImageAsync again.
            kotlin.runCatching {
                app.startService(Intent(app, PetOverlayWindowService::class.java))
            }
        }

        /**
         * Reloads the persisted built-in sprite into a running overlay. If the service is not
         * running this is a no-op (the next [start] will pick the value up). Safe from any thread.
         */
        fun refreshCharacter(context: Context) {
            val app = context.applicationContext
            // Re-deliver a start command so onStartCommand runs applyCharacterAsync again.
            kotlin.runCatching {
                app.startService(Intent(app, PetOverlayWindowService::class.java))
            }
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.pet_overlay_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.pet_overlay_channel_desc)
                setShowBadge(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }

        /**
         * Decodes [path] into a [Bitmap] sized to fit within [targetW] x [targetH] while
         * preserving aspect ratio. Returns `null` if the file does not exist or decoding fails.
         * Downscales using inSampleSize to avoid loading huge images into memory.
         */
        internal fun decodeScaledBitmap(path: String, targetW: Int, targetH: Int): Bitmap? {
            val file = File(path)
            if (!file.exists() || !file.isFile) return null
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                val targetWidth = if (targetW > 0) targetW else bounds.outWidth
                val targetHeight = if (targetH > 0) targetH else bounds.outHeight
                val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888 // keep alpha channel for transparency
                }
                BitmapFactory.decodeFile(path, opts)
            } catch (e: Exception) {
                DebugLog.e(TAG, "Failed to decode pet overlay image: $path", e)
                null
            }
        }

        private fun computeInSampleSize(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
            if (srcW <= dstW && srcH <= dstH) return 1
            var sample = 1
            while (srcW / (sample * 2) >= dstW && srcH / (sample * 2) >= dstH) {
                sample *= 2
            }
            return sample
        }
    }
}
