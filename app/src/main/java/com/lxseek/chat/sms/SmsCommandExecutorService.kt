package com.lxseek.chat.sms

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
import com.lxseek.chat.adb.AdbLog
import com.lxseek.chat.tool.ShellToolProvider
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.agent.GenerationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 短信命令执行服务：解析 `smsf#` 命令并在手机本地执行。
 *
 * 由 [SmsCommandReceiver] 通过 startService 拉起。每条命令在 IO 协程里执行，
 * 结果通过本地通知展示 + 写入 [AdbLog]（可在设置页「ADB Shell」执行日志里看到）。
 *
 * 命令语法（参考 SmsForwarder，简化为 Lxchat 适用版本）：
 * - `smsf#shell#exec#<command>` — 通过 [ShellToolProvider] 走 ADB Shell（root / Shizuku）执行
 * - `smsf#ai#chat#<prompt>` — 通过 [com.lxseek.chat.automation.TaskExecutionEngine] 让 AI 生成回复
 * - `smsf#wifi#on` / `smsf#wifi#off` — 开关 WiFi（通过 `svc wifi enable/disable` 走 ADB Shell）
 * - `smsf#help` — 返回帮助信息（本地通知展示）
 *
 * 解析用 `split("#", limit = 4)`，因此参数段里允许出现 `#`（例如 shell 命令 `echo a#b`）。
 *
 * 安全：本服务不做白名单校验（[SmsCommandReceiver] 已做）；只做命令解析与执行。
 */
