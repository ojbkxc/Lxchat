package com.lxseek.chat.pet

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.lxseek.chat.MainActivity
import com.lxseek.chat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * The draggable "pet" bubble shown on the system overlay.
 *
 * Rendering has three modes, tried in priority order:
 * 1. **User bitmap** — a custom transparent PNG set via [setCustomBitmap].
 * 2. **Spritesheet** — a WebP atlas set via [setSpritesheet]; the active frame is resolved from
 *    [PetAnimation] based on the current [PetEmotion] and elapsed wall-clock time. A
 *    [Choreographer] loop drives continuous invalidation while the spritesheet is attached.
 * 3. **Canvas fallback** — a hand-drawn gradient bubble with a face, used for [PetCharacter.CLASSIC]
 *    or when no spritesheet could be loaded.
 *
 * Tap (a short, low-movement touch) launches [MainActivity]. Drag moves the bubble; on release it
 * animates to the nearest horizontal screen edge ("edge snapping"). Touches on fully transparent
 * pixels pass through to the app below. A transient status-tip capsule is drawn above the pet while
 * the emotion is non-idle. Everything is rendered at 70% opacity via [Canvas.saveLayerAlpha].
 */
class PetFloatingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var windowParams: WindowManager.LayoutParams? = null

    @Volatile private var customBitmap: Bitmap? = null

    // ---- Spritesheet frame animation ----
    /** WebP atlas bitmap; when non-null the view renders frames from it instead of the Canvas bubble. */
    @Volatile private var spritesheetBitmap: Bitmap? = null
    /** Nanosecond timestamp at which the current animation state started playing. */
    private var animStartNanos: Long = 0L
    /** Active choreographer callback, non-null while the frame loop is running. */
    private var frameCallback: Choreographer.FrameCallback? = null
    /** Source rect inside the atlas for the current frame (reused per draw). */
    private val srcRect = Rect()
    /** Destination rect for the spritesheet frame, computed in onSizeChanged to preserve aspect. */
    private val frameDstRect = RectF()

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bitmapDstRect = Rect()

    // ---- Canvas fallback bubble paints ----
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = SHADOW_ALPHA }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PET_WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2.5f)
    }
    private val eyeWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PET_WHITE }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyeHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PET_WHITE }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val smilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PET_WHITE; style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND
    }

    // ---- Character / emotion state ----
    private var currentCharacter: PetCharacter = PetCharacter.CLASSIC
    private var characterPalette: PetPalette = PetPalette.CLASSIC
    private var tipSlotHeight = 0
    private var bubbleCenterY = 0f

    // ---- Status-tip bubble ----
    private val tipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TIP_BG_COLOR; style = Paint.Style.FILL }
    private val tipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TIP_TEXT_COLOR; textSize = dp(TIP_TEXT_SIZE_DP); textAlign = Paint.Align.CENTER
    }
    private val tipArrowPath = Path()

    // ---- Touch bookkeeping ----
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0
    private var wasDragging = false
    private var snapAnimator: ValueAnimator? = null

    @Volatile private var currentEmotion: PetEmotion = PetEmotion.IDLE
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
                    animStartNanos = System.nanoTime() // reset so the new state starts at frame 0
                    invalidate()
                }
            }
        }
        startFrameLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        emotionScope?.cancel()
        emotionScope = null
        cancelSnapAnimation()
        stopFrameLoop()
    }

    fun bindWindowParams(params: WindowManager.LayoutParams) { windowParams = params }

    fun setCustomBitmap(bitmap: Bitmap?) {
        customBitmap = bitmap
        requestLayout(); invalidate()
    }

    /** Sets the spritesheet atlas bitmap; pass null to fall back to the Canvas bubble. */
    fun setSpritesheet(bitmap: Bitmap?) {
        spritesheetBitmap = bitmap
        animStartNanos = System.nanoTime()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        tipSlotHeight = (h - w).coerceAtLeast(0)
        bubbleCenterY = (w / 2f) + tipSlotHeight
        bitmapDstRect.set(0, 0, w, h)
        computeFrameDstRect(w, h)
        rebuildBubbleShader()
    }

    /** Fits a 192x208 cell into the bubble area (below the tip slot), preserving aspect ratio. */
    private fun computeFrameDstRect(w: Int, h: Int) {
        val availW = w.toFloat()
        val availH = (h - tipSlotHeight).toFloat()
        if (availW <= 0f || availH <= 0f) { frameDstRect.setEmpty(); return }
        val cellAspect = PetAnimation.CELL_WIDTH.toFloat() / PetAnimation.CELL_HEIGHT.toFloat()
        val dstW: Float
        val dstH: Float
        if (availW / availH > cellAspect) { dstH = availH; dstW = dstH * cellAspect }
        else { dstW = availW; dstH = dstW / cellAspect }
        val left = (availW - dstW) / 2f
        val top = tipSlotHeight.toFloat() + (availH - dstH) / 2f
        frameDstRect.set(left, top, left + dstW, top + dstH)
    }

    private fun bubbleRadius(): Float = (minOf(width, height) - dp(BORDER_PADDING_DP)) / 2f

    private fun rebuildBubbleShader() {
        if (width <= 0 || height <= 0) return
        val cx = width / 2f
        val cy = bubbleCenterY
        val radius = bubbleRadius()
        bubblePaint.shader = RadialGradient(
            cx - radius * LIGHT_OFFSET, cy - radius * LIGHT_OFFSET, radius * GRADIENT_RADIUS_SCALE,
            intArrayOf(characterPalette.light, characterPalette.mid, characterPalette.dark),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
    }

    fun setCharacter(character: PetCharacter) {
        if (currentCharacter == character) return
        currentCharacter = character
        characterPalette = PetPalette.of(character)
        applyPalette()
        rebuildBubbleShader()
        invalidate()
    }

    private fun applyPalette() {
        shadowPaint.color = characterPalette.shadow
        pupilPaint.color = characterPalette.pupil
        blushPaint.color = characterPalette.blush
        accentPaint.color = characterPalette.accent
    }

    // ---- Frame loop ----

    private fun startFrameLoop() {
        animStartNanos = System.nanoTime()
        scheduleNextFrame()
    }

    private fun scheduleNextFrame() {
        if (frameCallback != null) return
        val cb = Choreographer.FrameCallback {
            frameCallback = null
            if (isAttachedToWindow && spritesheetBitmap != null) {
                invalidate()
                scheduleNextFrame()
            }
        }
        frameCallback = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }

    private fun stopFrameLoop() {
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
    }

    // ---- Draw ----

    override fun onDraw(canvas: Canvas) {
        val savedLayer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), OVERLAY_ALPHA)
        try {
            val custom = customBitmap
            val sheet = spritesheetBitmap
            when {
                custom != null && !custom.isRecycled -> drawCustomBitmap(canvas, custom)
                sheet != null && !sheet.isRecycled -> drawSpritesheetFrame(canvas, sheet)
                else -> drawDefaultBubble(canvas)
            }
            drawTipBubble(canvas)
        } finally {
            canvas.restoreToCount(savedLayer)
        }
    }

    private fun drawCustomBitmap(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawBitmap(bitmap, null, bitmapDstRect, bitmapPaint)
    }

    /** Resolves the current frame from [PetAnimation] and draws it from the atlas. */
    private fun drawSpritesheetFrame(canvas: Canvas, bitmap: Bitmap) {
        val state = PetAnimation.stateForEmotion(currentEmotion)
        val elapsedMs = (System.nanoTime() - animStartNanos) / NANOS_PER_MS
        val tick = PetAnimation.playbackTickAtElapsedMs(state, elapsedMs)
        val f = tick.frame
        srcRect.set(f.x, f.y, f.x + f.width, f.y + f.height)
        canvas.drawBitmap(bitmap, srcRect, frameDstRect, bitmapPaint)
    }

    private fun drawCharacterAccents(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val r = radius
        when (currentCharacter) {
            PetCharacter.CLASSIC -> Unit
            PetCharacter.DADA -> {
                val stemTop = cy - r * 0.92f; val stemBottom = cy - r * 0.58f
                val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = characterPalette.dark; strokeWidth = dp(1.8f); strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(cx, stemBottom, cx, stemTop, stemPaint)
                canvas.drawCircle(cx, stemTop - r * 0.04f, r * 0.13f, accentPaint)
            }
            PetCharacter.HUHU -> {
                val tipY = cy - r * 0.94f; val baseY = cy - r * 0.62f; val half = r * 0.16f
                val path = Path().apply { moveTo(cx, tipY); lineTo(cx - half, baseY); lineTo(cx + half, baseY); close() }
                canvas.drawPath(path, accentPaint)
            }
            PetCharacter.BUBU -> {
                val y = cy - r * 0.8f; val arm = r * 0.16f
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = characterPalette.accent; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(cx - arm, y - arm, cx + arm, y + arm, p)
                canvas.drawLine(cx - arm, y + arm, cx + arm, y - arm, p)
            }
            PetCharacter.HUIHUI -> {
                val y = cy - r * 0.82f; val gap = r * 0.16f; val tWidth = dp(2f)
                val teeth = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = characterPalette.accent }
                listOf(-gap, 0f, gap).forEach { dx ->
                    canvas.drawRoundRect(cx + dx - tWidth / 2, y - r * 0.14f, cx + dx + tWidth / 2, y + r * 0.14f, tWidth / 2, tWidth / 2, teeth)
                }
            }
        }
    }

    private fun tipText(): CharSequence? = when (currentEmotion) {
        PetEmotion.THINKING -> context.getString(R.string.pet_tip_thinking)
        PetEmotion.HAPPY -> context.getString(R.string.pet_tip_done)
        PetEmotion.SAD -> context.getString(R.string.pet_tip_sad)
        PetEmotion.ERROR -> context.getString(R.string.pet_tip_error)
        PetEmotion.IDLE -> null
    }

    private fun drawTipBubble(canvas: Canvas) {
        if (tipSlotHeight <= 0) return
        val text = tipText() ?: return
        val w = width
        val textWidth = tipTextPaint.measureText(text.toString())
        val padX = dp(TIP_HORIZONTAL_PADDING_DP)
        val capWidth = textWidth + padX * 2
        val capHeight = tipTextPaint.textSize + dp(TIP_VERTICAL_PADDING_DP) * 2
        val cx = w / 2f
        val left = (cx - capWidth / 2).coerceAtLeast(dp(TIP_EDGE_MARGIN_DP))
        val right = (cx + capWidth / 2).coerceAtMost(w - dp(TIP_EDGE_MARGIN_DP))
        val top = dp(TIP_TOP_PADDING_DP)
        val bottom = top + capHeight
        val rect = RectF(left, top, right, bottom)
        canvas.drawRoundRect(rect, capHeight / 2, capHeight / 2, tipBgPaint)
        val baseline = (top + bottom) / 2f - (tipTextPaint.ascent() + tipTextPaint.descent()) / 2f
        canvas.drawText(text.toString(), cx, baseline, tipTextPaint)
        val bubbleTop = bubbleCenterY - bubbleRadius()
        tipArrowPath.reset()
        tipArrowPath.moveTo(cx, bubbleTop + dp(TIP_ARROW_OVERLAP_DP))
        tipArrowPath.lineTo(cx - dp(TIP_ARROW_HALF_DP), bottom)
        tipArrowPath.lineTo(cx + dp(TIP_ARROW_HALF_DP), bottom)
        tipArrowPath.close()
        canvas.drawPath(tipArrowPath, tipBgPaint)
    }

    // ---- Canvas fallback bubble ----

    private fun drawDefaultBubble(canvas: Canvas) {
        val cx = width / 2f
        val cy = bubbleCenterY
        val radius = bubbleRadius()
        canvas.drawCircle(cx, cy + dp(SHADOW_OFFSET_DP), radius + dp(SHADOW_SPREAD_DP), shadowPaint)
        canvas.drawCircle(cx, cy, radius, bubblePaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)
        drawCharacterAccents(canvas, cx, cy, radius)
        val eyeY = cy - radius * EYE_VERTICAL_RATIO
        val eyeGap = radius * EYE_GAP_RATIO
        val eyeRadius = radius * EYE_RADIUS_RATIO
        val pupilRadius = eyeRadius * PUPIL_SCALE
        val highlightRadius = eyeRadius * HIGHLIGHT_SCALE
        val highlightOffset = eyeRadius * HIGHLIGHT_OFFSET_SCALE
        when (currentEmotion) {
            PetEmotion.THINKING -> {
                drawEyes(canvas, cx - eyeGap, cx + eyeGap, eyeY, eyeRadius, pupilRadius, highlightRadius, highlightOffset, -0.28f)
                drawOpenMouth(canvas, cx, cy, radius, small = true)
            }
            PetEmotion.HAPPY -> {
                drawHappyEyes(canvas, cx - eyeGap, cx + eyeGap, eyeY, eyeRadius)
                drawWideSmile(canvas, cx, cy, radius)
            }
            PetEmotion.SAD -> {
                drawEyes(canvas, cx - eyeGap, cx + eyeGap, eyeY, eyeRadius, pupilRadius, highlightRadius, highlightOffset, 0.35f)
                drawFrown(canvas, cx, cy, radius)
            }
            PetEmotion.ERROR -> {
                drawFlatEyes(canvas, cx - eyeGap, cx + eyeGap, eyeY, eyeRadius)
                drawFlatMouth(canvas, cx, cy, radius)
            }
            PetEmotion.IDLE -> {
                drawEyes(canvas, cx - eyeGap, cx + eyeGap, eyeY, eyeRadius, pupilRadius, highlightRadius, highlightOffset, PUPIL_DROP_SCALE)
                drawSmile(canvas, cx, cy, radius)
            }
        }
        val blushY = cy + radius * BLUSH_VERTICAL_RATIO
        val blushGap = radius * BLUSH_GAP_RATIO
        val blushRadius = radius * BLUSH_RADIUS_RATIO
        canvas.drawCircle(cx - blushGap, blushY, blushRadius, blushPaint)
        canvas.drawCircle(cx + blushGap, blushY, blushRadius, blushPaint)
    }

    private fun drawEyes(canvas: Canvas, leftX: Float, rightX: Float, eyeY: Float, eyeRadius: Float, pupilRadius: Float, highlightRadius: Float, highlightOffset: Float, pupilDrop: Float) {
        canvas.drawCircle(leftX, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawCircle(leftX, eyeY + eyeRadius * pupilDrop, pupilRadius, pupilPaint)
        canvas.drawCircle(leftX - highlightOffset, eyeY - highlightOffset, highlightRadius, eyeHighlightPaint)
        canvas.drawCircle(rightX, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawCircle(rightX, eyeY + eyeRadius * pupilDrop, pupilRadius, pupilPaint)
        canvas.drawCircle(rightX - highlightOffset, eyeY - highlightOffset, highlightRadius, eyeHighlightPaint)
    }

    private fun drawHappyEyes(canvas: Canvas, leftX: Float, rightX: Float, eyeY: Float, eyeRadius: Float) {
        canvas.drawCircle(leftX, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawArc(leftX - eyeRadius * 0.7f, eyeY - eyeRadius * 0.7f, leftX + eyeRadius * 0.7f, eyeY + eyeRadius * 0.7f, 200f, 140f, false, pupilPaint)
        canvas.drawCircle(rightX, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawArc(rightX - eyeRadius * 0.7f, eyeY - eyeRadius * 0.7f, rightX + eyeRadius * 0.7f, eyeY + eyeRadius * 0.7f, 200f, 140f, false, pupilPaint)
    }

    private fun drawFlatEyes(canvas: Canvas, leftX: Float, rightX: Float, eyeY: Float, eyeRadius: Float) {
        val stroke = pupilPaint.strokeWidth
        pupilPaint.strokeWidth = dp(1.6f); pupilPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawCircle(leftX, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawLine(leftX - eyeRadius * 0.55f, eyeY, leftX + eyeRadius * 0.55f, eyeY, pupilPaint)
        canvas.drawCircle(rightX, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawLine(rightX - eyeRadius * 0.55f, eyeY, rightX + eyeRadius * 0.55f, eyeY, pupilPaint)
        pupilPaint.strokeWidth = stroke
    }

    private fun drawSmile(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawArc(cx - radius * SMILE_WIDTH_RATIO, cy + radius * SMILE_TOP_RATIO, cx + radius * SMILE_WIDTH_RATIO, cy + radius * SMILE_BOTTOM_RATIO, SMILE_START_ANGLE, SMILE_SWEEP_ANGLE, false, smilePaint)
    }

    private fun drawWideSmile(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawArc(cx - radius * SMILE_WIDTH_RATIO * 1.2f, cy + radius * SMILE_TOP_RATIO, cx + radius * SMILE_WIDTH_RATIO * 1.2f, cy + radius * SMILE_BOTTOM_RATIO * 1.25f, 10f, 160f, false, smilePaint)
    }

    private fun drawOpenMouth(canvas: Canvas, cx: Float, cy: Float, radius: Float, small: Boolean) {
        val r = if (small) radius * 0.12f else radius * 0.2f
        canvas.drawCircle(cx, cy + radius * 0.28f, r, smilePaint)
    }

    private fun drawFrown(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawArc(cx - radius * SMILE_WIDTH_RATIO, cy + radius * 0.05f, cx + radius * SMILE_WIDTH_RATIO, cy + radius * 0.6f, SMILE_START_ANGLE + 180f, SMILE_SWEEP_ANGLE, false, smilePaint)
    }

    private fun drawFlatMouth(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val stroke = smilePaint.strokeWidth
        smilePaint.strokeWidth = dp(3f); smilePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx - radius * SMILE_WIDTH_RATIO, cy + radius * 0.32f, cx + radius * SMILE_WIDTH_RATIO, cy + radius * 0.32f, smilePaint)
        smilePaint.strokeWidth = stroke
    }

    // ---- Touch / interaction ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val params = windowParams ?: return performClickFallback(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isTransparentAt(event.x, event.y)) return false
        // For the built-in pet, touches above the bubble crown pass through.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && customBitmap == null && spritesheetBitmap == null && event.y < bubbleCenterY - bubbleRadius()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSnapAnimation()
                parent?.requestDisallowInterceptTouchEvent(true)
                downRawX = event.rawX; downRawY = event.rawY
                downWindowX = params.x; downWindowY = params.y
                wasDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                if (!wasDragging && hypot(event.rawX - downRawX, event.rawY - downRawY) > DRAG_THRESHOLD_DP * resources.displayMetrics.density) wasDragging = true
                if (wasDragging) { params.x = downWindowX + dx; params.y = downWindowY + dy; updateWindowLayout(params) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!wasDragging) { launchApp(); return true }
                snapToNearestEdge(params)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Returns true if the pixel at (x, y) is fully transparent. Checks the custom bitmap first,
     * then the current spritesheet frame, so touches on transparent atlas pixels pass through.
     */
    private fun isTransparentAt(x: Float, y: Float): Boolean {
        val custom = customBitmap
        if (custom != null && !custom.isRecycled) return isTransparentInBitmap(custom, x, y, bitmapDstRect, 0, 0)
        val sheet = spritesheetBitmap
        if (sheet != null && !sheet.isRecycled) {
            val state = PetAnimation.stateForEmotion(currentEmotion)
            val elapsedMs = (System.nanoTime() - animStartNanos) / NANOS_PER_MS
            val tick = PetAnimation.playbackTickAtElapsedMs(state, elapsedMs)
            val f = tick.frame
            return isTransparentInBitmap(sheet, x, y, frameDstRect, f.x, f.y)
        }
        return false
    }

    /** Maps view-local coords to bitmap coords and checks alpha. */
    private fun isTransparentInBitmap(bitmap: Bitmap, x: Float, y: Float, dst: RectF, srcX: Int, srcY: Int): Boolean {
        val bw = bitmap.width; val bh = bitmap.height
        if (bw <= 0 || bh <= 0) return false
        if (dst.width() <= 0f || dst.height() <= 0f) return false
        if (x < dst.left || x > dst.right || y < dst.top || y > dst.bottom) return false
        val cellW = PetAnimation.CELL_WIDTH
        val cellH = PetAnimation.CELL_HEIGHT
        val fx = srcX + ((x - dst.left) / dst.width() * cellW).toInt().coerceIn(0, cellW - 1)
        val fy = srcY + ((y - dst.top) / dst.height() * cellH).toInt().coerceIn(0, cellH - 1)
        if (fx < 0 || fx >= bw || fy < 0 || fy >= bh) return false
        return bitmap.getPixel(fx, fy).ushr(24) == 0
    }

    private fun updateWindowLayout(params: WindowManager.LayoutParams) {
        try {
            context.getSystemService(Context.WINDOW_SERVICE)?.let { wm -> (wm as WindowManager).updateViewLayout(this, params) }
        } catch (_: IllegalArgumentException) { }
    }

    private fun snapToNearestEdge(params: WindowManager.LayoutParams) {
        val viewWidth = if (width > 0) width else params.width
        val screenWidth = resources.displayMetrics.widthPixels
        val leftTarget = 0
        val rightTarget = (screenWidth - viewWidth).coerceAtLeast(0)
        val target = if (params.x <= (leftTarget + rightTarget) / 2) leftTarget else rightTarget
        if (target == params.x) return
        cancelSnapAnimation()
        val animator = ValueAnimator.ofInt(params.x, target).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator(SNAP_INTERPOLATOR_FACTOR)
            addUpdateListener { a ->
                params.x = a.animatedValue as Int
                if (isAttachedToWindow) updateWindowLayout(params) else cancel()
            }
        }
        snapAnimator = animator
        animator.start()
    }

    private fun cancelSnapAnimation() { snapAnimator?.cancel(); snapAnimator = null }

    private fun performClickFallback(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) launchApp()
        return true
    }

    private fun launchApp() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        try { context.startActivity(intent) } catch (_: Exception) { }
    }

    private companion object {
        const val PET_WHITE = 0xFFFFFFFF.toInt()
        const val NANOS_PER_MS = 1_000_000L

        const val TIP_BG_COLOR = 0xFF1F2937.toInt()
        const val TIP_TEXT_COLOR = 0xFFFFFFFF.toInt()
        const val TIP_TEXT_SIZE_DP = 10f
        const val TIP_HORIZONTAL_PADDING_DP = 10f
        const val TIP_VERTICAL_PADDING_DP = 5f
        const val TIP_TOP_PADDING_DP = 4f
        const val TIP_EDGE_MARGIN_DP = 4f
        const val TIP_ARROW_HALF_DP = 5f
        const val TIP_ARROW_OVERLAP_DP = 4f

        const val BORDER_PADDING_DP = 6f
        const val SHADOW_ALPHA = 64
        const val SHADOW_OFFSET_DP = 3f
        const val SHADOW_SPREAD_DP = 1f
        const val LIGHT_OFFSET = 0.25f
        const val GRADIENT_RADIUS_SCALE = 1.3f

        const val EYE_VERTICAL_RATIO = 0.12f
        const val EYE_GAP_RATIO = 0.32f
        const val EYE_RADIUS_RATIO = 0.16f
        const val PUPIL_SCALE = 0.6f
        const val PUPIL_DROP_SCALE = 0.1f
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
        const val OVERLAY_ALPHA = 178
        const val SNAP_DURATION_MS = 300L
        const val SNAP_INTERPOLATOR_FACTOR = 1.5f
    }
}
