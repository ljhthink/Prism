package io.prism.embedding

/**
 * 文本 → 模型输入张量的编码器抽象（UXR9 US-901 引入）。
 *
 * BERT WordPiece（[BertWordPieceTokenizer]）与 XLM-R Unigram（[UnigramTokenizer]）
 * 两种端侧 tokenizer 共用同一协议，使 [OnnxEmbedder] 与具体 tokenizer 解耦——
 * 切换多语言嵌入模型时无需改动推理核心。
 *
 * 返回 [BertWordPieceTokenizer.TokenizationResult]（三张量等长），与既有
 * OnnxEmbedder 推理路径完全兼容。
 */
interface TokenEncoder {
    /**
     * 编码文本为模型输入三张量（input_ids / attention_mask / token_type_ids）。
     *
     * @param text 原始文本
     * @param maxLength 最大序列长度（含 BOS/EOS 等特殊 token）。默认值 512 仅在接口声明
     *   （override 不得重复声明，Kotlin 规则），经接口类型调用时生效。
     */
    fun encode(text: String, maxLength: Int = 512): BertWordPieceTokenizer.TokenizationResult
}
