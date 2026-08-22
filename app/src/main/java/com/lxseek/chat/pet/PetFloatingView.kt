package com.lxseek.chat.pet

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
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
 * The bubble and face are drawn directly in [onDraw] so we ship zero GIF/animation assets — a
 * self-contained, modern chat-style bubble with a radial gradient body, soft drop shadow, white
 * border, big glossy eyes with highlights, blush, and a friendly smile. The radial gradient is
 * built once in [onSizeChanged] to avoid per-frame allocation.
 */
class PetFloatingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Set once by the owning service before the view is shown. Never mutated afterwards. */
    private var windowParams: WindowManager.LayoutParams? = null

    // Bubble body — shader is assigned in onSizeChanged once the size is known.
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Soft drop shadow under the bubble for a floating feel.
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SHADOW_COLOR
        alpha = SHADOW_ALPHA
    }
    // Crisp white border around the bubble.
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PET_WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    // Eye whites.
    private val eyeWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PET_WHITE }
    // Dark pupils.
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PUPIL_COLOR }
    // Tiny specular highlight on each eye.
    private val eyeHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PET_WHITE }
    // Semi-transparent pink blush on the cheeks.
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUSH_COLOR }
    // The smile arc.
    private val smilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PET_WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }

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

    /**
     * Builds the radial gradient once per layout pass instead of every frame. The light source is
     * offset toward the upper-left so the bubble reads as a glossy sphere lit from that direction.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val radius = (minOf(w, h) - dp(BORDER_PADDING_DP)) / 2f
        bubblePaint.shader = RadialGradient(
            cx - radius * LIGHT_OFFSET,
            cy - radius * LIGHT_OFFSET,
            radius * GRADIENT_RADIUS_SCALE,
            intArrayOf(BUBBLE_LIGHT, BUBBLE_MID, BUBBLE_DARK),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = (minOf(w, h) - dp(BORDER_PADDING_DP)) / 2f

        // 1. Soft drop shadow — a slightly larger, semi-transparent circle offset downward.
        canvas.drawCircle(cx, cy + dp(SHADOW_OFFSET_DP), radius + dp(SHADOW_SPREAD_DP), shadowPaint)

        // 2. Glossy gradient bubble body.
        canvas.drawCircle(cx, cy, radius, bubblePaint)

        // 3. White border on top of the body.
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // 4. Big, round, glossy eyes — whites + pupils + specular highlights.
        val eyeY = cy - radius * EYE_VERTICAL_RATIO
        val eyeGap = radius * EYE_GAP_RATIO
        val eyeRadius = radius * EYE_RADIUS_RATIO
        val pupilRadius = eyeRadius * PUPIL_SCALE
        val highlightRadius = eyeRadius * HIGHLIGHT_SCALE
        val highlightOffset = eyeRadius * HIGHLIGHT_OFFSET_SCALE
        // Left eye.
        canvas.drawCircle(cx - eyeGap, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawCircle(cx - eyeGap, eyeY + eyeRadius * PUPIL_DROP_SCALE, pupilRadius, pupilPaint)
        canvas.drawCircle(
            cx - eyeGap - highlightOffset,
            eyeY - highlightOffset,
            highlightRadius,
            eyeHighlightPaint,
        )
        // Right eye.
        canvas.drawCircle(cx + eyeGap, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawCircle(cx + eyeGap, eyeY + eyeRadius * PUPIL_DROP_SCALE, pupilRadius, pupilPaint)
        canvas.drawCircle(
            cx + eyeGap - highlightOffset,
            eyeY - highlightOffset,
            highlightRadius,
            eyeHighlightPaint,
        )

        // 5. Pink blush on both cheeks.
        val blushY = cy + radius * BLUSH_VERTICAL_RATIO
        val blushGap = radius * BLUSH_GAP_RATIO
        val blushRadius = radius * BLUSH_RADIUS_RATIO
        canvas.drawCircle(cx - blushGap, blushY, blushRadius, blushPaint)
        canvas.drawCircle(cx + blushGap, blushY, blushRadius, blushPaint)

        // 6. Friendly smile — a rounded, natural arc.
        canvas.drawArc(
            cx - radius * SMILE_WIDTH_RATIO,
            cy + radius * SMILE_TOP_RATIO,
            cx + radius * SMILE_WIDTH_RATIO,
            cy + radius * SMILE_BOTTOM_RATIO,
            SMILE_START_ANGLE,
            SMILE_SWEEP_ANGLE,
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
        // Original palette — kept for compatibility; PET_BLUE is now the gradient midpoint.
        const val PET_BLUE = 0xFF3B82F6.toInt()
        const val PET_WHITE = 0xFFFFFFFF.toInt()

        // Bubble radial gradient stops: light highlight → brand blue → deep blue rim.
        const val BUBBLE_LIGHT = 0xFF93C5FD.toInt()
        const val BUBBLE_MID = PET_BLUE // brand blue sits at the gradient midpoint
        const val BUBBLE_DARK = 0xFF1D4ED8.toInt()
        // Soft, semi-transparent deep-blue shadow under the bubble.
        const val SHADOW_COLOR = 0xFF1E3A8A.toInt()
        const val SHADOW_ALPHA = 64 // ~25% opacity — gentle, not muddy.
        // Dark, near-navy pupils for a cute, focused gaze.
        const val PUPIL_COLOR = 0xFF1E3A8A.toInt()
        // Translucent pink blush (ARGB: 0x33 alpha ≈ 20%).
        const val BLUSH_COLOR = 0x33FF8FAB.toInt()

        // Geometry constants (dp where suffixed, ratios of the bubble radius otherwise).
        const val BORDER_PADDING_DP = 6f
        const val SHADOW_OFFSET_DP = 3f
        const val SHADOW_SPREAD_DP = 1f
        // Light source offset and gradient reach, as a fraction of the radius.
        const val LIGHT_OFFSET = 0.25f
        const val GRADIENT_RADIUS_SCALE = 1.3f

        // Face feature ratios (relative to bubble radius).
        const val EYE_VERTICAL_RATIO = 0.12f
        const val EYE_GAP_RATIO = 0.32f
        const val EYE_RADIUS_RATIO = 0.16f
        const val PUPIL_SCALE = 0.6f
        const val PUPIL_DROP_SCALE = 0.1f // pupils sit just below the eye center.
        const val HIGHLIGHT_SCALE = 0.35f
        const val HIGHLIGHT_OFFSET_SCALE = 0.35f
        const val BLUSH_VERTICAL_RATIO = 0.08f
        const val BLUSH_GAP_RATIO = 0.55f
        const val BLUSH_RADIUS_RATIO = 0.13f
        const val SMILE_WIDTH_RATIO = 0.35f
        const val SMILE_TOP_RATIO = 0.05f
        const val SMILE_BOTTOM_RATIO = 0.55f
        const val SMILE_START_ANGLE = 20f
        const val SMILE_SWEEP_ANGLE = 140f

        const val DRAG_THRESHOLD_DP = 14f
    }
}