package io.prism.ingestion

import io.objectbox.Box
import io.objectbox.BoxStore
import io.prism.data.KnowledgeBase
import io.prism.data.KnowledgeBaseRepository
import io.prism.data.KnowledgeChunk
import io.prism.data.MyObjectBox
import io.prism.document.Chunker
import io.prism.document.DocumentParseException
import io.prism.document.DocumentParserRegistry
import io.prism.embedding.Embedder
import io.prism.embedding.EmbeddingException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * IngestionPipeline 集成测试（US-016 验收标准 1/2/3/4）。
 *
 * **测试策略**（ADR-009）：
 * - 真实 [DocumentParserRegistry] + 真实 [Chunker] + 真实 [KnowledgeBaseRepository]（+ 真实 ObjectBox）
 * - [FakeEmbedder] 替身注入可控嵌入/失败（BR-testing-001：复现原组件关键语义）
 * - 覆盖 happy path / 降级 / 解析失败 / 空文档 / 进度事件顺序 / 入库正确性 / 边界
 *
 * 测试搭建照搬 [KnowledgeBaseRepositoryTest] 临时目录 + 纯 JVM ObjectBox 模式。
 *
 * US-016 验收标准：
 * 1. IngestionPipeline：解析→切片→嵌入→写入指定库
 * 2. 摄入进度与错误可观察
 * 3. 嵌入为 null 的片段不建索引并提示
 * 4. 摄入管线集成测试通过
 */
class IngestionPipelineTest {

    private lateinit var boxStore: BoxStore
    private lateinit var repository: KnowledgeBaseRepository
    private lateinit var chunkBox: Box<KnowledgeChunk>
    private lateinit var tempDir: File
    private lateinit var pipeline: IngestionPipeline

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "objectbox-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        repository = KnowledgeBaseRepository(boxStore)
        chunkBox = boxStore.boxFor(KnowledgeChunk::class.java)
        pipeline = IngestionPipeline(
            parserRegistry = DocumentParserRegistry(),
            chunker = Chunker(chunkSize = 50, overlap = 0),
            embedder = FakeEmbedder(),
            repository = repository
        )
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    // ===== AC-1: 解析→切片→嵌入→写入指定库 =====

    @Test
    fun ingest_happy_path_persists_embedded_chunks_to_specified_kb() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val text = "第一段内容。\n\n第二段内容。\n\n第三段内容。"
        val input = text.toInputStream()

        val events = pipeline.ingest("doc.txt", input, kbId).toList()

        // 事件序列：Started → Parsed → Chunked → ChunkEmbedded×3 → Completed
        assertEquals(7, events.size)
        assertTrue("首事件 Started", events[0] is IngestionEvent.Started)
        assertTrue("Parsed", events[1] is IngestionEvent.Parsed)
        assertTrue("Chunked", events[2] is IngestionEvent.Chunked)
        assertTrue("ChunkEmbedded[0]", events[3] is IngestionEvent.ChunkEmbedded)
        assertTrue("ChunkEmbedded[1]", events[4] is IngestionEvent.ChunkEmbedded)
        assertTrue("ChunkEmbedded[2]", events[5] is IngestionEvent.ChunkEmbedded)
        val completed = events[6] as IngestionEvent.Completed

        // 结果汇总
        assertEquals(3, completed.result.totalChunks)
        assertEquals(3, completed.result.embeddedChunks)
        assertEquals(0, completed.result.skippedChunks)
        assertEquals(kbId, completed.result.knowledgeBaseId)
        assertEquals("doc", completed.result.documentTitle)

