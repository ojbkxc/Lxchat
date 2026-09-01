package com.lxseek.chat.tool

import com.lxseek.chat.adb.RootDetector
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 应用管理工具集（root-only）。
 *
 * 提供面向 AI 的按应用粒度的管理能力：应用状态查询、冻结/解冻、强制停止、
 * 清数据/清缓存、卸载/debloat、组件禁用/启用、AppOps 查询与设置、运行时权限
 * 授予/撤销、联网与后台限制、dexopt 编译优化。
 *
 * 底层全部复用系统自带的 pm/am/cmd 命令，通过 `su -c` 按需执行：无常驻服务、
 * 不移植第三方代码，仅在 root 可用时披露给模型。工具命名统一使用 app_* 语义。
 */
class AppManageToolProvider : ToolProvider {

    private companion object {
        const val MAX_OUTPUT = 6000
        const val TIMEOUT_DEFAULT = 60000
        const val TIMEOUT_COMPILE = 295000

        val TOOL_NAMES = setOf(
            "app_list",
            "app_info",
            "app_freeze",
            "app_unfreeze",
            "app_force_stop",
            "app_clear_data",
            "app_trim_caches",
            "app_uninstall",
            "app_disable_component",
            "app_enable_component",
            "appops_get",
            "appops_set",
            "app_set_permission",
            "app_block_internet",
            "app_restrict_background",
            "app_dexopt",
        )

        val APP_OPS = setOf(
            "ACCESS_ICON", "ACTIVATE_PLATFORM_VPN", "ACTIVATE_VPN", "ADD_VOICEMAIL",
            "ANSWER_PHONE_CALLS", "API_COUNTER", "APP_BACKGROUND_LOCATION", "APPOP_WIFI_SCAN",
            "ASSIST_ASSISTANT", "ASSIST_SCREENSHOT", "AUDIO_ALARM_VOLUME", "AUDIO_ACCESSIBILITY_VOLUME",
            "AUDIO_BLUETOOTH_VOLUME", "AUDIO_MASTER_VOLUME", "AUDIO_MEDIA_VOLUME", "AUDIO_NOTIFICATION_VOLUME",
            "AUDIO_RING_VOLUME", "AUDIO_VOICE_CALL_VOLUME", "AUTOFILL", "AVRCP_CONTROL", "BIND_ACCESSIBILITY_SERVICE",
            "BIND_CARRIER_SERVICES", "BIND_CHROME_OS_ACCOUNT", "BIND_CLIPBOARD_SERVICE", "BIND_CONNECTION_SERVICE",
            "BIND_DEVICE_ADMIN", "BIND_INCALL_SERVICE", "BIND_PHONE_ACCOUNT", "BIND_SCREEN_READER", "BIND_TRUST_AGENT",
            "BIND_VOICE_INTERACTION", "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "BODY_SENSORS",
            "CALL_LOG_MICROPHONE", "CAMERA", "CAMERA_DISABLE_TRANSIENT_TERMINATION", "CAMERA_IN_USE_WHILE_SCREEN_OFF",
            "CAMERA_OBSERVER", "CHANGE_WIFI_STATE", "CHROME_OS_APP_WEB_ASSISTANT", "CLIPBOARD", "COARSE_LOCATION",
            "COARSE_WIFI_LOCATION", "CONTACTS_GOOGLE", "CONTACTS_PROVIDER", "CONTROL_SCREEN_LED", "CONTROL_SCREEN_SLEEP",
            "CONTROL_SYSTEM_UPDATE", "CRASH_HANDLER", "DATA_SAVER_MODE", "DELETE_ACCESSIBILITY_EVENTS", "DELETE_CACHE_FILES",
            "DELETE_CLIPBOARD", "DELETE_KEYLOGGER", "DELETE_PACKAGES", "DEVICE_IDLE_IGNORE", "DEVICE_IDLE_MODE",
            "DIAGNOSTIC_API", "DRAGNDROP", "DROPBOX", "DUMP", "ENROLL_BIOMETRIC", "ESTABLISH_VPN", "EXEMPT_FROM_AUTOREVOKE",
            "EXEMPT_FROM_AUTOREVOKE_BY_RESTRICTION", "EXEMPT_FROM_AUTOREVOKE_BY_STANDBY", "EXEMPT_FROM_AUTOREVOKE_BY_TIME",
            "FINE_LOCATION", "FOOTPRINT", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_CAMERA", "FOREGROUND_SERVICE_CONNECTED_DEVICE",
            "FOREGROUND_SERVICE_DATA_SYNC", "FOREGROUND_SERVICE_HEALTH", "FOREGROUND_SERVICE_LOCATION", "FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "FOREGROUND_SERVICE_MEDIA_PROJECTION", "FOREGROUND_SERVICE_MEDIA_PROCESSING", "FOREGROUND_SERVICE_PHONE_CALL",
            "FOREGROUND_SERVICE_REMOTE_MESSAGING", "FOREGROUND_SERVICE_SPECIAL_USE", "FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
            "GET_ACCOUNTS", "GET_USED_CODES", "GET_USED_CODES_RUNTIME", "GPS", "GROUP_INTERACTION", "GUN_ATTRIBUTION",
            "HANDLE_TRUSTED_ACTIVITIES", "HMAC", "HOME_APP_SHORTCUT", "HOME_CONTROL", "INTERACT_ACROSS_PROFILES", "INTERNET",
            "INVOKE_MAIN_SETTINGS", "KEYBOARD_BACKLIGHT", "KEY_CW_CCW_ROTATION", "KEY_ENTER", "KEY_FAST_FORWARD", "KEY_FFWD",
            "KEY_HEADSET_HOOK", "KEY_MEDIA_FAST_FORWARD", "KEY_MEDIA_NEXT", "KEY_MEDIA_PAUSE", "KEY_MEDIA_PLAY", "KEY_MEDIA_PLAY_PAUSE",
            "KEY_MEDIA_PREVIOUS", "KEY_MEDIA_REWIND", "KEY_MEDIA_STOP", "KEY_NEXT", "KEY_PAUSE", "KEY_PLAY", "KEY_PREVIOUS",
            "KEY_REWIND", "KEY_STOP", "LEGACY_STORAGE", "LOCATION", "LOCATION_ACCESS_CHECK", "LOCATION_BYPASS", "LOCATION_FUSION",
            "MANAGE_CREDENTIALS", "MANAGE_EXTERNAL_STORAGE", "MANAGE_IPSEC_TUNNELS", "MANAGE_MEDIA", "MANAGE_ONGOING_CALLS",
            "MANAGE_ONE_TIME_PERMISSION_SESSIONS", "MANAGE_SENSORS", "MANAGE_USER_ISOLATION", "MOCK_LOCATION", "MONITOR_DEVICE_LOCK_STATE",
            "MONITOR_HIGH_POWER_LOCATION", "MONITOR_LOCATION", "MONITOR_PHONE_STATE", "MUTE_MICROPHONE", "NEARBY_WIFI_DEVICES",
            "NETWORK_SECURITY_LOGGING", "NFC_CHANNEL", "NFC_DEBUG", "NFC_PREFERRED_PAYMENT", "NFC_SCAN", "NOTIFICATION_CLICK",
            "NOTIFICATION_COOLDOWN", "NOTIFICATION_MEDIA", "NOTIFICATION_RINGTONE", "NOTIFICATION_SERVICE", "NOTIFICATION_UPDATE_OP",
            "OEM_CAPTURE_DEBUG", "OEM_GPS", "OEM_RESTRICT_SMS", "OEM_UI_FOOTPRINT", "OVERRIDE_CAMERA_LENS", "OVERRIDE_COMPONENT_ENABLED_STATE",
            "OVERRIDE_VIBRATION_INTENSITY", "PACKAGE_ACCESS", "PACKAGE_ACCESS_BY_RESTRICTION", "PACKAGE_ACCESS_BY_STANDBY",
            "PACKAGE_ACCESS_BY_TIME", "PACKAGE_ACCESS_UPGRADE", "PHONE_CALL", "PHONE_CALL_MICROPHONE", "PHONE_CALL_SCREENING",
            "PHONE_STATE", "PICTURE_IN_PICTURE", "PLAY_AUDIO", "POST_NOTIFICATION", "PROCESS_ALERTS", "PROCESS_MEDIA",
            "PROCESS_PROVIDER", "QUIET_TIME_END", "QUIET_TIME_START", "QUIET_TIME", "READ_BASIC_PHONE_STATE", "READ_BLOCKED_NUMBER",
            "READ_BODY_SENSORS", "READ_CALL_LOG", "READ_CELL_BROADCASTS", "READ_CLIPBOARD", "READ_CONTACTS", "READ_DEVICE_CONFIG",
            "READ_EXTERNAL_STORAGE", "READ_HOME_APP_SHORTCUT", "READ_ICC_APDU", "READ_ICC_SMS", "READ_MEDIA_AUDIO", "READ_MEDIA_IMAGES",
            "READ_MEDIA_VIDEO", "READ_MEDIA_VISUAL_USER_SELECTED", "READ_NOTIFICATION_SOUND", "READ_OEM_UNLOCK_STATE", "READ_PHONE_NUMBERS",
            "READ_PHONE_STATE", "READ_PROJECTION_STATE", "READ_RUNTIME_PROFILES", "READ_SMS", "READ_SOUND", "READ_SYNC_SETTINGS",
            "READ_SYNC_STATS", "READ_VOICEMAIL", "READ_WIFI_CREDENTIAL", "READ_WIFI_NETWORK_INFO", "READ_WIFI_PASSCODE",
            "RECEIVE_DEVICE_ADMIN", "RECEIVE_DEVICE_ORIENTATION", "RECEIVE_EMERGENCY_BROADCAST", "RECEIVE_MEDIA", "RECEIVE_MMS",
            "RECEIVE_SMS", "RECEIVE_WAP_PUSH", "RECORD_AUDIO", "RECORD_AUDIO_HOTWORD", "RECORD_AUDIO_OWNERSHIP", "RUN_ANY_IN_BACKGROUND",
            "RUN_BACKGROUND_SHORT_SERVICES", "RUN_IN_BACKGROUND", "SCHEDULE_EXACT_ALARM", "SEND_RESPOND_VIA_MESSAGE", "SEND_SMS",
            "SENSOR_OTHER", "SENSOR_DEVICE_ATTRIBUTION", "SENSOR_SCREEN", "SET_ALARM", "SET_CLIPBOARD", "SET_WALLPAPER",
            "SIGNAL_STRENGTH", "SMS_FINANCIAL_TRANSACTIONS", "SMS_READ_CC", "SMS_READ_FULL", "SMS_READ_ICF", "SMS_READ_RB",
            "SMS_READ_UNDELIVERED", "SMS_READ_USER", "SMS_READ_WAP_MMS", "SMS_SEND", "SMS_WRITE_CC", "SMS_WRITE_FULL",
            "SMS_WRITE_ICF", "SMS_WRITE_RB", "SMS_WRITE_UNDELIVERED", "SMS_WRITE_USER", "START_FOREGROUND", "START_LEGACY",
            "STOP_BODY_SENSOR", "STORAGE_BACKGROUND", "STORAGE_INTERNAL", "STORAGE_MANAGER", "SYSTEM_ALERT_WINDOW",
            "TAKE_AUDIO_FOCUS", "TAKE_MEDIA_BUTTONS", "TAKE_PICTURE", "TAKE_SCREENSHOT", "TAKE_VIDEO", "TASTE", "TEMP_ACCESS",
            "TOAST_WINDOW", "TRAIN_DEVICE_ATTRIBUTION", "TURN_SCREEN_ON", "TURN_SCREEN_ON_TRANSIENT_TERMINATION",
            "UNLIMITED_BACKGROUND_DEPRECATED", "UPDATE_APP_OPS_STATS", "USE_BIOMETRIC", "USE_COLOR", "USE_FINGERPRINT",
            "USE_SIP", "VIBRATE", "VIBRATE_DEVICE_ATTRIBUTION", "VIEW_DEVICE_ATTRIBUTION", "VOICE_CLASSIFIER", "VOICE_INTERACTION",
            "WIFI_SCAN", "WRITE_BLOCKED_NUMBER", "WRITE_CALL_LOG", "WRITE_CLIPBOARD", "WRITE_CONTACTS", "WRITE_DEFAULT_ACCOUNT",
            "WRITE_EXTERNAL_STORAGE", "WRITE_HOME_APP_SHORTCUT", "WRITE_ICC_APDU", "WRITE_MEDIA_KEYEVENT", "WRITE_NOTIFICATION_SOUND",
            "WRITE_OEM_UNLOCK_STATE", "WRITE_PROJECTION_STATE", "WRITE_SMS", "WRITE_SOUND", "WRITE_SYNC_SETTINGS", "WRITE_VOICEMAIL",
            "WRITE_WIFI_CREDENTIAL",
        )

        private fun validPackage(pkg: String): Boolean = Regex("[a-zA-Z0-9._-]+").matches(pkg)
    }



