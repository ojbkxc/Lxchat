package com.lxseek.chat.androidcontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
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
                    override fun onSuccess(screenshot: ScreenshotResult?) {
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

    companion object {
        private val lock = Any()

        /** Set on [onServiceConnected], cleared on [onUnbind]. Never holds a strong view owner. */
        @Volatile
        var instance: AndroidUiControllerService? = null
    }
}