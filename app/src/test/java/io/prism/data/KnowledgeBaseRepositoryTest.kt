package io.prism.data

import io.objectbox.Box
import io.objectbox.BoxStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * KnowledgeBaseRepository 单元测试（US-015 验收标准 2/4）。
 *
 * 覆盖：
 * 1. CRUD 基础（save/get/getAll/findByName/removeAll）
 * 2. 级联删除原子性（删库后其下 chunk 全删、他库/默认库 chunk 不受影响）
 * 3. 默认库语义（id=0L 拒绝删除、旧数据归属默认库）
 * 4. chunkCount 运行时聚合
 * 5. Flow 订阅（knowledgeBases 在 save/remove 后更新）
 *
 * 测试搭建照搬 [KnowledgeChunkCrudTest] 临时目录 + 纯 JVM ObjectBox 模式（ADR-008 5.4，
 * 考古报告 §5.1）。MyObjectBox 由 ObjectBox plugin 编译期生成，自动包含 KnowledgeBase 实体。
 */
class KnowledgeBaseRepositoryTest {

    private lateinit var boxStore: BoxStore
    private lateinit var repository: KnowledgeBaseRepository
    private lateinit var chunkBox: Box<KnowledgeChunk>
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "objectbox-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        repository = KnowledgeBaseRepository(boxStore)
        chunkBox = boxStore.boxFor(KnowledgeChunk::class.java)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    // ===== CRUD 基础 =====

    @Test
    fun save_assigns_positive_id() {
        val kb = KnowledgeBase(name = "工作")
        val id = repository.save(kb)
        assertTrue("ObjectBox 应分配正数 id", id > 0)
    }

    @Test
    fun get_returns_persisted_knowledge_base() {
        val id = repository.save(KnowledgeBase(name = "学习"))

        val retrieved = repository.get(id)
        assertNotNull(retrieved)
        assertEquals("学习", retrieved!!.name)
        assertTrue("createdAt 应被持久化", retrieved.createdAt > 0)
    }

    @Test
    fun get_returns_null_for_nonexistent_id() {
        assertNull("不存在的 id 应返回 null", repository.get(99999L))
    }

    @Test
    fun get_returns_null_for_default_kb_id() {
        assertNull("默认库 id=0L 不持久化，应返回 null", repository.get(KnowledgeBaseRepository.DEFAULT_KB_ID))
    }

    @Test
    fun get_all_returns_sorted_by_created_at_ascending() {
        val first = KnowledgeBase(name = "第一", createdAt = 1000L)
        val second = KnowledgeBase(name = "第二", createdAt = 2000L)
        val third = KnowledgeBase(name = "第三", createdAt = 500L)

        repository.save(first)
        repository.save(second)
        repository.save(third)

        val all = repository.getAll()
        assertEquals(3, all.size)
        // 按 createdAt 升序：第三(500) < 第一(1000) < 第二(2000)
        assertEquals("第三", all[0].name)
        assertEquals("第一", all[1].name)
        assertEquals("第二", all[2].name)
    }

    @Test
    fun find_by_name_returns_matching() {
        repository.save(KnowledgeBase(name = "工作"))

        val found = repository.findByName("工作")
        assertNotNull(found)
        assertEquals("工作", found!!.name)
    }

    @Test
    fun find_by_name_returns_null_when_not_found() {
        assertNull(repository.findByName("不存在"))
    }

    @Test
    fun save_with_existing_id_updates() {
        val id = repository.save(KnowledgeBase(name = "原名"))
        val saved = repository.get(id)!!

        saved.name = "新名"
        repository.save(saved)

        val updated = repository.get(id)
        assertEquals("新名", updated!!.name)
    }

