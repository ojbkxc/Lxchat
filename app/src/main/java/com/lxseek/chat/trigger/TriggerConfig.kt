package com.lxseek.chat.trigger

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
 * 一条条件触发规则：当系统状态（电量/网络/充电）满足 [type] + [threshold] + [condition] 时，
 * 把 [prompt] 交给 AI 执行，结果通过本地通知展示。
 *
 * - [id]：UUID，规则唯一标识。
 * - [threshold]：阈值，语义随 [type] 变化（电量百分比 / 网络类型编码等）。
 * - [condition]：比较条件，如 "below"/"above"；网络类型规则可留空。
 * - [cooldownMs]：同一规则两次触发的最小间隔，默认 5 分钟，防止电量在阈值附近抖动反复触发。
 * - [lastTriggeredAt]：上次触发时间戳，配合 [cooldownMs] 做冷却判断；由 [TriggerExecutorService] 写回。
 */
@Serializable
data class TriggerRule(
    val id: String,
    val name: String,
    val type: TriggerType,
    val threshold: Int = 0,
    val condition: String = "",
    val prompt: String,
    val modelId: String? = null,
    val enabled: Boolean = true,
    val lastTriggeredAt: Long = 0,
    val cooldownMs: Long = 300_000L,
)

/**
 * 触发类型。
 *
 * - [BATTERY_LOW]：电量低于 [TriggerRule.threshold] 百分比时触发。
 * - [BATTERY_HIGH]：电量高于 [TriggerRule.threshold] 百分比时触发（典型用法：充电到 80% 提醒）。
 * - [NETWORK_CHANGE]：网络状态变化时触发（WiFi/移动数据/无网络）。
 * - [CHARGING_START]：插入电源时触发。
 * - [CHARGING_STOP]：拔掉电源时触发。
 */
@Serializable
enum class TriggerType {
    BATTERY_LOW,
    BATTERY_HIGH,
    NETWORK_CHANGE,
    CHARGING_START,
    CHARGING_STOP,
}

/**
 * 触发系统整体配置。
 *
 * - [enabled]：总开关。关闭后所有 receiver 都不再触发 AI 任务。
 * - [rules]：规则列表，顺序即展示顺序。
 * - [dedicatedConversationId]：AI 任务落库的专用会话 id。首次触发时若为空会新建一个名为
 *   「条件触发」的会话并写回，跨重启保留上下文。
 */
@Serializable
data class TriggerConfig(
    val enabled: Boolean = false,
    val rules: List<TriggerRule> = emptyList(),
    val dedicatedConversationId: String = "",
)

/** 触发配置持久化：用独立的 preferencesDataStore("condition_trigger")。 */
private val Context.triggerDataStore by preferencesDataStore(name = "condition_trigger")

/**
 * 触发配置存储，模仿 [com.lxseek.chat.notification.NotificationReplyStore] 与
 * [com.lxseek.chat.sms.SmsCommandConfigStore] 的写法。
 *
 * Receiver 在 goAsync() 协程里调 [currentConfig] 读取；UI 用 [config] Flow 观察；
 * [TriggerExecutorService] 触发后用 [markTriggered] 写回 lastTriggeredAt。
 */
class TriggerConfigStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val KEY_CONFIG = stringPreferencesKey("config_json")
    private val KEY_CONVERSATION_ID = stringPreferencesKey("dedicated_conversation_id")

    val config: Flow<TriggerConfig> = context.triggerDataStore.data.map { pref ->
        val raw = pref[KEY_CONFIG] ?: return@map TriggerConfig()
        decodeConfig(raw)
    }

    /** 挂起读取当前配置（用于 BroadcastReceiver 的 goAsync 协程内）。 */
    suspend fun currentConfig(): TriggerConfig = config.first()

    suspend fun update(transform: (TriggerConfig) -> TriggerConfig) {
        context.triggerDataStore.edit { pref ->
            val current = try {
                json.decodeFromString<TriggerConfig>(pref[KEY_CONFIG] ?: return@edit)
            } catch (e: Exception) {
                TriggerConfig()
            }
            pref[KEY_CONFIG] = json.encodeToString(transform(current))
        }
    }

    /** 增加一条规则（id 由调用方生成）。 */
    suspend fun addRule(rule: TriggerRule) {
        update { it.copy(rules = it.rules + rule) }
    }

    /** 删除一条规则。 */
    suspend fun removeRule(id: String) {
        update { it.copy(rules = it.rules.filterNot { r -> r.id == id }) }
    }

    /** 更新一条规则（替换同 id 的旧值）。 */
    suspend fun upsertRule(rule: TriggerRule) {
        update { cfg ->
            cfg.copy(rules = cfg.rules.map { if (it.id == rule.id) rule else it })
        }
    }

    /**
     * 标记规则已触发：写回 lastTriggeredAt = now。
     * 由 [TriggerExecutorService] 在 AI 任务开始前调用，确保冷却窗口生效。
     */
    suspend fun markTriggered(id: String, now: Long = System.currentTimeMillis()) {
        update { cfg ->
            cfg.copy(rules = cfg.rules.map { r ->
                if (r.id == id) r.copy(lastTriggeredAt = now) else r
            })
        }
    }

    /** 读写专用会话 id（AI 任务落库于此）。 */
    suspend fun conversationId(): String? =
        context.triggerDataStore.data.first()[KEY_CONVERSATION_ID]

    suspend fun setConversationId(id: String) {
        context.triggerDataStore.edit { it[KEY_CONVERSATION_ID] = id }
    }

    private fun decodeConfig(raw: String): TriggerConfig =
        runCatching { json.decodeFromString<TriggerConfig>(raw) }
            .getOrElse { TriggerConfig() }

}