package com.lxseek.chat.api.compression

/**
 * Headroom 风格的内容感知压缩入口（输入侧省 token）。
 *
 * 移植自 headroom（https://github.com/chopratejas/headroom）的 ContentRouter 思想：
 * 发送给 LLM 之前先检测内容类型（JSON / 日志 / 搜索结果 / 纯文本），
 * 再路由到对应的确定性压缩器。设计约束与上游一致：
 *
 * - **纯函数、确定性**：同一输入永远得到同一输出，无随机性；
 * - **失败降级**：任何解析异常都返回原文，绝不抛错中断请求；
 * - **只在结果变小时采用**：压缩后反而更大就原样返回（上游 min_savings 约束）；
 * - **只压"机器内容"**：代码块、用户正文、错误原文永不改写（Auto-Clarity 对应面）。
 */
object ContentRouter {

    /** 参与压缩的最小文本长度；更短的内容压缩收益抵不上 marker 开销。 */
    const val MIN_COMPRESS_LENGTH = 2048

    /**
     * 压缩一段工具输出文本。
     *
     * @param text 原始文本。
     * @return 压缩后的文本；无法压缩或压缩无收益时返回原文。
     */
    fun compress(text: String): String {
        val length = text.length
        if (length < MIN_COMPRESS_LENGTH) return text
        return try {
            val candidate = when {
                JsonCompressor.looksLikeJson(text) -> JsonCompressor.compress(text)
                LogCompressor.looksLikeLog(text) -> LogCompressor.compress(text)
                else -> null
            } ?: return text
            // 只在严格变小时采用（含 marker 开销后仍然更小）。
            if (candidate.length < length) candidate else text
        } catch (_: Exception) {
            text
        }
    }
}
