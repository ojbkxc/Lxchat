package com.lxseek.chat.tool

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.DisplayMetrics
import android.view.WindowManager

import com.lxseek.chat.adb.RootDetector
import com.lxseek.chat.adb.ShizukuManager
import com.lxseek.chat.androidcontrol.AndroidUiControllerService
import java.io.File
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.agent.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Text-model device control for the on-device apps (WeChat, Alipay, etc.).
 *
 * The model never sees a screenshot: it plans from an accessibility node-tree dump and
 * fires click / set-text / back / home actions through [AndroidUiControllerService].
 * This matches the "UI-semantics, text-LLM" approach used by AutoDroid / DroidBot-GPT while
 * keeping a tiny footprint (no native UI tree libs, no image pipeline).
 */
class AndroidAppControllerToolProvider(private val app: Application) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun prop(t: String, d: String) = ToolProperty(t, d)
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "android_accessibility_status",
                description = "Return whether the Android accessibility bridge is connected, which app is currently in the foreground, and how to enable the bridge if it is off. Call this FIRST before any other android_* tool so you know whether actions are possible.",
                parameters = ToolParameters(properties = emptyMap()),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_open_app",
                description = "Open an app by its common name (e.g. WeChat, Alipay, Douyin) or by an Android package id (e.g. com.tencent.mm). Works without accessibility enabled. Use android_known_apps to see the built-in aliases you can ask for.",
                parameters = ToolParameters(
                    properties = mapOf("appId" to prop("string", "A known alias (e.g. 'weixin', 'alipay', 'douyin') or a full package id (e.g. 'com.tencent.mm').")),
                    required = listOf("appId"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_known_apps",
                description = "List the built-in app aliases this tool can open by name. Use this when the user names an app so you can pick the correct alias.",
                parameters = ToolParameters(properties = emptyMap()),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_read_ui",
                description = "Dump the current screen's interactive elements (labels, buttons, input fields) from the accessibility node tree, each with a stable index, clickable/editable flags and a center coordinate. Use this after opening an app or after any action to see what is on screen now. Prefer tapping by 'index' (unambiguous) over 'label' (can collide).",
                parameters = ToolParameters(properties = emptyMap()),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_click",
                description = "Tap an element. Provide exactly one target: 'index' (stable index from android_read_ui, most reliable), 'label' (text/content-description substring), or x/y screen coordinates. Index/coordinate avoid label collisions. Requires the accessibility bridge.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "index" to prop("integer", "Optional stable element index from android_read_ui, e.g. 3."),
                        "label" to prop("string", "Optional text shown on the element to tap, e.g. 'Send' or '搜索'."),
                        "x" to prop("integer", "Optional screen x coordinate to tap."),
                        "y" to prop("integer", "Optional screen y coordinate to tap."),
                    ),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_input",
                description = "Type text into an input field. If 'index' or 'into' is given, that field is tapped first to focus it; otherwise types into the current focused/editable field. Set the text directly; no IME is needed.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "text" to prop("string", "The text to type."),
                        "index" to prop("integer", "Optional stable element index of the input field (from android_read_ui)."),
                        "into" to prop("string", "Optional label of the input field to focus first."),
                    ),
                    required = listOf("text"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_focus_clear_text",
                description = "Clear the focused (or first editable) input field. Use this instead of backspacing when you want to replace existing content, since the cursor position is invisible to text models.",
                parameters = ToolParameters(properties = emptyMap()),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_swipe",
                description = "Scroll or swipe. Give a 'direction' (up/down/left/right, swipes ~1/3 of the screen from the center) to scroll a list, or give explicit x1/y1/x2/y2 coordinates. Optionally a 'duration_ms'. Requires the accessibility bridge.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "direction" to prop("string", "Optional 'up' | 'down' | 'left' | 'right' for a centered scroll."),
                        "x1" to prop("integer", "Optional start x."),
                        "y1" to prop("integer", "Optional start y."),
                        "x2" to prop("integer", "Optional end x."),
                        "y2" to prop("integer", "Optional end y."),
                        "duration_ms" to prop("integer", "Optional swipe duration in ms (default 300)."),
                    ),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_long_press",
                description = "Long-press an element to open its context menu. Provide a 'label', an 'index' (from android_read_ui), or x/y coordinates. Requires the accessibility bridge.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "index" to prop("integer", "Optional stable element index from android_read_ui."),
                        "label" to prop("string", "Optional element text/content-description to long-press."),
                        "x" to prop("integer", "Optional screen x coordinate."),
                        "y" to prop("integer", "Optional screen y coordinate."),
                    ),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_press_key",
                description = "Send a key press. Navigation keys (back | home | recents | notifications | " +
                    "quick_settings) go through the accessibility bridge. All other keys are injected via the " +
                    "Shizuku/root shell with 'input keyevent': volume_up | volume_down | volume_mute | power | menu | " +
                    "enter | backspace | tab | space | escape | delete | dpad_up | dpad_down | dpad_left | dpad_right | " +
                    "dpad_center | camera. A raw numeric Android keycode (e.g. 24) also works.",
                parameters = ToolParameters(
                    properties = mapOf("key" to prop("string", "Key name (e.g. 'volume_down') or a numeric keycode (e.g. 25).")),
                    required = listOf("key"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_pinch",
                description = "Two-finger pinch zoom at a screen point. 'scale' > 1 zooms in (e.g. 2.0), < 1 zooms out (e.g. 0.5); " +
                    "or give explicit start_span/end_span distances in px. Use for maps, photos and canvases that a plain " +
                    "android_swipe cannot zoom. Requires the accessibility bridge.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "x" to prop("integer", "Optional centre x of the pinch (screen px). Default: screen centre."),
                        "y" to prop("integer", "Optional centre y of the pinch (screen px). Default: screen centre."),
                        "scale" to prop("number", "Zoom factor: >1 zoom in, <1 zoom out. Ignored when start_span/end_span are given."),
                        "start_span" to prop("integer", "Initial distance between the two fingers in px (default 400)."),
                        "end_span" to prop("integer", "Final distance between the two fingers in px."),
                        "duration_ms" to prop("integer", "Gesture duration in ms (default 600)."),
                    ),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_wait_for",
                description = "Wait until a text or element appears on (or disappears from) the current screen by " +
                    "polling the accessibility tree. Use this after android_click/android_input when the app needs " +
                    "time to load the next screen, instead of repeatedly calling android_read_ui (each call costs a " +
                    "full dump). With mode=appear the success result includes the element's stable index, so you can " +
                    "android_click index=<i> directly. Requires the accessibility bridge.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "text" to prop("string", "Substring of the element's text or content-description to wait for, e.g. '发送' or 'Login'."),
                        "mode" to prop("string", "appear (default): wait until the text shows up. gone: wait until it disappears (e.g. a loading spinner)."),
                        "timeout_ms" to prop("integer", "Max wait in ms, 500..60000, default 10000."),
                    ),
                    required = listOf("text"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_go_back",
                description = "Simulate the system back button.",
                parameters = ToolParameters(properties = emptyMap()),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_go_home",
                description = "Simulate the system home button." ,
                parameters = ToolParameters(properties = emptyMap()),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_screenshot",
                description = "Capture the current screen via the accessibility bridge (Android 11+) and " +
                    "save a PNG to the app cache, returning its path. Best-effort: secure surfaces (banking, " +
                    "some full-screen) can fail. Use this when a user needs proof of what is on screen or a " +
                    "visual you cannot derive from the UI dump; otherwise prefer android_read_ui (cheaper).",
                parameters = ToolParameters(properties = emptyMap()),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_see",
                description = "Vision loop: capture the screen, overlay a numbered N×N grid (ids 1..N², " +
                    "row-major from top-left), and optionally tap or swipe by grid id or fractional coordinate. " +
                    "The model looks at the annotated screenshot (annotated_path) and the cell map (cells), then " +
                    "calls this tool again with action=tap/swipe and grid_id (or fx/fy). action: 'look' only " +
                    "captures+annotates; 'tap' captures+annotates and taps if grid_id/fx/fy given; 'swipe' " +
                    "captures+annotates and swipes if from_grid_id+to_grid_id (or fx1/fy1/fx2/fy2) given. Use this " +
                    "when android_read_ui cannot see the target (Canvas, WebView interior, game) but a screenshot can. " +
                    "Requires the accessibility bridge (Android 11+ for capture).",
                parameters = ToolParameters(
                    properties = mapOf(
                        "instruction" to prop("string", "What to look for / do, e.g. 'tap the search button'. Echoed back so the model can reason."),
                        "grid_size" to prop("integer", "Grid size N for an N×N overlay. Default 4."),
                        "action" to prop("string", "look | tap | swipe. Default look."),
                        "grid_id" to prop("integer", "For tap: grid cell id (1..N²) to tap."),
                        "fx" to prop("number", "For tap: fractional x (0..1) of screen width."),
                        "fy" to prop("number", "For tap: fractional y (0..1) of screen height."),
                        "from_grid_id" to prop("integer", "For swipe: grid cell id to swipe from."),
                        "to_grid_id" to prop("integer", "For swipe: grid cell id to swipe to."),
                        "fx1" to prop("number", "For swipe: fractional start x (0..1)."),
                        "fy1" to prop("number", "For swipe: fractional start y (0..1)."),
                        "fx2" to prop("number", "For swipe: fractional end x (0..1)."),
                        "fy2" to prop("number", "For swipe: fractional end y (0..1)."),
                    ),
                    required = listOf("instruction"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "wechat_open_chat",
                description = "Open WeChat and best-effort navigate to the chat with a contact by " +
                    "searching their name. Requires the accessibility bridge. Each step (open search, " +
                    "type contact, tap first result) reports whether it succeeded; any failure returns a " +
                    "clear status so you can fall back to android_read_ui + android_click instead of giving up.",
                parameters = ToolParameters(
                    properties = mapOf("contact" to prop("string", "The contact name or remark (备注) to find, e.g. '张三'.")),
                    required = listOf("contact"),
                ),
            )),
        )
    }

    override fun handles(name: String): Boolean =
        name.startsWith("android_") || name.startsWith("wechat_")

    override fun riskLevel(name: String): RiskLevel = when (name) {
        "android_accessibility_status", "android_read_ui", "android_known_apps", "android_wait_for" -> RiskLevel.ReadOnly
        "android_open_app", "android_go_back", "android_go_home", "android_press_key" -> RiskLevel.LowRisk
        // Clicking/typing/swiping inside another app can cause real side effects (sending, posting).
        "android_click", "android_input", "android_focus_clear_text", "android_swipe",
        "android_long_press", "wechat_open_chat", "android_screenshot", "android_see", "android_pinch" -> RiskLevel.Moderate
        else -> RiskLevel.ReadOnly
    }

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "android_accessibility_status" -> statusJson()
                "android_known_apps" -> knownAppsJson()
                "android_read_ui" -> readUiJson()
                "android_open_app" -> openApp(arguments)
                "android_click" -> click(arguments)
                "android_input" -> input(arguments)
                "android_focus_clear_text" -> focusClearTextJson()
                "android_swipe" -> swipe(arguments)
                "android_long_press" -> longPress(arguments)
                "android_press_key" -> pressKey(arguments)
                "android_pinch" -> pinch(arguments)
                "android_wait_for" -> waitFor(arguments)
                "android_go_back" -> goBackJson()
                "android_go_home" -> goHomeJson()
                "android_screenshot" -> screenshotJson()
                "android_see" -> see(arguments)
                "wechat_open_chat" -> wechatOpenChat(arguments)
                else -> err("unknown_tool", "Unknown android tool: $name")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("AndroidCtrl", "android_$name failed", e)
            err("tool_error", e.message)
        }
    }

    private fun service(): AndroidUiControllerService? = AndroidUiControllerService.instance

    /** Shared Shizuku bridge for keyevent injection; same pattern as [ShellToolProvider]. */
    private val shizukuManager: ShizukuManager? by lazy { ShizukuManager(app) }

    private fun statusJson(): String = buildJsonObject {
        put("type", "android_status")
        val svc = service()
        put("accessibilityEnabled", svc != null)
        put("accessibilityConnected", svc?.isConnected() == true)
        svc?.activePackage()?.let { put("activePackage", it) }
        if (svc == null) {
            put("hint", "The accessibility bridge is OFF. User must enable it once: Settings -> Accessibility -> LxChat -> on. Read-only tools still work without it.")
        }
    }.toString()

    private fun knownAppsJson(): String = buildJsonObject {
        put("type", "android_known_apps")
        put("note", "Pass these as appId to android_open_app. A full package id also works.")
        put("apps", buildJsonArray {
            KNOWN_APPS.forEach { (alias, label, pkg) ->
                add(buildJsonObject {
                    put("alias", alias); put("label", label); put("package", pkg)
                })
            }
        })
    }.toString()

    /**
     * Compact, indexed UI dump for text-only models: keeps actionable + labelled nodes, gives each a
     * stable index, a short type, a single best "label" (text > contentDescription > trailing id segment),
     * and the element's center coordinate so the model can act by index or by tapping coordinates.
     */
    private fun readUiJson(): String = buildJsonObject {
        put("type", "android_ui_dump")
        val svc = service()
        if (svc == null) {
            put("error", "accessibility_off")
            put("items", buildJsonArray { })
        } else {
            svc.activePackage()?.let { put("activePackage", it) }
            put("note", "Tap precisely with android_click index=<i>. cx/cy is the element centre for coordinate taps.")
            put("items", buildJsonArray {
                svc.dumpCurrentUi().forEachIndexed { i, n ->
                    add(buildJsonObject {
                        put("i", i)
                        put("type", shortClassName(n.nodeClass))
                        labelFor(n)?.let { put("label", it) }
                        n.resourceId?.let { put("id", it.substringAfterLast('/')) }
                        put("clickable", n.clickable)
                        put("editable", n.editable)
                        put("enabled", n.enabled)
                        put("focused", n.focused)
                        if (n.scrollable) put("scrollable", true)
                        put("cx", n.x + n.width / 2)
                        put("cy", n.y + n.height / 2)
                    })
                }
            })
        }
    }.toString()

    private fun openApp(arguments: String): String {
        val arg = argString("appId", arguments)
        if (arg.isNullOrBlank()) return err("no_app", "Missing appId.")
        val pkg = resolvePackage(arg)
        if (pkg == null) {
            return buildJsonObject {
                put("type", "android_open_app")
                put("status", "error")
                put("error", "app_not_found")
                put("hint", "Unknown app '$arg'. Try android_known_apps or pass a full package id.")
            }.toString()
        }
        runCatching {
            val launch = app.packageManager.getLaunchIntentForPackage(pkg)
            val intent = launch
                ?: Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(pkg)
                }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        }.onFailure { e ->
            return buildJsonObject {
                put("type", "android_open_app")
                put("status", "error")
                put("error", "launch_failed")
                put("message", e.message)
            }.toString()
        }
        return buildJsonObject {
            put("type", "android_open_app")
            put("status", "ok")
            put("package", pkg)
        }.toString()
    }

    /** Tap by index, label, or x/y coordinates. Index/coordinate avoid label collisions. */
    private fun click(arguments: String): String {
        val svc = service() ?: return err("accessibility_off", "Enable accessibility first.")
        val index = argInt("index", arguments)
        val label = argString("label", arguments)
        val x = argInt("x", arguments)
        val y = argInt("y", arguments)
        var ok: Boolean
        var target: String
        when {
            index != null -> {
                val node = indexedNode(svc, index)
                    ?: return notFound("index", "No element at index $index. Re-run android_read_ui.")
                ok = svc.clickAt(node.x + node.width / 2, node.y + node.height / 2)
                target = "index=$index"
            }
            label != null -> {
                ok = svc.clickByLabel(label)
                target = "label=$label"
            }
            x != null && y != null -> {
                ok = svc.clickAt(x, y)
                target = "x=$x,y=$y"
            }
            else -> return err("no_target", "Provide one of: index, label, or x/y.")
        }
        return buildJsonObject {
            put("type", "android_click")
            put("status", if (ok) "ok" else "not_found")
            put("target", target)
            if (!ok) put("hint", "Element not found. Dump the UI with android_read_ui to see exact indices/labels.")
        }.toString()
    }

    private fun input(arguments: String): String {
        val text = argString("text", arguments)
        val into = argString("into", arguments)
        val index = argInt("index", arguments)
        val svc = service() ?: return err("accessibility_off", "Enable accessibility first.")
        if (text.isNullOrBlank()) return err("no_text", "Missing text.")
        val ok = if (index != null) {
            val node = indexedNode(svc, index)
            if (node == null) return err("no_target", "No editable element at index $index. Re-run android_read_ui.")
            // Focus the field first (click its centre), then type into the focused editable.
            svc.clickAt(node.x + node.width / 2, node.y + node.height / 2) && svc.focusAndInput(text, null)
        } else {
            svc.focusAndInput(text, into)
        }
        return buildJsonObject {
            put("type", "android_input")
            put("status", if (ok) "ok" else "error")
            if (!ok) put("hint", "No editable field found/focused. Dump UI and click the field first.")
        }.toString()
    }

    private fun focusClearTextJson(): String {
        val svc = service() ?: return err("accessibility_off", "Enable accessibility first.")
        val ok = svc.clearFocusedText()
        return buildJsonObject {
            put("type", "android_focus_clear_text")
            put("status", if (ok) "ok" else "error")
            if (!ok) put("hint", "No editable field found to clear.")
        }.toString()
    }

    private fun swipe(arguments: String): String {
        val svc = service() ?: return err("accessibility_off", "Enable accessibility first.")
        val direction = argString("direction", arguments)
        val x1 = argInt("x1", arguments); val y1 = argInt("y1", arguments)
        val x2 = argInt("x2", arguments); val y2 = argInt("y2", arguments)
        val duration = argInt("duration_ms", arguments)?.toLong() ?: 300L
        val ok = if (!direction.isNullOrBlank()) {
            svc.swipeDirection(direction)
        } else if (x1 != null && y1 != null && x2 != null && y2 != null) {
            svc.swipe(x1, y1, x2, y2, duration)
        } else {
            return err("no_swipe_target", "Provide 'direction', or x1/y1/x2/y2 coordinates.")
        }
        return buildJsonObject {
            put("type", "android_swipe")
            put("status", if (ok) "ok" else "error")
            if (!ok) put("hint", "Swipe was rejected by the system.")
        }.toString()
    }

    private fun longPress(arguments: String): String {
        val svc = service() ?: return err("accessibility_off", "Enable accessibility first.")
        val index = argInt("index", arguments)
        val label = argString("label", arguments)
        val x = argInt("x", arguments)
        val y = argInt("y", arguments)
        var ok: Boolean
        var target: String
        when {
            index != null -> {
                val node = indexedNode(svc, index)
                    ?: return notFound("index", "No element at index $index. Re-run android_read_ui.")
                ok = svc.longPressAt(node.x + node.width / 2, node.y + node.height / 2)
                target = "index=$index"
            }
            label != null -> {
                val node = labelNode(svc, label)
                    ?: return notFound("label", "Element '$label' not found. Re-run android_read_ui.")
                ok = svc.longPressAt(node.x + node.width / 2, node.y + node.height / 2)
                target = "label=$label"
            }
            x != null && y != null -> {
                ok = svc.longPressAt(x, y)
                target = "x=$x,y=$y"
            }
            else -> return err("no_target", "Provide one of: index, label, or x/y.")
        }
        return buildJsonObject {
            put("type", "android_long_press")
            put("status", if (ok) "ok" else "error")
            put("target", target)
            if (!ok) put("hint", "Long-press was rejected by the system.")
        }.toString()
    }

    /**
     * 导航键（back/home/recents/notifications/quick_settings）只依赖无障碍桥；
     * 其余按键（音量、电源、菜单、编辑键等）无障碍服务注入不了，必须通过
     * Shizuku/root shell 的 `input keyevent` 注入 —— Lxchat 已有 AdbShellBackend，
     * 复用它而不是再起一条 shell 通路。
     */
    private suspend fun pressKey(arguments: String): String {
        val key = argString("key", arguments)
        if (key.isNullOrBlank()) return err("no_key", "Missing key. See android_press_key description for valid names.")
        val lower = key.lowercase()
        if (lower in GLOBAL_KEYS) {
            val svc = service() ?: return err("accessibility_off", "Enable accessibility first.")
            val ok = svc.pressGlobalKey(key)
            return buildJsonObject {
                put("type", "android_press_key")
                put("status", if (ok) "ok" else "error")
                put("key", key)
                put("via", "accessibility")
                if (!ok) put("hint", "System rejected the global action.")
            }.toString()
        }
        val code = KEYCODES[lower] ?: key.trim().toIntOrNull()
            ?: return err("unknown_key", "Unknown key '$key'. Use a name from the tool description or a numeric keycode.")
        val backend = adbBackend()
            ?: return buildJsonObject {
                put("type", "android_press_key")
                put("status", "error")
                put("key", key)
                put("error", "shell_unavailable")
                put("hint", "Key '$key' needs shell injection: install/enable Shizuku (or root), then retry. Navigation keys (back/home/recents/…) still work via accessibility.")
            }.toString()
        val ok = runCatching {
            backend.executeCommand("input keyevent $code", "", 8_000)
        }.isSuccess
        return buildJsonObject {
            put("type", "android_press_key")
            put("status", if (ok) "ok" else "error")
            put("key", key)
            put("keycode", code)
            put("via", "shell")
            if (!ok) put("hint", "'input keyevent $code' failed. Check that Shizuku (or root) is active.")
        }.toString()
    }

    /** Root 可用则 root，否则要求 Shizuku 就绪（与 ShellToolProvider 的 ADB Shell 通路一致）。 */
    private fun adbBackend(): AdbShellBackend? {
        val root = RootDetector.isRootAvailable()
        val mgr = shizukuManager
        if (!root && (mgr == null || !mgr.isReady())) return null
        return AdbShellBackend(root, mgr)
    }

    /**
     * 双指缩放：给 scale 或显式 span，中心点默认屏幕中央。
     * scale 换算成 endSpan = startSpan * scale，交给无障碍桥的两 stroke 手势执行。
     */
    private fun pinch(arguments: String): String {
        val svc = service() ?: return err("accessibility_off", "Enable accessibility first.")
        val (screenW, screenH) = screenSize()
        val maxSpan = minOf(screenW, screenH)
        val x = argInt("x", arguments) ?: screenW / 2
        val y = argInt("y", arguments) ?: screenH / 2
        val duration = argInt("duration_ms", arguments)?.toLong()?.coerceIn(100L, 5_000L) ?: 600L
        val startSpan = argInt("start_span", arguments)?.coerceIn(50, maxSpan) ?: 400
        val endSpanRaw = argInt("end_span", arguments)
            ?: argDouble("scale", arguments)?.let { scale -> (startSpan * scale).toInt() }
            ?: return err("no_zoom_target", "Provide 'scale' (>1 zoom in, <1 zoom out), or explicit start_span/end_span.")
        val endSpan = endSpanRaw.coerceIn(50, maxSpan)
        val ok = svc.pinch(x, y, startSpan, endSpan, duration)
        return buildJsonObject {
            put("type", "android_pinch")
            put("status", if (ok) "ok" else "error")
            put("centre", "x=$x,y=$y")
            put("span", "$startSpan -> $endSpan px")
            put("via", "accessibility")
            if (!ok) put("hint", "Pinch was rejected by the system (some secure screens block gestures).")
        }.toString()
    }

    /**
     * 等待文本/元素出现或消失：轮询无障碍 dump（间隔 400ms），命中即返回。
     * appear 命中时带上稳定 index，AI 可直接 android_click index=<i>，
     * 免去「点完 → read_ui → 判断 → 再点」中间每一轮的整树 dump 成本。
     */
    private suspend fun waitFor(arguments: String): String {
        val svc = service() ?: return err("accessibility_off", "Enable accessibility first.")
        val text = argString("text", arguments) ?: return err("no_text", "Missing text to wait for.")
        val gone = argString("mode", arguments)?.equals("gone", ignoreCase = true) == true
        val timeoutMs = argInt("timeout_ms", arguments)?.coerceIn(500, 60_000) ?: 10_000
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val nodes = svc.dumpCurrentUi()
            val hit = nodes.indexOfFirst { n ->
                (n.text?.contains(text, ignoreCase = true) == true) ||
                    (n.contentDescription?.contains(text, ignoreCase = true) == true)
            }
            if (gone) {
                if (hit < 0) {
                    return buildJsonObject {
                        put("type", "android_wait_for")
                        put("status", "ok")
                        put("text", text)
                        put("mode", "gone")
                    }.toString()
                }
            } else if (hit >= 0) {
                val n = nodes[hit]
                return buildJsonObject {
                    put("type", "android_wait_for")
                    put("status", "ok")
                    put("text", text)
                    put("mode", "appear")
                    put("index", hit)
                    put("cx", n.x + n.width / 2)
                    put("cy", n.y + n.height / 2)
                    put("clickable", n.clickable)
                    put("hint", "Found. Tap it with android_click index=$hit.")
                }.toString()
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                return buildJsonObject {
                    put("type", "android_wait_for")
                    put("status", "timeout")
                    put("text", text)
                    put("mode", if (gone) "gone" else "appear")
                    put("hint", "Not settled within ${timeoutMs}ms. Dump the screen with android_read_ui to see what is actually showing.")
                }.toString()
            }
            delay(minOf(400L, remaining))
        }
    }

    private fun notFound(target: String, hint: String): String = buildJsonObject {
        put("type", "android_error")
        put("error", "not_found")
        put("target", target)
        put("hint", hint)
    }.toString()

    /** Returns the snapshot at stable [index] by re-running the identical dump the UI used. */
    private fun indexedNode(svc: AndroidUiControllerService, index: Int): AndroidUiControllerService.UiNodeSnapshot? {
        val nodes = svc.dumpCurrentUi()
        return nodes.getOrNull(index)
    }

    /** First node whose text or content-description contains [label]. */
    private fun labelNode(svc: AndroidUiControllerService, label: String): AndroidUiControllerService.UiNodeSnapshot? =
        svc.dumpCurrentUi().firstOrNull { n ->
            (n.text?.contains(label, ignoreCase = true) == true) ||
                (n.contentDescription?.contains(label, ignoreCase = true) == true)
        }

    /** Short class name (strip the package path), e.g. android.widget.Button -> Button. */
    private fun shortClassName(nodeClass: String): String {
        val trimmed = nodeClass.trim().ifBlank { return "Node" }
        return trimmed.substringAfterLast('.').ifBlank { trimmed }
    }

    /** The single best human label to represent a node: text, else contentDescription, else null. */
    private fun labelFor(n: AndroidUiControllerService.UiNodeSnapshot): String? {
        val text = n.text?.takeIf { it.isNotBlank() }
        if (text != null) return text
        val desc = n.contentDescription?.takeIf { it.isNotBlank() }
        if (desc != null) return desc
        return null
    }

    private fun goBackJson(): String {
        val ok = service()?.goBack() == true
        return buildJsonObject { put("type", "android_go_back"); put("status", if (ok) "ok" else "error") }.toString()
    }

    private fun goHomeJson(): String {
        val ok = service()?.goHome() == true
        return buildJsonObject { put("type", "android_go_home"); put("status", if (ok) "ok" else "error") }.toString()
    }

    private fun screenshotJson(): String {
        val svc = service() ?: return err("accessibility_off", "Enable accessibility to capture the screen.")
        return when (val out = svc.takeScreenshot(app.cacheDir)) {
            is AndroidUiControllerService.ScreenshotOutcome.Success -> buildJsonObject {
                put("type", "android_screenshot")
                put("status", "ok")
                put("path", out.path)
            }.toString()
            is AndroidUiControllerService.ScreenshotOutcome.Failure -> buildJsonObject {
                put("type", "android_screenshot")
                put("status", "error")
                out.reason?.let { put("reason", it) }
            }.toString()
            AndroidUiControllerService.ScreenshotOutcome.NotSupported -> buildJsonObject {
                put("type", "android_screenshot")
                put("status", "not_supported")
                put("reason", "Screenshot needs Android 11 (API 30)+.")
            }.toString()
        }
    }

    /**
     * android_see — vision loop: capture → overlay a numbered grid → return the annotated
     * screenshot + cell map; if action is tap/swipe and coordinates are supplied, convert
     * to pixels and dispatch the gesture. The multimodal LLM does the actual seeing; this
     * tool only draws the grid and does the coordinate maths.
     */
    private fun see(arguments: String): String {
        val svc = service() ?: return err("accessibility_off", "Enable accessibility first.")
        val instruction = argString("instruction", arguments) ?: return err("no_instruction", "Missing instruction.")
        val gridSize = argInt("grid_size", arguments)?.coerceIn(2, 12) ?: 4
        val action = argString("action", arguments) ?: "look"
        val (screenW, screenH) = screenSize()

        // 1) Capture + annotate with a numbered grid.
        val capture = svc.takeScreenshot(app.cacheDir)
        val annotatedPath: String = when (capture) {
            is AndroidUiControllerService.ScreenshotOutcome.Success -> {
                val src = BitmapFactory.decodeFile(capture.path)
                    ?: return err("decode_failed", "Could not decode screenshot.")
                val annotated = VisionAssist.drawGridOverlay(src, gridSize)
                src.recycle()
                try {
                    val file = File(app.cacheDir, "see_${System.currentTimeMillis()}.png")
                    file.outputStream().use { annotated.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                    file.absolutePath
                } finally {
                    annotated.recycle()
                }
            }
            is AndroidUiControllerService.ScreenshotOutcome.Failure ->
                return buildJsonObject {
                    put("type", "android_see"); put("status", "error"); put("error", "capture_failed")
                    capture.reason?.let { put("reason", it) }
                }.toString()
            AndroidUiControllerService.ScreenshotOutcome.NotSupported ->
                return buildJsonObject {
                    put("type", "android_see"); put("status", "not_supported")
                    put("reason", "Screenshot needs Android 11 (API 30)+.")
                }.toString()
        }

        // 2) Cell map so the model can resolve ids without doing arithmetic.
        val cells = buildJsonArray {
            for (id in 1..gridSize * gridSize) {
                val (cx, cy) = VisionAssist.gridToPixel(id, gridSize, screenW, screenH)
                add(buildJsonObject { put("id", id); put("cx", cx); put("cy", cy) })
            }
        }

        // 3) Optional action: convert grid id / fraction → pixels and dispatch.
        var actionStatus: String? = null
        var actionTarget: String? = null
        var actionError: String? = null
        when (action.lowercase()) {
            "tap" -> {
                val gridId = argInt("grid_id", arguments)
                val fx = argDouble("fx", arguments); val fy = argDouble("fy", arguments)
                if (gridId != null) {
                    val (px, py) = VisionAssist.gridToPixel(gridId, gridSize, screenW, screenH)
                    actionTarget = "grid_id=$gridId"
                    actionStatus = if (svc.clickAt(px, py)) "ok" else "failed"
                } else if (fx != null && fy != null) {
                    val (px, py) = VisionAssist.fractionToPixel(fx, fy, screenW, screenH)
                    actionTarget = "fx=$fx,fy=$fy"
                    actionStatus = if (svc.clickAt(px, py)) "ok" else "failed"
                } else {
                    actionError = "Provide grid_id or fx/fy to tap. Look at annotated_path first."
                }
            }
            "swipe" -> {
                val fromId = argInt("from_grid_id", arguments); val toId = argInt("to_grid_id", arguments)
                val fx1 = argDouble("fx1", arguments); val fy1 = argDouble("fy1", arguments)
                val fx2 = argDouble("fx2", arguments); val fy2 = argDouble("fy2", arguments)
                if (fromId != null && toId != null) {
                    val a = VisionAssist.gridToPixel(fromId, gridSize, screenW, screenH)
                    val b = VisionAssist.gridToPixel(toId, gridSize, screenW, screenH)
                    actionTarget = "from=$fromId,to=$toId"
                    actionStatus = if (svc.swipe(a.first, a.second, b.first, b.second)) "ok" else "failed"
                } else if (fx1 != null && fy1 != null && fx2 != null && fy2 != null) {
                    val a = VisionAssist.fractionToPixel(fx1, fy1, screenW, screenH)
                    val b = VisionAssist.fractionToPixel(fx2, fy2, screenW, screenH)
                    actionTarget = "fx1=$fx1,fy1=$fy1,fx2=$fx2,fy2=$fy2"
                    actionStatus = if (svc.swipe(a.first, a.second, b.first, b.second)) "ok" else "failed"
                } else {
                    actionError = "Provide from_grid_id+to_grid_id or fx1/fy1/fx2/fy2 to swipe."
                }
            }
            // "look" — capture only, no action.
        }

        return buildJsonObject {
            put("type", "android_see")
            put("status", "ok")
            put("instruction", instruction)
            put("action", action)
            put("grid_size", gridSize)
            put("screen_width", screenW)
            put("screen_height", screenH)
            put("annotated_path", annotatedPath)
            put("cells", cells)
            put("note", "Ids 1..N² row-major from top-left. Next: action=tap/swipe with grid_id or fx/fy (0..1).")
            actionStatus?.let { put("action_status", it) }
            actionTarget?.let { put("action_target", it) }
            actionError?.let { put("action_error", it) }
        }.toString()
    }

    /** Real screen size in pixels (deprecated path kept for broad API compatibility). */
    private fun screenSize(): Pair<Int, Int> {
        val wm = app.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }


    /**
     * 打开微信并尽力而为地搜到联系人、点进聊天窗口。每一步都返回结构化结果：
     * 中途任一环节失败（搜索入口找不到/输入框定位失败/无匹配）都会给出明确 status 与 hint，
     * 让 AI 回退到 android_read_ui + android_click 继续，而不是神秘失败。
     */
    private fun wechatOpenChat(arguments: String): String {
        val contact = argString("contact", arguments)
        if (contact.isNullOrBlank()) return err("no_contact", "Missing contact.")
        // 1) 打开微信。
        val openFailed = runCatching {
            val launch = app.packageManager.getLaunchIntentForPackage(WECHAT_PACKAGE)
                ?: error("no launcher intent")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(launch)
        }.exceptionOrNull()
        if (openFailed != null) {
            return buildJsonObject {
                put("type", "wechat_open_chat")
                put("status", "open_failed")
                put("contact", contact)
                put("hint", "无法打开微信：${openFailed.message}")
            }.toString()
        }
        val svc = service()
        if (svc == null) {
            return buildJsonObject {
                put("type", "wechat_open_chat")
                put("status", "accessibility_off")
                put("contact", contact)
                put("hint", "已打开微信，但无障碍桥未启用。启用后我才能帮你搜索并进入与「$contact」的聊天；现在只能用 android_read_ui 手工浏览。")
            }.toString()
        }
        // 2) 等待微信真正到前台且主界面就绪（冷启动数秒、可能有启动页/广告），
        //    否则无障碍树仍是上一个应用，后续搜索必然失败。
        if (!waitForWechatHome(svc)) {
            return buildJsonObject {
                put("type", "wechat_open_chat")
                put("contact", contact)
                put("status", "launch_timeout")
                put("hint", "微信已拉起但主界面未就绪（可能在启动页/广告/权限弹窗）。请用 android_read_ui 查看当前界面，处理后重试或手工继续。")
            }.toString()
        }
        return wechatSearchOpen(svc, contact)
    }

    /**
     * 等待微信到达前台，并轮询无障碍树直到主界面特征（底部「微信」tab 或搜索入口）
     * 出现。两条超时都用 [Thread.sleep] 轮询 —— 该工具全程跑在 Dispatchers.IO。
     */
    private fun waitForWechatHome(svc: AndroidUiControllerService): Boolean {
        val launchDeadline = System.currentTimeMillis() + WECHAT_LAUNCH_TIMEOUT_MS
        while (System.currentTimeMillis() < launchDeadline) {
            if (svc.activePackage() == WECHAT_PACKAGE) break
            Thread.sleep(200)
        }
        if (svc.activePackage() != WECHAT_PACKAGE) return false
        val homeDeadline = System.currentTimeMillis() + WECHAT_HOME_TIMEOUT_MS
        while (System.currentTimeMillis() < homeDeadline) {
            val homeVisible = svc.dumpCurrentUi().any { n ->
                val text = n.text?.trim()
                text == "微信" || text == "WeChat" ||
                    n.contentDescription?.contains("搜索") == true ||
                    n.text?.contains("搜索") == true
            }
            if (homeVisible) return true
            Thread.sleep(300)
        }
        return true // 主界面特征没等到也放行：部分版本 dump 不含这些节点，交给后续步骤自行报错。
    }

    private fun wechatSearchOpen(svc: AndroidUiControllerService, contact: String): String {
        // 先逐步执行并记录每一步结果，再统一构造 JSON（避免在 putJsonArray 内层访问外层 builder）。
        val searchClicked = svc.clickByLabel("搜索") || svc.clickByLabel("Search")
        val typed = searchClicked && svc.focusAndInput(contact, null)
        // 搜索结果需要网络往返，轮询等待联系人条目出现后再点击（M10）。
        var opened = false
        if (typed) {
            val resultDeadline = System.currentTimeMillis() + WECHAT_RESULT_TIMEOUT_MS
            while (System.currentTimeMillis() < resultDeadline) {
                if (svc.clickByLabel(contact)) { opened = true; break }
                Thread.sleep(300)
            }
        }
        val (status, hint) = when {
            !searchClicked -> "search_not_found" to
                "未找到微信搜索入口（界面可能已变化）。请用 android_read_ui dump 后手工定位再继续。"
            !typed -> "input_failed" to "未能定位搜索输入框。dump 界面确认焦点后重试。"
            !opened -> "not_found" to "搜索后未匹配到「$contact」的可点击结果。dump 界面确认候选后手工点击。"
            else -> "ok" to null
        }
        return buildJsonObject {
            put("type", "wechat_open_chat")
            put("contact", contact)
            put("status", status)
            hint?.let { put("hint", it) }
            putJsonArray("steps") {
                add(buildJsonObject { put("step", "打开搜索"); put("ok", searchClicked) })
                add(buildJsonObject { put("step", "输入联系人"); put("ok", typed) })
                add(buildJsonObject { put("step", "点开聊天"); put("ok", opened) })
            }
        }.toString()
    }

    private fun argString(key: String, arguments: String): String? =
        argValue(key, arguments) { it.takeIf(String::isNotBlank) }

    private fun argInt(key: String, arguments: String): Int? =
        argValue(key, arguments, String::toIntOrNull)

    private fun argDouble(key: String, arguments: String): Double? =
        argValue(key, arguments, String::toDoubleOrNull)

    /**
     * 统一的单值参数解析（R1：argString/argInt/argDouble 的公共实现）。解析核心
     * 委托 ToolArgHelpers.argPrimitive（W4F 三处重复实现合并）：JSON 非法、字段
     * 缺失或类型不符时一律返回 null，由调用方决定报错文案；值交给 [parse] 前先
     * trim（空白归一化为 null 的语义保留在本类），且与旧实现的异常兜底范围一致
     * （parse 回调抛出的异常同样归一化为 null）。
     */
    private fun <T> argValue(key: String, arguments: String, parse: (String) -> T?): T? =
        argPrimitive(key, arguments)?.content
            ?.let { runCatching { parse(it.trim()) }.getOrNull() }

    private fun resolvePackage(appId: String): String? {
        val id = appId.trim()
        if (id.contains('.')) return id
        return KNOWN_APPS.firstOrNull { it.first == id.lowercase() || it.second == id }?.third
    }

    private fun err(code: String, message: String?): String = toolError("android_error", code, message)

    private companion object {
        private const val WECHAT_PACKAGE = "com.tencent.mm"

        /** wechat_open_chat 各阶段等待上限（冷启动较慢的设备上放得比较宽）。 */
        private const val WECHAT_LAUNCH_TIMEOUT_MS = 10_000L
        private const val WECHAT_HOME_TIMEOUT_MS = 5_000L
        private const val WECHAT_RESULT_TIMEOUT_MS = 5_000L

        /** Keys injected through the accessibility bridge's performGlobalAction. */
        private val GLOBAL_KEYS = setOf("back", "home", "recents", "notifications", "quick_settings")

        /** Named keys injected via 'input keyevent <code>' through the Shizuku/root shell.
         *  Values are standard Android keycodes (KeyEvent.KEYCODE_*). */
        private val KEYCODES = mapOf(
            "volume_up" to 24,
            "volume_down" to 25,
            "volume_mute" to 164,
            "power" to 26,
            "menu" to 82,
            "enter" to 66,
            "backspace" to 67,
            "tab" to 61,
            "space" to 62,
            "escape" to 111,
            "delete" to 112,
            "dpad_up" to 19,
            "dpad_down" to 20,
            "dpad_left" to 21,
            "dpad_right" to 22,
            "dpad_center" to 23,
            "camera" to 27,
        )

        // (alias, display label, package id)
        val KNOWN_APPS = listOf(
            Triple("weixin", "微信", "com.tencent.mm"),
            Triple("wechat", "WeChat", "com.tencent.mm"),
            Triple("qq", "QQ", "com.tencent.mobileqq"),
            Triple("alipay", "支付宝", "com.eg.android.AlipayGphone"),
            Triple("taobao", "淘宝", "com.taobao.taobao"),
            Triple("jingdong", "京东", "com.jingdong.app.mall"),
            Triple("douyin", "抖音", "com.ss.android.ugc.aweme"),
            Triple("weibo", "微博", "com.sina.weibo"),
            Triple("meituan", "美团", "com.sankuai.meituan"),
            Triple("dianping", "大众点评", "com.dianping.v1"),
            Triple("bilibili", "哔哩哔哩", "tv.danmaku.bili"),
            Triple("kgmusic", "酷狗音乐", "com.kugou.android"),
            Triple("qqmusic", "QQ音乐", "com.tencent.qqmusic"),
            Triple("maps", "地图", "com.autonavi.minimap"),
            Triple("settings", "设置", "com.android.settings"),
        )
    }
}
