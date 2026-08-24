package com.lxseek.chat.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.lxseek.chat.util.DebugLog

/**
 * QR code Composable for device pairing and WeChat binding. Encodes [content]
 * into a QR matrix using ZXing (all versions, all lengths), then renders it via
 * Compose [Canvas].
 *
 * - Generation is cached with `remember(content)` so recomposition with
 *   unchanged content does not re-encode.
 * - On failure (content empty / encode error) a placeholder box is shown
 *   and the failure is logged via [DebugLog].
 * - The QR is rendered on a white background so it scans reliably under both
 *   light and dark themes (scanners expect dark-on-light modules).
 *
 * @param content  text to encode (e.g. `"lxchat://pair/LX-AB12CD"` or a WeChat URL)
 * @param modifier outer modifier
 * @param size     square edge length in dp (default 200.dp)
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp
) {
    val matrix = remember(content) { encodeQr(content) }

    if (matrix != null) {
        val onModule = Color.Black
        val offModule = Color.White
        Canvas(modifier = modifier.size(size)) {
            val n = matrix.width
            val minDim = minOf(this.size.width, this.size.height)
            val cell = minDim / n
            val total = cell * n
            val inset = (minDim - total) / 2f
            drawRect(
                color = offModule,
                topLeft = Offset(inset, inset),
                size = Size(total, total)
            )
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (matrix.get(x, y)) {
                        drawRect(
                            color = onModule,
                            topLeft = Offset(inset + x * cell, inset + y * cell),
                            size = Size(cell, cell)
                        )
                    }
                }
            }
        }
    } else {
        Box(
            modifier = modifier
                .size(size)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("N/A", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun encodeQr(content: String): BitMatrix? {
    if (content.isEmpty()) {
        DebugLog.w("QrCode", "content is empty")
        return null
    }
    return try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to "L",
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 320, 320, hints)
    } catch (e: Exception) {
        DebugLog.e("QrCode", "encode failed", e)
        null
    }
}
