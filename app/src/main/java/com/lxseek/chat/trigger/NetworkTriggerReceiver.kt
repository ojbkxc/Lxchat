package com.lxseek.chat.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 网络触发接收器：监听 `android.net.conn.CONNECTIVITY_CHANGE`，解析当前网络类型
 * （WiFi / 移动数据 / 无网络），匹配 NETWORK_CHANGE 规则并交给 [TriggerExecutorService] 执行。
 *
 * 设计要点：
 * 1. Android N+ 已 deprecated `CONNECTIVITY_CHANGE`，且 O+ manifest 静态 receiver 收不到。
 *    本类提供 [registerDynamic] / [unregisterDynamic]，由应用启动时动态注册；
 *    manifest 里的静态注册保留给旧设备与任务要求。Android M+ 推荐用
 *    `ConnectivityManager.registerNetworkCallback`，但为了与 BatteryReceiver 保持一致并
 *    兼容旧设备，这里仍用 `CONNECTIVITY_CHANGE` + 主动查询 `getActiveNetwork` 拿真实状态。
 * 2. 用 [lastSeenType] 记录上次网络类型，仅在类型真正变化时才触发，避免重复广播导致重复触发。
 * 3. onReceive 只做轻量查询，DataStore 读取与冷却判断放到 goAsync() 协程里。
 */
class NetworkTriggerReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NetworkTrigger"

        /** 动态注册一个实例到 [context]，返回该实例以便后续 unregister。 */
        fun registerDynamic(context: Context): NetworkTriggerReceiver {
            val receiver = NetworkTriggerReceiver()
            val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            context.applicationContext.registerReceiver(receiver, filter)
            return receiver
        }

        fun unregisterDynamic(context: Context, receiver: NetworkTriggerReceiver) {
            runCatching {
                context.applicationContext.unregisterReceiver(receiver)
            }
        }
    }

    /** 网络类型编码：0=无网络, 1=WiFi, 2=移动数据, -1=未知（首次）。 */
    private var lastSeenType: Int = -1

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) return

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            DebugLog.w(TAG, "ConnectivityManager null, skip")
            return
        }
        val type = currentNetworkType(cm)
        val prev = lastSeenType
        lastSeenType = type
        // 首次（prev == -1）不触发，只记录初始状态。
        if (prev == -1) return
        if (prev == type) return

        val typeLabel = typeLabel(type)
        DebugLog.d(TAG, "network changed: $prev -> $type ($typeLabel)")

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = TriggerConfigStore(context.applicationContext)
                val cfg = store.currentConfig()
                if (!cfg.enabled) return@launch
                val now = System.currentTimeMillis()
                for (rule in cfg.rules) {
                    if (!rule.enabled) continue
                    if (rule.type != TriggerType.NETWORK_CHANGE) continue
                    if (inCooldown(rule, now)) {
                        DebugLog.d(TAG, "rule ${rule.name} in cooldown, skip")
                        continue
                    }
                    val ctxSummary = "网络切换到 $typeLabel"
                    dispatch(context, rule.id, ctxSummary)
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "onReceive async failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** 查询当前网络类型：0=无网络, 1=WiFi, 2=移动数据。 */
    private fun currentNetworkType(cm: ConnectivityManager): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return 0
            val caps = cm.getNetworkCapabilities(network) ?: return 0
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                else -> 0
            }
        }
        // 旧设备回退到 deprecated API。
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo ?: return 0
        if (!info.isConnectedOrConnecting) return 0
        return when (info.type) {
            ConnectivityManager.TYPE_WIFI -> 1
            ConnectivityManager.TYPE_MOBILE -> 2
            else -> 0
        }
    }

    private fun typeLabel(type: Int): String = when (type) {
        1 -> "WiFi"
        2 -> "移动数据"
        else -> "无网络"
    }

    private fun inCooldown(rule: TriggerRule, now: Long): Boolean =
        rule.cooldownMs > 0 && (now - rule.lastTriggeredAt) < rule.cooldownMs

    private fun dispatch(context: Context, ruleId: String, ctxSummary: String) {
        val intent = Intent(context, TriggerExecutorService::class.java).apply {
            putExtra(TriggerExecutorService.EXTRA_RULE_ID, ruleId)
            putExtra(TriggerExecutorService.EXTRA_TRIGGER_CONTEXT, ctxSummary)
        }
        context.startService(intent)
    }
}