    @Test
    fun remove_all_clears_all_knowledge_bases_and_their_chunks() {
        val kb1 = repository.save(KnowledgeBase(name = "库1"))
        val kb2 = repository.save(KnowledgeBase(name = "库2"))
        // G-03: 为每个库添加 chunk，验证 removeAll 级联删除 chunk
        chunkBox.put(KnowledgeChunk(title = "1a", content = "1a", knowledgeBaseId = kb1))
        chunkBox.put(KnowledgeChunk(title = "1b", content = "1b", knowledgeBaseId = kb1))
        chunkBox.put(KnowledgeChunk(title = "2a", content = "2a", knowledgeBaseId = kb2))
        assertEquals(2, repository.getAll().size)
        assertEquals(2, repository.chunkCount(kb1))
        assertEquals(1, repository.chunkCount(kb2))

        repository.removeAll()

        assertEquals(0, repository.getAll().size)
        assertEquals("kb1 chunk 应级联删除", 0, repository.chunkCount(kb1))
        assertEquals("kb2 chunk 应级联删除", 0, repository.chunkCount(kb2))
    }

    // ===== 级联删除原子性 =====

    @Test
    fun remove_deletes_associated_chunks() {
        val kbId = repository.save(KnowledgeBase(name = "工作"))

        // 库下 3 个 chunk
        chunkBox.put(KnowledgeChunk(title = "c1", content = "内容1", knowledgeBaseId = kbId))
        chunkBox.put(KnowledgeChunk(title = "c2", content = "内容2", knowledgeBaseId = kbId))
        chunkBox.put(KnowledgeChunk(title = "c3", content = "内容3", knowledgeBaseId = kbId))
        assertEquals(3, repository.chunkCount(kbId))

        repository.remove(kbId)

        assertEquals("删库后其下 chunk 应全删", 0, repository.chunkCount(kbId))
        assertNull("库本身也应删除", repository.get(kbId))
    }

    @Test
    fun remove_does_not_affect_other_knowledge_base_chunks() {
        val kb1 = repository.save(KnowledgeBase(name = "工作"))
        val kb2 = repository.save(KnowledgeBase(name = "学习"))

        chunkBox.put(KnowledgeChunk(title = "c1", content = "工作内容", knowledgeBaseId = kb1))
        chunkBox.put(KnowledgeChunk(title = "c2", content = "学习内容", knowledgeBaseId = kb2))

        repository.remove(kb1)

        assertEquals("删 kb1 后 kb1 的 chunk 应为 0", 0, repository.chunkCount(kb1))
        assertEquals("删 kb1 不应影响 kb2 的 chunk", 1, repository.chunkCount(kb2))
        assertNotNull("kb2 应仍存在", repository.get(kb2))
    }

    @Test
    fun remove_does_not_affect_default_kb_chunks() {
        val kb1 = repository.save(KnowledgeBase(name = "工作"))

        // 默认库（0L）的 chunk
        chunkBox.put(KnowledgeChunk(title = "默认1", content = "默认内容", knowledgeBaseId = KnowledgeBaseRepository.DEFAULT_KB_ID))
        // 自建库的 chunk
        chunkBox.put(KnowledgeChunk(title = "c1", content = "工作内容", knowledgeBaseId = kb1))

        repository.remove(kb1)

        assertEquals("删自建库不应影响默认库 chunk", 1, repository.chunkCount(KnowledgeBaseRepository.DEFAULT_KB_ID))
    }

    @Test
    fun remove_default_kb_throws_illegal_argument() {
        try {
            repository.remove(KnowledgeBaseRepository.DEFAULT_KB_ID)
            org.junit.Assert.fail("删除默认库应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常信息应说明默认库", e.message!!.contains("默认库"))
        }
        // 验证默认库 chunk 仍可正常入库（默认库未被破坏）
        chunkBox.put(KnowledgeChunk(title = "默认", content = "内容", knowledgeBaseId = KnowledgeBaseRepository.DEFAULT_KB_ID))
        assertEquals(1, repository.chunkCount(KnowledgeBaseRepository.DEFAULT_KB_ID))
    }

    @Test
    fun remove_all_does_not_affect_default_kb_chunks() {
        val kb1 = repository.save(KnowledgeBase(name = "工作"))
        chunkBox.put(KnowledgeChunk(title = "默认1", content = "默认", knowledgeBaseId = KnowledgeBaseRepository.DEFAULT_KB_ID))
        chunkBox.put(KnowledgeChunk(title = "工作1", content = "工作", knowledgeBaseId = kb1))

        repository.removeAll()

        assertEquals("removeAll 不应删默认库 chunk", 1, repository.chunkCount(KnowledgeBaseRepository.DEFAULT_KB_ID))
        assertEquals("removeAll 应删所有自建库", 0, repository.getAll().size)
    }

