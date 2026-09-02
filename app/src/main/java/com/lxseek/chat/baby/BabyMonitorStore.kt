package com.lxseek.chat.baby

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 婴儿监护独立 DataStore（与核心 settings 分离，避免继续膨胀 SettingsManager）。 */
private val Context.babyMonitorDataStore by preferencesDataStore(name = "baby_monitor")

private val BABY_MONITOR_ENABLED = booleanPreferencesKey("baby_monitor_enabled")
/** 选中要通知的渠道 effectiveChannelId 集合；空集 = 通知所有已启用渠道（用户需求默认）。 */
private val BABY_MONITOR_SELECTED_CHANNELS = stringSetPreferencesKey("baby_monitor_selected_channels")
/** 触发持续命中次数（1..10）。 */
private val BABY_MONITOR_SUSTAIN = intPreferencesKey("baby_monitor_sustain_hits")
/** 报警冷却分钟（1..120）。 */
private val BABY_MONITOR_COOLDOWN_MIN = intPreferencesKey("baby_monitor_cooldown_min")

/**
 * 婴儿哭声监护偏好存储。
 *
 * 需求（用户 2026-09-02 指示）：
 *  - 哭声检测是**单独的触发开关**，与 IM 渠道解耦；
 *  - 开启后默认通知**所有已启用**的 IM 渠道（[selectedChannels] 为空集时语义）；
 *  - 开关后面可以下拉当前启用渠道，用复选框选择通知哪几个（[selectedChannels] 非空时语义）。
 */
class BabyMonitorStore(private val context: Context) {

    data class Config(
        val enabled: Boolean = false,
        /** 空集 = 全部已启用渠道。 */
        val selectedChannels: Set<String> = emptySet(),
        val sustainHits: Int = 3,
        val cooldownMinutes: Int = 1,
    )

    val config: Flow<Config> = context.babyMonitorDataStore.data.map { pref ->
        Config(
            enabled = pref[BABY_MONITOR_ENABLED] ?: false,
            selectedChannels = pref[BABY_MONITOR_SELECTED_CHANNELS] ?: emptySet(),
            sustainHits = (pref[BABY_MONITOR_SUSTAIN] ?: 3).coerceIn(1, 10),
            cooldownMinutes = (pref[BABY_MONITOR_COOLDOWN_MIN] ?: 1).coerceIn(1, 120),
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.babyMonitorDataStore.edit { it[BABY_MONITOR_ENABLED] = enabled }
    }

    /** 设置选中渠道；传空集回到「通知全部已启用渠道」。 */
    suspend fun setSelectedChannels(channelIds: Set<String>) {
        context.babyMonitorDataStore.edit { it[BABY_MONITOR_SELECTED_CHANNELS] = channelIds }
    }

    suspend fun setSustainHits(hits: Int) {
        context.babyMonitorDataStore.edit { it[BABY_MONITOR_SUSTAIN] = hits.coerceIn(1, 10) }
    }

    suspend fun setCooldownMinutes(minutes: Int) {
        context.babyMonitorDataStore.edit { it[BABY_MONITOR_COOLDOWN_MIN] = minutes.coerceIn(1, 120) }
    }
}
