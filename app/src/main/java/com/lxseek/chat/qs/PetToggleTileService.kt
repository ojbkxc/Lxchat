package com.lxseek.chat.qs

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.lxseek.chat.R
import com.lxseek.chat.pet.PetOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile that toggles the desktop-pet floating bubble on and off.
 *
 * When turning it on without the `SYSTEM_ALERT_WINDOW` permission, the tile collapses to the system
 * overlay-permission screen so the user can grant access; the pet only appears after that grant.
 */
class PetToggleTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refreshState()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked()) {
            // Defer to after the device is unlocked so a background-start is allowed.
            unlockAndRun { toggle() }
        } else {
            toggle()
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshState()
    }

    private fun toggle() {
        scope.launch {
            val turnOn = !PetOverlayController.isEnabled(applicationContext)
            if (turnOn && !PetOverlayController.canDrawOverlay(applicationContext)) {
                // No overlay permission: send the user to the system grant screen; keep the tile off.
                launchPermissionSettings()
                refreshState()
                return@launch
            }
            PetOverlayController.setEnabled(applicationContext, turnOn)
            refreshState()
        }
    }

    private suspend fun launchPermissionSettings() {
        withContext(Dispatchers.Main) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshState() {
        scope.launch {
            val enabled = PetOverlayController.isEnabled(applicationContext)
            main.post {
                val tile = qsTile ?: return@post
                tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.contentDescription = getString(
                    if (enabled) R.string.qs_pet_on_desc else R.string.qs_pet_off_desc,
                )
                tile.updateTile()
            }
        }
    }
}