    // ===== 旧数据归属默认库（向后兼容） =====

    @Test
    fun legacy_chunk_without_knowledge_base_id_belongs_to_default_kb() {
        // 模拟旧数据：仅用 title/content 构造（knowledgeBaseId 走默认值 0L）
        val legacyChunk = KnowledgeChunk(title = "旧文档", content = "旧内容")
        val id = chunkBox.put(legacyChunk)

        val retrieved = chunkBox.get(id)
        assertEquals(
            "旧 chunk 加字段后应自动归属默认库（knowledgeBaseId=0L）",
            KnowledgeBaseRepository.DEFAULT_KB_ID,
            retrieved.knowledgeBaseId
        )
        assertEquals(1, repository.chunkCount(KnowledgeBaseRepository.DEFAULT_KB_ID))
    }

    @Test
    fun legacy_chunks_counted_in_default_kb() {
        // 3 条旧数据 + 2 条默认库新数据 + 1 条自建库数据
        chunkBox.put(KnowledgeChunk(title = "旧1", content = "1"))
        chunkBox.put(KnowledgeChunk(title = "旧2", content = "2"))
        chunkBox.put(KnowledgeChunk(title = "旧3", content = "3"))
        chunkBox.put(KnowledgeChunk(title = "新默认", content = "4", knowledgeBaseId = KnowledgeBaseRepository.DEFAULT_KB_ID))
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        chunkBox.put(KnowledgeChunk(title = "工作", content = "5", knowledgeBaseId = kbId))

        assertEquals("默认库应有 4 条（3 旧 + 1 新）", 4, repository.chunkCount(KnowledgeBaseRepository.DEFAULT_KB_ID))
        assertEquals("自建库应有 1 条", 1, repository.chunkCount(kbId))
    }

    // ===== chunkCount 边界 =====

    @Test
    fun chunk_count_returns_zero_for_empty_kb() {
        val kbId = repository.save(KnowledgeBase(name = "空库"))
        assertEquals(0, repository.chunkCount(kbId))
    }

    @Test
    fun chunk_count_returns_zero_for_nonexistent_kb() {
        assertEquals("不存在的库 chunkCount 应为 0", 0, repository.chunkCount(99999L))
    }

    // ===== Flow 订阅 =====

    @Test
    fun knowledge_bases_flow_emits_initial_empty_list() = runBlocking {
        val list = repository.knowledgeBases.first()
        assertTrue("初始 Flow 应为空列表", list.isEmpty())
    }

    @Test
    fun knowledge_bases_flow_updates_after_save() = runBlocking {
        repository.save(KnowledgeBase(name = "工作"))
        repository.save(KnowledgeBase(name = "学习"))

        val list = repository.knowledgeBases.first()
        assertEquals(2, list.size)
    }

    @Test
    fun knowledge_bases_flow_updates_after_remove() = runBlocking {
        val id = repository.save(KnowledgeBase(name = "工作"))
        assertEquals(1, repository.knowledgeBases.first().size)

        repository.remove(id)
        assertEquals(0, repository.knowledgeBases.first().size)
    }

    // ===== 边界场景 =====

    @Test
    fun save_empty_name_is_allowed_at_repository_layer() {
        // 业务校验属 UI 层，Repository 不做限制
        val id = repository.save(KnowledgeBase(name = ""))
        val retrieved = repository.get(id)
        assertEquals("", retrieved!!.name)
    }

    @Test
    fun save_duplicate_name_is_allowed_at_repository_layer() {
        // 无唯一约束，重名允许（业务层若需唯一性应额外校验）
        repository.save(KnowledgeBase(name = "工作"))
        repository.save(KnowledgeBase(name = "工作"))
        assertEquals(2, repository.getAll().size)
    }

    @Test
    fun remove_nonexistent_id_does_not_throw() {
        // ObjectBox box.remove(不存在的 id) 行为：不抛异常，no-op
        repository.remove(99999L)
    }

