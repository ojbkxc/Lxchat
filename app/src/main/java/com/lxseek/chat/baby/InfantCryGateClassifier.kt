package com.lxseek.chat.baby

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.lxseek.chat.util.DebugLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.nio.FloatBuffer
import java.util.zip.ZipFile

/**
 * InfantCryNet Stage-0 哭声门控分类器（Kotlin 端推理）。
 *
 * 模型来源（safiabenarbia2001-ux/InfantCryNet 的 `cry_gate.pkl`）在 PC 上被拆解为：
 *  - 1 个 StandardScaler（ONNX）
 *  - 3 组交叉拟合 × (RandomForest + GradientBoosting + LogisticRegression)（ONNX）
 *  - 每组一个 Isotonic 校准查找表（JSON 内嵌断点）
 *
 * 推理流程（对齐 sklearn `Pipeline(StandardScaler, CalibratedClassifierCV(cv=3))`）：
 *  1. 45 维特征（[InfantCryFeatures.extract]）→ scaler 标准化
 *  2. 每组三个子模型 predict_proba[:,1] 按 2:1:1 加权（VotingClassifier soft）
 *  3. 加权结果经 Isotonic 分段线性表校准
 *  4. 三组取平均 = 最终「哭声概率」
 *
 * PC 端验证：与原 sklearn 输出误差 < 1e-7（合成哭声 0.97 / 白噪声 0.00）。
 *
 * 模型包为 assets 内 `infantcrynet_gate.zip`（首次使用时拷贝到
 * filesDir/baby_monitor/infantcrynet/ 并加载；同目录存在即跳过拷贝）。
 */
