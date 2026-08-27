package com.lxseek.chat.trigger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lxseek.chat.LxChatApplication
import com.lxseek.chat.R
import com.lxseek.chat.automation.TaskExecutionEngine
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 条件触发执行服务：由 [BatteryTriggerReceiver] / [NetworkTriggerReceiver] 通过 startService 拉起，
 * 在 IO 协程里完成「读取规则 → 跑 AI → 写回 lastTriggeredAt → 本地通知展示结果」。
 *
 * 把耗时操作从 BroadcastReceiver 搬到 Service 的原因：
 * 1. Receiver.onReceive 必须快进快出（即使 goAsync 也只有 ~10 秒），AI 生成动辄数十秒。
 * 2. Service 有独立生命周期，可承载长耗时任务；执行完自动 stopSelf。
 * 3. 与 [com.lxseek.chat.sms.SmsCommandExecutorService] 保持一致的架构。
 *
 * Intent extras：
 * - [EXTRA_RULE_ID]：要执行的 [TriggerRule] id。
 * - [EXTRA_TRIGGER_CONTEXT]：人类可读的触发上下文摘要（如「电量 15%，未充电」），拼到 prompt 前面。
 */
class TriggerExecutorService : Service() {
    companion object {
        private const val TAG = "TriggerExec"
        const val EXTRA_RULE_ID = "rule_id"
        const val EXTRA_TRIGGER_CONTEXT = "trigger_context"

        private const val CHANNEL_ID = "trigger_result"
        private const val NOTIFICATION_ID = 0x5C02 // "SC02"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ruleId = intent?.getStringExtra(EXTRA_RULE_ID)
        if (ruleId.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val triggerContext = intent.getStringExtra(EXTRA_TRIGGER_CONTEXT).orEmpty()

        scope.launch {
            try {
                executeRule(ruleId, triggerContext)
            } catch (e: Exception) {
                DebugLog.e(TAG, "executeRule failed: $ruleId", e)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 取规则 → 标记触发 → 跑 AI → 通知展示。 */
    private suspend fun executeRule(ruleId: String, triggerContext: String) {
        val container = (application as LxChatApplication).container
        val store = TriggerConfigStore(applicationContext)
        val cfg = store.currentConfig()
        if (!cfg.enabled) {
            DebugLog.d(TAG, "trigger system disabled, skip $ruleId")
            return
        }
        val rule = cfg.rules.firstOrNull { it.id == ruleId } ?: run {
            DebugLog.w(TAG, "rule $ruleId not found, skip")
            return
        }
        if (!rule.enabled) {
            DebugLog.d(TAG, "rule ${rule.name} disabled, skip")
            return
        }

        // 先写回 lastTriggeredAt，确保冷却窗口在 AI 漫长生成期间也生效。
        store.markTriggered(rule.id)

        // 确保专用会话存在；首次触发时新建并写回配置。
        val convId = cfg.dedicatedConversationId.ifBlank {
            val newId = container.conversationRepository.createConversation(
                title = "条件触发",
                systemPromptId = null,
                modelId = null,
            )
            store.setConversationId(newId)
            newId
        }

        val fullPrompt = buildPrompt(rule, triggerContext)
        val output = runAgent(container.taskExecutionEngine, convId, fullPrompt, rule.modelId)
        showResultNotification(rule.name, triggerContext, output)
    }

    /** 把触发上下文摘要拼到用户配置的 prompt 前面，让 AI 知道「为什么被叫起来」。 */
    private fun buildPrompt(rule: TriggerRule, triggerContext: String): String = buildString {
        append("[条件触发] ")
        append(rule.name)
        if (triggerContext.isNotBlank()) {
            append("（")
            append(triggerContext)
            append("）")
        }
        append('\n')
        append(rule.prompt)
    }

    /** 调 [TaskExecutionEngine.runOnce] 跑一轮 AI 生成，返回人类可读结果文本。 */
    private suspend fun runAgent(
        engine: TaskExecutionEngine,
        convId: String,
        prompt: String,
        modelId: String?,
    ): String = try {
        when (val result = engine.runOnce(conversationId = convId, userText = prompt, modelId = modelId)) {
            is TaskExecutionEngine.Result.Success -> result.text
            is TaskExecutionEngine.Result.Busy -> "AI 忙: ${result.reason}"
            is TaskExecutionEngine.Result.Failure -> "AI 失败: ${result.reason}"
        }
    } catch (e: Exception) {
        "AI 执行异常: ${e.message ?: e.javaClass.simpleName}"
    }

    // ── 结果通知 ────────────────────────────────────────────

    private fun showResultNotification(ruleName: String, triggerContext: String, output: String) {
        ensureChannel()
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val title = "[$ruleName] ${triggerContext.take(40)}"
        val body = if (output.length > 800) output.take(800) + "…" else output
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body.take(120))
                .setSmallIcon(R.drawable.ic_notification)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: RuntimeException) {
            DebugLog.w(TAG, "showResultNotification failed", e)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "条件触发结果",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "条件触发规则执行完成后的结果通知"
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        } catch (e: Throwable) {
            DebugLog.w(TAG, "createChannel failed", e)
        }
    }
}