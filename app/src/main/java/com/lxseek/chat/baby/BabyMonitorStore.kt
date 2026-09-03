package com.lxseek.chat.baby

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
/** 灵敏度档位（SENSITIVE / NORMAL / STABLE）。 */
private val BABY_MONITOR_SENSITIVITY = stringPreferencesKey("baby_monitor_sensitivity")
/** 免打扰时段开关。 */
private val BABY_MONITOR_QUIET_ENABLED = booleanPreferencesKey("baby_monitor_quiet_enabled")
/** 免打扰开始时刻（当天分钟数，0..1439）。 */
private val BABY_MONITOR_QUIET_START = intPreferencesKey("baby_monitor_quiet_start_min")
/** 免打扰结束时刻（当天分钟数，0..1439；支持跨午夜，begin>end 表示到次日)。 */
private val BABY_MONITOR_QUIET_END = intPreferencesKey("baby_monitor_quiet_end_min")

/** 灵敏度档位：一键切换整套判定阈值（吸收 android-vad 的灵敏度分级思路）。 */
enum class BabySensitivity {
    /** 宝宝平时比较稳，用更严苛阈值压低误报。 */
    STABLE,

    /** 默认，兼顾召回与误报。 */
    NORMAL,

    /** 宝宝比较敏感 / 更容易被忽略，降低门槛提升召回。 */
    SENSITIVE,
}

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
        val sensitivity: BabySensitivity = BabySensitivity.NORMAL,
        val quietHoursEnabled: Boolean = false,
        val quietStartMin: Int = 22 * 60,
        val quietEndMin: Int = 7 * 60,
    ) {
        /** 免打扰时段内（含跨午夜）。方便服务/测试复用。 */
        fun inQuietHours(minuteOfDay: Int): Boolean {
            if (!quietHoursEnabled) return false
            val m = ((minuteOfDay % 1440) + 1440) % 1440
            return if (quietStartMin <= quietEndMin) {
                m in quietStartMin until quietEndMin
            } else {
                m >= quietStartMin || m < quietEndMin
            }
        }
    }

    val config: Flow<Config> = context.babyMonitorDataStore.data.map { pref ->
        Config(
            enabled = pref[BABY_MONITOR_ENABLED] ?: false,
            selectedChannels = pref[BABY_MONITOR_SELECTED_CHANNELS] ?: emptySet(),
            sustainHits = (pref[BABY_MONITOR_SUSTAIN] ?: 3).coerceIn(1, 10),
            cooldownMinutes = (pref[BABY_MONITOR_COOLDOWN_MIN] ?: 1).coerceIn(1, 120),
            sensitivity = runCatching {
                BabySensitivity.valueOf(pref[BABY_MONITOR_SENSITIVITY] ?: "NORMAL")
            }.getOrDefault(BabySensitivity.NORMAL),
            quietHoursEnabled = pref[BABY_MONITOR_QUIET_ENABLED] ?: false,
            quietStartMin = (pref[BABY_MONITOR_QUIET_START] ?: 22 * 60).coerceIn(0, 1439),
            quietEndMin = (pref[BABY_MONITOR_QUIET_END] ?: 7 * 60).coerceIn(0, 1439),
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

    suspend fun setSensitivity(sensitivity: BabySensitivity) {
        context.babyMonitorDataStore.edit { it[BABY_MONITOR_SENSITIVITY] = sensitivity.name }
    }

    suspend fun setQuietHoursEnabled(enabled: Boolean) {
        context.babyMonitorDataStore.edit { it[BABY_MONITOR_QUIET_ENABLED] = enabled }
    }

    suspend fun setQuietStartMin(min: Int) {
        context.babyMonitorDataStore.edit { it[BABY_MONITOR_QUIET_START] = min.coerceIn(0, 1439) }
    }

    suspend fun setQuietEndMin(min: Int) {
        context.babyMonitorDataStore.edit { it[BABY_MONITOR_QUIET_END] = min.coerceIn(0, 1439) }
    }
}
