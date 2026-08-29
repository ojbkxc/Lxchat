package com.lxseek.chat.plugin

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.lxseek.chat.tool.ToolDescriptor
import com.lxseek.chat.tool.ToolProvider
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Permission-aware decorator over a [ToolProvider]. Keeps the wrapped provider's tool
 * definitions visible (so the model can still learn the capability exists) but degrades
 * execution to a clear, actionable JSON error while any required Android permission is
 * missing — instead of letting the model repeatedly attempt a tool that is guaranteed to
 * fail. This mirrors dph project1's "dual-channel graceful degradation" idea without any
 * extra dependency.
 *
 * Required permission entries may be a normal manifest permission string
 * (e.g. [android.Manifest.permission.READ_CONTACTS]) or one of the special sentinels
 * [NOTIFICATION_LISTENER], [USAGE_STATS] or [MEDIA_PROJECTION] for grant flows that are
 * not plain runtime permissions.
 */
class PermissionGatedToolProvider(
    private val app: Context,
    private val delegate: ToolProvider,
    private val requiredPermissions: List<String>,
) : ToolProvider {


    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> =
        delegate.toolDescriptors(ctx)

    override fun definitions(ctx: GenerationContext): List<com.lxseek.chat.api.ToolDefinition> =
        delegate.definitions(ctx)

    override fun handles(name: String): Boolean = delegate.handles(name)

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String {
        requiredPermissions.firstOrNull { !isGranted(it) }?.let { missing ->
            return buildJsonError(missing)
        }
        return delegate.execute(name, arguments, ctx)
    }

    private fun isGranted(permission: String): Boolean = when (permission) {
        NOTIFICATION_LISTENER -> hasNotificationListenerAccess()
        USAGE_STATS -> hasUsageAccess()
        MEDIA_PROJECTION -> hasMediaProjection()
        else -> ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationListenerAccess(): Boolean {
        val flat = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
        ) ?: return false
        val target = ComponentName(app, "com.lxseek.chat.tool.LxNotificationListenerService")
        return flat.split(":").any { entry ->
            ComponentName.unflattenFromString(entry) == target
        }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    app.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    app.packageName,
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun hasMediaProjection(): Boolean {
        // MediaProjection grant is a one-shot runtime token that cannot be queried lazily;
        // the wrapped ScreenRecordToolProvider owns the re-request flow, so treat it as granted
        // and let the provider surface the actionable error when no session is active.
        return true
    }

    private fun buildJsonError(permission: String): String {
        val hint = when (permission) {
            NOTIFICATION_LISTENER ->
                "Notification access has not been granted. Open Settings -> Apps -> Special access -> " +
                    "Notification access and enable LxChat."
            USAGE_STATS ->
                "Usage access has not been granted. Open Settings -> Apps -> Special access -> " +
                    "Usage access and enable LxChat."
            MEDIA_PROJECTION ->
                "Screen recording permission is not available. Start a new recording session to " +
                    "re-request MediaProjection permission."
            else ->
                "Permission $permission has not been granted. Open Settings -> Apps -> LxChat -> " +
                    "Permissions and grant it."
        }
        return buildJsonObject {
            put("type", "permission_required")
            put("permission", permission)
            put("ok", false)
            put("hint", hint)
        }.toString()
    }

    companion object {
        /** Notification access (Settings secure list). */
        const val NOTIFICATION_LISTENER = "lxchat:notification_listener"

        /** Usage-access via AppOps (Settings → Special access → Usage access). */
        const val USAGE_STATS = "lxchat:usage_stats"

        /** MediaProjection runtime grant. */
        const val MEDIA_PROJECTION = "lxchat:media_projection"
    }
}