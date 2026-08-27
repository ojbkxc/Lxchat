package com.lxseek.chat.tool

import android.app.ActivityManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Point
import android.hardware.camera2.CameraManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.StatFs
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.viewmodel.GenerationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * On-device status & control tools ported from android-mcp-bridge's default tool suite.
 *
 * These fill the gaps LxChat already did not cover (battery, clipboard, device/screen info,
 * storage/memory, network/wifi, sensors, installed apps, volume, flashlight, notification).
 * They are permission-light: the manifest already declares VIBRATE / POST_NOTIFICATIONS /
 * CAMERA / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE, and every permission-dependent call
 * degrades to a structured error (never crashes) when the grant is missing.
 */
class DeviceToolProvider(private val app: Application) : ToolProvider {

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> =
        definitions(ctx).map { def ->
            val name = def.function.name
            ToolDescriptor(
                definition = def,
                riskLevel = risk(name),
                tier = tier(name),
                requiresApproval = name == "device_flashlight" || name == "device_notify",
            )
        }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun prop(t: String, d: String) = ToolProperty(t, d)
        return listOf(
            tool(
                "device_info",
                "Return basic device identity: manufacturer, model, Android version, SDK, screen resolution/density. Prefer this over guessing from the OS.",
                emptyMap(), emptyList(),
            ),
            tool(
                "device_battery",
                "Return battery level (0-100), charging state, and temperature for the current device.",
                emptyMap(), emptyList(),
            ),
            tool(
                "device_time",
                "Return the current wall-clock time, date, and local timezone on the device.",
                emptyMap(), emptyList(),
            ),
            tool(
                "device_screen",
                "Return screen size, pixel resolution, pixel density, refresh rate, and orientation of the device display.",
                emptyMap(), emptyList(),
            ),
            tool(
                "device_storage",
                "Return filesystem capacity and free bytes for the device's data partition and app-external storage.",
                emptyMap(), emptyList(),
            ),
            tool(
                "device_memory",
                "Return total and available system RAM on the device.",
                emptyMap(), emptyList(),
            ),
            tool(
                "device_network",
                "Return the active network type (WiFi/cellular/ethernet/none) and raw connectivity state.",
                emptyMap(), emptyList(),
            ),
            tool(
                "device_wifi",
                "Return WiFi link info: RSSI, link speed (Mbps), and band frequency. Does not reveal the SSID (that needs a location grant).",
                emptyMap(), emptyList(),
            ),
            tool(
                "device_sensors",
                "List the sensors available on this device (accelerometer, gyroscope, etc.) by name, type, and vendor.",
                emptyMap(), emptyList(),
            ),
            tool(
                "device_read_sensor",
                "Read one sensor's latest values. Specify a sensor from device_sensors (e.g. 'accelerometer'). Defaults to accelerometer if omitted.",
                mapOf("sensor" to prop("string", "Sensor name substring (e.g. 'accelerometer') to read.")),
                emptyList(),
            ),
            tool(
                "device_apps",
                "List installed applications. Pass an optional 'filter' to narrow by package/label substring. Returns up to 100 matches plus the total count.",
                mapOf("filter" to prop("string", "Optional substring of package id or app name to filter by.")),
                emptyList(),
            ),
            tool(
                "device_app_info",
                "Return detail for one installed package: version, label, uid, targetSdk, install location, and category.",
                mapOf("package" to prop("string", "The Android package id, e.g. com.tencent.mm.")),
                listOf("package"),
            ),
            tool(
                "device_clipboard_read",
                "Return the current clipboard text of the device.",
                emptyMap(), emptyList(),
            ),
            tool(
                "device_clipboard_write",
                "Write text to the device clipboard (overwrites the current clipboard content).",
                mapOf("text" to prop("string", "The text to place on the clipboard.")),
                listOf("text"),
            ),
            tool(
                "device_vibrate",
                "Vibrate the device for a short moment (about 300ms).",
                emptyMap(), emptyList(),
            ),
            tool(
                "device_volume",
                "Return the current and maximum audio volume for the music/media stream.",
                emptyMap(), emptyList(),
            ),
            tool(
                "device_set_volume",
                "Set the music/media stream volume to an absolute level between 0 and the stream maximum.",
                mapOf("level" to prop("integer", "Target volume level (0..max). Use device_volume to read max.")),
                listOf("level"),
            ),
            tool(
                "device_flashlight",
                "Toggle the camera flashlight on or off. Requires the camera permission to be granted.",
                mapOf("on" to prop("boolean", "true to turn on, false (default) to turn off.")),
                emptyList(),
            ),
            tool(
                "device_notify",
                "Show a system notification to the user with the given title and body. Requires notification permission.",
                mapOf(
                    "title" to prop("string", "Notification title."),
                    "body" to prop("string", "Notification body text."),
                ),
                listOf("title", "body"),
            ),
        )
    }

    override fun handles(name: String): Boolean = name.startsWith("device_")

    override fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "device_info" -> infoJson()
                "device_battery" -> batteryJson()
                "device_time" -> timeJson()
                "device_screen" -> screenJson()
                "device_storage" -> storageJson()
                "device_memory" -> memoryJson()
                "device_network" -> networkJson()
                "device_wifi" -> wifiJson()
                "device_sensors" -> sensorsJson()
                "device_read_sensor" -> readSensor(arguments)
                "device_apps" -> appsJson(arguments)
                "device_app_info" -> appInfo(arguments)
                "device_clipboard_read" -> clipboardRead()
                "device_clipboard_write" -> clipboardWrite(arguments)
                "device_vibrate" -> vibrate()
                "device_volume" -> volumeJson()
                "device_set_volume" -> setVolume(arguments)
                "device_flashlight" -> flashlight(arguments)
                "device_notify" -> notify(arguments)
                else -> err("unknown_tool", "Unknown device tool: $name")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("DeviceTool", "device_$name failed", e)
            err("tool_error", e.message)
        }
    }

    // ── Read-only status tools ─────────────────────────────────

    private fun infoJson(): String = buildJsonObject {
        put("type", "device_info")
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("androidVersion", Build.VERSION.RELEASE)
        put("sdk", Build.VERSION.SDK_INT)
        put("hardware", Build.HARDWARE)
        val dm = app.resources.displayMetrics
        put("screen", "${dm.widthPixels}x${dm.heightPixels}")
        put("densityDpi", dm.densityDpi)
    }.toString()

    @Suppress("DEPRECATION")
    private fun batteryJson(): String = buildJsonObject {
        put("type", "device_battery")
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val sticky = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        put("level", if (scale > 0) (level * 100 / scale) else level)
        put("status", sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            ?.let { when (it) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                else -> "unknown"
            } })
        sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let {
            val plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            put("plugged", when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> if (it == BatteryManager.BATTERY_STATUS_CHARGING) "charging" else "unplugged"
            })
        }
        sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.let { put("temperatureCelsius", it / 10.0) }
    }.toString()

    private fun timeJson(): String = buildJsonObject {
        put("type", "device_time")
        put("epochMillis", System.currentTimeMillis())
        val now = Date()
        put("iso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(now))
        put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now))
        put("time", SimpleDateFormat("HH:mm:ss", Locale.US).format(now))
        put("timezone", TimeZone.getDefault().id)
        put("timezoneOffsetHours", TimeZone.getDefault().getOffset(now.time) / 3600000.0)
    }.toString()

    private fun screenJson(): String = buildJsonObject {
        put("type", "device_screen")
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = app.resources.displayMetrics
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            put("widthPx", bounds.width())
            put("heightPx", bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val size = Point().also { wm.defaultDisplay.getRealSize(it) }
            put("widthPx", size.x)
            put("heightPx", size.y)
        }
        put("densityDpi", dm.densityDpi)
        put("density", dm.density)
        put("scaledDensity", dm.scaledDensity)
        @Suppress("DEPRECATION")
        val refresher: Int = wm.defaultDisplay.mode?.refreshRate?.let { it.toInt() } ?: 0
        put("refreshRateHz", refresher)
        @Suppress("DEPRECATION")
        put("rotationDegrees", wm.defaultDisplay.rotation * 90)
    }.toString()

    private fun storageJson(): String = buildJsonObject {
        put("type", "device_storage")
        val data = stat(Environment.getDataDirectory())
        data?.let {
            put("dataTotalBytes", it.totalBytes)
            put("dataAvailableBytes", it.availableBytes)
            put("dataUsedPercent", if (it.totalBytes > 0) it.usedPercent() else 0)
        }
        val ext = app.getExternalFilesDir(null)
        val extStat = ext?.let { stat(it) }
        extStat?.let {
            put("appExternalTotalBytes", it.totalBytes)
            put("appExternalAvailableBytes", it.availableBytes)
        }
        put("appExternalPath", ext?.absolutePath)
    }.toString()

    private fun memoryJson(): String = buildJsonObject {
        put("type", "device_memory")
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        put("totalRamBytes", mi.totalMem)
        put("availableRamBytes", mi.availMem)
        put("lowMemory", mi.lowMemory)
        put("availPercent", if (mi.totalMem > 0) mi.availMem * 100 / mi.totalMem else 0)
    }.toString()

    private fun networkJson(): String = buildJsonObject {
        put("type", "device_network")
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val active = cm.activeNetworkInfo
        val connected = active?.isConnected == true || cm.activeNetwork != null
        put("connected", connected)
        val type = active?.type
        put("type", when (type) {
            ConnectivityManager.TYPE_WIFI -> "wifi"
            ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_MOBILE_HIPRI -> "cellular"
            ConnectivityManager.TYPE_ETHERNET -> "ethernet"
            else -> null
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (caps != null) {
                put("transport", when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                })
                put("metered", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED).not())
                put("validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
        }
        put("subtype", active?.subtypeName)
    }.toString()

    private fun wifiJson(): String = buildJsonObject {
        put("type", "device_wifi")
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = try { wm.connectionInfo } catch (_: SecurityException) { null }
        if (info == null) {
            put("available", false)
            put("hint", "WiFi info unavailable (permission or WiFi off).")
        } else {
            put("available", true)
            put("rssiDbm", info.rssi)
            put("linkSpeedMbps", info.linkSpeed)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) put("frequencyMhz", info.frequency)
        }
    }.toString()

    private fun sensorsJson(): String = buildJsonObject {
        put("type", "device_sensors")
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        put("sensors", buildJsonArray {
            sm.getSensorList(Sensor.TYPE_ALL).forEachIndexed { idx, s ->
                add(buildJsonObject {
                    put("index", idx)
                    put("name", s.name)
                    put("vendor", s.vendor)
                    put("type", s.type)
                    put("version", s.version)
                    put("powerMA", s.power)
                    put("resolution", s.resolution)
                    put("range", s.maximumRange)
                })
            }
        })
    }.toString()

    private fun readSensor(arguments: String): String {
        val query = argString("sensor", arguments)?.takeIf { it.isNotBlank() } ?: "accelerometer"
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getSensorList(Sensor.TYPE_ALL)
            .firstOrNull { it.name.lowercase().contains(query.lowercase()) }
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return err("no_sensor", "No sensor matching '$query' found")
        return try {
            val latch = CountDownLatch(1)
            val thread = HandlerThread("lxchat-sensor-read").also { it.start() }
            val handler = Handler(thread.looper)
            val valuesHolder = java.util.concurrent.atomic.AtomicReference(FloatArray(0))
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    valuesHolder.set(event.values.clone())
                    latch.countDown()
                }
                override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
            val got = latch.await(3, TimeUnit.SECONDS)
            sm.unregisterListener(listener)
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
            if (!got) return err("timeout", "No reading from '${sensor.name}' within 3s")
            buildJsonObject {
                put("type", "device_read_sensor")
                put("sensor", sensor.name)
                put("values", buildJsonArray { valuesHolder.get().forEach { add(it) } })
            }.toString()
        } catch (e: SecurityException) {
            err("permission_denied", e.message)
        }
    }

    private fun appsJson(arguments: String): String {
        val filter = argString("filter", arguments)?.trim().orEmpty()
        val pm = app.packageManager
        val all = pm.getInstalledApplications(0)
        val needle = filter.lowercase()
        val matched = if (needle.isEmpty()) all
            else all.filter {
                it.packageName.lowercase().contains(needle) ||
                    pm.getApplicationLabel(it).toString().lowercase().contains(needle)
            }
        return buildJsonObject {
            put("type", "device_apps")
            put("total", matched.size)
            put("shown", minOf(matched.size, 100))
            put("apps", buildJsonArray {
                matched.sortedBy { pm.getApplicationLabel(it).toString().lowercase() }.take(100)
                    .forEach { ai ->
                        add(buildJsonObject {
                            put("package", ai.packageName)
                            put("label", pm.getApplicationLabel(ai).toString())
                            put("system", (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                            put("enabled", pm.getApplicationEnabledSetting(ai.packageName) !=
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                        })
                    }
            })
        }.toString()
    }

    private fun appInfo(arguments: String): String {
        val pkg = argString("package", arguments)?.trim()
            ?: return err("no_package", "Missing package.")
        val pm = app.packageManager
        val ai = try { pm.getApplicationInfo(pkg, 0) }
            catch (e: PackageManager.NameNotFoundException) {
                return err("not_found", "Unknown package '$pkg'")
            }
        return buildJsonObject {
            put("type", "device_app_info")
            put("package", ai.packageName)
            put("label", pm.getApplicationLabel(ai).toString())
            put("uid", ai.uid)
            put("targetSdk", ai.targetSdkVersion)
            put("installLocation", ai.installLocation)
            put("sourceDir", ai.sourceDir)
            put("dataDir", ai.dataDir)
            put("category", ai.category)
            put("system", (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
            put("enabled", pm.getApplicationEnabledSetting(ai.packageName) !=
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            val vi = try { pm.getPackageInfo(pkg, 0) } catch (_: Exception) { null }
            vi?.versionName?.let { put("versionName", it) }
            vi?.longVersionCode?.let { put("versionCode", it) }
        }.toString()
    }

    // ── Control tools ─────────────────────────────────────────

    @Suppress("DEPRECATION")
    private val clipboard: ClipboardManager
        get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun clipboardRead(): String {
        val clip = try { clipboard.primaryClip } catch (_: SecurityException) { null }
        val text = clip?.getItemAt(0)?.coerceToText(app)?.toString().orEmpty()
        return buildJsonObject {
            put("type", "device_clipboard_read")
            put("hasText", text.isNotEmpty())
            put("text", text)
        }.toString()
    }

    private fun clipboardWrite(arguments: String): String {
        val text = argString("text", arguments)
            ?: return err("no_text", "Missing text.")
        clipboard.setPrimaryClip(ClipData.newPlainText("lxchat", text))
        return buildJsonObject { put("type", "device_clipboard_write"); put("status", "ok") }.toString()
    }

    private fun vibrate(): String {
        val vib = app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!vib.hasVibrator()) return err("no_vibrator", "Device has no vibrator.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(android.os.VibrationEffect.createOneShot(300, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vib.vibrate(300)
        }
        return buildJsonObject { put("type", "device_vibrate"); put("status", "ok") }.toString()
    }

    private fun volumeJson(): String = buildJsonObject {
        put("type", "device_volume")
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        put("stream", "music")
        put("level", am.getStreamVolume(AudioManager.STREAM_MUSIC))
        put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }.toString()

    private fun setVolume(arguments: String): String {
        val level = argInt("level", arguments)
            ?: return err("invalid_level", "Missing level.")
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val clamped = level.coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        return buildJsonObject {
            put("type", "device_set_volume")
            put("status", "ok")
            put("level", clamped)
            put("max", max)
        }.toString()
    }

    private fun flashlight(arguments: String): String {
        val on = argString("on", arguments)?.toBooleanStrictOrNull() ?: false
        return try {
            val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull()
                ?: return err("no_camera", "No camera/torch available.")
            cm.setTorchMode(id, on)
            buildJsonObject {
                put("type", "device_flashlight")
                put("status", "ok")
                put("on", on)
            }.toString()
        } catch (e: SecurityException) {
            err("permission_denied", "Camera permission not granted: ${e.message}")
        } catch (e: Exception) {
            err("torch_error", e.message)
        }
    }

    private fun notify(arguments: String): String {
        val title = argString("title", arguments) ?: "Notification"
        val body = argString("body", arguments).orEmpty()
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return err("permission_denied", "Notification permission not granted.")
        }
        val channelId = "device_tool"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Device Tools", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "Notifications shown by the device_* agent tools"
            nm.createNotificationChannel(ch)
        }
        val nid = "device_tool".hashCode() and 0xffff
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(app, channelId)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(app).setChannelId(channelId)
        }
        builder
            .setSmallIcon(app.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
        nm.notify(nid, builder.build())
        return buildJsonObject { put("type", "device_notify"); put("status", "ok") }.toString()
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, ToolProperty>,
        required: List<String>,
    ) = ToolDefinition(function = ToolFunction(
        name = name,
        description = description,
        parameters = ToolParameters(properties = properties, required = required),
    ))

    private fun risk(name: String): RiskLevel = when (name) {
        "device_clipboard_write", "device_vibrate", "device_set_volume", "device_flashlight", "device_notify" ->
            RiskLevel.Moderate
        else -> RiskLevel.ReadOnly
    }

    private fun tier(name: String): ToolTier = when (name) {
        "device_info", "device_battery", "device_time", "device_screen", "device_storage",
        "device_memory", "device_network", "device_wifi", "device_sensors", "device_read_sensor",
        "device_apps", "device_app_info", "device_clipboard_read", "device_volume" ->
            ToolTier.Extended
        else -> ToolTier.Dangerous
    }

    private class StatSnapshot(val totalBytes: Long, val availableBytes: Long) {
        fun usedPercent(): Long = (totalBytes - availableBytes) * 100 / totalBytes
    }

    private fun stat(dir: java.io.File): StatSnapshot? = try {
        val st = StatFs(dir.absolutePath)
        StatSnapshot(
            totalBytes = st.blockCountLong * st.blockSizeLong,
            availableBytes = st.availableBlocksLong * st.blockSizeLong,
        )
    } catch (_: Exception) {
        null
    }

    private fun argString(key: String, arguments: String): String? {
        val stripped = arguments.ifBlank { "{}" }
        return try {
            val el = Json.decodeFromString<Map<String, JsonPrimitive>>(stripped)[key]
            val v = el?.content ?: return null
            if (v == "null") null else v
        } catch (_: Exception) {
            null
        }
    }

    private fun argInt(key: String, arguments: String): Int? {
        val stripped = arguments.ifBlank { "{}" }
        return try {
            Json.decodeFromString<Map<String, JsonPrimitive>>(stripped)[key]?.content?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "device_error")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()
}