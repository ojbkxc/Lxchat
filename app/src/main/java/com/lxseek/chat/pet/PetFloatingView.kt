package com.lxseek.chat.pet

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
import com.lxseek.chat.util.DebugLog

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
    /** RectF used for custom-bitmap drawing + transparent-pixel hit testing (per-draw updated). */
    private val bitmapDstRectF = RectF()

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
    /** drawCharacterAccents 专用描边笔（onDraw 每帧复用，不再 new Paint）。 */
    private val accentStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    /** drawCharacterAccents HUIHUI 齿形专用填充笔。 */
    private val accentFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** HUHU 三角描边复用路径，避免每帧分配。 */
    private val accentPath = Path()
    /** 平眼线专用笔：固定 1.6dp 圆头，独立于 pupilPaint。 */
    private val flatEyeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    /** 平嘴线专用笔：固定 3dp 圆头，独立于 smilePaint。 */
    private val flatMouthLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PET_WHITE; style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND
    }
    private val smilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PET_WHITE; style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND
    }

    // ---- Character / emotion state ----
    private var currentCharacter: PetCharacter = PetCharacter.HUHU
    private var characterPalette: PetPalette = PetPalette.HUHU
    private var tipSlotHeight = 0
    private var bubbleCenterY = 0f
    /** Actual pet size in pixels (may differ from [width] when the window is widened for tips). */
    private var petSizePx = 0
    /** Horizontal center of the pet within the window (pet is centered in the window). */
    private var petCenterX = 0f

    // ---- Status-tip caption (frameless text) ----
    private val tipTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TIP_TEXT_COLOR; textSize = dp(TIP_TEXT_SIZE_DP); textAlign = Paint.Align.CENTER
        // Outline pass style: fill + stroke lets the caption survive any background.
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    /** Cached StaticLayout for the tip text; rebuilt only when text or width changes. */
    private var tipLayoutCache: StaticLayout? = null
    private var tipLayoutCacheKey: String? = null

    // ---- Touch bookkeeping ----
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0
    private var wasDragging = false
    private var snapAnimator: ValueAnimator? = null

    @Volatile private var currentEmotion: PetEmotion = PetEmotion.IDLE
    /** When non-null, the pet is being dragged and plays a directional run animation. */
    @Volatile private var dragOverrideState: PetAnimation.State? = null
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

    fun bindWindowParams(params: WindowManager.LayoutParams) {
        // Allow the window to extend outside the screen bounds so the pet can snap flush against
        // the left/right edges. Without FLAG_LAYOUT_NO_LIMITS the WindowManager clamps the window
        // x/y to the visible screen, which leaves the pet offset by half of (windowWidth - petWidth)
        // and never truly reaches the edge. The tip-bubble drawing already handles the
        // "window partly off-screen" case (see drawTipBubble), so this flag is safe and does not
        // affect touch pass-through (which is handled at the View level via onTouchEvent).
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        windowParams = params
    }

    /** Sets the actual pet size in pixels. The window may be wider than this to fit tip text. */
    fun setPetSize(sizePx: Int) {
        petSizePx = sizePx
        if (width > 0 && height > 0) {
            tipSlotHeight = (height - petSizePx).coerceAtLeast(0)
            petCenterX = width / 2f
            bubbleCenterY = (petSizePx / 2f) + tipSlotHeight
            computeFrameDstRect(width, height)
            rebuildBubbleShader()
            invalidate()
        }
    }

    fun setCustomBitmap(bitmap: Bitmap?) {
        customBitmap = bitmap
        requestLayout(); invalidate()
    }

    /** Sets the spritesheet atlas bitmap; pass null to fall back to the Canvas bubble. */
    fun setSpritesheet(bitmap: Bitmap?) {
        spritesheetBitmap = bitmap
        animStartNanos = System.nanoTime()
        // Restart the frame loop when a non-null spritesheet is attached. The loop may have
        // stopped itself earlier (spritesheetBitmap was null in the FrameCallback guard), so
        // without this call only the first frame would ever render — applyCharacterAsync
        // hits this path when swapping characters on an already-attached view.
        if (bitmap != null && isAttachedToWindow) scheduleNextFrame()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val effectivePetSize = if (petSizePx > 0) petSizePx else w
        tipSlotHeight = (h - effectivePetSize).coerceAtLeast(0)
        petCenterX = w / 2f
        bubbleCenterY = (effectivePetSize / 2f) + tipSlotHeight
        bitmapDstRectF.set(0f, 0f, w.toFloat(), h.toFloat())
        computeFrameDstRect(w, h)
        rebuildBubbleShader()
    }

    /** Fits a 192x208 cell into the bubble area (below the tip slot), preserving aspect ratio. */
    private fun computeFrameDstRect(w: Int, h: Int) {
        val effectivePetSize = if (petSizePx > 0) petSizePx else w
        val availW = effectivePetSize.toFloat()
        val availH = (h - tipSlotHeight).toFloat()
        if (availW <= 0f || availH <= 0f) { frameDstRect.setEmpty(); return }
        val cellAspect = PetAnimation.CELL_WIDTH.toFloat() / PetAnimation.CELL_HEIGHT.toFloat()
        val dstW: Float
        val dstH: Float
        if (availW / availH > cellAspect) { dstH = availH; dstW = dstH * cellAspect }
        else { dstW = availW; dstH = dstW / cellAspect }
        // Center the frame horizontally within the window (pet is centered).
        val left = petCenterX - dstW / 2f
        val top = tipSlotHeight.toFloat() + (availH - dstH) / 2f
        frameDstRect.set(left, top, left + dstW, top + dstH)
    }

    private fun bubbleRadius(): Float {
        val size = if (petSizePx > 0) petSizePx else minOf(width, height)
        return (size - dp(BORDER_PADDING_DP)) / 2f
    }

    private fun rebuildBubbleShader() {
        if (width <= 0 || height <= 0) return
        val cx = petCenterX
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
        val custom = customBitmap
        val sheet = spritesheetBitmap
        when {
            custom != null && !custom.isRecycled -> drawCustomBitmap(canvas, custom)
            sheet != null && !sheet.isRecycled -> drawSpritesheetFrame(canvas, sheet)
            else -> drawDefaultBubble(canvas)
        }
        // The tip capsule is drawn OUTSIDE the 70%-alpha layer: it carries readable text
        // (streaming model output), so dimming it together with the pet made the words
        // nearly illegible and made the bubble appear to flicker with the pet's alpha.
        drawTipBubble(canvas)
    }

    private fun drawCustomBitmap(canvas: Canvas, bitmap: Bitmap) {
        // Draw into the pet body area only (below the tip slot), preserving aspect ratio.
        // Stretching across the whole window deformed the image: the window includes the
        // tip headroom strip and is widened to TIP_WIDTH_DP, so a square PNG was squashed.
        val target = if (frameDstRect.isEmpty) bitmapDstRectF else frameDstRect
        if (target.isEmpty) return
        val scale = minOf(target.width() / bitmap.width, target.height() / bitmap.height)
        val drawW = bitmap.width * scale
        val drawH = bitmap.height * scale
        val left = target.left + (target.width() - drawW) / 2f
        val top = target.top + (target.height() - drawH) / 2f
        bitmapDstRectF.set(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(bitmap, null, bitmapDstRectF, bitmapPaint)
    }

    /** Resolves the current frame from [PetAnimation] and draws it from the atlas. */
    private fun drawSpritesheetFrame(canvas: Canvas, bitmap: Bitmap) {
        val state = dragOverrideState ?: PetAnimation.stateForEmotion(currentEmotion)
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
                accentStrokePaint.color = characterPalette.dark; accentStrokePaint.strokeWidth = dp(1.8f)
                canvas.drawLine(cx, stemBottom, cx, stemTop, accentStrokePaint)
                canvas.drawCircle(cx, stemTop - r * 0.04f, r * 0.13f, accentPaint)
            }
            PetCharacter.HUHU -> {
                val tipY = cy - r * 0.94f; val baseY = cy - r * 0.62f; val half = r * 0.16f
                accentPath.rewind()
                accentPath.moveTo(cx, tipY); accentPath.lineTo(cx - half, baseY); accentPath.lineTo(cx + half, baseY); accentPath.close()
                canvas.drawPath(accentPath, accentPaint)
            }
            PetCharacter.BUBU -> {
                val y = cy - r * 0.8f; val arm = r * 0.16f
                accentStrokePaint.color = characterPalette.accent; accentStrokePaint.strokeWidth = dp(2f)
                canvas.drawLine(cx - arm, y - arm, cx + arm, y + arm, accentStrokePaint)
                canvas.drawLine(cx - arm, y + arm, cx + arm, y - arm, accentStrokePaint)
            }
            PetCharacter.HUIHUI -> {
                val y = cy - r * 0.82f; val gap = r * 0.16f; val tWidth = dp(2f)
                accentFillPaint.color = characterPalette.accent
                listOf(-gap, 0f, gap).forEach { dx ->
                    canvas.drawRoundRect(cx + dx - tWidth / 2, y - r * 0.14f, cx + dx + tWidth / 2, y + r * 0.14f, tWidth / 2, tWidth / 2, accentFillPaint)
                }
            }
        }
    }

    private fun tipText(): CharSequence? {
        return when (currentEmotion) {
            PetEmotion.THINKING -> context.getString(R.string.pet_tip_thinking)
            PetEmotion.HAPPY -> context.getString(R.string.pet_tip_done)
            PetEmotion.SAD -> context.getString(R.string.pet_tip_sad)
            PetEmotion.ERROR -> context.getString(R.string.pet_tip_error)
            PetEmotion.WAITING -> context.getString(R.string.pet_tip_waiting)
            PetEmotion.IDLE -> null
        }
    }

    private fun drawTipBubble(canvas: Canvas) {
        if (tipSlotHeight <= 0) return
        val text = tipText() ?: return
        val w = width
        val edgeMargin = dp(TIP_EDGE_MARGIN_DP)
        // Compute the on-screen usable width first (window may extend past screen edges),
        // so the StaticLayout is built with the true available width and never overflows.
        val params = windowParams
        val screenWidth = resources.displayMetrics.widthPixels
        val minLeft = if (params != null && params.x < 0) (-params.x).toFloat() + edgeMargin else edgeMargin
        val maxRight = if (params != null && params.x + w > screenWidth) (screenWidth - params.x).toFloat() - edgeMargin else w - edgeMargin
        val availableWidth = (maxRight - minLeft).coerceAtLeast(0f)
        val maxTextWidth = (availableWidth - dp(TIP_STROKE_WIDTH_DP) * 2).coerceAtLeast(0f)
        if (maxTextWidth <= 0f) return
        // Layout cache: the Choreographer loop invalidates at display refresh rate, and
        // rebuilding a StaticLayout (text shaping) per frame during streaming made the
        // overlay visibly janky. Rebuild only when text or width actually changed.
        val cacheKey = "${text.hashCode()}:${maxTextWidth.toInt()}"
        val layout: StaticLayout
        // 局部快照：tipLayoutCache 是可变 var，判空与使用之间 smart-cast 失效，
        // 快照到局部 val 后直接使用，消除 !! 并避免两次读取间的状态变化
        val cached = tipLayoutCache
        if (cached != null && tipLayoutCacheKey == cacheKey) {
            layout = cached
        } else {
            val tipText = text.toString()
            layout = StaticLayout.Builder.obtain(tipText, 0, tipText.length, tipTextPaint, maxTextWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                // 1.25x line spacing: multi-line captions stay airy instead of a dense block.
                .setLineSpacing(0f, TIP_LINE_SPACING)
                .setIncludePad(false)
                .build()
            tipLayoutCache = layout
            tipLayoutCacheKey = cacheKey
        }
        // layout.width is the constructor width, NOT the rendered text width — measure the
        // widest line so the caption hugs the text instead of spanning the whole window.
        var textWidth = 0f
        for (i in 0 until layout.lineCount) {
            textWidth = maxOf(textWidth, layout.getLineWidth(i))
        }
        // Frameless caption: no bubble, no arrow — just the text with a soft dark outline so
        // it reads on any background (the old filled capsule clashed with app content and
        // felt foreign wherever the pet floated).
        val capWidth = (textWidth + dp(TIP_STROKE_WIDTH_DP) * 2).coerceAtMost(availableWidth)
        // Vertical budget: the caption must stay between the window top and the pet.
        // Long streaming tails clip to the LAST lines that fit (newest output).
        val petTop = bubbleCenterY - bubbleRadius()
        // Caption center: window center, shifted to the visible-area center when partially off-screen.
        val cx = if (params != null && (params.x < 0 || params.x + w > screenWidth)) {
            val visLeft = params.x.coerceAtLeast(0)
            val visRight = (params.x + w).coerceAtMost(screenWidth)
            (visLeft + visRight) / 2f - params.x
        } else {
            w / 2f
        }
        // Position: centered on cx, clamped so it never leaves the visible area, sitting
        // just above the pet with a fixed gap.
        val left = (cx - capWidth / 2).coerceIn(minLeft, (maxRight - capWidth).coerceAtLeast(minLeft))
        // Bottom edge anchors to just above the pet; when the layout is taller than the
        // budget, the HEAD clips away above the window's top padding and the tail (newest
        // output) stays visible right above the pet.
        val textBottom = petTop - dp(TIP_GAP_DP)
        val textTop = (textBottom - layout.height.toFloat()).coerceAtLeast(dp(TIP_TOP_PADDING_DP))
        canvas.save()
        // Soft outline pass: paint the text offset in translucent dark on 8 directions, then
        // the bright fill on top — readable over light AND dark app content with no box.
        val stroke = tipTextPaint.strokeWidth
        val fillColor = tipTextPaint.color
        tipTextPaint.strokeWidth = dp(TIP_STROKE_WIDTH_DP)
        tipTextPaint.color = TIP_STROKE_COLOR
        for (angle in 0 until 8) {
            val rad = Math.toRadians(angle * 45.0)
            canvas.save()
            canvas.translate(
                left + (Math.cos(rad) * dp(TIP_STROKE_WIDTH_DP) * 0.7f).toFloat(),
                textTop + (Math.sin(rad) * dp(TIP_STROKE_WIDTH_DP) * 0.7f).toFloat(),
            )
            layout.draw(canvas)
            canvas.restore()
        }
        tipTextPaint.strokeWidth = stroke
        tipTextPaint.color = fillColor
        canvas.translate(left, textTop)
        layout.draw(canvas)
        canvas.restore()
    }

    // ---- Canvas fallback bubble ----

    private fun drawDefaultBubble(canvas: Canvas) {
        val cx = petCenterX
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
            PetEmotion.WAITING -> {
                drawEyes(canvas, cx - eyeGap, cx + eyeGap, eyeY, eyeRadius, pupilRadius, highlightRadius, highlightOffset, 0f)
                drawOpenMouth(canvas, cx, cy, radius, small = true)
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
        flatEyeLinePaint.strokeWidth = dp(1.6f)
        canvas.drawCircle(leftX, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawLine(leftX - eyeRadius * 0.55f, eyeY, leftX + eyeRadius * 0.55f, eyeY, flatEyeLinePaint)
        canvas.drawCircle(rightX, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawLine(rightX - eyeRadius * 0.55f, eyeY, rightX + eyeRadius * 0.55f, eyeY, flatEyeLinePaint)
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
        canvas.drawLine(cx - radius * SMILE_WIDTH_RATIO, cy + radius * 0.32f, cx + radius * SMILE_WIDTH_RATIO, cy + radius * 0.32f, flatMouthLinePaint)
    }

    // ---- Touch / interaction ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val params = windowParams ?: return performClickFallback(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isTransparentAt(event.x, event.y)) return false
        // Only the pet BODY starts a drag/tap — never the surrounding window area (which
        // exists just for tip text headroom). Canvas bubble: inside the drawn circle AND
        // within the central tap-target. Bitmap pets (spritesheet / custom): inside the
        // frame rect AND within the central tap target of the pet's bounding circle, so
        // edges/fringes that survived the alpha test don't grab from far outside the sprite.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val inBody = if (customBitmap == null && spritesheetBitmap == null) {
                val dx = event.x - petCenterX
                val dy = event.y - bubbleCenterY
                hypot(dx, dy) <= bubbleRadius()
            } else {
                val frame = if (customBitmap != null && frameDstRect.isEmpty) bitmapDstRectF else frameDstRect
                frame.contains(event.x, event.y)
            }
            if (!inBody || isOutsideTapTarget(event.x, event.y)) return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSnapAnimation()
                parent?.requestDisallowInterceptTouchEvent(true)
                downRawX = event.rawX; downRawY = event.rawY
                downWindowX = params.x; downWindowY = params.y
                wasDragging = false
                dragOverrideState = null
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                if (!wasDragging && hypot(event.rawX - downRawX, event.rawY - downRawY) > DRAG_THRESHOLD_DP * resources.displayMetrics.density) wasDragging = true
                if (wasDragging) {
                    params.x = clampWindowX(downWindowX + dx, params)
                    params.y = clampWindowY(downWindowY + dy, params)
                    updateWindowLayout(params)
                    // Directional run animation: right drag -> RUNNING_RIGHT, left drag -> RUNNING_LEFT.
                    val newState = if (dx >= 0) PetAnimation.State.RUNNING_RIGHT else PetAnimation.State.RUNNING_LEFT
                    if (newState != dragOverrideState) {
                        dragOverrideState = newState
                        animStartNanos = System.nanoTime()
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // CANCEL (system gesture stole the stream) must clear drag state the same way
                // as UP, otherwise the pet stays frozen mid-run-animation and never snaps back.
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                if (dragOverrideState != null) {
                    dragOverrideState = null
                    animStartNanos = System.nanoTime()
                    invalidate()
                }
                if (!cancelled && !wasDragging) { launchApp(); return true }
                if (cancelled && !wasDragging) return true
                snapToNearestEdge(params)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Clamps a candidate window x so the pet body (not the window, which may be wider for
     * tip text) can leave the screen by at most [DRAG_OFFSCREEN_FRACTION] of its size.
     * Without this, FLAG_LAYOUT_NO_LIMITS lets the user drag the pet completely off-screen
     * with no way to get it back short of toggling the overlay.
     */
    private fun clampWindowX(x: Int, params: WindowManager.LayoutParams): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        val viewWidth = if (width > 0) width else params.width
        val effectivePetSize = if (petSizePx > 0) petSizePx else viewWidth
        val horizontalOffset = (viewWidth - effectivePetSize) / 2
        // Pet-left / pet-right in screen coordinates for the candidate window x.
        val petLeft = x + horizontalOffset
        val petRight = x + horizontalOffset + effectivePetSize
        val maxOff = (effectivePetSize * DRAG_OFFSCREEN_FRACTION).toInt()
        return when {
            petRight < maxOff -> maxOff - horizontalOffset - effectivePetSize
            petLeft > screenWidth - maxOff -> screenWidth - maxOff - horizontalOffset - effectivePetSize
            else -> x
        }
    }

    /** Vertical counterpart of [clampWindowX]: keeps the pet reachable on the y axis. */
    private fun clampWindowY(y: Int, params: WindowManager.LayoutParams): Int {
        val screenHeight = resources.displayMetrics.heightPixels
        val viewHeight = if (height > 0) height else params.height
        val effectivePetSize = if (petSizePx > 0) petSizePx else viewHeight
        // The pet body occupies the bottom part of the window (tip slot is above it).
        val tipSlot = if (height > 0) (height - effectivePetSize).coerceAtLeast(0) else 0
        val maxOff = (effectivePetSize * DRAG_OFFSCREEN_FRACTION).toInt()
        val petTop = y + tipSlot
        val petBottom = y + viewHeight
        return when {
            petBottom < maxOff -> maxOff - viewHeight
            petTop > screenHeight - maxOff -> screenHeight - maxOff - tipSlot
            else -> y
        }
    }

    /**
     * Returns true if the touch point falls outside the shrunken tap target.
     *
     * Only the central [TAP_TARGET_RADIUS_RATIO] of the pet radius responds to touches; the
     * outer ring passes through to the app below so taps near the pet's edges don't trigger a
     * drag or app launch by accident. The check is centered on the pet body ([petCenterX],
     * [bubbleCenterY]) and scaled by [bubbleRadius], so it works for both the Canvas bubble
     * and the spritesheet frame (which shares the same center and is sized from [petSizePx]).
     */
    private fun isOutsideTapTarget(x: Float, y: Float): Boolean {
        val dx = x - petCenterX
        val dy = y - bubbleCenterY
        // Bitmap pets: the tap target is the central circle of the frame rect (the sprite
        // occupies frameDstRect, which shares the pet center). Canvas bubble: central circle
        // of the drawn bubble. Either way touches on the outer fringe pass through.
        val reach = if (customBitmap != null || spritesheetBitmap != null) {
            val frame = if (customBitmap != null && frameDstRect.isEmpty) bitmapDstRectF else frameDstRect
            minOf(frame.width(), frame.height()) * 0.5f * TAP_TARGET_RADIUS_RATIO
        } else {
            bubbleRadius() * TAP_TARGET_RADIUS_RATIO
        }
        return hypot(dx, dy) > reach
    }

    /**
     * Returns true if the pixel at (x, y) is fully transparent. Checks the custom bitmap first,
     * then the current spritesheet frame, so touches on transparent atlas pixels pass through.
     */
    private fun isTransparentAt(x: Float, y: Float): Boolean {
        val custom = customBitmap
        if (custom != null && !custom.isRecycled) {
            // Custom bitmaps map 1:1 onto their dst rect — no spritesheet cell math.
            return isTransparentInBitmap(custom, x, y, bitmapDstRectF, custom.width, custom.height)
        }
        val sheet = spritesheetBitmap
        if (sheet != null && !sheet.isRecycled) {
            val state = dragOverrideState ?: PetAnimation.stateForEmotion(currentEmotion)
            val elapsedMs = (System.nanoTime() - animStartNanos) / NANOS_PER_MS
            val tick = PetAnimation.playbackTickAtElapsedMs(state, elapsedMs)
            val f = tick.frame
            return isTransparentInBitmap(sheet, x, y, frameDstRect, PetAnimation.CELL_WIDTH, PetAnimation.CELL_HEIGHT, f.x, f.y)
        }
        return false
    }

    /** Maps view-local coords to bitmap coords and checks alpha. [srcX]/[srcY] offset into a
     *  spritesheet atlas; [cellW]/[cellH] bound one atlas cell (or the full bitmap). */
    private fun isTransparentInBitmap(
        bitmap: Bitmap,
        x: Float,
        y: Float,
        dst: RectF,
        cellW: Int,
        cellH: Int,
        srcX: Int = 0,
        srcY: Int = 0,
    ): Boolean {
        val bw = bitmap.width; val bh = bitmap.height
        if (bw <= 0 || bh <= 0) return false
        if (dst.width() <= 0f || dst.height() <= 0f) return false
        if (x < dst.left || x > dst.right || y < dst.top || y > dst.bottom) return true
        val fx = srcX + ((x - dst.left) / dst.width() * cellW).toInt().coerceIn(0, cellW - 1)
        val fy = srcY + ((y - dst.top) / dst.height() * cellH).toInt().coerceIn(0, cellH - 1)
        if (fx < 0 || fx >= bw || fy < 0 || fy >= bh) return true
        return bitmap.getPixel(fx, fy).ushr(24) == 0
    }

    private fun updateWindowLayout(params: WindowManager.LayoutParams) {
        try {
            context.getSystemService(Context.WINDOW_SERVICE)?.let { wm -> (wm as WindowManager).updateViewLayout(this, params) }
        } catch (e: IllegalArgumentException) { DebugLog.d("PetFloatingView", "updateViewLayout failed (view not attached)", e) }
    }

    private fun snapToNearestEdge(params: WindowManager.LayoutParams) {
        val viewWidth = if (width > 0) width else params.width
        val screenWidth = resources.displayMetrics.widthPixels
        val effectivePetSize = if (petSizePx > 0) petSizePx else viewWidth
        // When the window is wider than the pet (for tip text), offset so the pet
        // itself snaps to the screen edge, not the window edge.
        val horizontalOffset = (viewWidth - effectivePetSize) / 2
        val leftTarget = -horizontalOffset
        val rightTarget = (screenWidth - viewWidth + horizontalOffset).coerceAtLeast(leftTarget)
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
        try { context.startActivity(intent) } catch (e: Exception) { DebugLog.w("PetFloatingView", "launchApp failed", e) }
    }

    private companion object {
        const val PET_WHITE = 0xFFFFFFFF.toInt()
        const val NANOS_PER_MS = 1_000_000L

        const val TIP_TEXT_COLOR = 0xFFFFFFFF.toInt()
        const val TIP_STROKE_COLOR = 0x99000000.toInt()
        const val TIP_TEXT_SIZE_DP = 11f
        const val TIP_LINE_SPACING = 1.2f
        const val TIP_STROKE_WIDTH_DP = 2f
        const val TIP_GAP_DP = 6f
        const val TIP_TOP_PADDING_DP = 4f
        const val TIP_EDGE_MARGIN_DP = 4f

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
        const val SNAP_DURATION_MS = 300L
        const val SNAP_INTERPOLATOR_FACTOR = 1.5f

        // How much of the pet body may hang off a screen edge while dragging (fraction of
        // petSizePx). 0.5 keeps half the sprite visible as a grab handle for pulling it back.
        const val DRAG_OFFSCREEN_FRACTION = 0.5f

        // Fraction of the bubble radius that responds to taps; touches outside this central
        // circle pass through to the app below. 0.6 keeps the pet body tappable while letting
        // the outer 40% (transparent atlas padding + sprite fringe) pass through, reducing
        // accidental drags/launches when the user taps near the pet's edges.
        const val TAP_TARGET_RADIUS_RATIO = 0.6f
    }
}
