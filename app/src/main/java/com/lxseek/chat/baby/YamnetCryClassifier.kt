package com.lxseek.chat.baby

import android.content.Context
import com.lxseek.chat.util.DebugLog
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * YAMNet TFLite 推理封装（AudioSet 521 类音频事件分类）。
 *
 * 模型：`lite-model_yamnet_tflite_1.tflite`（TensorFlow Hub 官方全精度 float32 版，
 * ~16MB，非量化），下载地址见 [BabyModelManager.DOWNLOAD_URL]。输入 16kHz 单声道
 * float PCM，每次推理至少 ~0.975s（15600 样本）。
 *
 * 两种输出形态都被支持（不同来源的 YAMNet 转换版布局不同）：
 *  - `[1, frames, 521]`（3D）——逐帧分数，取哭声类逐帧最大值（对齐 crywatch 的
 *    "peak per-frame probability"）；
 *  - `[1, 521]`（2D）——整段平均分数，直接读哭声类。
 *
 * 类别索引按名字在运行期解析（不硬编码下标，抗模型版本差异）：
 *  - 哭声三连：`Baby cry, infant cry` / `Crying, sobbing` / `Whimper`
 *  - 语音：`Speech`
 */
class YamnetCryClassifier(context: Context, modelFile: File) {

    companion object {
        private const val TAG = "YamnetCryClassifier"

        /** YAMNet 官方 TFLite 的输入是 [1, 15600]（0.975s @16kHz）。 */
        const val INPUT_SAMPLES = 15600
        const val NUM_CLASSES = 521

        /** 哭声类（AudioSet display name）。 */
        val CRY_CLASS_NAMES = listOf("Baby cry, infant cry", "Crying, sobbing", "Whimper")

        /** 语音类（用于"有人在哄"抑制）。 */
        const val SPEECH_CLASS_NAME = "Speech"

        /** 模型旁边的类别名清单文件（下载器一并落盘）。 */
        const val LABELS_FILE_NAME = "yamnet_labels.txt"

        /**
         * TensorFlow Hub `google/yamnet/1` 官方 class map（CSV display_name 列）固定行号。
         * 顺序须与 [BabyModelManager] 落盘的 YAMNET_CLASS_NAMES 完全一致：
         * index 0=Speech … 18=Chuckle, chortle / 19=Crying, sobbing /
         * 20=Baby cry, infant cry / 21=Whimper。
         */
        val FALLBACK_INDEXES: Map<String, Int> = mapOf(
            "Speech" to 0,
            "Crying, sobbing" to 19,
            "Baby cry, infant cry" to 20,
            "Whimper" to 21,
        )
    }

    private val interpreter: Interpreter
    private val cryIndices: IntArray
    private val speechIndex: Int

    /** 输出是否为逐帧 3D 布局（true=3D, false=2D），按输出张量秩判定。 */
    private val frameLayout: Boolean

    /**
     * 输入张量秩：
     *  - 1 = 扁平波形 `[N]`（TF Hub 全精度版为动态 `[-1]`，报告 shape `[1]`）
     *  - 2 = `[1, N]`
     */
    private val inputRank: Int

    init {
        val options = Interpreter.Options().apply { numThreads = 2 }
        interpreter = Interpreter(loadModel(context, modelFile), options)
        // TFLite Java 在 allocateTensors() 之前不允许读取张量形态，先分配一次。
        interpreter.allocateTensors()

        // YAMNet TFLite 各版本输入形态不一致，必须显式统一：
        //  - TF Hub 全精度版（App 默认下载源，~16MB）：rank-1 **动态** [-1]（shape 报 [1]）。
        //    若不在推理前把输入张量 resize 到窗长，Interpreter.run 会因缓冲大小与张量
        //    大小（默认 1 个元素）不匹配而失败——该异常在部分 TFLite 版本走原生层 abort，
        //    无法被 Kotlin try/catch 捕获，表现为「启用哭声监护后直接闪退」。
        //  - 参考实现 baby-monitor 的复刻版：rank-1 固定 [15600]。
        //  - 其余转换版：rank-2 [1, 15600]。
        // 这里按秩把输入张量显式 resize 到推理窗长，固定 shape 的模型 resize 到同形是
        // no-op（安全）；动态模型则由此拿到正确形态。
        val inputShape = interpreter.getInputTensor(0).shape()
        inputRank = inputShape.size
        val targetShape = when (inputRank) {
            1 -> intArrayOf(INPUT_SAMPLES)
            2 -> intArrayOf(1, INPUT_SAMPLES)
            else -> {
                interpreter.close()
                throw IllegalArgumentException("不支持的 YAMNet 输入秩: $inputRank")
            }
        }
        if (!inputShape.contentEquals(targetShape)) {
            interpreter.resizeInput(0, targetShape)
            interpreter.allocateTensors()
        }

        val outputShape = interpreter.getOutputTensor(0).shape()
        frameLayout = outputShape.size == 3
        // 类别名在部分 TFLite 转换里无法从张量元数据读取；这里靠伴随的 labels 文件
        // （BabyModelManager 下载时一并落盘）。读不到时退回官方固定索引。
        val labels = loadLabels(modelFile)
        val resolver = LabelResolver(labels)
        cryIndices = CRY_CLASS_NAMES.mapNotNull { resolver.indexOf(it) }.toIntArray()
        speechIndex = resolver.indexOf(SPEECH_CLASS_NAME) ?: -1
        require(cryIndices.isNotEmpty()) { "YAMNet 模型缺少哭声类别（labels 不匹配）" }
        DebugLog.i(
            TAG,
            "YAMNet ready: input=${targetShape.contentToString()} output=${outputShape.contentToString()} " +
                "frameLayout=$frameLayout cry=${cryIndices.contentToString()} speech=$speechIndex",
        )
    }

