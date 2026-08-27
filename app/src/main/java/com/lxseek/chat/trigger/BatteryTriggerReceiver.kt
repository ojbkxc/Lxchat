package com.lxseek.chat.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 电池触发接收器：监听 [Intent.ACTION_BATTERY_CHANGED]，解析电量百分比与充电状态，
 * 匹配所有 enabled 的 [TriggerRule]（BATTERY_LOW / BATTERY_HIGH / CHARGING_START / CHARGING_STOP），
 * 命中且过了冷却窗口的规则交给 [TriggerExecutorService] 执行。
 *
 * 设计要点：
 * 1. Android O+ 对隐式广播的限制：[Intent.ACTION_BATTERY_CHANGED] 是 sticky protected broadcast，
 *    manifest 静态注册在 O+ 上收不到。本类同时提供 [registerDynamic] / [unregisterDynamic]，
 *    由应用启动时（[com.lxseek.chat.di.AppContainer.startProcessServices]）动态注册以保证实际可用；
 *    manifest 里的静态注册保留给旧设备与任务要求。
 * 2. onReceive 只做 Intent 解析（O(1)），DataStore 读取与冷却判断放到 goAsync() 协程里。
 * 3. 用 [lastSeenCharging] 记录上次充电状态，仅在「状态翻转」时触发 CHARGING_START/STOP，
 *    避免 BATTERY_CHANGED 频繁重播导致重复触发。
 */
class BatteryTriggerReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BatteryTrigger"

        /** 动态注册一个实例到 [context]，返回该实例以便后续 unregister。 */
        fun registerDynamic(context: Context): BatteryTriggerReceiver {
            val receiver = BatteryTriggerReceiver()
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            // BATTERY_CHANGED 是 sticky broadcast，register 后会立即收到一次当前状态——
            // 这正是我们想要的：让 lastSeenCharging 初始化为真实状态，避免开机即误触发。
            context.applicationContext.registerReceiver(receiver, filter)
            return receiver
        }

        fun unregisterDynamic(context: Context, receiver: BatteryTriggerReceiver) {
            runCatching {
                context.applicationContext.unregisterReceiver(receiver)
            }
        }
    }

    /** 上次见到的充电状态，用于检测「开始/停止充电」的翻转。 */
    private var lastSeenCharging: Int = -1

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BATTERY_CHANGED) return

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val pct = if (scale > 0) (level * 100 / scale) else -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        // 翻转检测：只在 charging 状态真正变化时才考虑 CHARGING_START/STOP。
        val prev = lastSeenCharging
        lastSeenCharging = if (isCharging) 1 else 0
        val justStartedCharging = prev != -1 && prev == 0 && isCharging
        val justStoppedCharging = prev != -1 && prev == 1 && !isCharging

        if (pct < 0) return
        DebugLog.d(TAG, "battery: pct=$pct charging=$isCharging start=$justStartedCharging stop=$justStoppedCharging")

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = TriggerConfigStore(context.applicationContext)
                val cfg = store.currentConfig()
                if (!cfg.enabled) return@launch
                val now = System.currentTimeMillis()
                for (rule in cfg.rules) {
                    if (!rule.enabled) continue
                    if (!matchesBattery(rule, pct, justStartedCharging, justStoppedCharging)) continue
                    if (inCooldown(rule, now)) {
                        DebugLog.d(TAG, "rule ${rule.name} in cooldown, skip")
                        continue
                    }
                    val ctxSummary = buildBatteryContext(rule, pct, isCharging)
                    dispatch(context, rule.id, ctxSummary)
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "onReceive async failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** 判断规则是否匹配当前电池状态。 */
    private fun matchesBattery(
        rule: TriggerRule,
        pct: Int,
        justStartedCharging: Boolean,
        justStoppedCharging: Boolean,
    ): Boolean = when (rule.type) {
        TriggerType.BATTERY_LOW -> rule.condition == "below" && pct <= rule.threshold
        TriggerType.BATTERY_HIGH -> rule.condition == "above" && pct >= rule.threshold
        TriggerType.CHARGING_START -> justStartedCharging
        TriggerType.CHARGING_STOP -> justStoppedCharging
        TriggerType.NETWORK_CHANGE -> false
    }

    private fun inCooldown(rule: TriggerRule, now: Long): Boolean =
        rule.cooldownMs > 0 && (now - rule.lastTriggeredAt) < rule.cooldownMs

    private fun buildBatteryContext(rule: TriggerRule, pct: Int, isCharging: Boolean): String = when (rule.type) {
        TriggerType.BATTERY_LOW -> "电量 $pct%（低于 ${rule.threshold}%）"
        TriggerType.BATTERY_HIGH -> "电量 $pct%（高于 ${rule.threshold}%）"
        TriggerType.CHARGING_START -> "开始充电（电量 $pct%）"
        TriggerType.CHARGING_STOP -> "停止充电（电量 $pct%）"
        TriggerType.NETWORK_CHANGE -> "电量 $pct%"
    }

    private fun dispatch(context: Context, ruleId: String, ctxSummary: String) {
        val intent = Intent(context, TriggerExecutorService::class.java).apply {
            putExtra(TriggerExecutorService.EXTRA_RULE_ID, ruleId)
            putExtra(TriggerExecutorService.EXTRA_TRIGGER_CONTEXT, ctxSummary)
        }
        context.startService(intent)
    }
}