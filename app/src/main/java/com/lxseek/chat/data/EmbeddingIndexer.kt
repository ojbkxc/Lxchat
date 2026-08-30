package com.lxseek.chat.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

object EmbeddingIndexer {

    fun floatsToBytes(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.BIG_ENDIAN)
        for (f in floats) buffer.putFloat(f)
        return buffer.array()
    }

    fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) floats[i] = buffer.float
        return floats
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        // 维度不一致时（例如用户切换 embedding 模型后查询历史向量），
        // 直接计算会抛出 ArrayIndexOutOfBoundsException 或返回错误结果。
        // 返回 0 相似度，让调用方的阈值过滤自然丢弃这些不匹配项。
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }

    /** L2 归一化（原地）。归一化后余弦相似度退化为点积，数值更稳且避免反复求范数。 */
    fun normalizeInPlace(v: FloatArray): FloatArray {
        var sq = 0f
        for (x in v) sq += x * x
        val norm = kotlin.math.sqrt(sq)
        if (norm == 0f || norm == 1f) return v
        val inv = 1f / norm
        for (i in v.indices) v[i] *= inv
        return v
    }

    /**
     * 对归一化向量做 INT8 量化（取值约在 [-1,1] → 缩放 128 后取整到 [-128,127]）。
     * 相比存 float，内存省约 4 倍，用于粗检索阶段的快速扫描。
     */
    fun quantizeInt8(normalized: FloatArray): ByteArray {
        val q = ByteArray(normalized.size)
        for (i in normalized.indices) {
            val v = (normalized[i] * 128f).toInt().coerceIn(-128, 127)
            q[i] = v.toByte()
        }
        return q
    }

    /** int8 整数点积（值越大越相似）。无浮点转换，纯字节运算，速度快。 */
    fun int8Dot(a: ByteArray, b: ByteArray): Int {
        var acc = 0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) acc += a[i] * b[i]
        return acc
    }

    /**
     * 轻量内存近似索引（ANN-style）：添加时归一化 + INT8 量化各存一份，
     * 检索时先用 int8 粗扫取 top-K 候选，再用原始 float 精确余弦重排，
     * 兼顾检索速度、内存占用与精度（候选池覆盖足够大，阈值语义不变）。
     *
     * @param V 条目标识（如 messageId）。
     */
    class EmbeddingIndex<V> {
        private val ids = ArrayList<V>()
        private val int8s = ArrayList<ByteArray>()
        private val norms = ArrayList<FloatArray>()

        val size: Int get() = ids.size

        fun add(id: V, vector: FloatArray) {
            normalizeInPlace(vector)
            ids.add(id)
            norms.add(vector)
            int8s.add(quantizeInt8(vector))
        }

        /**
         * 返回按精确余弦降序的 [(id, exactScore)] 结果，长度不超过 candidatePool。
         * candidatePool >= size 时等价于对全量精确打分（候选池覆盖全集，无精度损失）。
         */
        fun searchTopK(query: FloatArray, candidatePool: Int): List<Pair<V, Float>> {
            if (ids.isEmpty()) return emptyList()
            // 查询向量独立归一化（不污染调用方的原始数组）
            val q = FloatArray(query.size)
            System.arraycopy(query, 0, q, 0, query.size)
            normalizeInPlace(q)
            val qq = quantizeInt8(q)
            val k = candidatePool.coerceIn(1, ids.size)

            // 最小堆保存 int8 粗扫的 top-K 候选下标
            val heap = java.util.PriorityQueue<Pair<Int, Int>>(1, compareBy { it.second })
            for (i in ids.indices) {
                val s = int8Dot(qq, int8s[i])
                when {
                    heap.size < k -> heap.add(i to s)
                    s > heap.peek().second -> {
                        heap.poll()
                        heap.add(i to s)
                    }
                }
            }
            // 对候选做精确余弦重排（归一化后即为点积）
            return heap.map { (i, _) -> i to cosineSimilarity(q, norms[i]) }
                .sortedByDescending { it.second }
        }
    }
}
