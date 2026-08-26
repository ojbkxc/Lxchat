package com.lxseek.chat.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lxseek.chat.LxChatApplication
import com.lxseek.chat.automation.TaskExecutionEngine
import com.lxseek.chat.im.weixin.WeixinChannel
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统通知自动回复（NotificationListenerService）。
 *
 * 抓到目标 App（默认微信 com.tencent.mm）的在新通知后：
 *  1. 提取发送者（通知标题）与消息内容（EXTRA_TEXT / EXTRA_BIG_TEXT）；
 *  2. 用「昵称 → iLink user_id」映射解析回复对象（避免发错人）；
 *  3. 内容交 [TaskExecutionEngine.runOnce] 生成 AI 回复（落库到专用 Lxchat 会话，跨重启记住上下文）；
 *  4. 通过当前已绑定的 [WeixinChannel.sendMessage] 把回复发回该好友。
 *
 * 需要用户在系统"通知使用权"(Notification access)里授权本应用后才能收到回调。
 * 冷却/去重避免连发通知触发一串 AI 请求互相打断。
 */
class NotificationAutoReplyService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { NotificationReplyStore(applicationContext) }

    private val container get() = (application as LxChatApplication).container

    /** 通知去重（POST 与 UPDATE 可能各触发一次同一个 key）打点：key -> 时间戳。 */
    private val handledNotifications = ConcurrentHashMap<String, Long>()
    /** 按好友的自动回复冷却：userId -> 上次回复时间。 */
    private val lastReplyByUser = ConcurrentHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        if (sbn.notification == null) return
        serviceScope.launch { handleNotification(sbn) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        // 无动作：移除通知不触发回复。
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun handleNotification(sbn: StatusBarNotification) {
        try {
            val cfg = store.currentConfig()
            if (!cfg.enabled) return
            if (cfg.packages.isNotEmpty() && sbn.packageName !in cfg.packages) return

            val extras = sbn.notification.extras
            val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
            val content = bigText.ifBlank { text }
            if (sender.isEmpty() && content.isEmpty()) return

            val dedupKey = "${sbn.packageName}|${sbn.key}"
            if (isDuplicate(dedupKey)) return

            // 昵称 → user_id 映射决定了能否/向谁回复；解析不到就直接跳过（宁可不回，不发错）。
            val recipient = resolveRecipient(cfg.contacts, sender)
            if (recipient.isNullOrEmpty()) {
                DebugLog.w("NotifReply", "no recipient for sender=$sender, skip")
                return
            }

            if (inCooldown(recipient, cfg.cooldownMs)) return

            val convId = ensureConversation()
            val prompt = buildPrompt(cfg.promptHeader, sbn.packageName, sender, content)
            val reply = runAgent(convId, prompt)
            if (reply.isNullOrBlank()) {
                DebugLog.w("NotifReply", "AI returned empty reply for $sender")
                return
            }
            DebugLog.d("NotifReply", "replying to $recipient: ${reply.take(60)}")

            markReplied(recipient)
            sendReply(recipient, reply)
        } catch (e: Exception) {
            DebugLog.e("NotifReply", "handleNotification failed", e)
        }
    }

    /** 同一通知 key 在去重窗口内不重复处理（POST 后紧跟 UPDATE）。 */
    private fun isDuplicate(key: String): Boolean {
        val now = System.currentTimeMillis()
        if (handledNotifications.size > 256) {
            handledNotifications.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
        }
        val last = handledNotifications[key]
        if (last != null && now - last < DEDUP_WINDOW_MS) return true
        handledNotifications[key] = now
        return false
    }

    /** 昵称精确 → 忽略空白的宽匹配。命中任何一个即返回对应 user_id。 */
    private fun resolveRecipient(contacts: Map<String, String>, sender: String): String? {
        contacts[sender]?.let { return it }
        val normalized = sender.replace(" ", "").trim()
        contacts.entries.forEach { (name, id) ->
            if (name.replace(" ", "").trim() == normalized) return id
        }
        return null
    }

    /** 冷却期内不回复该好友，防连发触发一串 AI 请求。 */
    private fun inCooldown(userId: String, cooldownMs: Long): Boolean {
        val last = lastReplyByUser[userId]
        if (last == null || cooldownMs <= 0L) return false
        return (System.currentTimeMillis() - last) < cooldownMs
    }

    private fun markReplied(userId: String) {
        if (lastReplyByUser.size > 512) {
            val now = System.currentTimeMillis()
            lastReplyByUser.entries.removeIf { now - it.value > 24 * 60 * 60 * 1000L }
        }
        lastReplyByUser[userId] = System.currentTimeMillis()
    }

    /** 取得（或创建）专用于系统通知自动回复的 Lxchat 会话 id，跨重启复用。 */
    private suspend fun ensureConversation(): String {
        store.conversationId()?.takeIf { it.isNotBlank() }?.let { return it }
        val id = container.conversationRepository.createConversation(
            title = "系统通知自动回复",
            systemPromptId = null,
            modelId = null,
        )
        store.setConversationId(id)
        return id
    }

    private fun buildPrompt(header: String, appPackage: String, sender: String, content: String): String {
        val head = if (header.isBlank()) {
            "你正在代理我的微信账号做\"系统通知自动回复\"。好友「$sender」发来一条新消息，" +
                "请生成一条简短、得体、口语化的自动回复。不要编造事实，也不要在回复里泄露这是机器人。"
        } else {
            header.replace("{app}", appPackage).replace("{sender}", sender)
        }
        return buildString {
            append(head)
            append("\n消息内容：\n")
            append(content.take(MAX_PROMPT_CHARS))
        }
    }

    private suspend fun runAgent(convId: String, prompt: String): String? {
        val result = container.taskExecutionEngine.runOnce(
            conversationId = convId,
            userText = prompt,
        )
        return when (result) {
            is TaskExecutionEngine.Result.Success -> result.text
            is TaskExecutionEngine.Result.Busy -> {
                DebugLog.d("NotifReply", "conversation busy, skip this notification")
                null
            }
            is TaskExecutionEngine.Result.Failure -> {
                DebugLog.e("NotifReply", "runOnce failed: ${result.reason}")
                null
            }
        }
    }

    private suspend fun sendReply(recipient: String, reply: String) {
        val owner = container
        val wechat = owner.imBridgeService.channels().values
            .filterIsInstance<WeixinChannel>()
            .firstOrNull { it.isConfigured }
        if (wechat == null) {
            DebugLog.w("NotifReply", "no configured WeixinChannel to send reply")
            return
        }
        val result = wechat.sendMessage(recipient, reply)
        if (result is com.lxseek.chat.im.ImSendResult.Failure) {
            DebugLog.e("NotifReply", "send reply failed: ${result.reason}")
        } else {
            DebugLog.d("NotifReply", "sent reply to $recipient")
        }
    }

    private companion object {
        // 同一通知 POST/UPDATE 去重窗口（毫秒）。
        const val DEDUP_WINDOW_MS = 5_000L
        // 单个消息注入 AI 的最大字符数，防止超长通知占满上下文。
        const val MAX_PROMPT_CHARS = 6_000
    }
}