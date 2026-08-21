package com.lxseek.chat.pet

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.lxseek.chat.MainActivity
import kotlin.math.hypot

/**
 * The draggable "pet" bubble shown on the system overlay.
 *
 * Tap (a short, low-movement touch) launches [MainActivity] so the pet becomes a zero-navigation
 * shortcut back into LxChat. Drag moves the bubble around the screen; the bubble holds its new
 * position for the lifetime of the surrounding [PetOverlayWindowService], which persists on the
 * same [WindowManager.LayoutParams].
 *
 * The faces/bubble are drawn directly in [onDraw] so we ship zero GIF/animation assets for the
 * minimal v1 — a static, self-contained bubble that stays cheap and lint-clean.
 */
class PetFloatingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Set once by the owning service before the view is shown. Never mutated afterwards. */
    private var windowParams: WindowManager.LayoutParams? = null

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PET_BLUE }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PET_WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val featurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PET_WHITE }

    // Touch bookkeeping used to distinguish a tap from a drag.
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0
    private var wasDragging = false

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    /** Binds the [WindowManager.LayoutParams] that [PetOverlayWindowService] moves on drags. */
    fun bindWindowParams(params: WindowManager.LayoutParams) {
        windowParams = params
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = (minOf(w, h) - dp(4f)) / 2f

        // Bubble body.
        canvas.drawCircle(cx, cy, radius, bubblePaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // Simple friendly face: two eyes + a smile, centered and scaled to the bubble.
        val featureRadius = radius * 0.085f
        val eyeY = cy - radius * 0.15f
        val eyeGap = radius * 0.48f
        // Eyes.
        canvas.drawCircle(cx - eyeGap, eyeY, featureRadius, featurePaint)
        canvas.drawCircle(cx + eyeGap, eyeY, featureRadius, featurePaint)
        // Smile arc.
        val smilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PET_WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(
            cx - radius * 0.45f,
            cy + radius * 0.10f,
            cx + radius * 0.45f,
            cy + radius * 0.72f,
            20f,
            140f,
            false,
            smilePaint,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val params = windowParams
        // Never intercept the explicit onDismiss (the service removal path is out of band),
        // and without bound params we have nothing to move.
        if (params == null) {
            return performClickFallback(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downRawX = event.rawX
                downRawY = event.rawY
                downWindowX = params.x
                downWindowY = params.y
                wasDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                if (!wasDragging && hypot(event.rawX - downRawX, event.rawY - downRawY) > DRAG_THRESHOLD_DP * resources.displayMetrics.density) {
                    wasDragging = true
                }
                if (wasDragging) {
                    params.x = downWindowX + dx
                    params.y = downWindowY + dy
                    try {
                        context.getSystemService(Context.WINDOW_SERVICE)?.let { wm ->
                            (wm as WindowManager).updateViewLayout(this, params)
                        }
                    } catch (_: IllegalArgumentException) {
                        // View already detached; ignore mid-drag teardown.
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!wasDragging) {
                    launchApp()
                    return true
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun performClickFallback(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) launchApp()
        return true
    }

    /** Opens the app from the overlay. Minimal flags: NEW_TASK is mandatory off of an Activity. */
    private fun launchApp() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Swallow resolution/background-start edge cases; the overlay persists harmlessly.
        }
    }

    private companion object {
        const val PET_BLUE = 0xFF3B82F6.toInt()
        const val PET_WHITE = 0xFFFFFFFF.toInt()
        const val DRAG_THRESHOLD_DP = 14f
    }
}