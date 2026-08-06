package io.prism.embedding

import java.io.InputStream

/**
 * 嵌入引擎工厂 —— 从输入流加载 ONNX 模型与 BERT 词表，构造 [OnnxEmbedder]。
 *
 * **设计动机**：解耦 Android `AssetManager` 与核心引擎，使 [OnnxEmbedder]
 * 可在 JVM 单测中直接构造（注入文件流），Android 运行时由调用方从
 * `context.assets` 拉取流后传入。
 *
 * **Android 调用示例**：
 * ```kotlin
 * val embedder = context.assets.open("models/model_qint8_arm64.onnx").use { m ->
 *     context.assets.open("models/vocab.txt").use { v ->
 *         EmbedderFactory.create(m, v)
 *     }
 * }
 * ```
 *
 * US-014 验收标准 1：onnxruntime-android 加载 assets 中 all-MiniLM-L6-v2 ONNX INT8 模型
 */
object EmbedderFactory {

    /** assets 中模型与词表的默认路径。 */
    const val DEFAULT_MODEL_PATH = "models/model_qint8_arm64.onnx"
    const val DEFAULT_VOCAB_PATH = "models/vocab.txt"

    /**
     * 从输入流加载模型与词表，构造 [OnnxEmbedder]。
     *
     * 两个流会在方法内关闭。
     *
     * @param modelInput ONNX 模型输入流（~23MB，全部读入内存）
     * @param vocabInput vocab.txt 输入流（~226KB）
     * @param clock 时间源（测试可注入）
     * @return 已初始化但未加载 session 的 [OnnxEmbedder]（首次 embed 时按需加载）
     */
    fun create(
        modelInput: InputStream,
        vocabInput: InputStream,
        clock: OnnxEmbedder.Clock = OnnxEmbedder.Clock { System.currentTimeMillis() }
    ): OnnxEmbedder {
        val modelBytes = try {
            modelInput.use { it.readBytes() }
        } catch (e: Exception) {
            throw EmbeddingException(
                EmbeddingException.Stage.MODEL_LOAD,
                "读取模型流失败: ${e.message}",
                e
            )
        }
        val vocab = try {
            vocabInput.use { BertWordPieceTokenizer.loadVocab(it) }
        } catch (e: Exception) {
            throw EmbeddingException(
                EmbeddingException.Stage.TOKENIZER_INIT,
                "读取词表流失败: ${e.message}",
                e
            )
        }
        require(vocab.isNotEmpty()) { "词表为空" }
        return OnnxEmbedder(modelBytes, vocab, clock)
    }
}
