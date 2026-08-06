package io.prism.embedding

/**
 * 嵌入引擎接口 —— 将文本编码为固定维度向量。
 *
 * **职责**：文本 → 384 维向量（all-MiniLM-L6-v2，L2 归一化）。
 *
 * **实现分层**（US-014 ADR-007 5.2）：
 * - [OnnxEmbedder]：生产实现，基于 onnxruntime-android，加载 assets 中 INT8 量化模型
 * - 测试用 JVM 版 onnxruntime（桌面原生库），通过同一 [OnnxEmbedder] 代码路径验证
 *
 * **生命周期**：
 * - 模型按需加载（首次 [embed] 时加载到内存）
 * - 闲置超时后自动卸载（[checkAndUnload]），释放 ~23MB 模型内存
 * - 调用 [close] 永久释放资源
 *
 * **线程安全**：实现需保证 [embed] / [embedBatch] 可并发调用（内部加锁）。
 *
 * US-014 验收标准 2：embed(text) 将文本编码为 384 维向量
 * US-014 验收标准 3：模型按需加载，闲置 5 分钟后卸载释放内存
 */
interface Embedder : AutoCloseable {

    /**
     * 将单条文本编码为 384 维向量。
     *
     * @param text 原始文本（任意长度，内部按 maxSeqLen=512 截断）
     * @return 384 维 L2 归一化向量
     * @throws EmbeddingException 推理失败、模型加载失败等
     */
    fun embed(text: String): FloatArray

    /**
     * 批量编码多条文本（默认逐条调用 [embed]，子类可优化为 batch 推理）。
     *
     * @param texts 文本列表
     * @return 等长向量列表，顺序与输入一致
     */
    fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }

    /**
     * 模型是否已加载到内存。
     *
     * 用于上层观察资源状态（UI 提示「正在加载模型」/「模型已释放」）。
     */
    fun isLoaded(): Boolean

    /**
     * 检查闲置超时并按需卸载模型。
     *
     * 由上层定时调度器周期调用（如 Android Handler.postDelayed 或协程）。
     * 若距上次 [embed] 超过 [maxIdleMs]，关闭 ONNX session 释放内存，
     * 下次 [embed] 时自动重新加载。
     *
     * @param maxIdleMs 最大闲置毫秒数（ADR-007 5.2：默认 5 分钟）
     * @return true 表示本次执行了卸载
     */
    fun checkAndUnload(maxIdleMs: Long): Boolean
}
