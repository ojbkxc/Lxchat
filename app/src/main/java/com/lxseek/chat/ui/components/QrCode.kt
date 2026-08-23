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
import com.lxseek.chat.util.DebugLog
import kotlin.math.abs

/**
 * QR code Composable for device pairing. Encodes [content] into a QR matrix
 * using a minimal built-in encoder (byte mode, L error-correction, versions
 * 1-4 → up to ~62 bytes), then renders it via Compose [Canvas] — no third-party
 * barcode dependency required.
 *
 * - Generation is cached with `remember(content)` so recomposition with
 *   unchanged content does not re-encode.
 * - On failure (content empty / too long for v1-4) a placeholder box is shown
 *   and the failure is logged via [DebugLog].
 * - The QR is rendered on a white background so it scans reliably under both
 *   light and dark themes (scanners expect dark-on-light modules).
 *
 * Ported from HyX (which used ZXing); LxChat has no barcode dependency, so a
 * compact self-contained encoder replaces ZXing while keeping the same API.
 *
 * @param content  text to encode (e.g. `"lxchat://pair/LX-AB12CD"`)
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
            val n = matrix.size
            val minDim = minOf(this.size.width, this.size.height)
            val cell = minDim / n
            val total = cell * n
            val inset = (minDim - total) / 2f
            // White background plate (scanners expect dark-on-light).
            drawRect(
                color = offModule,
                topLeft = Offset(inset, inset),
                size = Size(total, total)
            )
            // Dark modules.
            for (y in 0 until n) {
                val rowBase = y * n
                for (x in 0 until n) {
                    if (matrix.modules[rowBase + x]) {
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
        // Fallback when encoding fails (content empty / too long).
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

// ─────────────────────────────────────────────────────────────────────────────
// QR encoder — byte mode, L error-correction, versions 1-4.
//
// Scope is intentionally minimal (one mode, one EC level, four versions) which
// covers short pairing URLs (~62 bytes max) while keeping the implementation
// compact. Output is a real, scannable QR code.
// ─────────────────────────────────────────────────────────────────────────────

private const val MAX_DATA_BYTES = 62 // v4-L byte-mode capacity

private fun encodeQr(content: String): QrMatrix? {
    val bytes = content.toByteArray(Charsets.UTF_8)
    if (bytes.isEmpty() || bytes.size > MAX_DATA_BYTES) {
        DebugLog.w("QrCode", "content length ${bytes.size} out of range (1..$MAX_DATA_BYTES)")
        return null
    }
    return try {
        QrEncoder.encode(bytes)
    } catch (e: Exception) {
        DebugLog.e("QrCode", "encode failed", e)
        null
    }
}

/** Flat boolean matrix; [modules] is row-major (y * size + x). */
private class QrMatrix(val size: Int) {
    val modules = BooleanArray(size * size)
    private val reserved = BooleanArray(size * size)
    fun isReserved(x: Int, y: Int) = reserved[y * size + x]
    /** Mark (x,y) as a function module and set its value. */
    fun reserve(x: Int, y: Int, dark: Boolean) {
        reserved[y * size + x] = true
        modules[y * size + x] = dark
    }
    /** Set a data module value (must not be reserved). */
    fun set(x: Int, y: Int, dark: Boolean) { modules[y * size + x] = dark }
    fun get(x: Int, y: Int) = modules[y * size + x]
}

private object QrEncoder {
    // Version params: [size, dataCodewords, ecCodewords, alignmentPos].
    // V1-4 @ L are all single-block, so no interleaving is needed.
    // alignmentPos = -1 means no alignment pattern for that version.
    private val VERSIONS = arrayOf(
        intArrayOf(21, 19, 7, -1),  // v1
        intArrayOf(25, 28, 10, 18), // v2
        intArrayOf(29, 44, 15, 22), // v3
        intArrayOf(33, 64, 20, 26), // v4
    )