class InfantCryGateClassifier private constructor(
    private val env: OrtEnvironment,
    private val scalerSession: OrtSession,
    private val groups: List<Group>,
) : Closeable {

    private class Group(
        val rf: OrtSession,
        val gb: OrtSession,
        val lr: OrtSession,
        val isoX: DoubleArray,
        val isoY: DoubleArray,
    ) {
        fun close() {
            rf.close(); gb.close(); lr.close()
        }
    }

    companion object {
        private const val TAG = "InfantCryGate"
        private const val MODEL_ZIP_ASSET = "infantcrynet_gate.zip"
        internal const val MODEL_DIR_NAME = "infantcrynet"

        /** 投票权重（sklearn VotingClassifier weights=[2,1,1]）。 */
        private const val W_RF = 2.0
        private const val W_GB = 1.0
        private const val W_LR = 1.0

        /**
         * 从 assets 模型包加载。包内文件：
         *  gate_scaler.onnx / gate_{0,1,2}_{rf,gb,lr}.onnx / cry_gate_meta.json
         */
        fun load(context: Context): Result<InfantCryGateClassifier> = runCatching {
            val dir = java.io.File(context.filesDir, "baby_monitor/$MODEL_DIR_NAME")
            if (!dir.isDirectory || java.io.File(dir, "gate_scaler.onnx").let { !it.isFile || it.length() == 0L }) {
                dir.mkdirs()
                context.assets.open(MODEL_ZIP_ASSET).use { input ->
                    java.io.File(dir, MODEL_ZIP_ASSET).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                ZipFile(java.io.File(dir, MODEL_ZIP_ASSET)).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        val target = java.io.File(dir, e.name)
                        if (e.isDirectory) continue
                        zip.getInputStream(e).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                java.io.File(dir, MODEL_ZIP_ASSET).delete()
            }
            val env = OrtEnvironment.getEnvironment()
            fun open(name: String): OrtSession =
                env.createSession(java.io.File(dir, name).readBytes(), OrtSession.SessionOptions())

            val meta = JSONObject(java.io.File(dir, "cry_gate_meta.json").readText())
            val groups = (0 until meta.getJSONArray("groups").length()).map { gi ->
                val g = meta.getJSONArray("groups").getJSONObject(gi)
                Group(
                    rf = open(g.getString("rf")),
                    gb = open(g.getString("gb")),
                    lr = open(g.getString("lr")),
                    isoX = toDoubles(g.getJSONArray("iso_x")),
                    isoY = toDoubles(g.getJSONArray("iso_y")),
                )
            }
            InfantCryGateClassifier(env, open("gate_scaler.onnx"), groups)
        }

        private fun toDoubles(arr: JSONArray): DoubleArray =
            DoubleArray(arr.length()) { arr.getDouble(it) }
    }

    /**
     * 对 45 维特征向量做推理。
     * @param features 键名须来自 [InfantCryFeatures.extract]（缺失键按 0 处理）。
     * @return 哭声概率 0..1；失败返回 null。
     */
    fun classify(features: Map<String, Double>): Double? = runCatching {
        val x = FloatArray(45) { features[FEATURE_ORDER[it]]?.toFloat() ?: 0f }
        val scaled = scale(x)
        val calibrated = DoubleArray(groups.size)
        for ((gi, g) in groups.withIndex()) calibrated[gi] = calibratedVote(g, scaled)
        calibrated.average()
    }.onFailure {
        DebugLog.e(TAG, "classify failed", it)
    }.getOrNull()

    /** scaler ONNX：输入 [1,45] float → 输出 [1,45]。 */
    private fun scale(x: FloatArray): FloatArray {
        val shape = longArrayOf(1, 45)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), shape).use { tensor ->
            scalerSession.run(mapOf("X" to tensor)).use { output ->
                @Suppress("UNCHECKED_CAST")
                val value = output.get(0).value as Array<FloatArray>
                return value[0]
            }
        }
    }

    /** 单组：三子模型 2:1:1 加权 → isotonic 分段线性校准。 */
    private fun calibratedVote(g: Group, x: FloatArray): Double {
        val pRf = probaPositive(g.rf, x)
        val pGb = probaPositive(g.gb, x)
        val pLr = probaPositive(g.lr, x)
        val vote = (W_RF * pRf + W_GB * pGb + W_LR * pLr) / (W_RF + W_GB + W_LR)
        return interpIso(g.isoX, g.isoY, vote)
    }

    /** 二分类子模型输出 [label, probabilities]，取 probabilities[:,1]。 */
    private fun probaPositive(session: OrtSession, x: FloatArray): Double {
        val shape = longArrayOf(1, 45)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), shape).use { tensor ->
            session.run(mapOf("X" to tensor)).use { output ->
                val value = output.get(1).value
                return when (value) {
                    is Array<*> -> (value[0] as FloatArray)[1].toDouble()
                    is FloatArray -> value[1].toDouble()
                    else -> 0.0
                }
            }
        }
    }

    /** 分段线性查找（numpy.interp 语义，边界取端点值）。 */
    private fun interpIso(xs: DoubleArray, ys: DoubleArray, v: Double): Double {
        if (v <= xs.first()) return ys.first()
        if (v >= xs.last()) return ys.last()
        var lo = 0
        var hi = xs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (xs[mid] <= v) lo = mid else hi = mid
        }
        val t = (v - xs[lo]) / (xs[hi] - xs[lo])
        return ys[lo] + t * (ys[hi] - ys[lo])
    }

    override fun close() {
        groups.forEach { it.close() }
        scalerSession.close()
    }

    /** sklearn `CryGate.feature_names` 的固定顺序。 */
    internal val FEATURE_ORDER: List<String> = listOf(
        "energy_mean", "energy_std", "energy_cv", "energy_max", "energy_entropy", "energy_delta_mean",
        "f0_mean", "f0_std", "f0_cv", "f0_median", "f0_range", "voiced_ratio", "voiced_prob_mean",
        "mfcc_0_mean", "mfcc_0_std", "mfcc_1_mean", "mfcc_1_std", "mfcc_2_mean", "mfcc_2_std",
        "mfcc_3_mean", "mfcc_3_std", "mfcc_4_mean", "mfcc_4_std", "mfcc_5_mean", "mfcc_5_std",
        "mfcc_6_mean", "mfcc_6_std", "mfcc_7_mean", "mfcc_7_std", "mfcc_8_mean", "mfcc_8_std",
        "mfcc_9_mean", "mfcc_9_std", "mfcc_10_mean", "mfcc_10_std", "mfcc_11_mean", "mfcc_11_std",
        "mfcc_12_mean", "mfcc_12_std",
        "spectral_centroid_mean", "spectral_centroid_std", "spectral_bandwidth_mean",
        "spectral_flatness_mean", "spectral_flatness_std",
        "zcr_mean",
    )
}
