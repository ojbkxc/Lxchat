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
}
