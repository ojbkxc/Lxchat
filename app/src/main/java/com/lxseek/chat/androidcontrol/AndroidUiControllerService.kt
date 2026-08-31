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
import android.os.Looper
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
        }
        return out as List<UiNodeSnapshot>
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
        found?.let { node ->
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK) && true
        } ?: return false
    }

    /** Focus the target (if a hint is given) or an editable node, then type [text]. */
    fun focusAndInput(text: String, targetHint: String?): Boolean = synchronized(lock) {
        val root = rootInActiveWindow ?: return false
        var edit: AccessibilityNodeInfo?
        if (!targetHint.isNullOrBlank()) {
            // A hint was given: click the node labelled by it first to bring the input into focus.
            val target = findNode(root) { n ->
                val t = n.text?.toString() ?: ""
                val d = n.contentDescription?.toString() ?: ""
                t.contains(targetHint, ignoreCase = true) || d.contains(targetHint, ignoreCase = true)
            }
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            edit = findNode(root) { n -> n.isEditable && n.isFocused }
        } else {
            edit = findNode(root) { n -> n.isEditable }
        }
        if (edit == null) return false
        // Bring it into focus, then set the text directly (no flaky IME typing).
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    /** Tap the visible point (x, y). Prefers clicking the containing clickable node so the app's
     *  onClick semantics fire; falls back to a synthetic tap gesture at that coordinate. */
    fun clickAt(x: Int, y: Int): Boolean = synchronized(lock) {
        val root = rootInActiveWindow ?: return false
        val node = clickableAt(root, x, y)
        if (node != null) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return dispatchGestureBlocking(tapGesture(x, y, 1L))
    }

    /** Long-press the visible point (x, y): node long-click when possible, else a gesture. */
    fun longPressAt(x: Int, y: Int): Boolean = synchronized(lock) {
        val root = rootInActiveWindow ?: return false
        val node = clickableAt(root, x, y)
        if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
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
    fun pressGlobalKey(key: String): Boolean = synchronized(lock) {        when (key.lowercase()) {
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
        val edit = findNode(root) { n -> n.isEditable && n.isFocused }
            ?: findNode(root) { n -> n.isEditable }
            ?: return false
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    fun goBack(): Boolean = synchronized(lock) {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun goHome(): Boolean = synchronized(lock) {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun activePackage(): String? = synchronized(lock) {
        rootInActiveWindow?.packageName?.toString()
    }

    fun isConnected(): Boolean = synchronized(lock) {
        rootInActiveWindow != null
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
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        holder.set(screenshot)
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                },
            )
            if (!latch.await(5, TimeUnit.SECONDS)) return ScreenshotOutcome.Failure("takeScreenshot timed out")
            val result = holder.get() ?: return ScreenshotOutcome.Failure("screenshot failed")
            val buffer = result.hardwareBuffer
            val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                ?: return ScreenshotOutcome.Failure("could not wrap screenshot buffer")
            val software = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val file = File(cacheDir, "screenshot_${System.currentTimeMillis()}.png")
            file.outputStream().use { out ->
                software.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            software.recycle()
            buffer.close()
            return ScreenshotOutcome.Success(file.absolutePath)
        } catch (e: Exception) {
            return ScreenshotOutcome.Failure(e.message)
        } finally {
            executor.shutdown()
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
                // Recycle nothing yet; caller owns cleanup.
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
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
            if (rect.contains(x, y)) {
                if (node.isClickable) {
                    val area = rect.width() * rect.height()
                    if (area < bestArea) { bestArea = area; best = node }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.add(it) }
                }
            }
        }
        return best
    }

    /** A single-stroke gesture at a point, [durationMs] long (1ms = tap, ~600ms = long press). */
    private fun tapGesture(x: Int, y: Int, durationMs: Long): GestureDescription {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return GestureDescription.Builder()
            .addStroke(StrokeDescription(path, 0L, durationMs))
            .build()
    }

    /** Dispatches a gesture on the accessibility thread and blocks (≤2s) for the callback. */
    private fun dispatchGestureBlocking(desc: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        val done = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val handler = Handler(Looper.getMainLooper())
        if (!dispatchGesture(desc, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done.set(true); latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { done.set(false); latch.countDown() }
        }, handler)) return false
        if (!latch.await(2, TimeUnit.SECONDS)) return false
        return done.get() ?: false
    }

    /** Bounds of the active window's root node, used to derive gesture geometry. */
    private fun windowRect(): Rect? = synchronized(lock) {
        val root = rootInActiveWindow ?: return null
        val rect = Rect()
        root.getBoundsInScreen(rect)
        rect
    }

    /** Tiny holder for a 4-int coordinate quadruple used by [swipeDirection]. */
    private data class Quad(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

    companion object {
        private val lock = Any()

        /** Set on [onServiceConnected], cleared on [onUnbind]. Never holds a strong view owner. */
        @Volatile
        var instance: AndroidUiControllerService? = null
    }
}