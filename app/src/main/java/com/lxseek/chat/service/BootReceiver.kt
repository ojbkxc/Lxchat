package com.lxseek.chat.service

import android.content.BroadcastReceiver
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import com.lxseek.chat.LxChatApplication
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-arms task alarms after a reboot or app update. AlarmManager alarms do not survive either,
 * so on BOOT_COMPLETED / MY_PACKAGE_REPLACED we touch the scheduler — accessing the container
 * starts it, and it re-schedules from each task's persisted nextRunAt.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                DebugLog.d("BootReceiver", "re-arming automation alarms after ${intent.action}")
                val scheduler = (context.applicationContext as LxChatApplication).container.automationScheduler
                scheduler.start()
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        scheduler.refreshAndAwait(
                            recalculateForClockChange = intent.action == Intent.ACTION_TIME_CHANGED ||
                                intent.action == Intent.ACTION_TIMEZONE_CHANGED,
                        )
                    } catch (e: Exception) {
                        DebugLog.e("BootReceiver", "Failed to re-arm automation alarms", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
