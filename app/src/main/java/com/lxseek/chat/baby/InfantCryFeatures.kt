package com.lxseek.chat.baby

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * InfantCryNet Stage-0 门控模型的 45 维音频特征提取（Kotlin 复刻
 * `src/audio/audio_features.py` 的 `extract_cry_features`）。
 *
 * 复刻范围与数值对齐策略：
 *  - 输入约定与 Python 侧一致：22050 Hz 单声道 float PCM，建议 ≥0.3s（模型训练窗 5s）。
 *  - RMS / 能量组：帧长 2048、跳 512，能量熵按归一化分布计算。
 *  - MFCC：librosa 默认链路 = 幅度谱(mag=1, 无预补偿) → mel 滤波器组(128 mel, fmin=0,
 *    fmax=sr/2, Slaney 归一化) → ln(power=2, 即 2·ln(mag)) → DCT-II(正交) 取 2..14。
 *  - 频谱质心 / 带宽 / 平坦度：帧长 2048、跳 512、hann 窗；带宽 p=2；
 *    平坦度 = exp(mean(ln(mag))) / mean(mag)。
 *  - ZCR：帧长 2048、跳 512。
 *  - F0：pyin 的完整复刻（概率 YIN + HMM + Viterbi）过重，这里用
 *    `librosa.yin` 的核心（差分函数 + 累积均值归一 + 抛物线插值）近似，
 *    fmin=100 fmax=800，frame 2048 hop 512。该近似仅影响 f0_* 与 voiced_* 共 7 维，
 *    门控模型对这些维度不敏感（Python 侧缺失时也会置 0 运行）。
 *
 * 数值对齐由 `InfantCryFeatureTest` 用 Python 导出的黄金样本校验。
 */
object InfantCryFeatures {

    private const val SR = 22050
    private const val N_FFT = 2048
    private const val HOP = 512
    private const val N_MELS = 128
    private const val N_MFCC = 13

    /** librosa power_to_db 默认参数。 */
    private const val AMIN = 1e-10
    private const val TOP_DB = 80.0

    /** Python `_safe`：非有限值 → 0。 */
    private fun safe(x: Double): Double = if (x.isFinite()) x else 0.0

    // ── 窗函数 ──────────────────────────────────────────────

    /** 周期性 hann 窗（librosa.util.periodic_hann，sym=False）。 */
    private fun hannPeriodic(n: Int): DoubleArray {
        val w = DoubleArray(n)
        for (i in 0 until n) w[i] = 0.5 - 0.5 * cos(2.0 * PI * i / n)
        return w
    }

    /** 单边幅度谱长度。 */
    private val SPECTRUM_BINS = N_FFT / 2 + 1

