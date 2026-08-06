package io.prism.embedding

/**
 * 嵌入引擎异常 —— 端侧 ONNX 推理、模型加载、tokenizer 缺失等错误的统一封装。
 *
 * US-014 嵌入引擎的对外错误类型，避免 onnxruntime 内部异常泄漏到上层。
 */
class EmbeddingException(
    val stage: Stage,
    message: String,
    cause: Throwable? = null
) : RuntimeException("[${stage.name}] $message", cause) {
    /** 故障阶段，便于日志归类与上游降级决策。 */
    enum class Stage {
        MODEL_LOAD,
        TOKENIZER_INIT,
        INFERENCE,
        POOLING,
        UNLOAD
    }
}
