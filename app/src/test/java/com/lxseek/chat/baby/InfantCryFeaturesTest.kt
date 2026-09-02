package com.lxseek.chat.baby

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InfantCryFeatures] 与 Python librosa 参考实现的数值对齐测试。
 *
 * 黄金样本来自 PC 端（`extract_cry_features`，librosa 0.11 / sklearn 1.8），
 * 音频为 4 秒 22050 Hz 合成哭声（450Hz 基频 + 颤音 + 哭-停节奏），
 * 完整数据在 `src/test/resources/baby/infantcrynet_golden.json`。
 *
 * 容差分级：MFCC/频谱/ZCR/能量 为精确实现，要求 1e-3 相对误差；
 * f0_* / voiced_*（pyin 的 yin 近似）放宽到 15%。
 */
class InfantCryFeaturesTest {

    private fun golden(): Triple<FloatArray, Map<String, Double>, Double> {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("baby/infantcrynet_golden.json")
        assertNotNull("黄金样本资源缺失", stream)
        val root = JSONObject(stream!!.bufferedReader().readText())
        val samplesArr = root.getJSONArray("samples")
        val samples = FloatArray(samplesArr.length()) { samplesArr.getDouble(it).toFloat() }
        val feats = root.getJSONObject("features")
        val expected = feats.keys().asSequence().associateWith { feats.getDouble(it) }
        return Triple(samples, expected, root.getDouble("proba"))
    }

    @Test
    fun `45 features align with librosa reference`() {
        val (samples, expected, _) = golden()
        val actual = InfantCryFeatures.extract(samples)

        assertEquals("特征数量", 45, actual.size)

        val approxKeys = setOf(
            "f0_mean", "f0_std", "f0_cv", "f0_median", "f0_range",
            "voiced_ratio", "voiced_prob_mean",
        )
        var checked = 0
        for ((key, exp) in expected) {
            val act = actual[key] ?: run {
                assertEquals("缺少特征 $key", 0.0, 0.0); continue
            }
            checked++
            if (key in approxKeys) {
                // pyin→yin 近似：允许 15% 相对偏差或绝对 0.1。
                assertTrue(
                    "$key: expect=$exp actual=$act 超出近似容差",
                    kotlin.math.abs(exp - act) <= maxOf(kotlin.math.abs(exp) * 0.15, 0.1),
                )
            } else {
                // 精确复刻组：相对 1e-3 或绝对 1e-4。
                assertTrue(
                    "$key: expect=$exp actual=$act 超出精确容差",
                    kotlin.math.abs(exp - act) <= maxOf(kotlin.math.abs(exp) * 1e-3, 1e-4),
                )
            }
        }
        assertEquals("对齐的特征数量", expected.size, checked)
    }

    @Test
    fun `silence yields near-zero energy features`() {
        val silence = FloatArray(22050) { 0f }
        val feats = InfantCryFeatures.extract(silence)
        assertEquals(0.0, feats["energy_mean"]!!, 1e-9)
        assertEquals(0.0, feats["zcr_mean"]!!, 1e-9)
        assertEquals(0.0, feats["energy_max"]!!, 1e-9)
    }

    @Test
    fun `pure tone has low flatness and stable f0`() {
        // 400Hz 纯音：平坦度应显著低于白噪声，f0 均值应接近 400。
        val sr = 22050
        val tone = FloatArray(sr * 2) { (0.5 * kotlin.math.sin(2 * Math.PI * 400 * it / sr)).toFloat() }
        val feats = InfantCryFeatures.extract(tone)
        assertTrue("纯音平坦度 ${feats["spectral_flatness_mean"]} 应 < 0.2",
            feats["spectral_flatness_mean"]!! < 0.2)
        val f0 = feats["f0_mean"]!!
        assertTrue("纯音 f0 $f0 应接近 400Hz", f0 in 350.0..450.0)
    }
}