    // ===== 负数 id 防御（G-04） =====

    @Test
    fun get_negative_id_throws_illegal_argument() {
        try {
            repository.get(-1L)
            org.junit.Assert.fail("负数 id 应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常信息应说明负数", e.message!!.contains("负数"))
        }
    }

    @Test
    fun remove_negative_id_throws_illegal_argument() {
        try {
            repository.remove(-1L)
            org.junit.Assert.fail("负数 id 应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常信息应说明负数", e.message!!.contains("负数"))
        }
    }

    @Test
    fun chunk_count_negative_id_throws_illegal_argument() {
        try {
            repository.chunkCount(-1L)
            org.junit.Assert.fail("负数 id 应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常信息应说明负数", e.message!!.contains("负数"))
        }
    }

    @Test
    fun multiple_knowledge_bases_each_get_unique_id() {
        val id1 = repository.save(KnowledgeBase(name = "库1"))
        val id2 = repository.save(KnowledgeBase(name = "库2"))
        val id3 = repository.save(KnowledgeBase(name = "库3"))

        assertTrue(id1 != id2)
        assertTrue(id2 != id3)
        assertTrue(id1 != id3)
    }

    @Test
    fun cascade_delete_preserves_other_kbs_and_their_chunks() {
        // 综合场景：3 个自建库，每个 2 chunk，删中间库后其余库与 chunk 完整保留
        val kb1 = repository.save(KnowledgeBase(name = "库1"))
        val kb2 = repository.save(KnowledgeBase(name = "库2"))
        val kb3 = repository.save(KnowledgeBase(name = "库3"))

        chunkBox.put(KnowledgeChunk(title = "1a", content = "1a", knowledgeBaseId = kb1))
        chunkBox.put(KnowledgeChunk(title = "1b", content = "1b", knowledgeBaseId = kb1))
        chunkBox.put(KnowledgeChunk(title = "2a", content = "2a", knowledgeBaseId = kb2))
        chunkBox.put(KnowledgeChunk(title = "2b", content = "2b", knowledgeBaseId = kb2))
        chunkBox.put(KnowledgeChunk(title = "3a", content = "3a", knowledgeBaseId = kb3))
        chunkBox.put(KnowledgeChunk(title = "3b", content = "3b", knowledgeBaseId = kb3))

        repository.remove(kb2)

        assertEquals("kb1 chunk 完整", 2, repository.chunkCount(kb1))
        assertEquals("kb2 chunk 已删", 0, repository.chunkCount(kb2))
        assertEquals("kb3 chunk 完整", 2, repository.chunkCount(kb3))
        assertNotNull("kb1 仍存在", repository.get(kb1))
        assertNull("kb2 已删", repository.get(kb2))
        assertNotNull("kb3 仍存在", repository.get(kb3))
    }

    // ===== HNSW 索引下的级联删除（G-01 验证） =====

    /**
     * 验证带 384 维 embedding 的 chunk 级联删除不触发 ObjectBox #1209
     * （IllegalStateException: Vector is missing for neighbor to repair）。
     *
     * guardrail-enforcer G-01 要求：US-016 入库后 chunk 有 embedding，
     * Query.remove() 在 HNSW 索引下可能触发已知 bug。本测试覆盖生产路径。
     */
    @Test
    fun remove_cascade_deletes_chunks_with_hnsw_embedding_without_error() {
        val kbId = repository.save(KnowledgeBase(name = "工作"))

        // 3 个带 384 维 embedding 的 chunk（one-hot 向量，参考 KnowledgeChunkVectorSearchTest 模式）
        chunkBox.put(KnowledgeChunk(
            title = "c1", content = "内容1",
            embedding = oneHot(0), knowledgeBaseId = kbId
        ))
        chunkBox.put(KnowledgeChunk(
            title = "c2", content = "内容2",
            embedding = oneHot(50), knowledgeBaseId = kbId
        ))
        chunkBox.put(KnowledgeChunk(
            title = "c3", content = "内容3",
            embedding = oneHot(100), knowledgeBaseId = kbId
        ))
        assertEquals(3, repository.chunkCount(kbId))

        // 级联删除：不应抛 IllegalStateException
        repository.remove(kbId)

        assertEquals("删库后带 embedding 的 chunk 应全删", 0, repository.chunkCount(kbId))
        assertNull("库本身也应删除", repository.get(kbId))
    }

