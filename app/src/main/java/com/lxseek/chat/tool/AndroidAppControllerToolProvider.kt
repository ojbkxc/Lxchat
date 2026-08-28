package com.lxseek.chat.tool

import android.app.Application
import android.content.Intent
import com.lxseek.chat.androidcontrol.AndroidUiControllerService
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
                description = "Dump the current screen's interactive elements (labels, buttons, input fields) from the accessibility node tree. Use this after opening an app or after any action to see what is on screen now.",
                parameters = ToolParameters(properties = emptyMap()),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_click",
                description = "Tap the visible element whose text or content-description contains the given label (for example the 'Send' button, a contact name, a menu row). Requires the accessibility bridge.",
                parameters = ToolParameters(
                    properties = mapOf("label" to prop("string", "Text shown on the element to tap, e.g. 'Send' or '搜索'.")),
                    required = listOf("label"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "android_input",
                description = "Type text into an input field. If 'into' is given, tap that field first to focus it; otherwise types into the current focused/editable field. Set the text directly; no IME is needed.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "text" to prop("string", "The text to type."),
                        "into" to prop("string", "Optional label of the input field to focus first."),
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
        "android_accessibility_status", "android_read_ui", "android_known_apps" -> RiskLevel.ReadOnly
        "android_open_app", "android_go_back", "android_go_home" -> RiskLevel.LowRisk
        // Clicking/typing inside another app can cause real side effects (sending, posting).
        "android_click", "android_input", "wechat_open_chat" -> RiskLevel.Moderate
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
                "android_go_back" -> goBackJson()
                "android_go_home" -> goHomeJson()
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

    private fun readUiJson(): String = buildJsonObject {
        put("type", "android_ui_dump")
        val svc = service()
        if (svc == null) {
            put("error", "accessibility_off")
            put("items", buildJsonArray { })
        } else {
            svc.activePackage()?.let { put("activePackage", it) }
            put("items", buildJsonArray {
                svc.dumpCurrentUi().forEach { n ->
                    add(buildJsonObject {
                        put("class", n.nodeClass)
                        n.text?.let { put("text", it) }
                        n.contentDescription?.let { put("contentDesc", it) }
                        n.resourceId?.let { put("resourceId", it) }
                        put("clickable", n.clickable)
                        put("editable", n.editable)
                        put("x", n.x); put("y", n.y); put("w", n.width); put("h", n.height)
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

    private fun click(arguments: String): String {
        val label = argString("label", arguments)
        val svc = service() ?: return err("accessibility_off", "Enable accessibility first.")
        if (label.isNullOrBlank()) return err("no_label", "Missing label.")
        val clicked = svc.clickByLabel(label)
        return buildJsonObject {
            put("type", "android_click")
            put("status", if (clicked) "ok" else "not_found")
            put("label", label)
            if (!clicked) put("hint", "Element not found. Dump the UI with android_read_ui to see exact labels.")
        }.toString()
    }

    private fun input(arguments: String): String {
        val text = argString("text", arguments)
        val into = argString("into", arguments)
        val svc = service() ?: return err("accessibility_off", "Enable accessibility first.")
        if (text.isNullOrBlank()) return err("no_text", "Missing text.")
        val ok = svc.focusAndInput(text, into)
        return buildJsonObject {
            put("type", "android_input")
            put("status", if (ok) "ok" else "error")
            if (!ok) put("hint", "No editable field found/focused. Dump UI and click the field first.")
        }.toString()
    }

    private fun goBackJson(): String {
        val ok = service()?.goBack() == true
        return buildJsonObject { put("type", "android_go_back"); put("status", if (ok) "ok" else "error") }.toString()
    }

    private fun goHomeJson(): String {
        val ok = service()?.goHome() == true
        return buildJsonObject { put("type", "android_go_home"); put("status", if (ok) "ok" else "error") }.toString()
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
        return wechatSearchOpen(svc, contact)
    }

    private fun wechatSearchOpen(svc: AndroidUiControllerService, contact: String): String {
        // 先逐步执行并记录每一步结果，再统一构造 JSON（避免在 putJsonArray 内层访问外层 builder）。
        val searchClicked = svc.clickByLabel("搜索") || svc.clickByLabel("Search")
        val typed = searchClicked && svc.focusAndInput(contact, null)
        val opened = typed && svc.clickByLabel(contact)
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

    private fun argString(key: String, arguments: String): String? {
        val stripped = arguments.ifBlank { "{}" }
        return try {
            val el = Json.decodeFromString<Map<String, JsonPrimitive>>(stripped)[key]?.content
            el?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolvePackage(appId: String): String? {
        val id = appId.trim()
        if (id.contains('.')) return id
        KNOWN_APPS.firstOrNull { it.first == id.lowercase() || it.second == id }?.let { return it.third }
        return KNOWN_APPS.firstOrNull { it.second.contains(id, ignoreCase = true) }?.third
    }

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "android_error")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()

    private companion object {
        private const val WECHAT_PACKAGE = "com.tencent.mm"

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
