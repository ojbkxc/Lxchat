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
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ContentUris
import android.content.ContentValues
import android.media.MediaMetadata
import android.media.MediaRecorder
import android.media.session.MediaSessionManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
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
                requiresApproval = name == "device_flashlight" || name == "device_notify" ||
                    name == "device_call" || name == "device_sms" || name == "device_record" ||
                    name == "device_saf_delete",
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
            // ── P2/P3 system-extension tools ─────────────────────────────
            tool(
                "device_call",
                "Place a phone call to the given number. mode='dial' (default) just opens the dialer; " +
                    "mode='call' dials immediately and requires the CALL_PHONE permission.",
                mapOf(
                    "number" to prop("string", "Phone number to dial/call, e.g. '13800138000'."),
                    "mode" to prop("string", "'dial' (default) or 'call'."),
                ),
                listOf("number"),
            ),
            tool(
                "device_sms",
                "Send a text message to the given number. Requires the SEND_SMS permission. " +
                    "Logs are masked; body is never persisted.",
                mapOf(
                    "number" to prop("string", "Recipient phone number."),
                    "text" to prop("string", "SMS body text."),
                ),
                listOf("number", "text"),
            ),
            tool(
                "device_media_read",
                "Return a list of the most recent images on the device from the MediaStore (name, " +
                    "size, date, mime, content uri). Useful to later open or forward a photo.",
                mapOf("limit" to prop("integer", "How many recent images to return (1..100), default 20.")),
                emptyList(),
            ),
            tool(
                "device_media_save",
                "Save a local image file (given an absolute source path) into the device gallery " +
                    "under Pictures/LxChat. Requires storage write permission on Android 9 and below.",
                mapOf(
                    "sourcePath" to prop("string", "Absolute path of the image file to save."),
                    "title" to prop("string", "Optional title; defaults to the source file name."),
                ),
                listOf("sourcePath"),
            ),
            tool(
                "device_saf_list",
                "List the children of a Storage Access Framework (SAF) document/tree uri. Returns " +
                    "document id, display name, mime, size and isDirectory for each child.",
                mapOf("uri" to prop("string", "A SAF tree/document uri previously granted by the user.")),
                listOf("uri"),
            ),
            tool(
                "device_saf_copy",
                "Copy a SAF document to a target parent folder. Both uris must already be user-granted.",
                mapOf(
                    "source" to prop("string", "SAF document uri to copy."),
                    "targetParent" to prop("string", "SAF tree/document uri of the destination folder."),
                ),
                listOf("source", "targetParent"),
            ),
            tool(
                "device_saf_move",
                "Move a SAF document to a target parent folder. Both uris must already be user-granted.",
                mapOf(
                    "source" to prop("string", "SAF document uri to move."),
                    "sourceParent" to prop("string", "SAF uri of the current parent folder."),
                    "targetParent" to prop("string", "SAF tree/document uri of the destination folder."),
                ),
                listOf("source", "sourceParent", "targetParent"),
            ),
            tool(
                "device_saf_delete",
                "Delete a SAF document. The document uri must already be user-granted.",
                mapOf("uri" to prop("string", "SAF document uri to delete.")),
                listOf("uri"),
            ),
            tool(
                "device_radio",
                "Toggle airplane mode / wifi / bluetooth on this device. Run as best-effort: the OS " +
                    "often denies non-root apps (needs system permission), in which case a clear hint is " +
                    "returned. Hotspot is not supported and reports so.",
                mapOf(
                    "target" to prop("string", "One of 'airplane', 'wifi', 'bluetooth', 'hotspot'."),
                    "on" to prop("boolean", "true to enable, false to disable."),
                ),
                listOf("target"),
            ),
            tool(
                "device_brightness",
                "Set the device screen brightness. Requires the 'WRITE_SETTINGS' special access which "
                    + "the user grants once; when missing, opens the grant page and returns a hint.",
                mapOf("level" to prop("integer", "Brightness 0..255.")),
                listOf("level"),
            ),
            tool(
                "device_media",
                "Query or control the active media sessions, best-effort. 'list' returns which media " +
                    "sessions are active; 'pause'/'play' asks the active session to pause/play. Non-media " +
                    "apps usually only see their own sessions, so this frequently returns empty; use " +
                    "device_volume/device_set_volume for reliable media volume.",
                mapOf("action" to prop("string", "'list', 'pause' or 'play'.")),
                listOf("action"),
            ),
            tool(
                "device_record",
                "Record audio for the given duration and save an AAC file to the app cache. Requires " +
                    "the RECORD_AUDIO permission and the app to be in the foreground.",
                mapOf("durationSeconds" to prop("integer", "Seconds to record (1..60), default 10.")),
                emptyList(),
            ),
        )
    }

    override fun handles(name: String): Boolean = name.startsWith("device_")

    override suspend fun execute(
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
                "device_call" -> call(arguments)
                "device_sms" -> sms(arguments)
                "device_media_read" -> mediaRead(arguments)
                "device_media_save" -> mediaSave(arguments)
                "device_saf_list" -> safList(arguments)
                "device_saf_copy" -> safCopy(arguments)
                "device_saf_move" -> safMove(arguments)
                "device_saf_delete" -> safDelete(arguments)
                "device_radio" -> radio(arguments)
                "device_brightness" -> brightness(arguments)
                "device_media" -> media(arguments)
                "device_record" -> record(arguments)
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
                put("values", buildJsonArray { valuesHolder.get().forEach { add(JsonPrimitive(it.toDouble())) } })
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

    // ── P2/P3 system-extension tools ──────────────────────────

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private fun call(arguments: String): String {
        val number = argString("number", arguments)?.trim()
            ?: return err("no_number", "Missing number.")
        val mode = argString("mode", arguments)?.trim()?.lowercase() ?: "dial"
        val action = if (mode == "call") Intent.ACTION_CALL else Intent.ACTION_DIAL
        if (action == Intent.ACTION_CALL && !hasPermission(Manifest.permission.CALL_PHONE)) {
            return err("permission_denied", "Calling directly needs the CALL_PHONE permission; use mode='dial' instead.")
        }
        return try {
            val intent = Intent(action, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            buildJsonObject {
                put("type", "device_call")
                put("status", if (action == Intent.ACTION_CALL) "calling" else "dialer_opened")
                put("mode", if (action == Intent.ACTION_CALL) "call" else "dial")
            }.toString()
        } catch (e: Exception) {
            err("launch_failed", "Could not launch dialer/call: ${e.message}")
        }
    }

    private fun sms(arguments: String): String {
        val number = argString("number", arguments)?.trim()
            ?: return err("no_number", "Missing number.")
        val text = argString("text", arguments)
            ?: return err("no_text", "Missing text.")
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            return err("permission_denied", "Sending SMS needs the SEND_SMS permission.")
        }
        return try {
            SmsManager.getDefault().sendTextMessage(number, null, text, null, null)
            buildJsonObject { put("type", "device_sms"); put("status", "sent") }.toString()
        } catch (e: SecurityException) {
            err("permission_denied", "SEND_SMS permission missing or revoked: ${e.message}")
        } catch (e: Exception) {
            err("sms_failed", e.message)
        }
    }

    @Suppress("DEPRECATION")
    private fun mediaReadPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun mediaRead(arguments: String): String {
        val limit = (argInt("limit", arguments) ?: 20).coerceIn(1, 100)
        val perm = mediaReadPermission()
        if (!hasPermission(perm)) {
            return err("permission_denied", "Reading gallery needs the $perm permission.")
        }
        val resolver = app.contentResolver
        val uri = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION") MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
        )
        return buildJsonObject {
            put("type", "device_media_read")
            resolver.query(uri, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                put("count", minOf(c.count, limit))
                put("images", buildJsonArray {
                    var taken = 0
                    while (c.moveToNext() && taken < limit) {
                        taken++
                        val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        add(buildJsonObject {
                            put("name", c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)))
                            put("sizeBytes", c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)))
                            put("dateAdded", c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)))
                            c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE))?.let { put("mime", it) }
                            put("uri", ContentUris.withAppendedId(uri, id).toString())
                        })
                    }
                })
            } ?: put("count", 0)
        }.toString()
    }

    private fun mediaSave(arguments: String): String {
        val sourcePath = argString("sourcePath", arguments)?.trim()
            ?: return err("no_source", "Missing sourcePath.")
        val title = argString("title", arguments)?.trim() ?: java.io.File(sourcePath).name
        val src = java.io.File(sourcePath)
        if (!src.exists() || !src.isFile) return err("not_found", "Source file not found: $sourcePath")
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    val name = title.ifBlank { src.name }
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/*")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LxChat")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = app.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return err("insert_failed", "MediaStore insert failed.")
                resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                buildJsonObject { put("type", "device_media_save"); put("status", "saved"); put("uri", uri.toString()) }.toString()
            } else {
                @Suppress("DEPRECATION")
                val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LxChat")
                if (!dir.exists()) dir.mkdirs()
                val dest = java.io.File(dir, title.ifBlank { src.name })
                src.copyTo(dest, overwrite = true)
                buildJsonObject { put("type", "device_media_save"); put("status", "saved"); put("path", dest.absolutePath) }.toString()
            }
        } catch (e: SecurityException) {
            err("permission_denied", "Storage write not granted: ${e.message}")
        } catch (e: Exception) {
            err("save_failed", e.message)
        }
    }

    // ── SAF file management helpers ───────────────────────────

    private fun safChildrenUri(uri: Uri): Uri? = when {
        DocumentsContract.isTreeUri(uri) ->
            try {
                DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            } catch (_: Exception) { null }
        DocumentsContract.isDocumentUri(app, uri) ->
            try {
                DocumentsContract.buildChildDocumentsUri(uri.authority, DocumentsContract.getDocumentId(uri))
            } catch (_: Exception) { null }
        else -> null
    }

    private fun safQueryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        return resolver.query(uri, cols, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
        }
    }

    private fun safMime(resolver: android.content.ContentResolver, uri: Uri): String? {
        val cols = arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE)
        return resolver.query(uri, cols, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
        }
    }

    private fun safList(arguments: String): String {
        val uriStr = argString("uri", arguments)?.trim()
            ?: return err("no_uri", "Missing uri.")
        val uri = Uri.parse(uriStr)
        val resolver = app.contentResolver
        val childrenUri = safChildrenUri(uri)
            ?: return err("bad_uri", "uri must be a granted SAF tree or document uri with a grants provider.")
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return try {
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                buildJsonObject {
                    put("type", "device_saf_list")
                    put("count", c.count)
                    put("children", buildJsonArray {
                        while (c.moveToNext()) {
                            val docId = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                            val mime = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
                            add(buildJsonObject {
                                put("documentId", docId)
                                put("name", c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)))
                                put("mime", mime)
                                put("isDirectory", mime == DocumentsContract.Document.MIME_TYPE_DIR)
                                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                                if (sizeIdx >= 0) put("sizeBytes", c.getLong(sizeIdx))
                            })
                        }
                    })
                }.toString()
            } ?: err("empty", "No children (empty folder or not granted).")
        } catch (e: SecurityException) {
            err("permission_denied", "SAF uri not user-granted: ${e.message}")
        } catch (e: java.io.FileNotFoundException) {
            err("not_granted", "SAF uri not granted or invalid: ${e.message}")
        }
    }

    private fun copyBytes(from: android.net.Uri?, to: android.net.Uri?): Boolean {
        if (from == null || to == null) return false
        val resolver = app.contentResolver
        return try {
            resolver.openInputStream(from)?.use { input ->
                resolver.openOutputStream(to, "w")?.use { output -> input.copyTo(output) }
            } != null
        } catch (_: Exception) {
            false
        }
    }

    private fun safCopy(arguments: String): String {
        val source = argString("source", arguments)?.trim()?.let { Uri.parse(it) }
            ?: return err("no_source", "Missing source.")
        val targetParent = argString("targetParent", arguments)?.trim()?.let { Uri.parse(it) }
            ?: return err("no_target", "Missing targetParent.")
        val resolver = app.contentResolver
        return try {
            val name = safQueryDisplayName(resolver, source) ?: "copy_${System.currentTimeMillis()}"
            val mime = safMime(resolver, source) ?: DocumentsContract.Document.MIME_TYPE_DIR
            val dest = DocumentsContract.createDocument(resolver, targetParent, mime, name)
                ?: return err("create_failed", "Could not create destination document.")
            if (!copyBytes(source, dest)) return err("copy_failed", "Copy failed.")
            buildJsonObject { put("type", "device_saf_copy"); put("status", "ok"); put("destination", dest.toString()) }.toString()
        } catch (e: SecurityException) {
            err("permission_denied", e.message)
        } catch (e: Exception) {
            err("copy_failed", e.message)
        }
    }

    private fun safMove(arguments: String): String {
        val source = argString("source", arguments)?.trim()?.let { Uri.parse(it) }
            ?: return err("no_source", "Missing source.")
        val sourceParent = argString("sourceParent", arguments)?.trim()?.let { Uri.parse(it) }
            ?: source
        val targetParent = argString("targetParent", arguments)?.trim()?.let { Uri.parse(it) }
            ?: return err("no_target", "Missing targetParent.")
        val resolver = app.contentResolver
        return try {
            // Prefer the platform moveDocument (API 24+), fall back to copy + delete.
            val moved = if (Build.VERSION.SDK_INT >= 24) {
                try {
                    DocumentsContract.moveDocument(resolver, source, sourceParent, targetParent) != null
                } catch (_: Exception) {
                    false
                }
            } else false
            val ok = if (moved) true else {
                val name = safQueryDisplayName(resolver, source) ?: "moved_${System.currentTimeMillis()}"
                val mime = safMime(resolver, source) ?: DocumentsContract.Document.MIME_TYPE_DIR
                val dest = DocumentsContract.createDocument(resolver, targetParent, mime, name)
                dest != null && copyBytes(source, dest) && deleteSaf(source)
            }
            if (!ok) return err("move_failed", "Move failed.")
            buildJsonObject { put("type", "device_saf_move"); put("status", "ok") }.toString()
        } catch (e: SecurityException) {
            err("permission_denied", e.message)
        } catch (e: Exception) {
            err("move_failed", e.message)
        }
    }

    private fun deleteSaf(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(app.contentResolver, uri)
    } catch (_: Exception) {
        false
    }

    private fun safDelete(arguments: String): String {
        val uri = argString("uri", arguments)?.trim()?.let { Uri.parse(it) }
            ?: return err("no_uri", "Missing uri.")
        return try {
            if (!deleteSaf(uri)) return err("delete_failed", "Delete failed (uri may not be granted).")
            buildJsonObject { put("type", "device_saf_delete"); put("status", "ok") }.toString()
        } catch (e: SecurityException) {
            err("permission_denied", e.message)
        } catch (e: Exception) {
            err("delete_failed", e.message)
        }
    }

    // ── Radio / brightness / media / record ───────────────────

    @Suppress("DEPRECATION")
    private fun radio(arguments: String): String {
        val target = argString("target", arguments)?.trim()?.lowercase()
            ?: return err("no_target", "Missing target.")
        val on = argString("on", arguments)?.toBooleanStrictOrNull() ?: false
        return when (target) {
            "wifi" -> {
                val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (!hasPermission(Manifest.permission.CHANGE_WIFI_STATE) && Build.VERSION.SDK_INT >= 29) {
                    err("permission_denied", "WiFi toggling needs CHANGE_WIFI_STATE.")
                } else {
                    try {
                        wm.isWifiEnabled = on
                        buildJsonObject { put("type", "device_radio"); put("target", "wifi"); put("status", "ok"); put("on", on) }.toString()
                    } catch (e: Exception) {
                        err("radio_denied", "WiFi toggle rejected by OS: ${e.message}. Open a deep link instead.")
                    }
                }
            }
            "bluetooth" -> {
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT) ||
                    (Build.VERSION.SDK_INT < 33 && !hasPermission(Manifest.permission.BLUETOOTH_ADMIN))) {
                    err("permission_denied", "Bluetooth toggling needs the necessary bluetooth permissions.")
                } else {
                    val adapter = try { BluetoothAdapter.getDefaultAdapter() } catch (_: SecurityException) { null }
                    if (adapter == null) err("no_bluetooth", "No bluetooth adapter.")
                    else try {
                        if (on) adapter.enable() else adapter.disable()
                        buildJsonObject { put("type", "device_radio"); put("target", "bluetooth"); put("status", "ok"); put("on", on) }.toString()
                    } catch (e: Exception) {
                        err("radio_denied", "Bluetooth toggle rejected: ${e.message}")
                    }
                }
            }
            "airplane" -> {
                if (!Settings.System.canWrite(app)) {
                    err("requires_write_settings", "Airplane mode toggle needs WRITE_SETTINGS special access.")
                } else {
                    try {
                        Settings.System.putInt(app.contentResolver, Settings.System.AIRPLANE_MODE_ON, if (on) 1 else 0)
                        val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", on)
                        app.sendBroadcast(intent)
                        buildJsonObject { put("type", "device_radio"); put("target", "airplane"); put("status", "ok"); put("on", on) }.toString()
                    } catch (e: Exception) {
                        err("radio_denied", e.message)
                    }
                }
            }
            "hotspot" -> err("not_supported", "Hotspot toggle is not supported (requires system privileges).")
            else -> err("bad_target", "target must be airplane|wifi|bluetooth|hotspot.")
        }
    }

    private fun brightness(arguments: String): String {
        val level = argInt("level", arguments)
            ?: return err("invalid_level", "Missing level.")
        if (!Settings.System.canWrite(app)) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${app.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(intent)
            } catch (e: Exception) { Log.d(\"DeviceTool\", \"operation failed\", e) }
            return err("requires_write_settings", "Screen brightness needs WRITE_SETTINGS; grant page opened.")
        }
        return try {
            val resolver = app.contentResolver
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(0, 255))
            buildJsonObject { put("type", "device_brightness"); put("status", "ok"); put("level", level.coerceIn(0, 255)) }.toString()
        } catch (e: Exception) {
            err("brightness_failed", e.message)
        }
    }

    @Suppress("DEPRECATION")
    private fun media(arguments: String): String {
        val action = argString("action", arguments)?.trim()?.lowercase() ?: "list"
        val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        return try {
            val controllers = msm.getActiveSessions(null) ?: emptyList()
            val sessions = controllers.map { c ->
                buildJsonObject {
                    put("package", c.packageName)
                    c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.let { put("title", it) }
                    c.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.let { put("mediaId", it) }
                }
            }
            val target = controllers.firstOrNull()
            when (action) {
                "play" -> target?.transportControls?.play()
                "pause" -> target?.transportControls?.pause()
            }
            buildJsonObject {
                put("type", "device_media")
                put("action", action)
                put("sessions", buildJsonArray { sessions.forEach { add(it) } })
                put("note", "Only your own sessions are visible without a notification-listener grant.")
            }.toString()
        } catch (e: SecurityException) {
            err("permission_denied", "Media sessions need notification-listener access on Android 11+: ${e.message}")
        } catch (e: Exception) {
            err("media_failed", e.message)
        }
    }

    private fun record(arguments: String): String {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            return err("permission_denied", "Recording needs the RECORD_AUDIO permission.")
        }
        val duration = (argInt("durationSeconds", arguments) ?: 10).coerceIn(1, 60)
        val out = java.io.File(app.cacheDir, "record_${System.currentTimeMillis()}.aac")
        return try {
            val recorder = MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setOutputFile(out.absolutePath)
            recorder.prepare()
            recorder.start()
            Thread.sleep(duration * 1000L)
            recorder.stop()
            recorder.reset()
            recorder.release()
            buildJsonObject {
                put("type", "device_record")
                put("status", "ok")
                put("durationSeconds", duration)
                put("path", out.absolutePath)
                put("sizeBytes", if (out.exists()) out.length() else 0)
            }.toString()
        } catch (e: SecurityException) {
            err("permission_denied", e.message)
        } catch (e: Exception) {
            err("record_failed", e.message)
        }
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
        "device_clipboard_write", "device_vibrate", "device_set_volume", "device_flashlight", "device_notify",
        "device_call", "device_sms", "device_media_save", "device_saf_delete",
        "device_radio", "device_brightness", "device_media", "device_record" ->
            RiskLevel.Moderate
        else -> RiskLevel.ReadOnly
    }

    private fun tier(name: String): ToolTier = when (name) {
        "device_info", "device_battery", "device_time", "device_screen", "device_storage",
        "device_memory", "device_network", "device_wifi", "device_sensors", "device_read_sensor",
        "device_apps", "device_app_info", "device_clipboard_read", "device_volume", "device_media_read",
        "device_saf_list" ->
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