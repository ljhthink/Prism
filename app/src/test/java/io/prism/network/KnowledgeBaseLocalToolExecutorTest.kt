package io.prism.network

import io.prism.data.KnowledgeBaseRepository
import io.prism.data.KnowledgeChunk
import io.prism.data.MyObjectBox
import io.prism.embedding.FakeEmbedder
import io.objectbox.BoxStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 知识库本地工具执行器测试（UXR4 问题 2/3，ADR-024 子决策 B）。
 *
 * 覆盖：
 * - handles 匹配（search / list_documents / get_document_content）
 * - search：语义检索返回片段（含来源与相似度）
 * - list_documents：枚举全部文档标题（默认库 + 自建库聚合）
 * - get_document_content：获取文档全文
 * - 参数缺失 / 空库 / embed 失败降级
 * - buildToolDefinitions 工具名与描述
 */
class KnowledgeBaseLocalToolExecutorTest {

    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File
    private lateinit var repo: KnowledgeBaseRepository
    private lateinit var executor: KnowledgeBaseLocalToolExecutor

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "kb-tool-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        repo = KnowledgeBaseRepository(boxStore)
        executor = KnowledgeBaseLocalToolExecutor(FakeEmbedder(), repo)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    // ==================== handles ====================

    @Test
    fun `handles matches knowledge base tools only`() {
        assertTrue(executor.handles(KnowledgeBaseLocalToolExecutor.TOOL_SEARCH))
        assertTrue(executor.handles(KnowledgeBaseLocalToolExecutor.TOOL_LIST_DOCUMENTS))
        assertTrue(executor.handles(KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT))
        assertFalse(executor.handles("web_search__search"))
        assertFalse(executor.handles("mcp_Filesystem__list_directory"))
        assertFalse(executor.handles("unknown"))
    }

    // ==================== search ====================

    @Test
    fun `search returns ranked snippets with source and similarity`() = runBlocking {
        seedDefaultKb()

        val result = executor.execute(
            KnowledgeBaseLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "Prism 是什么")
        )

        assertTrue("应包含知识库内容边界标记", result.startsWith("【知识库内容"))
        assertTrue("应包含来源标注", result.contains("文件=architecture.md"))
        assertTrue("应包含片段内容", result.contains("Prism 是一个 AI 助手"))
    }

    @Test
    fun `search supports specific knowledgeBaseId`() = runBlocking {
        seedDefaultKb()
        // 自建库不含匹配内容
        val kbId = repo.save(io.prism.data.KnowledgeBase(name = "empty-kb", createdAt = System.currentTimeMillis()))

        val result = executor.execute(
            KnowledgeBaseLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "Prism", "knowledgeBaseId" to kbId)
        )

        assertTrue("空库检索应返回未找到", result.contains("未找到"))
    }

    @Test
    fun `search missing query returns error`() = runBlocking {
        val result = executor.execute(
            KnowledgeBaseLocalToolExecutor.TOOL_SEARCH,
            emptyMap()
        )
        assertEquals("缺少必需参数 query", result)
    }

    @Test
    fun `search empty kb returns not found`() = runBlocking {
        val result = executor.execute(
            KnowledgeBaseLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "anything")
        )
        assertTrue("空知识库应返回未找到", result.contains("未找到"))
    }

    @Test
    fun `search embed failure degrades gracefully`() = runBlocking {
        val failingExecutor = KnowledgeBaseLocalToolExecutor(FakeEmbedder(throwOnCall = true), repo)
        val result = failingExecutor.execute(
            KnowledgeBaseLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "anything")
        )
        assertTrue("embed 失败应降级为错误文案", result.startsWith("知识库检索失败"))
    }

    // ==================== list_documents ====================

    @Test
    fun `listDocuments aggregates default and custom kb`() = runBlocking {
        seedDefaultKb()
        val kbId = repo.save(io.prism.data.KnowledgeBase(name = "custom", createdAt = System.currentTimeMillis()))
        repo.addChunk(
            KnowledgeChunk(
                title = "custom-doc.md#1",
                content = "自定义库内容",
                embedding = FloatArray(384),
                knowledgeBaseId = kbId
            )
        )

        val result = executor.execute(
            KnowledgeBaseLocalToolExecutor.TOOL_LIST_DOCUMENTS,
            emptyMap()
        )

        assertTrue("应包含默认库文档", result.contains("architecture.md"))
        assertTrue("应包含自建库文档", result.contains("custom-doc.md"))
    }

    @Test
    fun `listDocuments empty kb returns prompt`() = runBlocking {
        val result = executor.execute(
            KnowledgeBaseLocalToolExecutor.TOOL_LIST_DOCUMENTS,
            emptyMap()
        )
        assertTrue("空库应提示导入", result.contains("暂无资料"))
    }

    // ==================== get_document_content ====================

    @Test
    fun `getDocumentContent returns full document`() = runBlocking {
        seedDefaultKb()

        val result = executor.execute(
            KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT,
            mapOf("documentTitle" to "architecture.md")
        )

        assertTrue("应包含文档边界标记", result.contains("【知识库文档：architecture.md】"))
        assertTrue("应包含完整内容", result.contains("Prism 是一个 AI 助手"))
    }

    @Test
    fun `getDocumentContent missing title returns error`() = runBlocking {
        val result = executor.execute(
            KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT,
            emptyMap()
        )
        assertEquals("缺少必需参数 documentTitle", result)
    }

    @Test
    fun `getDocumentContent not found returns message`() = runBlocking {
        val result = executor.execute(
            KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT,
            mapOf("documentTitle" to "nonexistent.md")
        )
        assertTrue("未找到文档应返回提示", result.contains("未找到文档"))
    }

    // ==================== buildToolDefinitions ====================

    @Test
    fun `buildToolDefinitions returns 3 tools with kb namespace`() {
        val tools = KnowledgeBaseLocalToolExecutor.buildToolDefinitions()
        assertEquals(3, tools.size)
        assertEquals(KnowledgeBaseLocalToolExecutor.TOOL_SEARCH, tools[0].function.name)
        assertEquals(KnowledgeBaseLocalToolExecutor.TOOL_LIST_DOCUMENTS, tools[1].function.name)
        assertEquals(KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT, tools[2].function.name)
        // 描述应显式区分知识库与 Filesystem
        assertTrue("search 描述应提及知识库", tools[0].function.description.contains("知识库"))
        assertTrue("search 描述应声明非 Filesystem", tools[0].function.description.contains("不是") || tools[0].function.description.contains("Filesystem"))
    }

    // ==================== 辅助 ====================

    private fun seedDefaultKb() {
        repo.addChunk(
            KnowledgeChunk(
                title = "architecture.md#1",
                content = "Prism 是一个 AI 助手应用",
                embedding = FloatArray(384),
                knowledgeBaseId = KnowledgeBaseRepository.DEFAULT_KB_ID
            )
        )
        repo.addChunk(
            KnowledgeChunk(
                title = "architecture.md#2",
                content = "支持 MCP 工具与个人知识库",
                embedding = FloatArray(384),
                knowledgeBaseId = KnowledgeBaseRepository.DEFAULT_KB_ID
            )
        )
    }
}
