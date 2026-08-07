package io.prism.data

import io.objectbox.BoxStore
import io.prism.document.Chunker
import io.prism.document.DocumentParserRegistry
import io.prism.embedding.Embedder
import io.prism.embedding.EmbeddingException
import io.prism.ingestion.IngestionEvent
import io.prism.ingestion.IngestionPipeline
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * US-017 向量检索与 US-016 摄入管线集成测试（ac-verifier 补充）。
 *
 * 验证 IngestionPipeline 摄入 → KnowledgeBaseRepository.search 检索 的端到端协同：
 * 1. 摄入文档后能检索到结果，title 解析正确（documentTitle/chunkIndex）
 * 2. 指定库摄入 → 指定库检索
 * 3. 默认库摄入 → 默认库检索
 * 4. 嵌入失败的 chunk（embedding=null）不参与检索
 * 5. 摄入多 chunk → 全库检索跨库返回
 *
 * 测试策略：
 * - 真实 DocumentParserRegistry + 真实 Chunker + 真实 ObjectBox
 * - [IntegrationFakeEmbedder] 替身：基于 text.hashCode() 返回 oneHot 向量，
 *   相同文本返回相同向量，便于用 `embed(text)` 构造匹配查询向量
 *
 * 关联 ADR：ADR-009（摄入管线）+ ADR-010（检索）
 * 关联测试：[IngestionPipelineTest]（US-016 摄入测试）+ [KnowledgeBaseRetrievalTest]（US-017 检索测试）
 */
class KnowledgeBaseRetrievalIntegrationTest {

    private lateinit var boxStore: BoxStore
    private lateinit var repository: KnowledgeBaseRepository
    private lateinit var tempDir: File
    private lateinit var embedder: IntegrationFakeEmbedder
    private lateinit var pipeline: IngestionPipeline

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "objectbox-retrieval-integ-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        repository = KnowledgeBaseRepository(boxStore)
        embedder = IntegrationFakeEmbedder()
        pipeline = IngestionPipeline(
            parserRegistry = DocumentParserRegistry(),
            chunker = Chunker(chunkSize = 50, overlap = 0),
            embedder = embedder,
            repository = repository
        )
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    // ==================== 集成 1：摄入 → 检索 → title 解析 ====================

    /**
     * 摄入文档后检索，验证 title 解析为 documentTitle 与 chunkIndex。
     *
     * IngestionPipeline 生成 title 格式 `${documentTitle}#${index+1}`，
     * search 应通过 parseTitle 正确解析。
     */
    @Test
    fun ingest_then_search_returns_chunks_with_correct_source() = runBlocking {
        val text = "第一段独立内容。\n\n第二段独立内容。\n\n第三段独立内容。"
        val input = ByteArrayInputStream(text.toByteArray())

        val events = pipeline.ingest("测试文档.txt", input, knowledgeBaseId = 0L).toList()
        val completed = events.last() as IngestionEvent.Completed
        assertEquals("应摄入 3 chunk", 3, completed.result.totalChunks)
        assertEquals("embeddedChunks 应为 3", 3, completed.result.embeddedChunks)

        // 用第一个 chunk 的文本生成查询向量（与摄入时 embedding 相同）
        val queryVector = embedder.embed("第一段独立内容。")
        val results = repository.search(queryVector, k = 3, knowledgeBaseId = 0L)

        assertTrue("应检索到结果", results.isNotEmpty())
        // 验证 title 解析
        assertEquals("documentTitle 应为文件名去扩展名", "测试文档", results[0].documentTitle)
        assertNotNull("chunkIndex 应非 null", results[0].chunkIndex)
        assertEquals("chunkIndex 应为 1", 1, results[0].chunkIndex)
    }

    // ==================== 集成 2：指定库摄入 → 指定库检索 ====================

    /**
     * 摄入到自建库 → 检索指定自建库 → 仅返回该库结果。
     */
    @Test
    fun ingest_to_self_built_kb_then_search_specified_kb() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作知识库"))
        val text = "工作文档段落一。\n\n工作文档段落二。"
        val input = ByteArrayInputStream(text.toByteArray())

        pipeline.ingest("工作.txt", input, knowledgeBaseId = kbId).toList()

        // 默认库无 chunk，检索默认库应返回空
        val defaultResults = repository.search(embedder.embed("工作文档段落一。"), k = 5, knowledgeBaseId = 0L)
        assertTrue("默认库应无结果", defaultResults.isEmpty())

