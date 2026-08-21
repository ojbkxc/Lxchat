package com.lxseek.chat.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lxseek.chat.MainActivity
import com.lxseek.chat.R
import com.lxseek.chat.util.DebugLog

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
 */
class PetOverlayWindowService : Service() {

    private var floatingView: PetFloatingView? = null
    private var windowManager: WindowManager? = null

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
            addFloatingView()
        }
        return START_STICKY
    }

    private fun addFloatingView() {
        val wm = windowManager ?: return
        val density = resources.displayMetrics.density
        val sizePx = (SIZE_DP * density).toInt()
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
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

    private fun topMarginPx(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else (24 * resources.displayMetrics.density).toInt()
        return statusBar + (MARGIN_DP * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
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
    }
}