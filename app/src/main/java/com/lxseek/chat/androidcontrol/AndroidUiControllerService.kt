package com.lxseek.chat.androidcontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread

import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Accessibility bridge that lets a text-only LLM "see" and "touch" the currently focused app.
 *
 * The ToolProvider ([com.lxseek.chat.tool.AndroidAppControllerToolProvider]) reaches this
 * service through the [instance] singleton; no service reference is held directly, so the
 * tool keeps working across the service's own lifecycle (bind/unbind by the user).
 *
 * Safety notes:
 *  - Only reads node content exposed by [canRetrieveWindowContent], never by screenshots.
 *  - Actions are limited to click / set-text / global back & home. Nothing here performs
 *    payments, opening links outside an explicit tool arg, or root.
 *  - The service is inert until the user explicitly enables it in system Accessibility settings.
 */
class AndroidUiControllerService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // 服务被系统销毁时同样清空单例，避免 ToolProvider 持有已死服务的引用。
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Lightweight view of one node, cheap to build and to serialize. */
    data class UiNodeSnapshot(
        val nodeClass: String,
        val text: String?,
        val contentDescription: String?,
        val resourceId: String?,
        val clickable: Boolean,
        val editable: Boolean,
        val enabled: Boolean,
        val checked: Boolean,
        val focused: Boolean,
        val scrollable: Boolean,
        val packageName: String?,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    /** Read-only snapshot of the active window's interactive node tree. */
    fun dumpCurrentUi(maxNodes: Int = 160): List<UiNodeSnapshot> = synchronized(lock) {
        val root = rootInActiveWindow ?: return ArrayList()
        val out = ArrayList<UiNodeSnapshot>()
        val stack = java.util.ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && out.size < maxNodes && visited < maxNodes * 4) {
            val node = stack.poll() ?: break
            visited++
            val snap = snap(node)
            // Drop totally-empty boxes to keep the dump small for the text model.
            val text = snap.text?.takeIf { it.isNotBlank() }
            val desc = snap.contentDescription?.takeIf { it.isNotBlank() }
            if (text != null || desc != null || snap.clickable || snap.editable) {
                out.add(snap)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
            recycleNode(node)
        }
        // 提前退出（节点数/上限）时回收栈中残留节点，避免 API<33 的实例泄漏。
        drainAndRecycle(stack)
        return out
    }

    /** Click the first node whose text or content-description contains [label]. */
    fun clickByLabel(label: String): Boolean = synchronized(lock) {
        val root = rootInActiveWindow ?: return false
        val needle = label.trim()
        val found = findNode(root) { n ->
            val t = n.text?.toString() ?: ""
            val d = n.contentDescription?.toString() ?: ""
            n.isClickable && (t.contains(needle, ignoreCase = true) || d.contains(needle, ignoreCase = true))
        }
        val clicked = found?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        recycleNode(found)
        recycleNode(root)
        clicked
    }

    /** Focus the target (if a hint is given) or an editable node, then type [text]. */
    fun focusAndInput(text: String, targetHint: String?): Boolean = synchronized(lock) {
        val root = rootInActiveWindow ?: return false
        var edit: AccessibilityNodeInfo? = null
        if (!targetHint.isNullOrBlank()) {
            val target = findNode(root) { n ->
                val t = n.text?.toString() ?: ""
                val d = n.contentDescription?.toString() ?: ""
                (t.contains(targetHint, ignoreCase = true) || d.contains(targetHint, ignoreCase = true))
            }
            target?.let { t ->
                t.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                recycleNode(t)
            }
            edit = findNode(root) { n -> n.isEditable && n.isFocused }
        } else {
            edit = findNode(root) { n -> n.isEditable }
        }
        if (edit == null) {
            recycleNode(root)
            return false
        }
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val bundle = Bundle()
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val result = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        recycleNode(edit)
        recycleNode(root)
        result
    }

    /** Tap the visible point (x, y). Prefers clicking the containing clickable node so the app's
     *  onClick semantics fire; falls back to a synthetic tap gesture at that coordinate. */
    fun clickAt(x: Int, y: Int): Boolean = synchronized(lock) {
        val root = rootInActiveWindow ?: return false
        val node = clickableAt(root, x, y)
        val clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        recycleNode(node)
        recycleNode(root)
        if (clicked) return true
        return dispatchGestureBlocking(tapGesture(x, y, 1L))
    }

    /** Long-press the visible point (x, y): node long-click when possible, else a gesture. */
    fun longPressAt(x: Int, y: Int): Boolean = synchronized(lock) {
        val root = rootInActiveWindow ?: return false
        val node = clickableAt(root, x, y)
        val longClicked = node != null && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        recycleNode(node)
        recycleNode(root)
        if (longClicked) return true
        return dispatchGestureBlocking(tapGesture(x, y, 600L))
    }

    /** Swipe between two screen points over [durationMs]; used for scrolling lists and paging. */
    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long = 300L): Boolean = synchronized(lock) {
        val path = Path().apply { moveTo(fromX.toFloat(), fromY.toFloat()); lineTo(toX.toFloat(), toY.toFloat()) }
        val desc = GestureDescription.Builder()
            .addStroke(StrokeDescription(path, 0L, durationMs))
            .build()
        return dispatchGestureBlocking(desc)
    }

    /** Swipe a full directional gesture (up/down/left/right) across a third of the window. */
    fun swipeDirection(direction: String): Boolean = synchronized(lock) {
        val rect = windowRect() ?: return false
        val cx = rect.centerX(); val cy = rect.centerY()
        val dx = rect.width() / 3; val dy = rect.height() / 3
        val (x1, y1, x2, y2) = when (direction.lowercase()) {
            "up" -> Quad(cx, cy + dy, cx, cy - dy)
            "down" -> Quad(cx, cy - dy, cx, cy + dy)
            "left" -> Quad(cx + dx, cy, cx - dx, cy)
            "right" -> Quad(cx - dx, cy, cx + dx, cy)
            else -> return false
        }
        return swipe(x1, y1, x2, y2)
    }

    /**
     * Two-finger pinch zoom: two simultaneous horizontal strokes whose separation grows from
     * [startSpanPx] to [endSpanPx] around (cx, cy) over [durationMs]. endSpan > startSpan zooms
     * in, endSpan < startSpan zooms out. Both strokes run in ONE gesture dispatch so the system
     * sees a real two-pointer gesture.
     */
    fun pinch(cx: Int, cy: Int, startSpanPx: Int, endSpanPx: Int, durationMs: Long = 500L): Boolean = synchronized(lock) {
        if (startSpanPx <= 0 || endSpanPx <= 0) return false
        val finger1 = Path().apply {
            moveTo(cx - startSpanPx / 2f, cy.toFloat())
            lineTo(cx - endSpanPx / 2f, cy.toFloat())
        }
        val finger2 = Path().apply {
            moveTo(cx + startSpanPx / 2f, cy.toFloat())
            lineTo(cx + endSpanPx / 2f, cy.toFloat())
        }
        val desc = GestureDescription.Builder()
            .addStroke(StrokeDescription(finger1, 0L, durationMs))
            .addStroke(StrokeDescription(finger2, 0L, durationMs))
            .build()
        return dispatchGestureBlocking(desc)
    }

    /** Perform a global/system visible action by name: back / home / recents / notifications / quick_settings. */
    fun pressGlobalKey(key: String): Boolean = synchronized(lock) {
        when (key.lowercase()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            else -> false
        }
    }

    /** Clear the focused (or first) editable field. Text models can't see the cursor, so a
     *  destructive backspace is unreliable — clearing the whole field is deterministic. */
    fun clearFocusedText(): Boolean = synchronized(lock) {
        val root = rootInActiveWindow ?: return false
        val edit = findNode(root) { n -> n.isEditable }
        if (edit == null) {
            recycleNode(root)
            return false
        }
        val bundle = Bundle()
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        val result = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        recycleNode(edit)
        recycleNode(root)
        result
    }


    fun goBack(): Boolean = synchronized(lock) {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun goHome(): Boolean = synchronized(lock) {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun activePackage(): String? = synchronized(lock) {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        recycleNode(root)
        pkg
    }

    fun isConnected(): Boolean = synchronized(lock) {
        val root = rootInActiveWindow ?: return false
        recycleNode(root)
        true
    }

    /**
     * Capture the current screen via [AccessibilityService.takeScreenshot] (front-window capture,
     * API 30+) and write a PNG into [cacheDir]. Best-effort: secure surfaces return a failure and
     * the screenshot is not guaranteed on all devices. Returns the saved path on success.
     */
    fun takeScreenshot(cacheDir: File): ScreenshotOutcome {
        if (Build.VERSION.SDK_INT < 30) return ScreenshotOutcome.NotSupported
        val latch = CountDownLatch(1)
        val holder = AtomicReference<ScreenshotResult?>(null)
        val failed = AtomicReference<Int?>(null)
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        holder.set(screenshot)
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        failed.set(errorCode)
                        latch.countDown()
                    }
                },
            )
            // 超时兜底：系统回调彻底丢失时不再永久阻塞调用线程。
            if (!latch.await(5, TimeUnit.SECONDS)) {
                return ScreenshotOutcome.Failure("takeScreenshot timed out")
            }
            val result = holder.get()
                ?: return ScreenshotOutcome.Failure("screenshot failed (code=${failed.get()})")
            // hardwareBuffer 必须显式 close；所有提前返回路径都经由 finally 兜底，杜绝泄漏。
            val buffer = result.hardwareBuffer
                ?: return ScreenshotOutcome.Failure("screenshot returned no buffer")
            try {
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    ?: return ScreenshotOutcome.Failure("could not wrap screenshot buffer")
                val software = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                bitmap.recycle()
                if (software == null) {
                    return ScreenshotOutcome.Failure("could not copy screenshot buffer")
                }
                try {
                    val file = File(cacheDir, "screenshot_${System.currentTimeMillis()}.png")
                    file.outputStream().use { out ->
                        software.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    return ScreenshotOutcome.Success(file.absolutePath)
                } finally {
                    software.recycle()
                }
            } finally {
                buffer.close()
            }
        } catch (e: Exception) {
            return ScreenshotOutcome.Failure(e.message)
        }
    }

    sealed class ScreenshotOutcome {
        data class Success(val path: String) : ScreenshotOutcome()
        data class Failure(val reason: String?) : ScreenshotOutcome()
        object NotSupported : ScreenshotOutcome()
    }

    private fun snap(node: AccessibilityNodeInfo): UiNodeSnapshot {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        return UiNodeSnapshot(
            nodeClass = node.className?.toString() ?: "",
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            resourceId = node.viewIdResourceName,
            clickable = node.isClickable,
            editable = node.isEditable,
            enabled = node.isEnabled,
            checked = node.isChecked,
            focused = node.isFocused,
            scrollable = node.isScrollable,
            packageName = node.packageName?.toString(),
            x = rect.left,
            y = rect.top,
            width = rect.width(),
            height = rect.height(),
        )
    }

    private fun findNode(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val stack = java.util.ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 512) {
            val node = stack.poll() ?: break
            visited++
            if (match(node)) {
                // 命中的节点交给调用方继续使用；栈内其余节点立即回收。
                drainAndRecycle(stack)
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
            // 起点 root 由调用方负责回收，这里只回收遍历中获取的子节点。
            if (node !== root) recycleNode(node)
        }
        return null
    }

    /** Returns the smallest (innermost) clickable node whose bounds contain (x, y), if any. */
    private fun clickableAt(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val stack = java.util.ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        var best: AccessibilityNodeInfo? = null
        var bestArea = Int.MAX_VALUE
        while (stack.isNotEmpty() && visited < 512) {
            val node = stack.poll() ?: break
            visited++
            val rect = Rect()
            node.getBoundsInScreen(rect)
            var recycled = false
            if (rect.contains(x, y)) {
                if (node.isClickable) {
                    val area = rect.width() * rect.height()
                    if (area < bestArea) {
                        // 新命中：旧命中让位回收，node 移交给 best。
                        best?.let { recycleNode(it) }
                        bestArea = area
                        best = node
                        recycled = true
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.add(it) }
                }
            }
            if (!recycled && node !== root) recycleNode(node)
        }
        drainAndRecycle(stack)
        return best
    }

    /**
     * API<33 时 [AccessibilityNodeInfo] 实例由调用方负责回收（官方在 API 33 起改为
     * 自动管理）。回收失败不影响主流程，仅记录日志。
     */
    private fun recycleNode(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (Build.VERSION.SDK_INT < 33) {
            try {
                node.recycle()
            } catch (_: Exception) {
                // 个别 ROM 上节点已被系统回收，忽略即可。
            }
        }
    }

    /** 清空节点栈并逐个回收，用于遍历提前退出的场景。 */
    private fun drainAndRecycle(stack: java.util.ArrayDeque<AccessibilityNodeInfo>) {
        while (stack.isNotEmpty()) {
            recycleNode(stack.poll())
        }
    }

    /** A single-stroke gesture at a point, [durationMs] long (1ms = tap, ~600ms = long press). */
    private fun tapGesture(x: Int, y: Int, durationMs: Long): GestureDescription {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return GestureDescription.Builder()
            .addStroke(StrokeDescription(path, 0L, durationMs))
            .build()
    }

    /**
     * Dispatches a gesture and blocks (bounded by [GESTURE_TIMEOUT_MS]) for the callback.
     *
     * 回调跑在专用的后台线程（见 [gestureHandler]）而非主线程：此前回调建在
     * mainLooper 上，一旦本方法在主线程被调用，latch.await() 与回调互相等待，
     * 造成主线程假死（H1 死锁）。回调不依赖主线程后，即使调用方在主线程，
     * latch 也能由后台回调正常 countDown；超时兜底保证回调彻底丢失时也不会无限阻塞。
     */
    private fun dispatchGestureBlocking(desc: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        val done = AtomicReference<Boolean?>(null)
        if (!dispatchGesture(desc, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done.set(true); latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { done.set(false); latch.countDown() }
        }, gestureHandler)) return false
        // 8s 覆盖最长的 5s 手势 + 系统调度余量。
        if (!latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return false
        return done.get() ?: false
    }

    /** Bounds of the active window's root node, used to derive gesture geometry. */
    private fun windowRect(): Rect? = synchronized(lock) {
        val root = rootInActiveWindow ?: return null
        val rect = Rect()
        root.getBoundsInScreen(rect)
        recycleNode(root)
        rect
    }

    /** Tiny holder for a 4-int coordinate quadruple used by [swipeDirection]. */
    private data class Quad(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

    companion object {
        private val lock = Any()

        /** Set on [onServiceConnected], cleared on [onUnbind]. Never holds a strong view owner. */
        @Volatile
        var instance: AndroidUiControllerService? = null

        /** 手势回调专用后台线程：回调不依赖主线程，避免主线程 await 时的相互等待。 */
        private val gestureHandlerThread: HandlerThread =
            HandlerThread("ui-gesture-callback").apply { start() }

        private val gestureHandler = Handler(gestureHandlerThread.looper)

        /** 截图回调执行器：进程内复用，避免每次截图新建/销毁线程池。 */
        private val screenshotExecutor: ExecutorService =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "ui-screenshot").apply { isDaemon = true }
            }

        /** 手势最长 5s（pinch 上限）+ 调度余量。 */
        private const val GESTURE_TIMEOUT_MS = 8_000L
    }
}