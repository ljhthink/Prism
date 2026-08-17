package io.prism.network

import io.prism.data.KnowledgeBaseRepository
import io.prism.data.KnowledgeChunk
import io.prism.data.MyObjectBox
import io.prism.embedding.FakeEmbedder
import io.objectbox.BoxStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * UXR9 US-901 AC-3 补充测试（ac-verifier，TKN-UXR9-ACCEPTANCE-001）。
 *
 * 主 Agent 的 [KnowledgeBaseLocalToolExecutorTest] 验证了 search 的相似度阈值过滤（正向
 * 命中 + 空库 + embed 失败），但 **未验证「结果条数上限 top-2」**（`knowledge_base__search`
 * 工具路径同样要求 top-2 控制上下文膨胀）。本文件补充：
 *
 * - AC-3：3+ 条相关片段（全部通过 0.5 阈值）时，工具仅返回 top-2，不出现 [来源3]
 * - 阈值过滤 + top-2 联合：混合相关/无关片段时，仅相关片段且 ≤2 条返回
 * - 边界：恰 2 条相关 → 全部返回；恰 1 条相关 → 仅 1 条返回
 */
class KnowledgeBaseLocalToolExecutorUxr9Test {

    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File
    private lateinit var repo: KnowledgeBaseRepository
    private lateinit var executor: KnowledgeBaseLocalToolExecutor

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "kb-uxr9-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        repo = KnowledgeBaseRepository(boxStore)
        executor = KnowledgeBaseLocalToolExecutor(FakeEmbedder(), repo)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    private fun addChunk(title: String, content: String, embedding: FloatArray) {
        repo.addChunk(
            KnowledgeChunk(
                title = title,
                content = content,
                embedding = embedding,
                knowledgeBaseId = KnowledgeBaseRepository.DEFAULT_KB_ID
            )
        )
    }

    @Test
    fun `search with 3 relevant chunks returns only top-2`() = runBlocking {
        // 3 条与查询向量完全一致的 chunk（相似度 1.0，全部通过 0.5 阈值）
        val relevant = FakeEmbedder().embed("Prism 是什么")
        addChunk("doc.md#1", "片段一内容", relevant)
        addChunk("doc.md#2", "片段二内容", relevant)
        addChunk("doc.md#3", "片段三内容", relevant)

        val result = executor.execute(
            KnowledgeBaseLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "Prism 是什么", "topK" to 5)
        )
        assertTrue("应含来源1", result.contains("[来源1]"))
        assertTrue("应含来源2", result.contains("[来源2]"))
        assertFalse("top-2 上限：不应出现来源3", result.contains("[来源3]"))
        assertTrue("应含前两条内容", result.contains("片段一内容") && result.contains("片段二内容"))
        assertFalse("top-2 上限：第三条内容不应返回", result.contains("片段三内容"))
    }

    @Test
    fun `search filters below threshold then caps at top-2`() = runBlocking {
        // 混合集：2 条相关（相似度 1.0）+ 1 条无关（零向量 → 相似度 ≈0 < 0.5）
        val relevant = FakeEmbedder().embed("Prism 是什么")
        addChunk("rel.md#1", "相关片段一", relevant)
        addChunk("rel.md#2", "相关片段二", relevant)
        addChunk("unrelated.md#1", "无关的财务报告内容", FloatArray(384)) // 零向量

        val result = executor.execute(
            KnowledgeBaseLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "Prism 是什么", "topK" to 5)
        )
        assertTrue("应含相关片段一", result.contains("相关片段一"))
        assertTrue("应含相关片段二", result.contains("相关片段二"))
        assertFalse("无关片段不得返回（阈值过滤）", result.contains("财务报告"))
    }

    @Test
    fun `search with exactly 2 relevant returns both`() = runBlocking {
        val relevant = FakeEmbedder().embed("Kotlin 协程")
        addChunk("k1.md#1", "协程片段一", relevant)
        addChunk("k1.md#2", "协程片段二", relevant)

        val result = executor.execute(
            KnowledgeBaseLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "Kotlin 协程", "topK" to 5)
        )
        assertTrue("应含来源1", result.contains("[来源1]"))
        assertTrue("应含来源2", result.contains("[来源2]"))
        assertTrue("两条内容都应返回", result.contains("协程片段一") && result.contains("协程片段二"))
    }

    @Test
    fun `search with single relevant returns only one`() = runBlocking {
        val relevant = FakeEmbedder().embed("唯一话题")
        addChunk("only.md#1", "唯一片段", relevant)

        val result = executor.execute(
            KnowledgeBaseLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "唯一话题", "topK" to 5)
        )
        assertTrue("应含来源1", result.contains("[来源1]"))
        assertFalse("单条结果不应出现来源2", result.contains("[来源2]"))
    }
}