class SmsCommandExecutorService : Service() {
    companion object {
        private const val TAG = "SmsCmdExec"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_SENDER = "sender"

        private const val CHANNEL_ID = "sms_command_result"
        private const val NOTIFICATION_ID = 0x5C01 // "SC01"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.getStringExtra(EXTRA_COMMAND)
        if (command.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val sender = intent.getStringExtra(EXTRA_SENDER)
        DebugLog.d(TAG, "executing: $command (from $sender)")

        scope.launch {
            try {
                val output = executeCommand(command, sender)
                showResultNotification(command, output)
            } catch (e: Exception) {
                DebugLog.e(TAG, "execute failed: $command", e)
                showResultNotification(command, "执行失败: ${e.message ?: e.javaClass.simpleName}")
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

    // ── 命令解析与分发 ──────────────────────────────────────

    /** 解析并执行单条命令，返回结果文本（用于通知展示）。 */
    private suspend fun executeCommand(raw: String, sender: String?): String {
        // split(limit = 4)：smsf#功能#动作#参数 → ["smsf","功能","动作","参数"]
        // 参数段保留原始 #，不再次分割。
        val parts = raw.split("#", limit = 4)
        if (parts.size < 2) return "格式错误: $raw"
        val function = parts[1].trim().lowercase()
        val action = if (parts.size > 2) parts[2].trim().lowercase() else ""
        val param = if (parts.size > 3) parts[3] else ""

        return when (function) {
            "shell" -> executeShell(action, param)
            "ai" -> executeAi(action, param)
            "wifi" -> executeWifi(action)
            "help" -> helpText()
            else -> "未知功能: $function\n${helpText()}"
        }
    }

    // ── shell#exec ──────────────────────────────────────────

    /** 通过 ShellToolProvider 走 ADB Shell（root / Shizuku）执行命令。 */
    private suspend fun executeShell(action: String, param: String): String {
        if (action != "exec") return "shell 仅支持 exec 动作，收到: $action"
        if (param.isBlank()) return "shell#exec 缺少命令参数"
        val container = (application as LxChatApplication).container
        val shellProvider = ShellToolProvider(
            sandboxFactory = container.sandboxManagerFactory,
            imageStore = null,
            appContext = applicationContext,
        )
        // 最小 GenerationContext：只开 shellEnabled，server 由参数指定为 "ADB Shell"。
        val ctx = GenerationContext(shellEnabled = true)
        val args = buildJsonObject {
            put("command", param)
            put("server", "ADB Shell")
            put("timeout_ms", "30000")
        }.toString()
        val raw = try {
            shellProvider.execute("execute_shell_command", args, ctx)
        } catch (e: Exception) {
            return "shell 执行异常: ${e.message ?: e.javaClass.simpleName}"
        }
        // 解析 JSON 结果，提取 output / exit_code / error。
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            val error = (obj["error"] as? JsonPrimitive)?.content
            if (error != null) {
                val msg = (obj["message"] as? JsonPrimitive)?.content ?: "unknown error"
                "shell 错误: $msg"
            } else {
                val output = (obj["output"] as? JsonPrimitive)?.content ?: ""
                val exit = (obj["exit_code"] as? JsonPrimitive)?.content?.toIntOrNull()
                AdbLog.log("smsf#shell> $param  → exit=$exit out=${output.trim().take(200)}")
                buildString {
                    append("exit: ").append(exit ?: "?").append('\n')
                    append(output.trimEnd())
                }
            }
        } catch (_: Exception) {
            raw // JSON 解析失败就直接返回原始字符串
        }
    }

    // ── ai#chat ─────────────────────────────────────────────

    /** 通过 TaskExecutionEngine 让 AI 生成回复，落库到专用会话。 */
    private suspend fun executeAi(action: String, param: String): String {
        if (action != "chat") return "ai 仅支持 chat 动作，收到: $action"
        if (param.isBlank()) return "ai#chat 缺少 prompt"
        val container = (application as LxChatApplication).container
        val store = SmsCommandConfigStore(applicationContext)
        val cfg = store.currentConfig()

        // 确保专用会话存在；首次执行时新建并写回配置。
        val convId = cfg.dedicatedConversationId.ifBlank {
            val newId = container.conversationRepository.createConversation(
                title = "SMS 命令",
                systemPromptId = null,
                modelId = null,
            )
            store.update { it.copy(dedicatedConversationId = newId) }
            newId
        }

        return try {
            val result = container.taskExecutionEngine.runOnce(
                conversationId = convId,
                userText = param,
            )
            when (result) {
                is com.lxseek.chat.automation.TaskExecutionEngine.Result.Success ->
                    "AI 回复:\n${result.text}"
                is com.lxseek.chat.automation.TaskExecutionEngine.Result.Busy ->
                    "AI 忙: ${result.reason}"
                is com.lxseek.chat.automation.TaskExecutionEngine.Result.Failure ->
                    "AI 失败: ${result.reason}"
            }
        } catch (e: Exception) {
            "AI 执行异常: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ── wifi#on / wifi#off ──────────────────────────────────

    /**
     * 开关 WiFi。Android 10+ 普通应用无法直接调 WifiManager.setWifiEnabled，
     * 因此走 ADB Shell 执行 `svc wifi enable/disable`（需要 root 或 Shizuku）。
     */
    private suspend fun executeWifi(action: String): String {
        val cmd = when (action) {
            "on" -> "svc wifi enable"
            "off" -> "svc wifi disable"
            else -> return "wifi 仅支持 on/off，收到: $action"
        }
        val result = executeShell("exec", cmd)
        AdbLog.log("smsf#wifi#$action  → $cmd")
        return "wifi $action:\n$result"
    }

    // ── help ────────────────────────────────────────────────

    private fun helpText(): String = buildString {
        appendLine("Lxchat SMS 命令帮助")
        appendLine("格式: smsf#功能#动作#参数")
        appendLine()
        appendLine("支持的命令:")
        appendLine("  smsf#shell#exec#<命令>  执行 ADB Shell 命令 (root/Shizuku)")
        appendLine("  smsf#ai#chat#<提示词>   让 AI 生成回复")
        appendLine("  smsf#wifi#on            开启 WiFi")
        appendLine("  smsf#wifi#off           关闭 WiFi")
        appendLine("  smsf#help               显示本帮助")
        appendLine()
        appendLine("说明:")
        appendLine("  - 命令在手机本地执行，不联网、不远程")
        appendLine("  - shell/wifi 需要 root 或 Shizuku 已授权")
        appendLine("  - 结果通过本地通知展示")
    }

    // ── 结果通知 ────────────────────────────────────────────

    private fun showResultNotification(command: String, output: String) {
        ensureChannel()
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val title = command.take(40)
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
                "SMS 命令结果",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "短信命令执行完成后的结果通知"
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        } catch (e: Throwable) {
            DebugLog.w(TAG, "createChannel failed", e)
        }
    }
}