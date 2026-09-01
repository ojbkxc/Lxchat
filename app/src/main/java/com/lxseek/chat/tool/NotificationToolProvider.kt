package com.lxseek.chat.tool

import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.agent.GenerationContext
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * In-process snapshot of the notifications currently visible in the system shade.
 *
 * [LxNotificationListenerService] feeds this buffer as notifications are posted and removed,
 * and [NotificationToolProvider] reads from it. Holding the buffer outside the service lets the
 * tools answer without holding a reference to the service callbacks, and keeps the service itself
 * stateless apart from forwarding system events.
 *
 * The buffer is bounded by [MAX_ENTRIES] to avoid unbounded growth on devices with very chatty
 * notification producers; the oldest entry is evicted first when the cap is reached.
 */
internal object NotificationBuffer {
    private const val MAX_ENTRIES = 512

    private val notifications = ConcurrentHashMap<String, StatusBarNotification>()

    @Volatile
    private var serviceRef: WeakReference<NotificationListenerService>? = null

    /** True only while a [LxNotificationListenerService] is connected and receiving events. */
    @Volatile
    var connected: Boolean = false
        private set

    internal fun bind(service: NotificationListenerService) {
        serviceRef = WeakReference(service)
        connected = true
    }

    internal fun unbind() {
        connected = false
        serviceRef = null
    }

    /** The currently bound listener instance, or null when not connected. */
    fun service(): NotificationListenerService? = serviceRef?.get()

    /** Replace the whole buffer (used after an initial active-notification fetch). */
    internal fun replaceAll(all: Array<StatusBarNotification>) {
        notifications.clear()
        for (sbn in all) {
            if (sbn.notification != null) notifications[sbn.key] = sbn
        }
    }

    internal fun put(sbn: StatusBarNotification) {
        if (sbn.notification == null) return
        if (notifications.size >= MAX_ENTRIES) evictOldest()
        notifications[sbn.key] = sbn
    }

    internal fun remove(key: String) {
        notifications.remove(key)
    }

    fun snapshot(): List<StatusBarNotification> = notifications.values.toList()

    fun get(key: String): StatusBarNotification? = notifications[key]

    fun keysForPackage(pkg: String): List<String> =
        notifications.values.filter { it.packageName == pkg }.map { it.key }

    private fun evictOldest() {
        notifications.entries.minByOrNull { it.value.postTime }?.let { notifications.remove(it.key) }
    }
}

/**
 * NotificationListenerService that mirrors the system notification stream into [NotificationBuffer]
 * so that [NotificationToolProvider] can list and clear notifications.
 *
 * The user must grant notification access once via
 * Settings > Notifications > Notification access (or `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`).
 * Until then every `notification_*` tool returns a `notification_error` carrying a guidance message.
 */
class LxNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationBuffer.bind(this)
        // Seed the buffer with the notifications already in the shade so that notification_list
        // returns data immediately instead of waiting for the next post event.
        try {
            val active = getActiveNotifications()
            if (active != null) NotificationBuffer.replaceAll(active)
        } catch (e: Exception) {
            DebugLog.w("NotifListener", "getActiveNotifications failed")
        }
        DebugLog.d("NotifListener", "connected, buffered ${NotificationBuffer.snapshot().size} notifications")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationBuffer.unbind()
        DebugLog.d("NotifListener", "disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        NotificationBuffer.put(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        NotificationBuffer.remove(sbn.key)
    }
}

/**
 * Agent tools for reading and clearing the system notification shade.
 *
 * Backed by [NotificationBuffer] (kept in sync by [LxNotificationListenerService]). All tools
 * return a structured JSON string. When the listener is not connected, every tool returns an
 * `notification_error` carrying a guidance message explaining how to grant notification access.
 *
 * Tools exposed:
 *  - `notification_list`             — list active notifications (optional package/limit filter).
 *  - `notification_get`              — detail for one notification by key or id.
 *  - `notification_clear`            — dismiss one notification by key.
 *  - `notification_clear_all`        — dismiss every active notification.
 *  - `notification_clear_by_package` — dismiss every notification from one package.
 *
 * Read tools are [ToolTier.Extended]; clear tools are [ToolTier.Dangerous] and require approval.
 */
