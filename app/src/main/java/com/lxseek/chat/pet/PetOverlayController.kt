package com.lxseek.chat.pet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.lxseek.chat.LxChatApplication
import com.lxseek.chat.data.SettingsManager
import kotlinx.coroutines.flow.first

/**
 * Shared coordinator for the pet overlay: persists the on/off preference through the DI container's
 * [SettingsManager] and drives the [PetOverlayWindowService]. Both the Quick Settings tile and the
 * Settings page route their toggles through here so enable/disable behavior stays consistent.
 *
 * Enabling without `SYSTEM_ALERT_WINDOW` never starts the service — the caller is expected to first
 * send the user to the system overlay-permission screen (see [openPermissionSettings]).
 */
object PetOverlayController {

    private fun application(context: Context): LxChatApplication =
        context.applicationContext as LxChatApplication

    private fun settings(context: Context): SettingsManager =
        application(context).container.settingsManager

    fun canDrawOverlay(context: Context): Boolean =
        Settings.canDrawOverlays(context.applicationContext)

    /** Deep-links to the system "Display over other apps" permission page for this package. */
    fun openPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.applicationContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.applicationContext.startActivity(intent) }
    }

    suspend fun isEnabled(context: Context): Boolean =
        settings(context).petOverlayEnabled.first()

    /** Returns the persisted custom-image absolute path, or "" if none. */
    suspend fun getImagePath(context: Context): String =
        settings(context).petOverlayImagePath.first()

    /** Returns the persisted size scale (0.5~1.3); defaults to 1.0 (largest before the 30% bump). */
    suspend fun getSizeScale(context: Context): Float =
        settings(context).petOverlaySizeScale.first()

    /** Returns the persisted built-in sprite ([PetCharacter.prefKey]); defaults to classic. */
    suspend fun getCharacter(context: Context): PetCharacter =
        PetCharacter.fromKey(settings(context).petOverlayCharacter.first())

    /** Reloads the custom image into a running overlay; no-op if the service is not running. */
    fun refreshImage(context: Context) {
        PetOverlayWindowService.refreshImage(context)
    }

    /**
     * Switches the built-in sprite on a running overlay; no-op if the service is not running
     * (the next [PetOverlayWindowService.start] picks the new value up).
     */
    fun refreshCharacter(context: Context) {
        PetOverlayWindowService.refreshCharacter(context)
    }

    /**
     * Restarts the pet overlay service so it re-reads the size scale and rebuilds the window with
     * the new dimensions. No-op when the overlay is disabled or the permission is missing, so
     * dragging the size slider while the pet is off simply primes the value for the next enable.
     */
    suspend fun refreshSize(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        PetOverlayWindowService.stop(app)
        PetOverlayWindowService.start(app)
    }

    /**
     * Persists [enabled] and starts/stops the pet service.
     *
     * @return true when the desired state was fully applied; false when enabling was not possible
     *         (overlay permission missing) and the preference was left disabled so the caller can
     *         prompt for the permission instead.
     */
    suspend fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled) {
            if (!canDrawOverlay(context)) {
                settings(context).savePetOverlayEnabled(false)
                return false
            }
            settings(context).savePetOverlayEnabled(true)
            PetOverlayWindowService.createChannel(context)
            PetOverlayWindowService.start(context)
        } else {
            settings(context).savePetOverlayEnabled(false)
            PetOverlayWindowService.stop(context)
        }
        return true
    }
}