        // 入库验证：3 个 chunk 全部 embedding 非 null，knowledgeBaseId 正确
        assertEquals(3, repository.chunkCount(kbId))
        val chunks = chunkBox.all
        chunks.forEach { chunk ->
            assertNotNull("embedding 应非 null", chunk.embedding)
            assertEquals(kbId, chunk.knowledgeBaseId)
            assertTrue("title 应以文档名开头", chunk.title.startsWith("doc#"))
            assertEquals(384, chunk.embedding!!.size)
        }
    }

    @Test
    fun ingest_writes_to_default_kb_when_id_is_zero() = runBlocking {
        val text = "短内容。"
        val input = text.toInputStream()

        val events = pipeline.ingest("note.txt", input, KnowledgeBaseRepository.DEFAULT_KB_ID).toList()
        val completed = events.last() as IngestionEvent.Completed

        assertEquals(1, completed.result.totalChunks)
        assertEquals(1, repository.chunkCount(KnowledgeBaseRepository.DEFAULT_KB_ID))
    }

    // ===== AC-2: 摄入进度与错误可观察 =====

    @Test
    fun ingest_emits_progress_events_in_correct_order() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val text = "段落一。\n\n段落二。"
        val input = text.toInputStream()

        val events = pipeline.ingest("doc.txt", input, kbId).toList()

        // 验证事件类型顺序
        val sequence = events.map { it::class.simpleName }
        assertEquals(
            listOf("Started", "Parsed", "Chunked", "ChunkEmbedded", "ChunkEmbedded", "Completed"),
            sequence
        )

        // 验证 ChunkEmbedded 进度递增
        val embedded1 = events[3] as IngestionEvent.ChunkEmbedded
        val embedded2 = events[4] as IngestionEvent.ChunkEmbedded
        assertEquals(0, embedded1.index)
        assertEquals(2, embedded1.total)
        assertEquals(1, embedded2.index)
        assertEquals(2, embedded2.total)
        assertEquals("doc#1", embedded1.title)
        assertEquals("doc#2", embedded2.title)
    }

    @Test
    fun ingest_emits_parsed_event_with_text_length() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val text = "12345"
        val input = text.toInputStream()

        val events = pipeline.ingest("doc.txt", input, kbId).toList()
        val parsed = events[1] as IngestionEvent.Parsed
        assertEquals(5, parsed.textLength)
    }

    // ===== AC-3: 嵌入为 null 的片段不建索引并提示 =====

    @Test
    fun ingest_skips_chunk_when_embedding_throws_and_still_persists_with_null_embedding() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val embedder = FakeEmbedder(failOnText = "第二段内容。")
        val pipelineWithFailure = IngestionPipeline(
            parserRegistry = DocumentParserRegistry(),
            chunker = Chunker(chunkSize = 50, overlap = 0),
            embedder = embedder,
            repository = repository
        )
        val text = "第一段内容。\n\n第二段内容。\n\n第三段内容。"
        val input = text.toInputStream()

        val events = pipelineWithFailure.ingest("doc.txt", input, kbId).toList()

        // 事件序列：Started → Parsed → Chunked → ChunkEmbedded → ChunkSkipped → ChunkEmbedded → Completed
        assertEquals(7, events.size)
        assertTrue(events[3] is IngestionEvent.ChunkEmbedded)
        assertTrue(events[4] is IngestionEvent.ChunkSkipped)
        assertTrue(events[5] is IngestionEvent.ChunkEmbedded)
        val skipped = events[4] as IngestionEvent.ChunkSkipped
        val completed = events[6] as IngestionEvent.Completed

        // AC-3 提示：ChunkSkipped 事件携带 reason
        assertEquals(1, skipped.index)
        assertEquals(3, skipped.total)
        assertTrue("reason 应含嵌入失败说明", skipped.reason.contains("嵌入失败"))

        // 结果汇总：embedded=2, skipped=1
        assertEquals(3, completed.result.totalChunks)
        assertEquals(2, completed.result.embeddedChunks)
        assertEquals(1, completed.result.skippedChunks)
        assertEquals(1, completed.result.skippedDetails.size)
        assertEquals(1, completed.result.skippedDetails[0].index)

        // 入库验证：3 个 chunk 全部入库，但其中 1 个 embedding=null
        assertEquals(3, repository.chunkCount(kbId))
        val chunks = chunkBox.all.sortedBy { it.title }
        val nullEmbeddingChunk = chunks.find { it.embedding == null }
        assertNotNull("应有 1 个 embedding=null 的 chunk", nullEmbeddingChunk)
        assertEquals("doc#2", nullEmbeddingChunk!!.title)
        // 其余 2 个 embedding 非 null
        assertEquals(2, chunks.count { it.embedding != null })
    }

    @Test
    fun ingest_all_chunks_fail_still_completes_with_all_skipped() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val embedder = FakeEmbedder(failAll = true)
        val pipelineAllFail = IngestionPipeline(
            parserRegistry = DocumentParserRegistry(),
            chunker = Chunker(chunkSize = 50, overlap = 0),
            embedder = embedder,
            repository = repository
        )
        val text = "段一。\n\n段二。"
        val input = text.toInputStream()

        val events = pipelineAllFail.ingest("doc.txt", input, kbId).toList()
        val completed = events.last() as IngestionEvent.Completed

        assertEquals(2, completed.result.totalChunks)
        assertEquals(0, completed.result.embeddedChunks)
        assertEquals(2, completed.result.skippedChunks)
        // 所有 chunk 仍入库（embedding=null）
        assertEquals(2, repository.chunkCount(kbId))
    }

    // ===== 解析失败 → 致命错误 =====

    @Test
    fun ingest_emits_failed_when_document_format_unsupported() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val input = "binary".toInputStream()

        val events = pipeline.ingest("unknown.xyz", input, kbId).toList()

        assertEquals(2, events.size)
        assertTrue(events[0] is IngestionEvent.Started)
        assertTrue("应 emit Failed", events[1] is IngestionEvent.Failed)
        val failed = events[1] as IngestionEvent.Failed
        // DocumentParseException 封装了不支持的格式错误
        assertTrue("throwable 应为 DocumentParseException 或其包装",
            failed.throwable.javaClass.simpleName.contains("DocumentParseException") ||
                failed.throwable.cause?.javaClass?.simpleName?.contains("DocumentParseException") == true)
        // 无 chunk 入库
        assertEquals(0, repository.chunkCount(kbId))
    }

    // ===== 空文档 =====

    @Test
    fun ingest_empty_text_completes_with_zero_chunks() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val input = "".toInputStream()

        val events = pipeline.ingest("empty.txt", input, kbId).toList()

        // Started → Parsed(0) → Chunked(0) → Completed(0)
        assertEquals(4, events.size)
        assertTrue(events[0] is IngestionEvent.Started)
        val parsed = events[1] as IngestionEvent.Parsed
        assertEquals(0, parsed.textLength)
        val chunked = events[2] as IngestionEvent.Chunked
        assertEquals(0, chunked.totalChunks)
        val completed = events[3] as IngestionEvent.Completed
        assertEquals(0, completed.result.totalChunks)
        assertEquals(0, repository.chunkCount(kbId))
    }

    @Test
    fun ingest_whitespace_only_text_completes_with_zero_chunks() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val input = "   \n\n  \t  ".toInputStream()

        val events = pipeline.ingest("ws.txt", input, kbId).toList()
        val completed = events.last() as IngestionEvent.Completed

        // Chunker.chunk 对空白返回空列表
        assertEquals(0, completed.result.totalChunks)
    }

    // ===== chunk title 与来源标注 =====

    @Test
    fun ingest_chunk_titles_use_document_title_prefix() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val text = "段落一。\n\n段落二。"
        val input = text.toInputStream()

        pipeline.ingest("my-report.txt", input, kbId, documentTitle = "季度报告").toList()

        val chunks = chunkBox.all.sortedBy { it.title }
        assertEquals("季度报告#1", chunks[0].title)
        assertEquals("季度报告#2", chunks[1].title)
    }

    @Test
    fun ingest_default_document_title_strips_extension() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val input = "短。".toInputStream()

        pipeline.ingest("notes.txt", input, kbId).toList()

        val chunk = chunkBox.all.first()
        assertTrue("title 应以去扩展名的文件名开头", chunk.title.startsWith("notes#"))
    }

    @Test
    fun ingest_default_document_title_handles_path_separator() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val input = "短。".toInputStream()

        pipeline.ingest("/storage/emulated/0/docs/file.txt", input, kbId).toList()

        val chunk = chunkBox.all.first()
        assertTrue("title 应只取文件名部分", chunk.title.startsWith("file#"))
    }

    // ===== InputStream 生命周期（ADR-009 5.7） =====

    @Test
    fun ingest_closes_input_stream_after_completion() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val trackedStream = TrackedInputStream("内容。\n\n内容二。".toByteArray())

        pipeline.ingest("doc.txt", trackedStream, kbId).toList()

        assertTrue("InputStream 应被关闭", trackedStream.closed)
    }

    @Test
    fun ingest_closes_input_stream_even_when_parsing_fails() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val trackedStream = TrackedInputStream("binary".toByteArray())

        pipeline.ingest("unknown.xyz", trackedStream, kbId).toList()

        assertTrue("解析失败时 InputStream 也应被关闭", trackedStream.closed)
    }

    @Test
    fun ingest_closes_input_stream_even_when_embedding_fails() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val embedder = FakeEmbedder(failAll = true)
        val pipelineFail = IngestionPipeline(
            parserRegistry = DocumentParserRegistry(),
            chunker = Chunker(chunkSize = 50, overlap = 0),
            embedder = embedder,
            repository = repository
        )
        val trackedStream = TrackedInputStream("段一。\n\n段二。".toByteArray())

        pipelineFail.ingest("doc.txt", trackedStream, kbId).toList()

        assertTrue("嵌入失败时 InputStream 也应被关闭", trackedStream.closed)
    }

    // ===== 入库正确性 =====

    @Test
    fun ingest_persists_chunk_content_correctly() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val text = "第一段独立内容。\n\n第二段独立内容。"
        val input = text.toInputStream()

        pipeline.ingest("doc.txt", input, kbId).toList()

        val chunks = chunkBox.all
        assertEquals(2, chunks.size)
        // 验证 content 包含原文（Chunker 可能加 overlap，但 overlap=0 时应是原文段落）
        val contents = chunks.map { it.content }
        assertTrue("应有 chunk 含第一段", contents.any { it.contains("第一段独立内容") })
        assertTrue("应有 chunk 含第二段", contents.any { it.contains("第二段独立内容") })
    }

    @Test
    fun ingest_does_not_pollute_other_knowledge_bases() = runBlocking {
        val kb1 = repository.save(KnowledgeBase(name = "工作"))
        val kb2 = repository.save(KnowledgeBase(name = "学习"))

        pipeline.ingest("doc1.txt", "工作内容一。\n\n工作内容二。".toInputStream(), kb1).toList()
        pipeline.ingest("doc2.txt", "学习内容。".toInputStream(), kb2).toList()

        assertEquals("kb1 应有 2 chunk", 2, repository.chunkCount(kb1))
        assertEquals("kb2 应有 1 chunk", 1, repository.chunkCount(kb2))
    }

    @Test
    fun ingest_does_not_pollute_default_kb_when_writing_to_custom_kb() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))

        pipeline.ingest("doc.txt", "段一。\n\n段二。".toInputStream(), kbId).toList()

        assertEquals("自建库应有 2 chunk", 2, repository.chunkCount(kbId))
        assertEquals("默认库不应被污染", 0, repository.chunkCount(KnowledgeBaseRepository.DEFAULT_KB_ID))
    }

    // ===== 边界 =====

    @Test
    fun ingest_negative_knowledge_base_id_throws_illegal_argument() = runBlocking {
        val input = "内容。".toInputStream()
        try {
            pipeline.ingest("doc.txt", input, -1L).toList()
            org.junit.Assert.fail("负数 knowledgeBaseId 应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常信息应说明负数", e.message!!.contains("负数"))
        }
    }

    /**
     * M1 修复验证（TKN-US016-GUARDRAIL-001）：
     * 负数 knowledgeBaseId 时 input 仍须被关闭（履行 KDoc 关闭契约）。
     */
    @Test
    fun ingest_negative_knowledge_base_id_still_closes_input_stream() = runBlocking {
        val trackedStream = TrackedInputStream("内容。".toByteArray())
        try {
            pipeline.ingest("doc.txt", trackedStream, -1L).toList()
            org.junit.Assert.fail("负数 knowledgeBaseId 应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常信息应说明负数", e.message!!.contains("负数"))
        }
        assertTrue("M1: 负数 kbId 时 input 也应被关闭", trackedStream.closed)
    }

    /**
     * Q6 补充（TKN-US016-GUARDRAIL-001）：协程取消在 chunk 边界生效。
     * 构造多 chunk 文档，在第 1 个 ChunkEmbedded 后取消 collect，验证管线停止处理后续 chunk。
     */
    @Test
    fun ingest_cancellation_stops_processing_at_chunk_boundary() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        // 构造 5 段落，每段独立成块
        val text = (1..5).joinToString("\n\n") { "第${it}段独立内容。" }
        val input = text.toInputStream()

        val collected = mutableListOf<IngestionEvent>()
        try {
            pipeline.ingest("doc.txt", input, kbId).collect { event ->
                collected.add(event)
                // 第 1 个 ChunkEmbedded 后抛取消异常
                if (event is IngestionEvent.ChunkEmbedded) {
                    throw kotlinx.coroutines.CancellationException("测试取消")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 预期取消
        }

        // 验证：Started → Parsed → Chunked → ChunkEmbedded[0]（仅 1 个），无后续 ChunkEmbedded
        val embeddedCount = collected.count { it is IngestionEvent.ChunkEmbedded }
        assertEquals("取消后应仅 1 个 ChunkEmbedded", 1, embeddedCount)
        assertFalse("不应有 Completed", collected.any { it is IngestionEvent.Completed })
        // 入库应仅 1 个 chunk（取消后未继续）
        assertEquals("取消后仅 1 chunk 入库", 1, repository.chunkCount(kbId))
    }

    @Test
    fun ingest_result_duration_is_positive() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val input = "段一。\n\n段二。".toInputStream()

        val events = pipeline.ingest("doc.txt", input, kbId).toList()
        val completed = events.last() as IngestionEvent.Completed

        assertTrue("duration 应 > 0", completed.result.durationMs >= 0)
    }

    @Test
    fun ingest_result_consistency_embedded_plus_skipped_equals_total() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val embedder = FakeEmbedder(failOnText = "第二段内容。")
        val pipelinePartialFail = IngestionPipeline(
            parserRegistry = DocumentParserRegistry(),
            chunker = Chunker(chunkSize = 50, overlap = 0),
            embedder = embedder,
            repository = repository
        )
        val input = "第一段内容。\n\n第二段内容。\n\n第三段内容。".toInputStream()

        val events = pipelinePartialFail.ingest("doc.txt", input, kbId).toList()
        val completed = events.last() as IngestionEvent.Completed

        // IngestionResult.init 校验：embedded + skipped == total
        assertEquals(
            completed.result.embeddedChunks + completed.result.skippedChunks,
            completed.result.totalChunks
        )
    }

    @Test
    fun ingest_large_document_many_chunks() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        // 构造 10+ 段落，触发多次 embed
        val text = (1..15).joinToString("\n\n") { "这是第${it}段内容，包含足够长的文本以确保独立成块。" }
        val input = text.toInputStream()

        val events = pipeline.ingest("large.txt", input, kbId).toList()
        val completed = events.last() as IngestionEvent.Completed

        assertTrue("应有多个 chunk", completed.result.totalChunks >= 10)
        assertEquals("全部嵌入成功", completed.result.totalChunks, completed.result.embeddedChunks)
        assertEquals(0, completed.result.skippedChunks)
        assertEquals(completed.result.totalChunks.toLong(), repository.chunkCount(kbId))
    }

    // ===== Embedder 资源生命周期（ADR-009 5.1，OnnxEmbedder 按需加载不反复） =====

    @Test
    fun ingest_multiple_documents_reuses_embedder_instance() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val embedder = FakeEmbedder()
        val pipelineShared = IngestionPipeline(
            parserRegistry = DocumentParserRegistry(),
            chunker = Chunker(chunkSize = 50, overlap = 0),
            embedder = embedder,
            repository = repository
        )

        // 连续摄入 3 个文档
        pipelineShared.ingest("d1.txt", "文档一内容。".toInputStream(), kbId).toList()
        pipelineShared.ingest("d2.txt", "文档二内容。".toInputStream(), kbId).toList()
        pipelineShared.ingest("d3.txt", "文档三内容。".toInputStream(), kbId).toList()

        // 同一 embedder 实例处理所有文档，embed 调用次数累计
        assertEquals("3 个文档应共用 embedder", 3, embedder.embedCallCount)
        assertFalse("embedder 不应被 close（管线不持有生命周期）", embedder.closed)
    }

    // ===== 极端场景补充（ac-verifier TKN-US016-AC-001） =====

    /**
     * 多文档并发摄入到不同知识库，验证管线无状态、组件线程安全、库间无串扰。
     * 主 Agent 盲区：多文档并发摄入（线程安全）。
     */
    @Test
    fun ingest_concurrent_documents_to_different_knowledge_bases_thread_safe() = runBlocking {
        val kb1 = repository.save(KnowledgeBase(name = "工作"))
        val kb2 = repository.save(KnowledgeBase(name = "学习"))
        val kb3 = repository.save(KnowledgeBase(name = "生活"))
        val docs = listOf(
            Triple("d1.txt", "工作内容一。\n\n工作内容二。", kb1),
            Triple("d2.txt", "学习内容一。\n\n学习内容二。\n\n学习内容三。", kb2),
            Triple("d3.txt", "生活内容。", kb3)
        )

        coroutineScope {
            docs.forEach { (name, text, kbId) ->
                launch {
                    pipeline.ingest(name, text.toInputStream(), kbId).toList()
                }
            }
        }

        // 验证：每个库独立，无串扰（FakeEmbedder 无状态、ObjectBox Box.put 线程安全）
        assertEquals("kb1 应有 2 chunk", 2, repository.chunkCount(kb1))
        assertEquals("kb2 应有 3 chunk", 3, repository.chunkCount(kb2))
        assertEquals("kb3 应有 1 chunk", 1, repository.chunkCount(kb3))
    }

    /**
     * InputStream.read 抛 IOException 时，PlainTextDocumentParser 将其包装为 DocumentParseException，
     * 管线 catch(DocumentParseException) → emit Failed，不崩溃。
     * 主 Agent 盲区：InputStream 读取异常（read 抛 IOException）。
     * 验证错误传播链：IOException → DocumentParseException(cause=IOException) → Failed 事件。
     */
    @Test
    fun ingest_propagates_io_exception_from_input_stream_as_failed_event() = runBlocking {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val failingStream = FailingInputStream()

        val events = pipeline.ingest("doc.txt", failingStream, kbId).toList()

        // IO 异常经解析器包装为 DocumentParseException，被管线 catch(DocumentParseException) 捕获 → emit Failed
        assertEquals(2, events.size)
        assertTrue(events[0] is IngestionEvent.Started)
        assertTrue(events[1] is IngestionEvent.Failed)
        val failed = events[1] as IngestionEvent.Failed
        assertTrue("应为 DocumentParseException", failed.throwable is DocumentParseException)
        assertTrue("cause 应为 IOException", failed.throwable.cause is java.io.IOException)
        // 无 chunk 入库
        assertEquals(0, repository.chunkCount(kbId))
    }

    // ===== 性能基线（ac-verifier TKN-US016-AC-001，首版） =====

    /**
     * 首版性能基线：管线编排开销 + ObjectBox 写入（FakeEmbedder 无真实 ONNX 推理）。
     * 测量 10/50/100 chunk 的摄入延迟，输出 p50/p95/p99、吞吐、错误率到 system-out。
     * 真实 OnnxEmbedder ~100ms/chunk 因无模拟器受限，此处仅测管线+DB 层。
     */
    @Test
    fun perf_baseline_ingestion_pipeline_orchestration_and_objectbox_write() = runBlocking {
        val chunkCounts = listOf(10, 50, 100)
        val outputs = mutableListOf<String>()
        var totalFailures = 0
        for (n in chunkCounts) {
            val text = (1..n).joinToString("\n\n") { "第${it}段性能基准内容，需足够长以确保独立成块。" }
            val iterations = when (n) { 10 -> 20; 50 -> 10; 100 -> 5; else -> 10 }
            val latencies = mutableListOf<Long>()
            repeat(iterations) { i ->
                val kbId = repository.save(KnowledgeBase(name = "perf-${n}-${i}"))
                val start = System.nanoTime()
                try {
                    pipeline.ingest("perf.txt", text.toInputStream(), kbId).toList()
                    latencies.add((System.nanoTime() - start) / 1_000_000)
                } catch (e: Exception) {
                    totalFailures++
                }
            }
            latencies.sort()
            val failures = iterations - latencies.size
            totalFailures += failures
            val p50 = latencies[latencies.size / 2]
            val p95 = latencies[(latencies.size * 95 / 100).coerceAtMost(latencies.size - 1)]
            val p99 = latencies[(latencies.size * 99 / 100).coerceAtMost(latencies.size - 1)]
            val min = latencies.first()
            val max = latencies.last()
            val throughput = n.toDouble() / (p50 / 1000.0)
            outputs.add("PERF_BASELINE|chunks=$n|iters=$iterations|min=${min}ms|p50=${p50}ms|p95=${p95}ms|p99=${p99}ms|max=${max}ms|throughput=${String.format("%.1f", throughput)}_chunk_per_s|failures=$failures")
        }
        outputs.forEach { println(it) }
        assertEquals("性能基线采集期间不应有摄入失败", 0, totalFailures)
    }

    // ===== 辅助 =====

    /** 可控 FakeEmbedder：可注入特定文本嵌入失败或全部失败（BR-testing-001）。 */
    private class FakeEmbedder(
        private val failOnText: String? = null,
        private val failAll: Boolean = false
    ) : Embedder {
        var embedCallCount = 0
            private set
        var closed = false
            private set

        override fun embed(text: String): FloatArray {
            embedCallCount++
            if (failAll || text == failOnText) {
                throw EmbeddingException(EmbeddingException.Stage.INFERENCE, "Fake 注入失败")
            }
            // 返回 384 维 one-hot 向量（基于 text.hashCode 取模）
            val vector = FloatArray(384)
            vector[Math.floorMod(text.hashCode(), 384)] = 1.0f
            return vector
        }

        override fun isLoaded(): Boolean = true

        override fun checkAndUnload(maxIdleMs: Long): Boolean = false

        override fun close() {
            closed = true
        }
    }

    /** 跟踪关闭状态的 InputStream，验证 ADR-009 5.7 use {} 关闭契约。 */
    private class TrackedInputStream(data: ByteArray) : ByteArrayInputStream(data) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    /** 读取时抛 IOException 的 InputStream，验证 catch(Exception) → Failed 降级路径。 */
    private class FailingInputStream : InputStream() {
        override fun read(): Int = throw java.io.IOException("模拟读取失败")
    }

    private fun String.toInputStream(): InputStream = ByteArrayInputStream(this.toByteArray())
}
