package com.lxseek.chat.tool

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.min

/**
 * Pure helpers for the android_see vision loop: overlay a numbered grid on a screenshot
 * and convert grid ids / fractional coordinates to pixel coordinates.
 *
 * Zero external dependencies — only android.graphics.* + kotlin.math. The actual vision
 * reasoning is done by the multimodal LLM that looks at the annotated screenshot; this
 * object only draws the overlay and does the coordinate maths.
 */
internal object VisionAssist {

    /**
     * Draw a [gridSize]×[gridSize] grid on a mutable copy of [src], numbering cells 1..N²
     * in row-major order (1 = top-left, N² = bottom-right). Each number sits on a dark disc
     * for high contrast over any background. Returns a new mutable bitmap; the caller owns
     * recycling of [src] and the returned bitmap.
     */
    fun drawGridOverlay(src: Bitmap, gridSize: Int): Bitmap {
        val n = gridSize.coerceIn(2, 12)
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val cellW = w / n
        val cellH = h / n

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x88FF3B30.toInt() // semi-transparent red grid lines
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        for (i in 1 until n) {
            canvas.drawLine(i * cellW, 0f, i * cellW, h, linePaint)
            canvas.drawLine(0f, i * cellH, w, i * cellH, linePaint)
        }
        canvas.drawRect(0f, 0f, w, h, linePaint)

        val radius = min(cellW, cellH) * 0.18f
        val bgPaint = Paint().apply { color = 0xCC000000.toInt() } // 80% opaque black disc
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt() // white digits
            textSize = radius * 1.7f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val baseline = (textPaint.ascent() + textPaint.descent()) / 2f
        var id = 1
        for (row in 0 until n) {
            for (col in 0 until n) {
                val cx = col * cellW + cellW / 2f
                val cy = row * cellH + cellH / 2f
                canvas.drawCircle(cx, cy, radius, bgPaint)
                canvas.drawText(id.toString(), cx, cy - baseline, textPaint)
                id++
            }
        }
        return out
    }

    /** Map a 1-based row-major grid id to the pixel centre of its cell. */
    fun gridToPixel(gridId: Int, gridSize: Int, width: Int, height: Int): Pair<Int, Int> {
        val n = gridSize.coerceIn(2, 12)
        val idx = (gridId - 1).coerceIn(0, n * n - 1)
        val row = idx / n
        val col = idx % n
        val x = ((col + 0.5) * width / n).toInt()
        val y = ((row + 0.5) * height / n).toInt()
        return x to y
    }

    /** Map a fractional (0..1) coordinate to a pixel coordinate. */
    fun fractionToPixel(fx: Double, fy: Double, width: Int, height: Int): Pair<Int, Int> =
        (fx.coerceIn(0.0, 1.0) * width).toInt() to (fy.coerceIn(0.0, 1.0) * height).toInt()
}