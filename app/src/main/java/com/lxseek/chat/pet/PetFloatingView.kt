package com.lxseek.chat.pet

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RadialGradient
import android.graphics.Shader

import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.lxseek.chat.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 *
 * The view also supports a user-supplied [Bitmap] (typically a transparent PNG) via
 * [setCustomBitmap]; when present it is drawn instead of the default Canvas bubble. The whole
 * view is rendered at 70% opacity (30% transparency) via [Canvas.saveLayerAlpha]. Touch events
 * that land on fully transparent pixels of the custom bitmap are passed through to the app below
 * so the pet does not block interaction with the underlying window. After a drag the bubble
 * animates to the nearest horizontal screen edge ("edge snapping").
 */
class PetFloatingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Set once by the owning service before the view is shown. Never mutated afterwards. */
    private var windowParams: WindowManager.LayoutParams? = null

    /** User-supplied bitmap; when non-null it replaces the default Canvas bubble. */
    @Volatile
    private var customBitmap: Bitmap? = null

    /** Reusable paint for drawing the custom bitmap (alpha is applied via saveLayerAlpha). */
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** Destination rect for the custom bitmap, recomputed in onSizeChanged. */
    private val bitmapDstRect = Rect()

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

    /** Active edge-snap animator; cancelled if a new drag begins. */
    private var snapAnimator: ValueAnimator? = null

    /** Latest emotion driving the face; updated by the [PetEmotionController] observer. */
    @Volatile
    private var currentEmotion: PetEmotion = PetEmotion.IDLE
    /** Collects [PetEmotionController.emotion] while attached so the bubble reacts to the agent. */
    private var emotionScope: CoroutineScope? = null

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        emotionScope = scope
        scope.launch {
            PetEmotionController.emotion.collect { emotion ->
                if (emotion != currentEmotion) {
                    currentEmotion = emotion
                    invalidate()
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        emotionScope?.cancel()
        emotionScope = null
        cancelSnapAnimation()
    }

    /** Binds the [WindowManager.LayoutParams] that [PetOverlayWindowService] moves on drags. */
    fun bindWindowParams(params: WindowManager.LayoutParams) {
        windowParams = params
    }

    /**
     * Sets the user-supplied bitmap. Pass `null` to clear and fall back to the default Canvas
     * bubble. The bitmap is drawn at 70% opacity alongside the rest of the view. A defensive copy
     * is not taken — callers should hand over a bitmap they do not mutate.
     */
    fun setCustomBitmap(bitmap: Bitmap?) {
        customBitmap = bitmap
        // Force a re-layout so bitmapDstRect is recomputed for the new aspect ratio.
        requestLayout()
        invalidate()
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
        bitmapDstRect.set(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        // Apply 30% transparency (70% opacity) to everything drawn in this frame. saveLayerAlpha
        // is the simplest way to uniformly dim both the default Canvas bubble and any custom
        // bitmap without touching every Paint. The layer is bounded to this view's rect.
        val savedLayer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), OVERLAY_ALPHA)
        try {
            val bitmap = customBitmap
            if (bitmap != null && !bitmap.isRecycled) {
                drawCustomBitmap(canvas, bitmap)
            } else {
                drawDefaultBubble(canvas)
            }
        } finally {
            canvas.restoreToCount(savedLayer)
        }
    }

    /** Draws the user-supplied bitmap filling the view bounds. */
    private fun drawCustomBitmap(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawBitmap(bitmap, null, bitmapDstRect, bitmapPaint)
    }

    /** Draws the default Canvas bubble — gradient body, face, blush, smile. */
    private fun drawDefaultBubble(canvas: Canvas) {
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

        // 4. Face — eyes and mouth vary with the current emotion.
        val eyeY = cy - radius * EYE_VERTICAL_RATIO
        val eyeGap = radius * EYE_GAP_RATIO
        val eyeRadius = radius * EYE_RADIUS_RATIO
        val pupilRadius = eyeRadius * PUPIL_SCALE
        val highlightRadius = eyeRadius * HIGHLIGHT_SCALE
        val highlightOffset = eyeRadius * HIGHLIGHT_OFFSET_SCALE

        when (currentEmotion) {
            // Worried / thinking: pupils shift up, mouth becomes a small "o".
            PetEmotion.THINKING -> {
                drawEyes(canvas, cx - eyeGap, cx + eyeGap, eyeY, eyeRadius, pupilRadius, highlightRadius, highlightOffset, pupilDrop = -0.28f)
                drawOpenMouth(canvas, cx, cy, radius, small = true)
            }
            // Delighted: eyes drawn as happy arcs (^ ^), wide open smile.
            PetEmotion.HAPPY -> {
                drawHappyEyes(canvas, cx - eyeGap, cx + eyeGap, eyeY, eyeRadius)
                drawWideSmile(canvas, cx, cy, radius)
            }
            // Upset: eyes shift down with a frown.
            PetEmotion.SAD -> {
                drawEyes(canvas, cx - eyeGap, cx + eyeGap, eyeY, eyeRadius, pupilRadius, highlightRadius, highlightOffset, pupilDrop = 0.35f)
                drawFrown(canvas, cx, cy, radius)
            }
            // Error: eyes become flat dashes, mouth is a straight flat line.
            PetEmotion.ERROR -> {
                drawFlatEyes(canvas, cx - eyeGap, cx + eyeGap, eyeY, eyeRadius)
                drawFlatMouth(canvas, cx, cy, radius)
            }
            // Default / idle: the friendly smile.
            PetEmotion.IDLE -> {
                drawEyes(canvas, cx - eyeGap, cx + eyeGap, eyeY, eyeRadius, pupilRadius, highlightRadius, highlightOffset, pupilDrop = PUPIL_DROP_SCALE)
                drawSmile(canvas, cx, cy, radius)
            }
        }

        // 5. Pink blush on both cheeks.
        val blushY = cy + radius * BLUSH_VERTICAL_RATIO
        val blushGap = radius * BLUSH_GAP_RATIO
        val blushRadius = radius * BLUSH_RADIUS_RATIO
        canvas.drawCircle(cx - blushGap, blushY, blushRadius, blushPaint)
        canvas.drawCircle(cx + blushGap, blushY, blushRadius, blushPaint)
    }

    /** Standard round eyes with a pupil at [pupilDrop] (fraction of eye radius, +down / -up). */
    private fun drawEyes(
        canvas: Canvas,
        leftX: Float,
        rightX: Float,
        eyeY: Float,
        eyeRadius: Float,
        pupilRadius: Float,
        highlightRadius: Float,
        highlightOffset: Float,
        pupilDrop: Float,
    ) {
        canvas.drawCircle(leftX, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawCircle(leftX, eyeY + eyeRadius * pupilDrop, pupilRadius, pupilPaint)
        canvas.drawCircle(leftX - highlightOffset, eyeY - highlightOffset, highlightRadius, eyeHighlightPaint)
        canvas.drawCircle(rightX, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawCircle(rightX, eyeY + eyeRadius * pupilDrop, pupilRadius, pupilPaint)
        canvas.drawCircle(rightX - highlightOffset, eyeY - highlightOffset, highlightRadius, eyeHighlightPaint)
    }

    /** Happy "^^" eyes: two white circles with a thin upward arc instead of a pupil. */
    private fun drawHappyEyes(canvas: Canvas, leftX: Float, rightX: Float, eyeY: Float, eyeRadius: Float) {
        canvas.drawCircle(leftX, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawArc(
            leftX - eyeRadius * 0.7f, eyeY - eyeRadius * 0.7f,
            leftX + eyeRadius * 0.7f, eyeY + eyeRadius * 0.7f,
            200f, 140f, false, pupilPaint,
        )
        canvas.drawCircle(rightX, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawArc(
            rightX - eyeRadius * 0.7f, eyeY - eyeRadius * 0.7f,
            rightX + eyeRadius * 0.7f, eyeY + eyeRadius * 0.7f,
            200f, 140f, false, pupilPaint,
        )
    }

    /** Error eyes: flat horizontal strokes instead of round pupils. */
    private fun drawFlatEyes(canvas: Canvas, leftX: Float, rightX: Float, eyeY: Float, eyeRadius: Float) {
        val stroke = pupilPaint.strokeWidth
        pupilPaint.strokeWidth = dp(1.6f)
        pupilPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawCircle(leftX, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawLine(
            leftX - eyeRadius * 0.55f, eyeY, leftX + eyeRadius * 0.55f, eyeY, pupilPaint,
        )
        canvas.drawCircle(rightX, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawLine(
            rightX - eyeRadius * 0.55f, eyeY, rightX + eyeRadius * 0.55f, eyeY, pupilPaint,
        )
        pupilPaint.strokeWidth = stroke
    }

    /** The default friendly smile arc. */
    private fun drawSmile(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
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

    /** A wide, open happy smile. */
    private fun drawWideSmile(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawArc(
            cx - radius * SMILE_WIDTH_RATIO * 1.2f,
            cy + radius * SMILE_TOP_RATIO,
            cx + radius * SMILE_WIDTH_RATIO * 1.2f,
            cy + radius * SMILE_BOTTOM_RATIO * 1.25f,
            10f,
            160f,
            false,
            smilePaint,
        )
    }

    /** A small "o" mouth (thinking / unsure). */
    private fun drawOpenMouth(canvas: Canvas, cx: Float, cy: Float, radius: Float, small: Boolean) {
        val r = if (small) radius * 0.12f else radius * 0.2f
        canvas.drawCircle(cx, cy + radius * 0.28f, r, smilePaint)
    }

    /** A sad frown: an arc curving downward. */
    private fun drawFrown(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawArc(
            cx - radius * SMILE_WIDTH_RATIO,
            cy + radius * 0.05f,
            cx + radius * SMILE_WIDTH_RATIO,
            cy + radius * 0.6f,
            SMILE_START_ANGLE + 180f,
            SMILE_SWEEP_ANGLE,
            false,
            smilePaint,
        )
    }

    /** A flat mouth line (error / neutral-displeased). */
    private fun drawFlatMouth(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val stroke = smilePaint.strokeWidth
        smilePaint.strokeWidth = dp(3f)
        smilePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(
            cx - radius * SMILE_WIDTH_RATIO,
            cy + radius * 0.32f,
            cx + radius * SMILE_WIDTH_RATIO,
            cy + radius * 0.32f,
            smilePaint,
        )
        smilePaint.strokeWidth = stroke
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val params = windowParams
        // Never intercept the explicit onDismiss (the service removal path is out of band),
        // and without bound params we have nothing to move.
        if (params == null) {
            return performClickFallback(event)
        }
        // Transparent-pixel pass-through: if the touch lands on a fully transparent pixel of the
        // custom bitmap, decline the event so it falls through to the app underneath. We only
        // check on ACTION_DOWN because once we accept the stream we must keep consuming it.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isTransparentAt(event.x, event.y)) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSnapAnimation()
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
                    updateWindowLayout(params)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!wasDragging) {
                    launchApp()
                    return true
                }
                // Drag finished — animate to the nearest horizontal edge.
                snapToNearestEdge(params)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Returns `true` if the custom bitmap has a fully-transparent pixel at the given view-local
     * coordinates. Always returns `false` when no custom bitmap is set (the default bubble is a
     * solid circle and should never pass touches through).
     */
    private fun isTransparentAt(x: Float, y: Float): Boolean {
        val bitmap = customBitmap ?: return false
        if (bitmap.isRecycled) return false
        val bw = bitmap.width
        val bh = bitmap.height
        if (bw <= 0 || bh <= 0) return false
        // Map view-local coords to bitmap coords. The bitmap is drawn filling bitmapDstRect.
        val rect = bitmapDstRect
        if (rect.width() <= 0 || rect.height() <= 0) return false
        if (x < rect.left || x > rect.right || y < rect.top || y > rect.bottom) return false
        val bx = ((x - rect.left) / rect.width() * bw).toInt().coerceIn(0, bw - 1)
        val by = ((y - rect.top) / rect.height() * bh).toInt().coerceIn(0, bh - 1)
        return bitmap.getPixel(bx, by).ushr(24) == 0
    }

    /** Re-applies the current layout params via the WindowManager, swallowing detach races. */
    private fun updateWindowLayout(params: WindowManager.LayoutParams) {
        try {
            context.getSystemService(Context.WINDOW_SERVICE)?.let { wm ->
                (wm as WindowManager).updateViewLayout(this, params)
            }
        } catch (_: IllegalArgumentException) {
            // View already detached; ignore mid-drag teardown.
        }
    }

    /**
     * Animates [params.x] to the nearest horizontal screen edge (0 for left,
     * screenWidth - viewWidth for right) using a short decelerate tween. Updates the window
     * layout on every frame so the bubble visibly slides.
     */
    private fun snapToNearestEdge(params: WindowManager.LayoutParams) {
        val viewWidth = if (width > 0) width else params.width
        val screenWidth = resources.displayMetrics.widthPixels
        val leftTarget = 0
        val rightTarget = (screenWidth - viewWidth).coerceAtLeast(0)
        // Choose the closer edge.
        val target = if (params.x <= (leftTarget + rightTarget) / 2) leftTarget else rightTarget
        if (target == params.x) return
        cancelSnapAnimation()
        val animator = ValueAnimator.ofInt(params.x, target).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator(SNAP_INTERPOLATOR_FACTOR)
            addUpdateListener { a ->
                val value = a.animatedValue as Int
                params.x = value
                // The view may have been detached while animating; guard against that.
                if (isAttachedToWindow) {
                    updateWindowLayout(params)
                } else {
                    cancel()
                }
            }
        }
        snapAnimator = animator
        animator.start()
    }

    private fun cancelSnapAnimation() {
        snapAnimator?.cancel()
        snapAnimator = null
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

        // 30% transparency → 70% opacity. 255 * 0.7 ≈ 178.
        const val OVERLAY_ALPHA = 178

        // Edge-snap animation tuning.
        const val SNAP_DURATION_MS = 300L
        const val SNAP_INTERPOLATOR_FACTOR = 1.5f
    }
}
