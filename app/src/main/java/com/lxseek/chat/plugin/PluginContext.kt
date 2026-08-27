package com.lxseek.chat.plugin

import android.content.Context
import com.lxseek.chat.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope

/**
 * 插件运行时上下文：只暴露经过收敛的最小依赖，避免插件直接触达整个 AppContainer。
 */
class PluginContext(
    val appContext: Context,
    val scope: CoroutineScope,
    val settings: SettingsRepository,
)