    // ── GF(256) tables (modular polynomial 0x11D) ──
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)
    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }
    private fun gmul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /** Pick the smallest version (0-based index into VERSIONS) that fits [byteCount]. */
    private fun selectVersion(byteCount: Int): Int {
        // byte-mode overhead = 4 (mode indicator) + 8 (length) bits = 12 bits.
        val need = (12 + 8 * byteCount + 7) / 8 // ceil((12 + 8n) / 8)
        for (v in VERSIONS.indices) {
            if (need <= VERSIONS[v][1]) return v
        }
        return -1
    }

    fun encode(data: ByteArray): QrMatrix {
        val vi = selectVersion(data.size)
        require(vi >= 0) { "data too long for v1-4: ${data.size} bytes" }
        val size = VERSIONS[vi][0]
        val dataCw = VERSIONS[vi][1]
        val ecCw = VERSIONS[vi][2]
        val alignPos = VERSIONS[vi][3]

        // 1. Build data bit stream (byte mode).
        val bits = ArrayList<Int>(dataCw * 8)
        fun push(value: Int, width: Int) {
            for (i in width - 1 downTo 0) bits.add((value shr i) and 1)
        }
        push(0b0100, 4) // byte-mode indicator
        push(data.size, 8) // length (versions 1-9 use 8 bits)
        for (b in data) push(b.toInt() and 0xFF, 8)
        push(0b0000, 4) // terminator (may be truncated below)
        // 2. Pad to byte boundary, truncate to capacity, then pad with alternating bytes.
        while (bits.size % 8 != 0) bits.add(0)
        while (bits.size > dataCw * 8) bits.removeAt(bits.lastIndex)
        val padBytes = intArrayOf(0xEC, 0x11)
        var pi = 0
        while (bits.size < dataCw * 8) {
            push(padBytes[pi and 1], 8)
            pi++
        }
        // 3. Convert to codeword array.
        val dataCodewords = IntArray(dataCw)
        for (i in 0 until dataCw) {
            var v = 0
            for (j in 0 until 8) v = (v shl 1) or bits[i * 8 + j]
            dataCodewords[i] = v
        }
        // 4. RS error-correction codewords.
        val ecCodewords = rsEncode(dataCodewords, ecCw)

        // 5. Build module matrix: function patterns first, then data.
        val m = QrMatrix(size)
        placeFinder(m, 0, 0)
        placeFinder(m, size - 7, 0)
        placeFinder(m, 0, size - 7)
        placeTiming(m)
        if (alignPos > 0) placeAlignment(m, alignPos, alignPos)
        reserveFormat(m)

        // 6. Place data bits (zigzag from bottom-right).
        placeData(m, dataCodewords + ecCodewords)

        // 7. Choose best mask, apply it, then write format info.
        val maskId = chooseMask(m)
        applyMask(m, maskId)
        placeFormat(m, bchFormat((0b01 shl 3) or maskId)) // L = 01
        return m
    }

    // ── Function patterns ──
    private fun placeFinder(m: QrMatrix, x0: Int, y0: Int) {
        for (dy in 0..6) {
            for (dx in 0..6) {
                val onEdge = dx == 0 || dx == 6 || dy == 0 || dy == 6
                val center = dx in 2..4 && dy in 2..4
                m.reserve(x0 + dx, y0 + dy, onEdge || center)
            }
        }
    }

    private fun placeTiming(m: QrMatrix) {
        for (i in 0 until m.size) {
            if (!m.isReserved(i, 6)) m.reserve(i, 6, i % 2 == 0)
            if (!m.isReserved(6, i)) m.reserve(6, i, i % 2 == 0)
        }
    }

    private fun placeAlignment(m: QrMatrix, cx: Int, cy: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                val onEdge = dx == -2 || dx == 2 || dy == -2 || dy == 2
                val center = dx == 0 && dy == 0
                m.reserve(cx + dx, cy + dy, onEdge || center)
            }
        }
    }

    /** Reserve format-info positions (filled later) + the fixed dark module. */
    private fun reserveFormat(m: QrMatrix) {
        val n = m.size
        for (i in 0..8) {
            if (!m.isReserved(i, 8)) m.reserve(i, 8, false)
            if (!m.isReserved(8, i)) m.reserve(8, i, false)
        }
        for (i in 0..7) {
            m.reserve(n - 1 - i, 8, false)
            m.reserve(8, n - 1 - i, false)
        }
        m.reserve(8, n - 8, true) // fixed dark module
    }

    // ── Data placement (zigzag, bottom-right → top-left) ──
    private fun placeData(m: QrMatrix, codewords: IntArray) {
        var bitIndex = 0
        var x = m.size - 1
        var upward = true
        while (x > 0) {
            if (x == 6) x-- // skip timing column
            for (k in 0 until m.size) {
                val y = if (upward) m.size - 1 - k else k
                for (col in 0..1) {
                    val cx = x - col
                    if (cx < 0) continue
                    if (!m.isReserved(cx, y)) {
                        val cw = codewords[bitIndex / 8]
                        val bit = (cw shr (7 - (bitIndex % 8))) and 1
                        m.set(cx, y, bit == 1)
                        bitIndex++
                    }
                }
            }
            x -= 2
            upward = !upward
        }
    }

    // ── Masking ──
    private fun maskFn(mask: Int, row: Int, col: Int): Boolean = when (mask) {
        0 -> (row + col) % 2 == 0
        1 -> row % 2 == 0
        2 -> col % 3 == 0
        3 -> (row + col) % 3 == 0
        4 -> (row / 2 + col / 3) % 2 == 0
        5 -> (row * col) % 2 + (row * col) % 3 == 0
        6 -> ((row * col) % 2 + (row * col) % 3) % 2 == 0
        7 -> ((row + col) % 2 + (row * col) % 3) % 2 == 0
        else -> false
    }

    /** Apply [mask] to non-reserved modules: dark = dark XOR mask(row, col). */
    private fun applyMask(m: QrMatrix, mask: Int) {
        for (y in 0 until m.size) {
            for (x in 0 until m.size) {
                if (!m.isReserved(x, y) && maskFn(mask, y, x)) {
                    m.set(x, y, !m.get(x, y))
                }
            }
        }
    }

    /** Pick the mask (0-7) with the lowest penalty score. */
    private fun chooseMask(m: QrMatrix): Int {
        var best = 0
        var bestScore = Int.MAX_VALUE
        for (mask in 0..7) {
            val score = penaltyMasked(m, mask)
            if (score < bestScore) {
                bestScore = score
                best = mask
            }
        }
        return best
    }

    /** Penalty score for [m] as if [mask] were applied (without mutating [m]). */
    private fun penaltyMasked(m: QrMatrix, mask: Int): Int {
        val n = m.size
        val mod = Array(n) { y ->
            BooleanArray(n) { x ->
                val v = m.get(x, y)
                if (m.isReserved(x, y)) v else v xor maskFn(mask, y, x)
            }
        }
        return penalty(mod, n)
    }

    private fun penalty(mod: Array<BooleanArray>, n: Int): Int {
        var score = 0
        // N1: runs of 5+ same-color modules in rows.
        for (y in 0 until n) {
            var run = 1
            for (x in 1 until n) {
                if (mod[y][x] == mod[y][x - 1]) run++
                else { if (run >= 5) score += 3 + (run - 5); run = 1 }
            }
            if (run >= 5) score += 3 + (run - 5)
        }
        // N1: runs in columns.
        for (x in 0 until n) {
            var run = 1
            for (y in 1 until n) {
                if (mod[y][x] == mod[y - 1][x]) run++
                else { if (run >= 5) score += 3 + (run - 5); run = 1 }
            }
            if (run >= 5) score += 3 + (run - 5)
        }
        // N2: 2x2 same-color blocks.
        for (y in 0 until n - 1) {
            for (x in 0 until n - 1) {
                val v = mod[y][x]
                if (mod[y][x + 1] == v && mod[y + 1][x] == v && mod[y + 1][x + 1] == v) score += 3
            }
        }
        // N3: 1:1:3:1:1 dark-light-dark pattern (1011101) in rows/cols.
        val p = booleanArrayOf(true, false, true, true, true, false, true)
        for (y in 0 until n) {
            for (x in 0 until n - 6) {
                var match = true
                for (k in 0..6) if (mod[y][x + k] != p[k]) { match = false; break }
                if (match) score += 40
            }
        }
        for (x in 0 until n) {
            for (y in 0 until n - 6) {
                var match = true
                for (k in 0..6) if (mod[y + k][x] != p[k]) { match = false; break }
                if (match) score += 40
            }
        }
        // N4: dark-module ratio deviation from 50%.
        var dark = 0
        for (y in 0 until n) for (x in 0 until n) if (mod[y][x]) dark++
        val ratio = dark * 100 / (n * n)
        score += abs(ratio - 50) / 5 * 10
        return score
    }

    // ── Format info ──
    /** BCH(15,5) encode [data] (5 bits) → 15 bits, XORed with mask 0x5412. */
    private fun bchFormat(data: Int): Int {
        var v = data shl 10
        for (i in 4 downTo 0) {
            if ((v shr (i + 10)) and 1 == 1) v = v xor (0b10100110111 shl i)
        }
        return ((data shl 10) or v) xor 0b101010001010010
    }

    /** Write the 15 format bits to both reserved locations + dark module. */
    private fun placeFormat(m: QrMatrix, format: Int) {
        val n = m.size
        fun bit(i: Int) = ((format shr i) and 1) == 1
        // First copy (around top-left finder; skips timing modules at (6,8)/(8,6)).
        m.set(8, 0, bit(0)); m.set(8, 1, bit(1)); m.set(8, 2, bit(2))
        m.set(8, 3, bit(3)); m.set(8, 4, bit(4)); m.set(8, 5, bit(5))
        m.set(8, 7, bit(6)); m.set(8, 8, bit(7)); m.set(7, 8, bit(8))
        m.set(5, 8, bit(9)); m.set(4, 8, bit(10)); m.set(3, 8, bit(11))
        m.set(2, 8, bit(12)); m.set(1, 8, bit(13)); m.set(0, 8, bit(14))
        // Second copy (top-right + bottom-left).
        m.set(n - 1, 8, bit(0)); m.set(n - 2, 8, bit(1)); m.set(n - 3, 8, bit(2))
        m.set(n - 4, 8, bit(3)); m.set(n - 5, 8, bit(4)); m.set(n - 6, 8, bit(5))
        m.set(n - 7, 8, bit(6))
        m.set(8, n - 7, bit(7)); m.set(8, n - 6, bit(8)); m.set(8, n - 5, bit(9))
        m.set(8, n - 4, bit(10)); m.set(8, n - 3, bit(11)); m.set(8, n - 2, bit(12))
        m.set(8, n - 1, bit(13))
        // Fixed dark module.
        m.set(8, n - 8, true)
    }

    // ── Reed-Solomon over GF(256) ──
    private fun rsGenerator(degree: Int): IntArray {
        val g = IntArray(degree + 1)
        g[0] = 1
        for (i in 0 until degree) {
            // g = g * (x - α^i)  →  new_g[j] = g[j-1] - g[j]·α^i
            // (in GF(2^m) subtraction == XOR). High→low so g[j-1] is still old.
            for (j in degree downTo 1) {
                g[j] = g[j - 1] xor gmul(g[j], EXP[i])
            }
            g[0] = gmul(g[0], EXP[i])
        }
        return g
    }

    private fun rsEncode(data: IntArray, ecCount: Int): IntArray {
        val gen = rsGenerator(ecCount) // gen[ecCount] == 1
        val result = IntArray(ecCount)
        for (b in data) {
            val factor = b xor result[0]
            for (i in 0 until ecCount - 1) {
                result[i] = result[i + 1] xor gmul(gen[i + 1], factor)
            }
            result[ecCount - 1] = factor // gmul(gen[ecCount], factor) == factor
        }
        return result
    }
}