package com.lxseek.chat.sms

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 短信命令系统的配置。
 *
 * - [enabled]：总开关。关闭后即使已授予短信权限也不再识别 `smsf#` 命令。
 * - [allowedSenders]：允许发送命令的号码白名单（空表示允许所有来源，慎用）。
 *   号码比对会做 trim + 去前缀「+86」归一化，避免 8613800138000 / +8613800138000 / 138001380000 三种写法不一致。
 * - [dedicatedConversationId]：AI 命令 (`smsf#ai#chat#...`) 落库的专用会话 id。
 *   首次执行 AI 命令时若为空会自动新建一个名为「SMS 命令」的会话并写回此处，跨重启保留上下文。
 */
@Serializable
data class SmsCommandConfig(
    val enabled: Boolean = false,
    val allowedSenders: List<String> = emptyList(),
    val dedicatedConversationId: String = "",
)

/** 短信命令配置持久化：用独立的 preferencesDataStore("sms_command")。 */
private val Context.smsCommandDataStore by preferencesDataStore(name = "sms_command")

/** 短信命令配置存储，模仿 [com.lxseek.chat.notification.NotificationReplyStore] 的写法。 */
class SmsCommandConfigStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val KEY_CONFIG = stringPreferencesKey("config_json")

    val config: Flow<SmsCommandConfig> = context.smsCommandDataStore.data.map { pref ->
        val raw = pref[KEY_CONFIG] ?: return@map SmsCommandConfig()
        decodeConfig(raw)
    }

    /** 挂起读取当前配置（用于 BroadcastReceiver 的 goAsync 协程内）。 */
    suspend fun currentConfig(): SmsCommandConfig = config.first()

    suspend fun update(transform: (SmsCommandConfig) -> SmsCommandConfig) {
        context.smsCommandDataStore.edit { pref ->
            val current = try {
                json.decodeFromString<SmsCommandConfig>(pref[KEY_CONFIG] ?: return@edit)
            } catch (e: Exception) {
                SmsCommandConfig()
            }
            pref[KEY_CONFIG] = json.encodeToString(transform(current))
        }
    }

    private fun decodeConfig(raw: String): SmsCommandConfig =
        runCatching { json.decodeFromString<SmsCommandConfig>(raw) }
            .getOrElse { SmsCommandConfig() }

    /**
     * 归一化号码：去空格、去前缀「+86」「86」「0086」，保留纯 11 位手机号或原始字符串。
     * 用于白名单比对，避免不同写法导致匹配失败。
     */
    fun normalizeSender(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim()
        if (s.startsWith("+86")) s = s.substring(3)
        else if (s.startsWith("0086")) s = s.substring(4)
        else if (s.startsWith("86") && s.length == 13) s = s.substring(2) // 86 + 11位手机号
        return s.trim()
    }

    /** 判断号码是否在白名单内（白名单为空视为允许所有）。 */
    fun isAllowed(cfg: SmsCommandConfig, sender: String?): Boolean {
        if (cfg.allowedSenders.isEmpty()) return true
        val normalized = normalizeSender(sender)
        return cfg.allowedSenders.any { normalizeSender(it) == normalized }
    }
}