    // ==================== UX-001 问题 2（ADR-021）：文档级管理 ====================

    @Test
    fun listDocuments_groups_chunks_by_document_title() {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        // 两个文档，各 2 个 chunk（title 约定 `文档名#序号`）
        chunkBox.put(KnowledgeChunk(title = "文档A#1", content = "a1", knowledgeBaseId = kbId))
        chunkBox.put(KnowledgeChunk(title = "文档A#2", content = "a2", knowledgeBaseId = kbId))
        chunkBox.put(KnowledgeChunk(title = "文档B#1", content = "b1", knowledgeBaseId = kbId))

        val docs = repository.listDocuments(kbId)
        assertEquals("应列出 2 个去重文档", listOf("文档A", "文档B"), docs)
    }

    @Test
    fun listDocuments_handles_title_without_hash() {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        chunkBox.put(KnowledgeChunk(title = "无哈希标题", content = "c", knowledgeBaseId = kbId))

        val docs = repository.listDocuments(kbId)
        assertEquals("无 # 的 title 应整段作为文档标题", listOf("无哈希标题"), docs)
    }

    @Test
    fun deleteDocument_removes_all_chunks_of_that_document_only() {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        chunkBox.put(KnowledgeChunk(title = "文档A#1", content = "a1", knowledgeBaseId = kbId))
        chunkBox.put(KnowledgeChunk(title = "文档A#2", content = "a2", knowledgeBaseId = kbId))
        chunkBox.put(KnowledgeChunk(title = "文档B#1", content = "b1", knowledgeBaseId = kbId))

        val removed = repository.deleteDocument(kbId, "文档A")

        assertEquals("应删除 2 个 chunk", 2, removed)
        assertEquals("剩余 1 个 chunk（文档B）", 1, repository.chunkCount(kbId))
        assertEquals("文档B 仍保留", listOf("文档B"), repository.listDocuments(kbId))
    }

    @Test
    fun deleteDocument_returns_zero_for_nonexistent_document() {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        chunkBox.put(KnowledgeChunk(title = "文档A#1", content = "a1", knowledgeBaseId = kbId))

        assertEquals("不存在的文档删除应返回 0", 0, repository.deleteDocument(kbId, "不存在的文档"))
        assertEquals("原文档不受影响", 1, repository.chunkCount(kbId))
    }

    @Test
    fun moveDocument_moves_all_chunks_to_target_library() {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val targetId = repository.save(KnowledgeBase(name = "学习"))
        chunkBox.put(KnowledgeChunk(title = "文档A#1", content = "a1", knowledgeBaseId = kbId))
        chunkBox.put(KnowledgeChunk(title = "文档A#2", content = "a2", knowledgeBaseId = kbId))

        val moved = repository.moveDocument(kbId, "文档A", targetId)

        assertEquals("应移动 2 个 chunk", 2, moved)
        assertEquals("源库文档消失", emptyList<String>(), repository.listDocuments(kbId))
        assertEquals("目标库含文档A", listOf("文档A"), repository.listDocuments(targetId))
        assertEquals("目标库 chunk 数", 2, repository.chunkCount(targetId))
    }

    @Test
    fun moveDocument_same_library_is_noop() {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        chunkBox.put(KnowledgeChunk(title = "文档A#1", content = "a1", knowledgeBaseId = kbId))

        assertEquals("同库移动应为 no-op", 0, repository.moveDocument(kbId, "文档A", kbId))
        assertEquals("chunk 保留在源库", 1, repository.chunkCount(kbId))
    }

