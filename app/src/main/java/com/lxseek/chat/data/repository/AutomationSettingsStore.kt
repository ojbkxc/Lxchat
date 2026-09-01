package com.lxseek.chat.data.repository

import com.lxseek.chat.data.SettingsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

class AutomationSettingsStore(
    private val settingsManager: SettingsManager,
    private val scope: CoroutineScope,
    initialLoadSignals: MutableList<CompletableDeferred<Unit>>,
) {
    val automationToolsEnabled: StateFlow<Boolean> =
        sharedSettingsState(settingsManager.automationToolsEnabled, false, scope, initialLoadSignals)
}