    /**
     * 对一窗 16kHz 单声道 float PCM 做推理。
     *
     * @param samples 长度 >= [INPUT_SAMPLES]；不足时右侧补零。
     * @return 哭声合计概率与语音概率（同一帧上各哭声类分数之和、Speech 分数；
     *         多帧布局取逐帧最大）。解码失败返回 null。
     */
    fun classify(samples: FloatArray): Pair<Float, Float>? {
        val input = padToWindow(samples)
        val inputBuffer = ByteBuffer.allocateDirect(INPUT_SAMPLES * 4)
            .order(ByteOrder.nativeOrder())
        for (v in input) inputBuffer.putFloat(v.coerceIn(-1f, 1f))
        inputBuffer.rewind()

        return try {
            if (frameLayout) {
                // 逐帧布局 [1, frames, 521]：按实际帧数分配输出，避免与张量形状不符。
                val frames = (interpreter.getOutputTensor(0).shape().getOrNull(1) ?: 1).coerceAtLeast(1)
                val out = Array(1) { Array(frames) { FloatArray(NUM_CLASSES) } }
                interpreter.run(inputBuffer, out)
                var peakCry = 0f
                var peakSpeech = 0f
                for (frame in out[0]) {
                    val cry = cryIndices.sumOf { frame[it].toDouble() }.toFloat()
                    if (cry > peakCry) peakCry = cry
                    if (speechIndex >= 0 && frame[speechIndex] > peakSpeech) {
                        peakSpeech = frame[speechIndex]
                    }
                }
                peakCry.coerceIn(0f, 1f) to peakSpeech.coerceIn(0f, 1f)
            } else {
                val out = Array(1) { FloatArray(NUM_CLASSES) }
                interpreter.run(inputBuffer, out)
                val row = out[0]
                val cry = cryIndices.sumOf { row[it].toDouble() }.toFloat()
                val speech = if (speechIndex >= 0) row[speechIndex] else 0f
                cry.coerceIn(0f, 1f) to speech.coerceIn(0f, 1f)
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "classify failed", e)
            null
        }
    }

    fun close() {
        runCatching { interpreter.close() }
    }

    // ── 内部工具 ──────────────────────────────────────────────

    private fun padToWindow(samples: FloatArray): FloatArray {
        if (samples.size >= INPUT_SAMPLES) return samples.copyOf(INPUT_SAMPLES)
        return samples.copyOf(INPUT_SAMPLES)
    }

    private fun loadModel(context: Context, file: File): MappedByteBuffer =
        file.inputStream().channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())

    /**
     * 类别名解析：优先读模型旁的 `yamnet_labels.txt`（下载器一并落盘）；
     * 读不到时退回 TensorFlow Hub yamnet/1 的官方固定顺序（CSV class map），
     * 其中 Speech=0，三个哭声类按 AudioSet 本体顺序硬编码。
     */
    private inner class LabelResolver(private val labels: List<String>) {
        fun indexOf(name: String): Int? {
            val idx = labels.indexOf(name)
            if (idx >= 0) return idx
            return FALLBACK_INDEXES[name]
        }
    }

    private fun loadLabels(modelFile: File): List<String> {
        val labelFile = File(modelFile.parentFile, LABELS_FILE_NAME)
        return runCatching { labelFile.readLines().filter { it.isNotBlank() } }
            .getOrDefault(emptyList())
    }
}