    private fun isAvailable(): Boolean = RootDetector.isRootAvailable()

    override fun handles(name: String): Boolean = name in TOOL_NAMES

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!isAvailable()) return emptyList()
        return definitions()
    }

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> {
        if (!isAvailable()) return emptyList()
        return definitions().map { def ->
            ToolDescriptor(
                definition = def,
                riskLevel = riskOf(def.function.name),
                tier = tierOf(def.function.name),
                requiresApproval = requiresApprovalOf(def.function.name),
            )
        }
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!isAvailable()) {
            return jsonError(name, "App management tools require root access (su).")
        }
        return when (name) {
            "app_list" -> appList(arguments)
            "app_info" -> appInfo(arguments)
            "app_freeze" -> appFreeze(arguments, unfreeze = false)
            "app_unfreeze" -> appFreeze(arguments, unfreeze = true)
            "app_force_stop" -> appForceStop(arguments)
            "app_clear_data" -> appClearData(arguments)
            "app_trim_caches" -> appTrimCaches(arguments)
            "app_uninstall" -> appUninstall(arguments)
            "app_disable_component" -> appSetComponent(arguments, enable = false)
            "app_enable_component" -> appSetComponent(arguments, enable = true)
            "appops_get" -> appopsGet(arguments)
            "appops_set" -> appopsSet(arguments)
            "app_set_permission" -> appSetPermission(arguments)
            "app_block_internet" -> appToggleOp(arguments, "app_block_internet", "INTERNET")
            "app_restrict_background" -> appToggleOp(arguments, "app_restrict_background", "RUN_IN_BACKGROUND")
            "app_dexopt" -> appDexopt(arguments)
            else -> "Unknown tool: $name"
        }
    }

    // ── 底层执行（公共实现见 ShellToolJson.kt） ──

    private fun runRoot(cmd: String, timeoutMs: Int = TIMEOUT_DEFAULT): RootResult =
        com.lxseek.chat.tool.runRoot(cmd, timeoutMs)

    private fun result(name: String, cmd: String, res: RootResult): String =
        rootToolResult(name, cmd, res, MAX_OUTPUT)

    // ── 查询类工具 ──

    private fun appList(arguments: String): String {
        val args = parseToolArgs(arguments)
        val type = arg(args, "type").lowercase().ifBlank { "all" }
        val filter = arg(args, "filter").ifBlank { null }
        val flag = when (type) {
            "system" -> " -s"
            "third", "user" -> " -3"
            else -> ""
        }
        // Quote the filter to prevent shell injection via crafted substrings.
        val grep = filter?.let { " | grep -i ${com.lxseek.chat.util.ShellQuote.quote(it)}" } ?: ""
        val cmd = "pm list packages$flag$grep"
        return result("app_list", cmd, runRoot(cmd, TIMEOUT_DEFAULT))
    }

    private fun appInfo(arguments: String): String {
        val args = parseToolArgs(arguments)
        val pkg = arg(args, "package")
        if (!validPackage(pkg)) {
            return jsonError("app_info", "package is required (e.g. com.example.app)")
        }
        val cmd = "dumpsys package $pkg | grep -E " +
            "\"^Package \\[|userId=|versionName=|versionCode=|enabled=|stopped=|installerPackageName|grantedPermissions\" " +
            "| head -80"
        return result("app_info", cmd, runRoot(cmd, TIMEOUT_DEFAULT))
    }

    private fun appopsGet(arguments: String): String {
        val args = parseToolArgs(arguments)
        val pkg = arg(args, "package")
        if (!validPackage(pkg)) {
            return jsonError("appops_get", "package is required (e.g. com.example.app)")
        }
        val op = arg(args, "op").ifBlank { null }
        val cmd = if (op != null) "cmd appops get $pkg $op" else "cmd appops get $pkg"
        return result("appops_get", cmd, runRoot(cmd, TIMEOUT_DEFAULT))
    }

    // ── 应用状态/数据 ──

    private fun appFreeze(arguments: String, unfreeze: Boolean): String {
        val args = parseToolArgs(arguments)
        val pkg = arg(args, "package")
        if (!validPackage(pkg)) {
            return jsonError(if (unfreeze) "app_unfreeze" else "app_freeze", "package is required")
        }
        val cmd = if (unfreeze) "pm enable $pkg" else "pm disable-user --user 0 $pkg"
        return result(if (unfreeze) "app_unfreeze" else "app_freeze", cmd, runRoot(cmd, TIMEOUT_DEFAULT))
    }

    private fun appForceStop(arguments: String): String {
        val args = parseToolArgs(arguments)
        val pkg = arg(args, "package")
        if (!validPackage(pkg)) {
            return jsonError("app_force_stop", "package is required")
        }
        return result("app_force_stop", "am force-stop $pkg", runRoot("am force-stop $pkg", TIMEOUT_DEFAULT))
    }

    private fun appClearData(arguments: String): String {
        val args = parseToolArgs(arguments)
        val pkg = arg(args, "package")
        if (!validPackage(pkg)) {
            return jsonError("app_clear_data", "package is required")
        }
        return result("app_clear_data", "pm clear $pkg", runRoot("pm clear $pkg", TIMEOUT_DEFAULT))
    }

    private fun appTrimCaches(arguments: String): String {
        val args = parseToolArgs(arguments)
        val size = arg(args, "size").ifBlank { "512M" }.uppercase()
        if (!Regex("\\d+[KMG]").matches(size)) {
            return jsonError("app_trim_caches", "size must be like 100K, 512M or 1G")
        }
        return result("app_trim_caches", "pm trim-caches $size", runRoot("pm trim-caches $size", TIMEOUT_DEFAULT))
    }

    private fun appUninstall(arguments: String): String {
        val args = parseToolArgs(arguments)
        val pkg = arg(args, "package")
        if (!validPackage(pkg)) {
            return jsonError("app_uninstall", "package is required")
        }
        val mode = arg(args, "mode").lowercase().ifBlank { "user" }
        val cmd = when (mode) {
            "full" -> "pm uninstall $pkg"
            else -> "pm uninstall --user 0 $pkg"
        }
        return result("app_uninstall", cmd, runRoot(cmd, TIMEOUT_DEFAULT))
    }

    private fun appSetComponent(arguments: String, enable: Boolean): String {
        val args = parseToolArgs(arguments)
        val component = arg(args, "component")
        if (!Regex("[a-zA-Z0-9._/$-]+").matches(component)) {
            return jsonError(
                if (enable) "app_enable_component" else "app_disable_component",
                "component is required, format package/ClassName (e.g. com.example/.MainActivity)",
            )
        }
        val cmd = if (enable) "pm enable $component" else "pm disable-user --user 0 $component"
        return result(if (enable) "app_enable_component" else "app_disable_component", cmd, runRoot(cmd, TIMEOUT_DEFAULT))
    }

    // ── 权限 / AppOps ──

    private fun appSetPermission(arguments: String): String {
        val args = parseToolArgs(arguments)
        val action = arg(args, "action").lowercase()
        val pkg = arg(args, "package")
        val permission = arg(args, "permission")
        if (action !in setOf("grant", "revoke") || !validPackage(pkg) || !Regex("[a-zA-Z0-9._]+").matches(permission)) {
            return jsonError("app_set_permission", "action (grant/revoke), package and permission are required")
        }
        val cmd = "pm $action $pkg $permission"
        return result("app_set_permission", cmd, runRoot(cmd, TIMEOUT_DEFAULT))
    }

    private fun appopsSet(arguments: String): String {
        val args = parseToolArgs(arguments)
        val pkg = arg(args, "package")
        val op = arg(args, "op").uppercase()
        val mode = arg(args, "mode").lowercase()
        if (!validPackage(pkg) || op !in APP_OPS || mode !in setOf("allow", "ignore", "deny", "default")) {
            return jsonError(
                "appops_set",
                "package, op (AppOps name) and mode are required; mode must be allow/ignore/deny/default",
            )
        }
        val cmd = "cmd appops set $pkg $op $mode"
        return result("appops_set", cmd, runRoot(cmd, TIMEOUT_DEFAULT))
    }

    private fun appToggleOp(arguments: String, name: String, op: String): String {
        val args = parseToolArgs(arguments)
        val pkg = arg(args, "package")
        if (!validPackage(pkg)) {
            return jsonError(name, "package is required")
        }
        val mode = if (boolArg(args, "enable")) "ignore" else "allow"
        val cmd = "cmd appops set $pkg $op $mode"
        return result(name, cmd, runRoot(cmd, TIMEOUT_DEFAULT))
    }

    // ── 优化 ──

    private fun appDexopt(arguments: String): String {
        val args = parseToolArgs(arguments)
        val pkg = arg(args, "package")
        if (!validPackage(pkg)) {
            return jsonError("app_dexopt", "package is required")
        }
        val mode = arg(args, "mode").lowercase().ifBlank { "speed" }
        if (mode !in setOf("speed", "speed-profile", "verify", "full", "reset")) {
            return jsonError("app_dexopt", "mode must be speed, speed-profile, verify, full or reset")
        }
        val cmd = "cmd package compile -m $mode $pkg"
        return result("app_dexopt", cmd, runRoot(cmd, TIMEOUT_COMPILE))
    }

    // ── 工具定义与风险分级 ──

    private fun definitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = "app_list",
                description = "List installed packages. type: all (default), system (-s) or third (-3). " +
                    "Optional filter does a case-insensitive substring match on package names. Read-only.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "type" to ToolProperty("string", "all, system or third"),
                        "filter" to ToolProperty("string", "Optional keyword to filter package names"),
                    ),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "app_info",
                description = "Inspect a package: uid, versions, enabled/stopped state, installer and granted " +
                    "permissions. Read-only. Use before mutating an app.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package" to ToolProperty("string", "Android package name, e.g. com.example.app"),
                    ),
                    required = listOf("package"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "app_freeze",
                description = "Freeze (disable) an app so it cannot run for user 0. Reversible with app_unfreeze.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package" to ToolProperty("string", "Android package name"),
                    ),
                    required = listOf("package"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "app_unfreeze",
                description = "Unfreeze (re-enable) a previously frozen app.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package" to ToolProperty("string", "Android package name"),
                    ),
                    required = listOf("package"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "app_force_stop",
                description = "Force-stop a running app immediately. The app can be restarted later.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package" to ToolProperty("string", "Android package name"),
                    ),
                    required = listOf("package"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "app_clear_data",
                description = "Clear an app's private data (logs in, local settings and cache are wiped). " +
                    "Irreversible. Use app_info first.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package" to ToolProperty("string", "Android package name"),
                    ),
                    required = listOf("package"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "app_trim_caches",
                description = "Trim all app caches to free space. size is the target retained cache size " +
                    "like 100K, 512M or 1G (default 512M).",
                parameters = ToolParameters(
                    properties = mapOf(
                        "size" to ToolProperty("string", "Target cache size like 100K, 512M, 1G"),
                    ),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "app_uninstall",
                description = "Uninstall an app. mode user (default) removes it for user 0 only (debloat, " +
                    "system apps keep their copy); mode full uninstalls completely (only works for apps the " +
                    "user installed).",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package" to ToolProperty("string", "Android package name"),
                        "mode" to ToolProperty("string", "user or full"),
                    ),
                    required = listOf("package"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "app_disable_component",
                description = "Disable a single component (Activity/Service/Receiver/Provider) of an app, " +
                    "formatted as package/ClassName or fully-qualified. Reversible with app_enable_component.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "component" to ToolProperty("string", "Component, e.g. com.example/.MainActivity"),
                    ),
                    required = listOf("component"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "app_enable_component",
                description = "Re-enable a previously disabled component.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "component" to ToolProperty("string", "Component, e.g. com.example/.MainActivity"),
                    ),
                    required = listOf("component"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "appops_get",
                description = "Query an app's AppOps (special permission switches like location, camera, " +
                    "background, internet). Without op, returns all ops. Read-only.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package" to ToolProperty("string", "Android package name"),
                        "op" to ToolProperty("string", "Optional AppOps name, e.g. CAMERA, INTERNET"),
                    ),
                    required = listOf("package"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "appops_set",
                description = "Set an AppOps mode for an app: allow, ignore (block silently), deny (force deny) " +
                    "or default. Common ops: CAMERA, RECORD_AUDIO, READ_CONTACTS, GET_ACCOUNTS, SYSTEM_ALERT_WINDOW, " +
                    "POST_NOTIFICATION, SCHEDULE_EXACT_ALARM, ACCESS_ICON.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package" to ToolProperty("string", "Android package name"),
                        "op" to ToolProperty("string", "AppOps name, e.g. CAMERA"),
                        "mode" to ToolProperty("string", "allow, ignore, deny or default"),
                    ),
                    required = listOf("package", "op", "mode"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "app_set_permission",
                description = "Grant or revoke a runtime permission for an app. action is grant or revoke; " +
                    "permission is the full name like android.permission.CAMERA.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "action" to ToolProperty("string", "grant or revoke"),
                        "package" to ToolProperty("string", "Android package name"),
                        "permission" to ToolProperty("string", "Permission name, e.g. android.permission.CAMERA"),
                    ),
                    required = listOf("action", "package", "permission"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "app_block_internet",
                description = "Block or restore an app's internet access. enable=true blocks network for the app, " +
                    "enable=false allows it again.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package" to ToolProperty("string", "Android package name"),
                        "enable" to ToolProperty("boolean", "true to block internet, false to allow"),
                    ),
                    required = listOf("package", "enable"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "app_restrict_background",
                description = "Restrict an app from running in the background to save battery/data. " +
                    "enable=true restricts, false lifts the restriction.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package" to ToolProperty("string", "Android package name"),
                        "enable" to ToolProperty("boolean", "true to restrict background, false to allow"),
                    ),
                    required = listOf("package", "enable"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "app_dexopt",
                description = "Pre-compile an app to speed up cold launch. mode: speed (default), speed-profile, " +
                    "verify, full or reset to restore system default. Costs some storage.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package" to ToolProperty("string", "Android package name"),
                        "mode" to ToolProperty("string", "speed, speed-profile, verify, full or reset"),
                    ),
                    required = listOf("package"),
                ),
            ),
        ),
    )

    private fun riskOf(name: String): RiskLevel = when (name) {
        "app_list", "app_info", "appops_get" -> RiskLevel.ReadOnly
        "app_dexopt" -> RiskLevel.LowRisk
        "app_force_stop", "app_trim_caches", "app_block_internet", "app_restrict_background" -> RiskLevel.Moderate
        else -> RiskLevel.HighRisk
    }

    private fun tierOf(name: String): ToolTier = when (name) {
        "app_list", "app_info", "appops_get" -> ToolTier.Extended
        else -> ToolTier.Dangerous
    }

    private fun requiresApprovalOf(name: String): Boolean = name !in setOf(
        "app_list",
        "app_info",
        "appops_get",
    )
}