        // 检索自建库应返回结果
        val results = repository.search(embedder.embed("工作文档段落一。"), k = 5, knowledgeBaseId = kbId)
        assertEquals("自建库应返回 2 条", 2, results.size)
        results.forEach { r ->
            assertEquals("结果应全部来自自建库", kbId, r.knowledgeBaseId)
            assertEquals("documentTitle 应为 '工作'", "工作", r.documentTitle)
        }
    }

    // ==================== 集成 3：默认库摄入 → 默认库检索 ====================

    /**
     * 摄入到默认库（kbId=0L）→ 检索默认库 → 返回结果。
     */
    @Test
    fun ingest_to_default_kb_then_search_default_kb() = runBlocking {
        val text = "默认库文档段落一。\n\n默认库文档段落二。"
        val input = ByteArrayInputStream(text.toByteArray())

        pipeline.ingest("默认文档.txt", input, knowledgeBaseId = 0L).toList()

        val results = repository.search(embedder.embed("默认库文档段落一。"), k = 5, knowledgeBaseId = 0L)
        assertEquals("默认库应返回 2 条", 2, results.size)
        results.forEach { r ->
            assertEquals("结果应来自默认库", 0L, r.knowledgeBaseId)
            assertEquals("documentTitle 应为 '默认文档'", "默认文档", r.documentTitle)
        }
    }

    // ==================== 集成 4：嵌入失败 chunk 不参与检索 ====================

    /**
     * 摄入时部分 chunk 嵌入失败（embedding=null）→ 检索不返回该 chunk。
     *
     * 用 [IntegrationFakeEmbedder] 的 failOnText 注入失败。
     */
    @Test
    fun ingest_with_embedding_failure_excluded_from_search() = runBlocking {
        val failEmbedder = IntegrationFakeEmbedder(failOnText = "第二段独立内容。")
        val failPipeline = IngestionPipeline(
            parserRegistry = DocumentParserRegistry(),
            chunker = Chunker(chunkSize = 50, overlap = 0),
            embedder = failEmbedder,
            repository = repository
        )

        val text = "第一段独立内容。\n\n第二段独立内容。\n\n第三段独立内容。"
        val input = ByteArrayInputStream(text.toByteArray())

        val events = failPipeline.ingest("测试.txt", input, knowledgeBaseId = 0L).toList()
        val completed = events.last() as IngestionEvent.Completed
        assertEquals("totalChunks 应为 3", 3, completed.result.totalChunks)
        assertEquals("embeddedChunks 应为 2（第2段失败）", 2, completed.result.embeddedChunks)
        assertEquals("skippedChunks 应为 1", 1, completed.result.skippedChunks)

        // 验证 chunkCount 为 3（全部入库，含 embedding=null 的）
        assertEquals("chunkCount 应为 3（含 embedding=null）", 3L, repository.chunkCount(0L))

        // 检索应只返回 2 条有效 embedding 的 chunk
        val results = repository.search(failEmbedder.embed("第一段独立内容。"), k = 5, knowledgeBaseId = 0L)
        assertEquals("检索应只返回 2 条有效 embedding chunk", 2, results.size)
    }

    // ==================== 集成 5：多库摄入 → 全库检索 ====================

    /**
     * 摄入到多个库 → 全库检索（kbId=null）→ 跨库返回。
     */
    @Test
    fun ingest_multiple_kbs_then_search_all_kb() = runBlocking {
        val kbId1 = repository.save(KnowledgeBase(name = "库A"))
        val kbId2 = repository.save(KnowledgeBase(name = "库B"))

        // 摄入到库A
        val inputA = ByteArrayInputStream("库A内容段落一。\n\n库A内容段落二。".toByteArray())
        pipeline.ingest("文档A.txt", inputA, knowledgeBaseId = kbId1).toList()

        // 摄入到库B
        val inputB = ByteArrayInputStream("库B内容段落一。".toByteArray())
        pipeline.ingest("文档B.txt", inputB, knowledgeBaseId = kbId2).toList()

        // 全库检索应跨库返回
        val queryVector = embedder.embed("库A内容段落一。")
        val results = repository.search(queryVector, k = 10, knowledgeBaseId = null)

        assertTrue("全库检索应返回 >= 3 条", results.size >= 3)
        val kbIds = results.map { it.knowledgeBaseId }.toSet()
        assertTrue("结果应包含多个库", kbIds.size > 1)
    }

    // ==================== 集成 6：摄入后检索 → RetrievalResult 完整字段 ====================

    /**
     * 摄入后检索，验证 RetrievalResult 含全部 7 字段。
     */
    @Test
    fun ingest_then_search_result_contains_all_fields() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "验证库"))
        val input = ByteArrayInputStream("验证内容段落。".toByteArray())
        pipeline.ingest("验证.txt", input, knowledgeBaseId = kbId).toList()

        val results = repository.search(embedder.embed("验证内容段落。"), k = 1, knowledgeBaseId = kbId)
        assertEquals(1, results.size)

        val r = results[0]
        assertTrue("chunkId 应 > 0", r.chunkId > 0)
        assertEquals("content 应匹配", "验证内容段落。", r.content)
        assertEquals("title 应为 '验证#1'", "验证#1", r.title)
        assertTrue("similarity 应 ∈ [-1,1]", r.similarity in -1.0..1.0)
        assertEquals("documentTitle 应为 '验证'", "验证", r.documentTitle)
        assertEquals("chunkIndex 应为 1", 1, r.chunkIndex)
        assertEquals("knowledgeBaseId 应为 kbId", kbId, r.knowledgeBaseId)
    }

    // ==================== FakeEmbedder 定义 ====================

    /**
     * 集成测试用 FakeEmbedder：基于 text.hashCode() 返回 384 维 oneHot 向量。
     *
     * 相同文本返回相同向量，便于用 `embed(text)` 构造匹配查询向量。
     * 支持 failOnText 注入嵌入失败（测试 embedding=null 降级）。
     */
    private class IntegrationFakeEmbedder(
        private val failOnText: String? = null
    ) : Embedder {
        override fun embed(text: String): FloatArray {
            if (text == failOnText) {
                throw EmbeddingException(EmbeddingException.Stage.INFERENCE, "测试注入失败")
            }
            val vector = FloatArray(384)
            vector[Math.floorMod(text.hashCode(), 384)] = 1.0f
            return vector
        }

        override fun isLoaded(): Boolean = true

        override fun checkAndUnload(maxIdleMs: Long): Boolean = false

        override fun close() {}
    }
}
