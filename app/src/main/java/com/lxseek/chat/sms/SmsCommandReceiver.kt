package com.lxseek.chat.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 短信命令接收器：监听收到短信，识别 `smsf#` 开头命令并交给 [SmsCommandExecutorService] 执行。
 *
 * 命令格式：`smsf#功能#动作#参数`
 * 支持的命令（详见 [SmsCommandExecutorService]）：
 * - `smsf#shell#exec#<command>` — 执行 ADB Shell 命令
 * - `smsf#ai#chat#<prompt>` — 让 AI 生成回复
 * - `smsf#wifi#on` / `smsf#wifi#off` — 开关 WiFi
 * - `smsf#help` — 返回帮助信息
 *
 * 设计要点：
 * 1. BroadcastReceiver.onReceive 必须快进快出，不能做耗时操作。这里只做命令识别，
 *    把配置读取（DataStore）放到 goAsync() 协程里，通过后再 startService。
 * 2. 白名单 / 总开关在 Receiver 里先过滤一遍，避免无谓地拉起 Service。
 * 3. 命令识别本身（startsWith）是 O(1) 字符串操作，留在主线程里没问题。
 */
class SmsCommandReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsCmd"
        const val PREFIX = "smsf#"

        /** 快速判断一段文本是否是 smsf 命令（仅做前缀判断，不解析）。 */
        fun isCommand(text: String): Boolean = text.startsWith(PREFIX)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // 先收集所有命中命令的短信，避免在协程里再遍历一次 PDU。
        val hits = ArrayList<Pair<String, String?>>(2) // (text, sender)
        for (sms in messages) {
            val text = sms.messageBody ?: continue
            if (!isCommand(text)) continue
            hits += text to sms.originatingAddress
        }
        if (hits.isEmpty()) return

        // goAsync() 给我们最多 10 秒去完成 DataStore 读取 + startService，远超所需。
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = SmsCommandConfigStore(context.applicationContext)
                val cfg = store.currentConfig()
                if (!cfg.enabled) {
                    DebugLog.d(TAG, "sms command ignored: feature disabled")
                    return@launch
                }
                for ((text, sender) in hits) {
                    if (!store.isAllowed(cfg, sender)) {
                        DebugLog.w(TAG, "sms command rejected: sender $sender not in whitelist")
                        continue
                    }
                    DebugLog.d(TAG, "received command: $text from $sender")
                    val serviceIntent = Intent(context, SmsCommandExecutorService::class.java).apply {
                        putExtra(SmsCommandExecutorService.EXTRA_COMMAND, text)
                        putExtra(SmsCommandExecutorService.EXTRA_SENDER, sender)
                    }
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "onReceive async failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}