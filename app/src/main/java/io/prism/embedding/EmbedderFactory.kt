package io.prism.embedding

import java.io.InputStream

/**
 * 嵌入引擎工厂 —— 从输入流加载 ONNX 模型与 BERT 词表，构造 [OnnxEmbedder]。
 *
 * **设计动机**：解耦 Android `AssetManager` 与核心引擎，使 [OnnxEmbedder]
 * 可在 JVM 单测中直接构造（注入文件流），Android 运行时由调用方从
 * `context.assets` 拉取流后传入。
 *
 * **Android 调用示例**（UXR9 US-901 生产路径，多语言模型 + Unigram tokenizer）：
 * ```kotlin
 * val embedder = context.assets.open("models/model_multilingual_qint8_arm64.onnx").use { m ->
 *     context.assets.open("models/tokenizer.json").use { t ->
 *         EmbedderFactory.createMultilingual(m, t)
 *     }
 * }
 * ```
 * 旧英文 BERT 模型（model_qint8_arm64.onnx + vocab.txt）仅测试使用，位于
 * `src/test/resources/models/`（Q-LOW-6：不打包进 APK）。
 *
 * US-014 验收标准 1：onnxruntime-android 加载 assets 中 all-MiniLM-L6-v2 ONNX INT8 模型
 */
object EmbedderFactory {

    /** assets 中模型与 tokenizer 的默认路径（UXR9 US-901：多语言嵌入模型）。 */
    const val DEFAULT_MODEL_PATH = "models/model_multilingual_qint8_arm64.onnx"
    const val DEFAULT_TOKENIZER_PATH = "models/tokenizer.json"

    /** 遗留英文模型的词表路径（仅测试/回退用，生产已切换多语言模型）。 */
    const val DEFAULT_VOCAB_PATH = "models/vocab.txt"

    /**
     * 从输入流加载多语言模型与 Unigram tokenizer，构造 [OnnxEmbedder]（UXR9 US-901）。
     *
     * 两个流会在方法内关闭。
     *
     * @param modelInput ONNX 模型输入流（多语言 MiniLM qint8，~113MB，全部读入内存）
     * @param tokenizerJsonInput tokenizer.json 输入流（Unigram 词表，~9MB）
     * @param clock 时间源（测试可注入）
     * @return 已初始化但未加载 session 的 [OnnxEmbedder]（首次 embed 时按需加载）
     */
    fun createMultilingual(
        modelInput: InputStream,
        tokenizerJsonInput: InputStream,
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
        val tokenizer = try {
            tokenizerJsonInput.use { UnigramTokenizer(it) }
        } catch (e: Exception) {
            throw EmbeddingException(
                EmbeddingException.Stage.TOKENIZER_INIT,
                "读取 tokenizer.json 失败: ${e.message}",
                e
            )
        }
        return OnnxEmbedder(modelBytes, tokenizer, clock)
    }

    /**
     * 从输入流加载模型与词表，构造 [OnnxEmbedder]（英文 BERT 路径，遗留/测试用）。
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
