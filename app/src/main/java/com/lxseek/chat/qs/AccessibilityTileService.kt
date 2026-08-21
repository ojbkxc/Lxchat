package com.lxseek.chat.qs

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.lxseek.chat.androidcontrol.AndroidUiControllerService

/**
 * Quick Settings tile exposing the Android-Accessibility bridge ("App control") status.
 *
 * Accessibility services can only be toggled by the user inside the system Accessibility
 * settings — there is no programmatic enable/disable the tile could call. So this tile is
 * display-only for the active state (STATE_ACTIVE when an accessibility bridge is connected)
 * and, on tap, deep-links to the system accessibility page. That gives a zero-navigation
 * flight path to enable the bridge that powers [com.lxseek.chat.tool.AndroidAppControllerToolProvider].
 */
class AccessibilityTileService : TileService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refreshState()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(intent)
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshState()
    }

    /** Projects whether the accessibility bridge is currently connected onto the tile state. */
    private fun refreshState() {
        val enabled = AndroidUiControllerService.instance != null
        main.post {
            val tile = qsTile ?: return@post
            tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.contentDescription = getString(
                if (enabled) R.string.qs_accessibility_on_desc else R.string.qs_accessibility_off_desc,
            )
            tile.updateTile()
        }
    }
}