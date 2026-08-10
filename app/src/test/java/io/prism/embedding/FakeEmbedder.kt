package io.prism.embedding

/**
 * 测试用 FakeEmbedder —— 基于文本哈希生成确定性 384 维向量。
 *
 * **设计**：
 * - 同一文本始终生成同一向量（确定性，便于断言）
 * - 不同文本生成不同向量（语义区分）
 * - 向量分量基于字符的 ASCII/Unicode 值分布到 384 维
 * - 不做 L2 归一化（测试不需要真实语义，只需确定性区分）
 *
 * **用途**：
 * - CrossSessionMemoryManager 单元测试（US-033）
 * - 任何需要 Embedder 但不需要真实 ONNX 推理的测试场景
 *
 * @param throwOnCall 是否在调用时抛异常（测试容错路径）
 */
class FakeEmbedder(
    private val throwOnCall: Boolean = false
) : Embedder {

    var callCount: Int = 0
        private set

    override fun embed(text: String): FloatArray {
        callCount++
        if (throwOnCall) throw EmbeddingException(
            EmbeddingException.Stage.INFERENCE,
            "Fake embedder failure"
        )

        val vector = FloatArray(384)
        if (text.isEmpty()) return vector

        // 基于文本内容生成确定性向量：
        // 每个字符的 Unicode 值分散到 384 维的不同位置
        text.forEachIndexed { charIndex, char ->
            val dimIndex = (charIndex + char.code) % 384
            vector[dimIndex] = vector[dimIndex] + char.code.toFloat() / 1000f
        }
        return vector
    }

    override fun isLoaded(): Boolean = !throwOnCall

    override fun checkAndUnload(maxIdleMs: Long): Boolean = false

    override fun close() {}
}
