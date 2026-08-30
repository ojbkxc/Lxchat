package com.lxseek.chat.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import com.lxseek.chat.MainActivity
import com.lxseek.chat.R
import java.util.UUID

/** Builds the foreground notification used by durable automation workers. */
object AutomationForegroundInfo {
    const val CHANNEL_ID = "lxchat_automation"
    private const val NOTIFICATION_BASE = 0x3500_0000

    /**
     * WorkManager tracks foreground notifications per WorkSpec. Include the WorkSpec id so two
     * overlapping occurrences for the same conversation never overwrite/cancel each other's
     * notification in SystemForegroundService.
     */
    fun forLoop(context: Context, conversationId: String, workSpecId: UUID): ForegroundInfo = create(
        context = context,
        text = context.getString(R.string.loop_running_notification),
        notificationId = loopNotificationId(conversationId, workSpecId),
        conversationId = conversationId,
    )

    internal val executionConstraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    internal fun taskNotificationId(taskId: String, workSpecId: UUID): Int =
        notificationId("task:$taskId:$workSpecId")

    internal fun loopNotificationId(conversationId: String, workSpecId: UUID): Int =
        notificationId("loop:$conversationId:$workSpecId")

    fun create(
        context: Context,
        text: String,
        notificationId: Int,
        conversationId: String? = null,
    ): ForegroundInfo {
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    notificationId,
                    Intent(context, MainActivity::class.java).apply {
                        if (conversationId != null) {
                            action = Intent.ACTION_VIEW
                            data = "lxchat://conversation/$conversationId".toUri()
                            putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                        }
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    internal fun notificationId(conversationId: String): Int =
        NOTIFICATION_BASE or (conversationId.hashCode() and 0x00ff_ffff)

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // getSystemService may return null on some environments; fail safe instead
        // of NPE-ing on manager.createNotificationChannel(...).
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Automation",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.automation_notification_channel_desc)
                setShowBadge(false)
                setSound(null, null)
            }
        )
    }
}
