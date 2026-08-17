package io.prism.embedding

import org.junit.Test
import java.io.File

/**
 * UXR9 诊断（TKN-UXR9-ARCHAEOLOGY-001 遗留验证项）：用**真实**多语言 ONNX 模型
 * （paraphrase-multilingual-MiniLM-L12-v2 qint8）实测中文句对余弦相似度分布，
 * 验证考古结论「英文模型对中文语义区分度差、无关片段相似度普遍 0.4~0.7」，
 * 并据此**实测校准** [ConversationViewModel.RAG_SIMILARITY_THRESHOLD] 与
 * [KnowledgeBaseLocalToolExecutor] 的检索阈值。
 *
 * 纯诊断用，输出打印到 stdout（gradle 加 --info 可见），不断言（避免固化可能过时的结论）。
 * 多语言模型 + Unigram tokenizer（tokenizer.json）与生产路径完全一致（EmbedderFactory.createMultilingual）。
 */
class ChineseSimilarityDiagnosticTest {

    private val queryXilian = "昔涟这个角色做了哪些事情"
    private val docXilian = "昔涟是崩坏星穹铁道中的角色，是翁法罗斯的英雄，有记忆相关的设定。"
    private val docFinance = "公司第三季度财务报告显示营收同比增长15%，净利润达12亿元。"
    private val docTech = "Prism 支持 Android 平台，用户可自行配置 OpenAI 兼容的模型端点。"
    private val greeting = "你好"
    private val confirmation = "好的，谢谢"

    @Test
    fun `measure chinese cosine similarity distribution with real multilingual model`() {
        val modelBytes = File(MODEL_PATH).readBytes()
        val tokenizer = UnigramTokenizer(File(TOKENIZER_PATH).inputStream())
        OnnxEmbedder(modelBytes, tokenizer).use { e ->
            val pairs = listOf(
                "相关-查询vs角色文档" to (queryXilian to docXilian),
                "无关-查询vs财务文档" to (queryXilian to docFinance),
                "无关-查询vs技术文档" to (queryXilian to docTech),
                "无关-查询vs寒暄" to (queryXilian to greeting),
                "无关-查询vs确认" to (queryXilian to confirmation),
                "无关-寒暄vs财务" to (greeting to docFinance),
                "相关-技术问题vs技术文档" to ("Prism 支持哪些平台？" to docTech),
                "无关-角色查询vs技术文档" to ("昔涟这个角色做了哪些事情" to docTech)
            )
            println("=== 中文句对余弦相似度（多语言 MiniLM qint8 + Unigram）===")
            pairs.forEach { (label, p) ->
                val a = e.embed(p.first)
                val b = e.embed(p.second)
                val sim = cosine(a, b)
                println("  $label: $sim")
            }
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot // 已 L2 归一化，dot = cosine
    }

    companion object {
        private const val MODEL_PATH = "src/main/assets/models/model_multilingual_qint8_arm64.onnx"
        private const val TOKENIZER_PATH = "src/main/assets/models/tokenizer.json"
    }
}
