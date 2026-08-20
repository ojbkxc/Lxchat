package com.lxseek.chat.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileOutputStream

internal object MessageLongImageRenderer {

    private const val WIDTH_PX = 1080
    private const val MAX_HEIGHT_PX = 20000
    private const val PADDING_PX = 48
    private const val TITLE_SIZE = 36f
    private const val BODY_SIZE = 30f
    private const val LINE_SPACING = 1.4f
    private const val SECTION_GAP = 32f
    private const val TITLE_COLOR = 0xFF1A1A1A.toInt()
    private const val BODY_COLOR = 0xFF333333.toInt()
    private const val BG_COLOR = 0xFFFFFFFF.toInt()
    private const val ACCENT_COLOR = 0xFF6200EE.toInt()

    fun renderToCacheFile(context: Context, title: String, body: String): File? {
        if (body.isBlank()) return null
        val bitmap = render(title, body) ?: return null
        val cacheDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(cacheDir, "lxchat_share_${System.currentTimeMillis()}.png")
        try {
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
        } catch (_: Throwable) {
            bitmap.recycle()
            return null
        }
        bitmap.recycle()
        return file
    }

    fun renderToBitmap(title: String, body: String): Bitmap? {
        if (body.isBlank()) return null
        return render(title, body)
    }

    private fun render(title: String, body: String): Bitmap? {
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TITLE_SIZE
            color = TITLE_COLOR
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = BODY_SIZE
            color = BODY_COLOR
            typeface = Typeface.DEFAULT
            linkColor = ACCENT_COLOR
        }
        val contentWidth = WIDTH_PX - 2 * PADDING_PX
        val titleLayout = if (title.isNotBlank()) {
            StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, contentWidth)
                .setLineSpacing(0f, LINE_SPACING)
                .build()
        } else null
        val bodyLayout = StaticLayout.Builder.obtain(body, 0, body.length, bodyPaint, contentWidth)
            .setLineSpacing(0f, LINE_SPACING)
            .build()
        var totalHeight = PADDING_PX * 2
        if (titleLayout != null) totalHeight += titleLayout.height + SECTION_GAP.toInt()
        totalHeight += bodyLayout.height
        if (totalHeight > MAX_HEIGHT_PX) totalHeight = MAX_HEIGHT_PX
        val bitmap = createBitmap(WIDTH_PX, totalHeight)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG_COLOR)
        var y = PADDING_PX.toFloat()
        if (titleLayout != null) {
            canvas.save()
            canvas.translate(PADDING_PX.toFloat(), y)
            titleLayout.draw(canvas)
            canvas.restore()
            y += titleLayout.height + SECTION_GAP
        }
        canvas.save()
        canvas.translate(PADDING_PX.toFloat(), y)
        bodyLayout.draw(canvas)
        canvas.restore()
        return bitmap
    }
}
