package com.lxseek.chat.qs

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.lxseek.chat.LxChatApplication
import com.lxseek.chat.R
import com.lxseek.chat.data.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * Quick Settings tile that toggles the Agent's automation-capable toolset on and off from the
 * system shade, mirroring the "AI tools" switch in Settings → Automation.
 *
 * While automation tools are OFF the text agent can still converse, but it cannot schedule
 * tasks / run reminders / execute shell — the eavesdropping-adjacent surface stays closed. This
 * gives a one-tap, zero-navigation safety kill switch for headless background automation.
 */
class AgentToggleTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refreshState()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val newValue = !settingsManager().automationToolsEnabled.first()
            settingsManager().saveAutomationToolsEnabled(newValue)
            refreshState()
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshState()
    }

    /** Reads the persisted switch and projects it onto the tile's active/label state. */
    private fun refreshState() {
        scope.launch {
            val enabled = settingsManager().automationToolsEnabled.first()
            main.post {
                val tile = qsTile ?: return@post
                tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.contentDescription = getString(
                    if (enabled) R.string.qs_agent_on_desc else R.string.qs_agent_off_desc,
                )
                tile.updateTile()
            }
        }
    }

    /** Process-scoped SettingsManager, reachable from the background tile process without a UI. */
    private fun settingsManager(): SettingsManager =
        (applicationContext as LxChatApplication).container.settingsManager
}