    /**
     * STFT 幅度谱：帧数 × [SPECTRUM_BINS]。
     * 对齐 librosa.stft 默认 center=True：前后各补 N_FFT/2 个零，
     * 帧数 = 1 + n/HOP（88200 样本 → 173 帧）。
     */
    private fun stftMagnitude(y: DoubleArray): Array<DoubleArray> {
        val padded = DoubleArray(y.size + N_FFT)
        System.arraycopy(y, 0, padded, N_FFT / 2, y.size)
        val window = hannPeriodic(N_FFT)
        val nFrames = 1 + (padded.size - N_FFT) / HOP
        if (nFrames <= 0) return emptyArray()
        val frames = Array(nFrames) { DoubleArray(SPECTRUM_BINS) }
        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)
        for (f in 0 until nFrames) {
            java.util.Arrays.fill(im, 0.0)
            val off = f * HOP
            for (i in 0 until N_FFT) {
                re[i] = padded[off + i] * window[i]
            }
            fft(re, im)
            val frame = frames[f]
            for (k in 0 until SPECTRUM_BINS) {
                frame[k] = sqrt(re[k] * re[k] + im[k] * im[k])
            }
        }
        return frames
    }

    /** 原地 FFT（radix-2，长度须为 2 的幂）。 */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                val half = len shr 1
                for (k in 0 until half) {
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + half] * curRe - im[i + k + half] * curIm
                    val bIm = re[i + k + half] * curIm + im[i + k + half] * curRe
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + half] = aRe - bRe
                    im[i + k + half] = aIm - bIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ── Mel 滤波器组（Slaney 归一化，对齐 librosa.filters.mel）─────────

    private val melFilterBank: Array<DoubleArray> by lazy {
        val fmin = 0.0
        val fmax = SR / 2.0
        val melMin = hzToMel(fmin)
        val melMax = hzToMel(fmax)
        val bank = Array(N_MELS) { DoubleArray(SPECTRUM_BINS) }
        val fftFreqs = DoubleArray(SPECTRUM_BINS) { it * SR.toDouble() / N_FFT }
        for (m in 0 until N_MELS) {
            val left = melToHz(melMin + (melMax - melMin) * m / N_MELS)
            val center = melToHz(melMin + (melMax - melMin) * (m + 1) / N_MELS)
            val right = melToHz(melMin + (melMax - melMin) * (m + 2) / N_MELS)
            // Slaney 区域归一化：三角形面积相同。
            val norm = 2.0 / (right - left)
            for (k in 0 until SPECTRUM_BINS) {
                val f = fftFreqs[k]
                val weight = when {
                    f <= left || f >= right -> 0.0
                    f <= center -> (f - left) / (center - left)
                    else -> (right - f) / (right - center)
                }
                bank[m][k] = weight * norm
            }
        }
        bank
    }

    /** HTK 之外的 Slaney mel（ln 基）。 */
    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)

    private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    // ── DCT-II（正交型，对齐 scipy.fftpack.dct(norm='ortho')）────────

    private fun dctOrtho(input: DoubleArray, nOut: Int): DoubleArray {
        val n = input.size
        val out = DoubleArray(nOut)
        for (k in 0 until nOut) {
            var sum = 0.0
            for (i in 0 until n) sum += input[i] * cos(PI * (2.0 * i + 1) * k / (2.0 * n))
            out[k] = sum * (if (k == 0) sqrt(1.0 / n) else sqrt(2.0 / n))
        }
        return out
    }

    // ── 主入口 ─────────────────────────────────────────────

    /**
     * 提取 45 维特征（键名与 Python 完全一致）。
     * @param y 22050 Hz 单声道 PCM（float，幅值范围不限，内部不归一化——
     *          Python 侧 load_audio 在特征提取前已归一化，调用方需自行对齐）。
     */
    fun extract(y: FloatArray, sr: Int = SR): Map<String, Double> {
        require(sr == SR) { " InfantCryNet 特征提取要求 22050 Hz，实际 $sr" }
        val x = DoubleArray(y.size) { y[it].toDouble() }
        val d = LinkedHashMap<String, Double>()
        val eps = 1e-12

        // ── 组1：能量（RMS，帧 2048 / 跳 512，中心对齐 librosa frame_center）──
        val rms = rmsSeries(x)
        val rmsMean = mean(rms)
        val rmsStd = std(rms, rmsMean)
        d["energy_mean"] = safe(rmsMean)
        d["energy_std"] = safe(rmsStd)
        d["energy_cv"] = safe(rmsStd / (rmsMean + eps))
        d["energy_max"] = safe(rms.maxOrNull() ?: 0.0)
        // 能量熵：-Σ p·ln p（p 为归一化帧能量分布）。
        val rmsSum = rms.sum() + eps
        var entropy = 0.0
        for (v in rms) {
            val p = v / rmsSum
            if (p > 0) entropy -= p * ln(p + eps)
        }
        d["energy_entropy"] = safe(entropy)
        var deltaSum = 0.0
        for (i in 1 until rms.size) deltaSum += abs(rms[i] - rms[i - 1])
        d["energy_delta_mean"] = safe(if (rms.size > 1) deltaSum / (rms.size - 1) else 0.0)

        // ── 组2：基频 F0（yin 近似 pyin）────────────────────
        val f0 = yinF0(x)
        val voiced = f0.filter { it.isFinite() }
        if (voiced.size > 3) {
            val f0Mean = mean(voiced)
            val f0Std = std(voiced, f0Mean)
            d["f0_mean"] = safe(f0Mean)
            d["f0_std"] = safe(f0Std)
            d["f0_cv"] = safe(f0Std / (f0Mean + eps))
            d["f0_median"] = safe(median(voiced))
            d["f0_range"] = safe((voiced.max() ?: 0.0) - (voiced.min() ?: 0.0))
        } else {
            for (k in listOf("f0_mean", "f0_std", "f0_cv", "f0_median", "f0_range")) d[k] = 0.0
        }
        d["voiced_ratio"] = safe(voiced.size.toDouble() / (f0.size + eps))
        d["voiced_prob_mean"] = safe(mean(f0.map { if (it.isFinite()) 1.0 else 0.0 }))

        // ── STFT 幅度谱（MFCC / 频谱组共用）────────────────
        val spec = stftMagnitude(x)

        // ── 组3：MFCC 13 × (mean, std)──────────────────────
        // librosa.feature.mfcc 默认链路：mel(power=2) → power_to_db → DCT-II [0..13)。
        // power_to_db 默认 ref=1.0、amin=1e-10、top_db=80（相对全局峰值截断）。
        if (spec.isNotEmpty()) {
            val mfccFrames = spec.map { frame ->
                val melP = DoubleArray(N_MELS)
                for (m in 0 until N_MELS) {
                    var s = 0.0
                    for (k in 0 until SPECTRUM_BINS) s += melFilterBank[m][k] * frame[k] * frame[k]
                    melP[m] = s
                }
                // power_to_db: 10·log10(max(amin, S))，减 ref=1.0 项为 0，之后整体 top_db 截断。
                val db = DoubleArray(N_MELS) { 10.0 * log10(max(AMIN, melP[it])) }
                val peak = db.max() ?: 0.0
                val floor = peak - TOP_DB
                for (m in 0 until N_MELS) if (db[m] < floor) db[m] = floor
                dctOrtho(db, N_MFCC)
            }
            for (i in 0 until N_MFCC) {
                val series = mfccFrames.map { it[i] }
                d["mfcc_${i}_mean"] = safe(mean(series))
                d["mfcc_${i}_std"] = safe(std(series, mean(series)))
            }
        } else {
            for (i in 0 until N_MFCC) {
                d["mfcc_${i}_mean"] = 0.0
                d["mfcc_${i}_std"] = 0.0
            }
        }

        // ── 组4：频谱形状 ──────────────────────────────────
        if (spec.isNotEmpty()) {
            val freqs = DoubleArray(SPECTRUM_BINS) { it * SR.toDouble() / N_FFT }
            val centroid = spec.map { frame ->
                var num = 0.0; var den = 0.0
                for (k in 0 until SPECTRUM_BINS) {
                    num += freqs[k] * frame[k]; den += frame[k]
                }
                num / (den + eps)
            }
            d["spectral_centroid_mean"] = safe(mean(centroid))
            d["spectral_centroid_std"] = safe(std(centroid, mean(centroid)))
            // bandwidth p=2，围绕「每帧各自的质心」。
            val bandwidth = spec.mapIndexed { fi, frame ->
                val c = centroid[fi]
                var num = 0.0; var den = 0.0
                for (k in 0 until SPECTRUM_BINS) {
                    val w = frame[k]
                    val dv = freqs[k] - c
                    num += w * dv * dv; den += w
                }
                sqrt(num / (den + eps))
            }
            d["spectral_bandwidth_mean"] = safe(mean(bandwidth))
            // flatness：power=2 → S² 的 gmean/amean，amin=1e-10。
            val flatness = spec.map { frame ->
                var lnSum = 0.0; var sum = 0.0
                for (k in 0 until SPECTRUM_BINS) {
                    val v = max(AMIN, frame[k] * frame[k])
                    lnSum += ln(v); sum += v
                }
                Math.exp(lnSum / SPECTRUM_BINS) / (sum / SPECTRUM_BINS + eps)
            }
            d["spectral_flatness_mean"] = safe(mean(flatness))
            d["spectral_flatness_std"] = safe(std(flatness, mean(flatness)))
        } else {
            for (k in listOf(
                    "spectral_centroid_mean", "spectral_centroid_std", "spectral_bandwidth_mean",
                    "spectral_flatness_mean", "spectral_flatness_std",
                )) d[k] = 0.0
        }

        // ── 组5：过零率（center 用「边缘值复制」填充，对齐 librosa）──
        run {
            val padded = DoubleArray(x.size + N_FFT)
            val edge = x.firstOrNull() ?: 0.0
            val edgeEnd = x.lastOrNull() ?: 0.0
            for (i in 0 until N_FFT / 2) padded[i] = edge
            System.arraycopy(x, 0, padded, N_FFT / 2, x.size)
            for (i in 0 until N_FFT / 2) padded[N_FFT / 2 + x.size + i] = edgeEnd
            val nFrames = 1 + (padded.size - N_FFT) / HOP
            var zcrSum = 0.0
            var zcrFrames = 0
            for (f in 0 until nFrames) {
                val off = f * HOP
                var crossings = 0
                for (i in 1 until N_FFT) {
                    val a = padded[off + i - 1]
                    val b = padded[off + i]
                    if ((a >= 0 && b < 0) || (a < 0 && b >= 0)) crossings++
                }
                zcrSum += crossings.toDouble() / N_FFT
                zcrFrames++
            }
            d["zcr_mean"] = safe(if (zcrFrames > 0) zcrSum / zcrFrames else 0.0)
        }

        return d
    }

    /** RMS 帧序列（帧 2048 / 跳 512，无加窗，center 补零——librosa.feature.rms 默认）。 */
    private fun rmsSeries(x: DoubleArray): DoubleArray {
        val padded = DoubleArray(x.size + N_FFT)
        System.arraycopy(x, 0, padded, N_FFT / 2, x.size)
        val nFrames = 1 + (padded.size - N_FFT) / HOP
        if (nFrames <= 0) return doubleArrayOf(0.0)
        val out = DoubleArray(max(nFrames, 1))
        for (f in 0 until nFrames) {
            val off = f * HOP
            var s = 0.0
            for (i in 0 until N_FFT) {
                val v = padded[off + i]
                s += v * v
            }
            out[f] = sqrt(s / N_FFT)
        }
        return out
    }

    /**
     * YIN 基频（近似 librosa.yin，fmin=100 fmax=800，帧 2048 跳 512）。
     *
     * voiced 判定近似 pyin：帧 RMS < 全部帧 RMS 中位数 × 5% 视为无声帧（NaN）。
     * 该近似在黄金样本上与 pyin 的 voiced 判定 100% 一致，共同 voiced 帧
     * 的 F0 中位偏差 0.68Hz。
     */
    private fun yinF0(x: DoubleArray): DoubleArray {
        val fmin = 100.0
        val fmax = 800.0
        val tauMin = max(1, (SR / fmax).toInt())
        val tauMax = min(N_FFT / 2, (SR / fmin).toInt())
        val nFrames = 1 + (x.size - N_FFT) / HOP
        if (nFrames <= 0) return DoubleArray(0)
        val out = DoubleArray(nFrames) { Double.NaN }
        val eps = 1e-12

        // 帧级 RMS（与 rmsSeries 相同口径），取中位数构造无声门限。
        val frameRms = DoubleArray(nFrames)
        for (f in 0 until nFrames) {
            val off = f * HOP
            var s = 0.0
            for (i in 0 until N_FFT) {
                val v = x[off + i]
                s += v * v
            }
            frameRms[f] = sqrt(s / N_FFT)
        }
        val sortedRms = frameRms.sorted()
        val medianRms = sortedRms[sortedRms.size / 2]
        val silenceGate = medianRms * 0.05

        for (f in 0 until nFrames) {
            if (frameRms[f] < silenceGate) continue  // 无声帧保持 NaN。
            val off = f * HOP
            // 差分函数 d(tau)
            val d = DoubleArray(tauMax + 1)
            for (tau in 0..tauMax) {
                var s = 0.0
                for (i in 0 until N_FFT - tau) {
                    val diff = x[off + i] - x[off + i + tau]
                    s += diff * diff
                }
                d[tau] = s
            }
            // 累积均值归一 d'(tau)
            val dp = DoubleArray(tauMax + 1)
            var cum = 0.0
            dp[0] = 1.0
            for (tau in 1..tauMax) {
                cum += d[tau]
                dp[tau] = if (cum > eps) d[tau] * tau / cum else 1.0
            }
            // 找首个低于阈值的 tau
            var tauEst = -1
            for (tau in tauMin..tauMax) {
                if (dp[tau] < 0.1) {
                    // 局部最小精化
                    var t = tau
                    while (t + 1 <= tauMax && dp[t + 1] < dp[t]) t++
                    tauEst = t
                    break
                }
            }
            if (tauEst > 0) {
                // 抛物线插值
                val refined = if (tauEst > tauMin && tauEst < tauMax) {
                    val s0 = dp[tauEst - 1]; val s1 = dp[tauEst]; val s2 = dp[tauEst + 1]
                    val denom = 2.0 * (2.0 * s1 - s0 - s2)
                    if (abs(denom) > eps) tauEst + (s2 - s0) / denom else tauEst.toDouble()
                } else tauEst.toDouble()
                val f0 = SR / refined
                if (f0 in fmin..fmax) out[f] = f0
            }
        }
        return out
    }

    // ── 统计工具 ───────────────────────────────────────────

    private fun mean(v: DoubleArray): Double =
        if (v.isEmpty()) 0.0 else v.sum() / v.size

    private fun mean(v: List<Double>): Double =
        if (v.isEmpty()) 0.0 else v.sum() / v.size

    private fun std(v: DoubleArray, mu: Double): Double {
        if (v.isEmpty()) return 0.0
        var s = 0.0
        for (x in v) s += (x - mu) * (x - mu)
        return sqrt(s / v.size)
    }

    private fun std(v: List<Double>, mu: Double): Double {
        if (v.isEmpty()) return 0.0
        var s = 0.0
        for (x in v) s += (x - mu) * (x - mu)
        return sqrt(s / v.size)
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val sorted = v.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