class NotificationToolProvider(private val app: Application) : ToolProvider {

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> =
        definitions(ctx).map { def ->
            val name = def.function.name
            ToolDescriptor(
                definition = def,
                riskLevel = risk(name),
                tier = tier(name),
                requiresApproval = name.startsWith("notification_clear"),
            )
        }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun prop(t: String, d: String) = ToolProperty(t, d)
        return listOf(
            tool(
                "notification_list",
                "List the notifications currently active in the system shade. Returns " +
                    "package_name, title, text, post_time, category, group_key, key and id for " +
                    "each. Optionally filter by package and cap the result count.",
                mapOf(
                    "package" to prop("string", "Optional package name to filter by (exact match)."),
                    "limit" to prop("integer", "Maximum notifications to return (1..200), default 50."),
                ),
                emptyList(),
            ),
            tool(
                "notification_get",
                "Return full detail for a single notification. Identify it by 'key' (preferred) " +
                    "or by numeric 'id'. Includes title, text, big_text, sub_text, category, " +
                    "priority, ongoing flag, post_time and group_key.",
                mapOf(
                    "key" to prop("string", "Notification key (from notification_list)."),
                    "id" to prop("integer", "Notification id (fallback when key is unknown)."),
                ),
                emptyList(),
            ),
            tool(
                "notification_clear",
                "Dismiss one notification from the shade by its key. Requires notification listener access.",
                mapOf("key" to prop("string", "Notification key to clear.")),
                listOf("key"),
            ),
            tool(
                "notification_clear_all",
                "Dismiss every active notification from the shade. Requires notification listener access.",
                emptyMap(), emptyList(),
            ),
            tool(
                "notification_clear_by_package",
                "Dismiss every notification originating from the given package. " +
                    "Requires notification listener access.",
                mapOf("package" to prop("string", "Package name whose notifications to clear.")),
                listOf("package"),
            ),
        )
    }

    override fun handles(name: String): Boolean = name.startsWith("notification_")

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "notification_list" -> listJson(arguments)
                "notification_get" -> getJson(arguments)
                "notification_clear" -> clear(arguments)
                "notification_clear_all" -> clearAll()
                "notification_clear_by_package" -> clearByPackage(arguments)
                else -> err("unknown_tool", "Unknown notification tool: $name")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("NotifTool", "notification_$name failed", e)
            err("tool_error", e.message)
        }
    }

    // ── Tools ─────────────────────────────────────────────────

    private fun listJson(arguments: String): String {
        if (!NotificationBuffer.connected) return notConnectedHint()
        val pkgFilter = argString("package", arguments)?.trim()?.takeIf { it.isNotEmpty() }
        val limit = (argInt("limit", arguments) ?: 50).coerceIn(1, 200)
        val items = NotificationBuffer.snapshot()
            .filter { pkgFilter == null || it.packageName == pkgFilter }
            .sortedByDescending { it.postTime }
            .take(limit)
        return buildJsonObject {
            put("type", "notification_list")
            put("connected", true)
            put("count", items.size)
            put("notifications", buildJsonArray {
                items.forEach { add(summarize(it)) }
            })
        }.toString()
    }

    private fun getJson(arguments: String): String {
        if (!NotificationBuffer.connected) return notConnectedHint()
        val key = argString("key", arguments)?.trim()?.takeIf { it.isNotEmpty() }
        val sbn = if (key != null) {
            NotificationBuffer.get(key)
        } else {
            val id = argInt("id", arguments)
            if (id != null) NotificationBuffer.snapshot().firstOrNull { it.id == id } else null
        }
        if (sbn == null) return err("not_found", "No notification matching the given key/id.")
        return buildJsonObject {
            put("type", "notification_get")
            put("connected", true)
            put("key", sbn.key)
            put("id", sbn.id)
            put("tag", sbn.tag)
            put("package_name", sbn.packageName)
            put("post_time", sbn.postTime)
            put("post_time_iso", isoTime(sbn.postTime))
            val n = sbn.notification
            if (n != null) {
                put("title", titleOf(n))
                put("text", textOf(n))
                put("big_text", n.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
                put("sub_text", n.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
                put("info_text", n.extras?.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString())
                put("summary_text", n.extras?.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString())
                put("category", n.category)
                put("priority", n.priority)
                put("ongoing", (n.flags and Notification.FLAG_ONGOING_EVENT) != 0)
                put("group_key", sbn.groupKey)
                put("when", n.`when`)
            }
        }.toString()
    }

    private fun clear(arguments: String): String {
        if (!NotificationBuffer.connected) return notConnectedHint()
        val key = argString("key", arguments)?.trim()
            ?: return err("no_key", "Missing key.")
        val service = NotificationBuffer.service() ?: return notConnectedHint()
        return try {
            service.cancelNotification(key)
            buildJsonObject {
                put("type", "notification_clear")
                put("status", "ok")
                put("key", key)
            }.toString()
        } catch (e: Exception) {
            err("clear_failed", e.message)
        }
    }

    private fun clearAll(): String {
        if (!NotificationBuffer.connected) return notConnectedHint()
        val service = NotificationBuffer.service() ?: return notConnectedHint()
        return try {
            service.cancelAllNotifications()
            buildJsonObject {
                put("type", "notification_clear_all")
                put("status", "ok")
            }.toString()
        } catch (e: Exception) {
            err("clear_failed", e.message)
        }
    }

    private fun clearByPackage(arguments: String): String {
        if (!NotificationBuffer.connected) return notConnectedHint()
        val pkg = argString("package", arguments)?.trim()
            ?: return err("no_package", "Missing package.")
        val service = NotificationBuffer.service() ?: return notConnectedHint()
        val keys = NotificationBuffer.keysForPackage(pkg)
        var cleared = 0
        for (k in keys) {
            try {
                service.cancelNotification(k)
                cleared++
            } catch (e: Exception) {
                DebugLog.w("NotifTool", "cancel $k failed")
            }
        }
        return buildJsonObject {
            put("type", "notification_clear_by_package")
            put("status", "ok")
            put("package", pkg)
            put("cleared", cleared)
            put("total", keys.size)
        }.toString()
    }

    // ── Notification field extraction ─────────────────────────

    /** Compact projection used by notification_list. */
    private fun summarize(sbn: StatusBarNotification) = buildJsonObject {
        put("key", sbn.key)
        put("id", sbn.id)
        put("package_name", sbn.packageName)
        put("post_time", sbn.postTime)
        put("post_time_iso", isoTime(sbn.postTime))
        val n = sbn.notification
        if (n != null) {
            put("title", titleOf(n))
            put("text", textOf(n))
            put("category", n.category)
            put("group_key", sbn.groupKey)
        }
    }

    private fun titleOf(n: Notification): String? =
        n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun textOf(n: Notification): String? =
        n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun isoTime(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(epochMillis))

    // ── Connection / guidance ─────────────────────────────────

    /**
     * Checks whether the user has granted notification-listener access to
     * [LxNotificationListenerService] by parsing the system secure setting. Used only to make the
     * guidance message accurate (granted-but-disconnected vs not-granted-at-all).
     */
    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners")
            ?: return false
        val target = ComponentName(app, LxNotificationListenerService::class.java)
        for (part in flat.split(":")) {
            val cn = ComponentName.unflattenFromString(part) ?: continue
            if (cn == target) return true
        }
        return false
    }

    private fun notConnectedHint(): String {
        val granted = isListenerEnabled()
        val msg = if (granted) {
            "Notification listener is granted but not connected yet. Wait a moment or restart the app."
        } else {
            "Notification listener is not enabled. Open Settings > Notifications > Notification access " +
                "and enable LxChat (LxNotificationListenerService), then retry."
        }
        return err("listener_not_connected", msg)
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun risk(name: String): RiskLevel = when (name) {
        "notification_clear", "notification_clear_all", "notification_clear_by_package" ->
            RiskLevel.HighRisk
        else -> RiskLevel.ReadOnly
    }

    private fun tier(name: String): ToolTier = when (name) {
        "notification_list", "notification_get" -> ToolTier.Extended
        else -> ToolTier.Dangerous
    }

    private fun err(code: String, message: String?): String = toolError("notification_error", code, message)
}