    @Test
    fun remove_cascade_deletes_mixed_embedding_and_non_embedding_chunks() {
        val kbId = repository.save(KnowledgeBase(name = "混合库"))

        // 混合：有 embedding 和无 embedding 的 chunk
        chunkBox.put(KnowledgeChunk(title = "有向量", content = "1", embedding = oneHot(0), knowledgeBaseId = kbId))
        chunkBox.put(KnowledgeChunk(title = "无向量", content = "2", embedding = null, knowledgeBaseId = kbId))
        chunkBox.put(KnowledgeChunk(title = "有向量2", content = "3", embedding = oneHot(100), knowledgeBaseId = kbId))

        repository.remove(kbId)

        assertEquals(0, repository.chunkCount(kbId))
        assertNull(repository.get(kbId))
    }

    /** 构造 384 维 one-hot 向量（参考 KnowledgeChunkVectorSearchTest.oneHot）。 */
    private fun oneHot(dominantIndex: Int): FloatArray {
        val vector = FloatArray(384)
        vector[dominantIndex] = 1.0f
        return vector
    }

    // ===== UXR3 问题 12（ADR-023）：文档内容查看 =====

    @Test
    fun getDocumentContent_concatenates_chunks_in_index_order() {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        // 乱序插入 chunk（#3 / #1 / #2），验证按 chunkIndex 升序拼接
        chunkBox.put(KnowledgeChunk(title = "文档A#3", content = "第三节", knowledgeBaseId = kbId))
        chunkBox.put(KnowledgeChunk(title = "文档A#1", content = "第一节", knowledgeBaseId = kbId))
        chunkBox.put(KnowledgeChunk(title = "文档A#2", content = "第二节", knowledgeBaseId = kbId))

        val content = repository.getDocumentContent(kbId, "文档A")

        assertEquals("应按序号升序拼接", "第一节\n\n第二节\n\n第三节", content)
    }

    @Test
    fun getDocumentContent_filters_other_documents_and_other_libraries() {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        val otherKbId = repository.save(KnowledgeBase(name = "学习"))
        chunkBox.put(KnowledgeChunk(title = "文档A#1", content = "a1", knowledgeBaseId = kbId))
        chunkBox.put(KnowledgeChunk(title = "文档A#2", content = "a2", knowledgeBaseId = kbId))
        // 其他文档 + 其他库的 chunk 不应混入
        chunkBox.put(KnowledgeChunk(title = "文档B#1", content = "b1", knowledgeBaseId = kbId))
        chunkBox.put(KnowledgeChunk(title = "文档A#1", content = "other-lib", knowledgeBaseId = otherKbId))

        val content = repository.getDocumentContent(kbId, "文档A")

        assertEquals("仅含目标文档在本库的分块", "a1\n\na2", content)
    }

    @Test
    fun getDocumentContent_returns_empty_when_no_match() {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        chunkBox.put(KnowledgeChunk(title = "文档A#1", content = "a1", knowledgeBaseId = kbId))

        assertEquals("无匹配文档应返回空串", "", repository.getDocumentContent(kbId, "不存在"))
    }

    @Test
    fun getDocumentContent_trims_chunk_content_and_edges() {
        val kbId = repository.save(KnowledgeBase(name = "工作"))
        chunkBox.put(KnowledgeChunk(title = "文档A#1", content = "  段落一  ", knowledgeBaseId = kbId))
        chunkBox.put(KnowledgeChunk(title = "文档A#2", content = "段落二\n", knowledgeBaseId = kbId))

        val content = repository.getDocumentContent(kbId, "文档A")

        assertEquals("应 trim 每块首尾空白并 trim 整体", "段落一\n\n段落二", content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun getDocumentContent_throws_for_negative_kb_id() {
        repository.getDocumentContent(-1L, "文档A")
    }

    @Test(expected = IllegalArgumentException::class)
    fun getDocumentContent_throws_for_blank_title() {
        repository.getDocumentContent(0L, "   ")
    }

    @Test
    fun getDocumentContent_default_library_supported() {
        // 默认库（id=0L）
        chunkBox.put(KnowledgeChunk(title = "笔记#1", content = "n1", knowledgeBaseId = 0L))
        chunkBox.put(KnowledgeChunk(title = "笔记#2", content = "n2", knowledgeBaseId = 0L))

        val content = repository.getDocumentContent(0L, "笔记")

        assertEquals("默认库文档应可查看", "n1\n\nn2", content)
    }
}
