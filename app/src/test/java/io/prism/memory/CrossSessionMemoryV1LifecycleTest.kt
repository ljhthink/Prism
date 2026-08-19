package io.prism.memory

import io.prism.data.MemoryRecord
import io.prism.data.MemoryRepository
import io.prism.data.MemorySearchResult
import io.prism.data.MyObjectBox
import io.prism.data.ProviderConfig
import io.prism.embedding.Embedder
import io.prism.network.ChatCompletionProvider
import io.objectbox.BoxStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * v1 记忆深度优化 US-103/104 生命周期测试：软衰减 / 容量回收 / 批量去重 / 注入预算。
 */
class CrossSessionMemoryV1LifecycleTest {

    private lateinit var boxStore: BoxStore
    private lateinit var memoryRepository: MemoryRepository

    @Before
    fun setUp() {
        val tempDir = Files.createTempDirectory("prism-v1-lifecycle-test").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        memoryRepository = MemoryRepository(boxStore)
    }

    @After
    fun tearDown() {
        boxStore.close()
    }

    // ==================== US-103：软衰减评分 computeRecallScore ====================

    @Test
    fun `computeRecallScore recent memory scores high`() {
        val manager = CrossSessionMemoryManager(FakeEmbedder2(), memoryRepository)
        val now = System.currentTimeMillis()
        val score = manager.computeRecallScore(
            priority = 50, accessCount = 0, timestamp = now - 1000L, now = now
        )
        assertTrue("近期记忆评分应接近 priority（≈50）", score > 45.0 && score <= 50.0)
    }

    @Test
    fun `computeRecallScore old memory decays`() {
        val manager = CrossSessionMemoryManager(FakeEmbedder2(), memoryRepository)
        val now = System.currentTimeMillis()
        val old = now - 100L * 24 * 3600 * 1000L // 100 天前
        val recent = manager.computeRecallScore(50, 0, now - 1000L, now)
        val oldScore = manager.computeRecallScore(50, 0, old, now)
        assertTrue("旧记忆评分应显著低于新记忆", oldScore < recent * 0.5)
    }

    @Test
    fun `computeRecallScore accessCount boosts score`() {
        val manager = CrossSessionMemoryManager(FakeEmbedder2(), memoryRepository)
        val now = System.currentTimeMillis()
        val noHit = manager.computeRecallScore(50, 0, now - 1000L, now)
        val manyHits = manager.computeRecallScore(50, 10, now - 1000L, now)
        assertTrue("命中次数应增强评分", manyHits > noHit)
    }

    // ==================== US-103：软衰减过滤（低于阈值移出注入集，保留在库） ====================

    @Test
    fun `soft decay excludes low priority memory from injection but keeps in library`() = runBlocking {
        val queryVector = FloatArray(384) { if (it == 0) 1f else 0f }
        val now = System.currentTimeMillis()
        // 高重要性：priority 50 → 通过衰减阈值
        memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "s1", content = "高重要性记忆",
                embedding = queryVector, timestamp = now - 1000L, turnCount = 1, priority = 50
            )
        )
        // 低重要性：priority 5 → recallScore ≈ 5 < 20 → 移出注入集
        memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "s1", content = "低重要性记忆",
                embedding = queryVector, timestamp = now - 1000L, turnCount = 2, priority = 5
            )
        )
        val manager = CrossSessionMemoryManager(
            embedder = QueryAlignedEmbedder(queryVector),
            memoryRepository = memoryRepository,
            retrievalThreshold = 0.4
        )
        val results = manager.retrieveRelevantMemories("查询", topK = 10)
        assertEquals("仅高重要性记忆应注入", 1, results.size)
        assertTrue("注入的是高重要性记忆", results[0].content.contains("高重要性"))
        // 低重要性记忆仍在库中（仅移出注入集）
        assertEquals("库中仍保留 2 条", 2L, memoryRepository.count())
    }

    // ==================== US-103：容量回收 ====================

    @Test
    fun `capacity eviction removes lowest priority oldest first`() {
        val now = System.currentTimeMillis()
        // 5 条记忆：3 条 priority 50（时间不同），2 条 priority 10
        (1..5).forEach { i ->
            val priority = if (i <= 2) 10 else 50
            memoryRepository.save(
                MemoryRecord(
                    id = 0, sessionId = "s$i", content = "记忆$i",
                    embedding = FloatArray(384) { if (it == 0) 1f else 0f },
                    timestamp = now - (5 - i) * 1000L, turnCount = 1, priority = priority
                )
            )
        }
        assertEquals(5L, memoryRepository.count())
        val evicted = memoryRepository.evictIfOverLimit(3)
        assertEquals("应回收 2 条", 2L, evicted)
        assertEquals("回收后剩 3 条", 3L, memoryRepository.count())
        // 低 priority(10) 的记录应被优先回收
        val remaining = memoryRepository.all()
        assertTrue("剩余记录应全部为高优先级", remaining.all { it.priority >= 50 })
    }

    // ==================== US-103：批量去重 dedupeSessionMemories ====================

    @Test
    fun `dedupe skip removes duplicate new memory`() = runBlocking {
        val embedder = FakeEmbedder2()
        val existing = memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "other", content = "[记忆] 用户喜欢喝美式咖啡",
                embedding = embedder.embed("[记忆] 用户喜欢喝美式咖啡"),
                timestamp = System.currentTimeMillis(), turnCount = 1, priority = 80
            )
        )
        val duplicate = memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "s1", content = "[记忆] 用户喜欢喝美式咖啡",
                embedding = embedder.embed("[记忆] 用户喜欢喝美式咖啡"),
                timestamp = System.currentTimeMillis(), turnCount = 1, priority = 80
            )
        )
        val provider = ReturningProvider(
            """[{"memoryIndex": 0, "action": "skip", "targetId": $existing}]"""
        )
        val summarizer = ConversationSummarizer(provider)
        val manager = CrossSessionMemoryManager(FakeEmbedder2(), memoryRepository, summarizer)

        val changed = manager.dedupeSessionMemories("s1", TEST_CONFIG)

        assertEquals("skip 应删除重复新记忆", 1, changed)
        assertFalse("新记忆应被删除", memoryRepository.all().any { it.id == duplicate })
        assertTrue("已有候选应保留", memoryRepository.all().any { it.id == existing })
    }

    @Test
    fun `dedupe update merges into target with version bump`() = runBlocking {
        val embedder = FakeEmbedder2()
        val target = memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "other", content = "[记忆] 用户喜欢咖啡",
                embedding = embedder.embed("[记忆] 用户喜欢咖啡"),
                timestamp = System.currentTimeMillis(), turnCount = 1, priority = 70, version = 3
            )
        )
        val newRec = memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "s1", content = "[记忆] 用户喜欢冰美式咖啡",
                embedding = embedder.embed("[记忆] 用户喜欢冰美式咖啡"),
                timestamp = System.currentTimeMillis(), turnCount = 1, priority = 90
            )
        )
        val provider = ReturningProvider(
            """[{"memoryIndex": 0, "action": "update", "targetId": $target}]"""
        )
        val summarizer = ConversationSummarizer(provider)
        val manager = CrossSessionMemoryManager(FakeEmbedder2(), memoryRepository, summarizer)

        val changed = manager.dedupeSessionMemories("s1", TEST_CONFIG)

        assertEquals("update 应合并 1 条", 1, changed)
        val updated = memoryRepository.all().first { it.id == target }
        assertTrue("目标内容应更新为新记忆", updated.content.contains("冰美式"))
        assertEquals("版本号应 +1", 4, updated.version)
        assertTrue("新记录应被删除", memoryRepository.all().none { it.id == newRec })
    }

    @Test
    fun `dedupe llm failure is no-op keeping records`() = runBlocking {
        memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "s1", content = "[记忆] 用户喜欢喝美式咖啡",
                timestamp = System.currentTimeMillis(), turnCount = 1, priority = 80
            )
        )
        // 去重 LLM 抛异常 → 降级不处理
        val provider = ReturningProvider(null, throwOnCall = true)
        val summarizer = ConversationSummarizer(provider)
        val manager = CrossSessionMemoryManager(FakeEmbedder2(), memoryRepository, summarizer)

        val changed = manager.dedupeSessionMemories("s1", TEST_CONFIG)

        assertEquals("LLM 失败应不处理", 0, changed)
        assertEquals("记录应完整保留", 1L, memoryRepository.count())
    }

    @Test
    fun `dedupe returns zero without summarizer or providerConfig`() = runBlocking {
        memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "s1", content = "[记忆] 用户喜欢咖啡",
                timestamp = System.currentTimeMillis(), turnCount = 1
            )
        )
        val noSummarizer = CrossSessionMemoryManager(FakeEmbedder2(), memoryRepository)
        assertEquals("无 summarizer 不去重", 0, noSummarizer.dedupeSessionMemories("s1", TEST_CONFIG))

        val withSummarizer = CrossSessionMemoryManager(
            FakeEmbedder2(), memoryRepository, ConversationSummarizer(ReturningProvider("[]"))
        )
        assertEquals("无 providerConfig 不去重", 0, withSummarizer.dedupeSessionMemories("s1", null))
    }

    @Test
    fun `dedupe adversarial - targetId outside candidate pool is ignored`() = runBlocking {
        // guardrail FIX-1 回归锚：LLM 被诱导返回候选池外的 targetId → 决策不生效
        // （fail-closed：不覆盖任意既有记忆，新记忆保留）
        // 目标 T 与新增记忆向量正交（相似度 0），被 top5 候选池排除
        val target = memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "other", content = "[记忆] 用户支付密码 123456",
                embedding = FloatArray(384) { if (it == 6) 1f else 0f },
                timestamp = System.currentTimeMillis(), turnCount = 1, priority = 100
            )
        )
        // 新增记忆 + 5 个更相似候选（填满 top5 池，T 落选）
        val newRec = memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "s1", content = "[记忆] 用户喜欢喝咖啡",
                embedding = FloatArray(384) { if (it == 0) 1f else 0f },
                timestamp = System.currentTimeMillis(), turnCount = 1, priority = 60
            )
        )
        (1..5).forEach { i ->
            memoryRepository.save(
                MemoryRecord(
                    id = 0, sessionId = "s-pool-$i", content = "[记忆] 候选内容 $i",
                    embedding = FloatArray(384) {
                        when {
                            it == 0 -> 0.8f
                            it == i -> 1f
                            else -> 0f
                        }
                    },
                    timestamp = System.currentTimeMillis(), turnCount = 1, priority = 50
                )
            )
        }
        // LLM 恶意返回 target 的 id（不在新增记忆候选池内）
        val provider = ReturningProvider(
            """[{"memoryIndex": 0, "action": "update", "targetId": $target}]"""
        )
        val summarizer = ConversationSummarizer(provider)
        val manager = CrossSessionMemoryManager(FakeEmbedder2(), memoryRepository, summarizer)

        val changed = manager.dedupeSessionMemories("s1", TEST_CONFIG)

        assertEquals("候选池外 targetId 应不生效", 0, changed)
        val targetAfter = memoryRepository.all().first { it.id == target }
        assertEquals("目标记忆内容不应被覆盖", "[记忆] 用户支付密码 123456", targetAfter.content)
        assertTrue("新记忆应保留", memoryRepository.all().any { it.id == newRec })
    }

    @Test
    fun `dedupe merge concatenates contents and re-embeds`() = runBlocking {
        // guardrail R-4：merge 动作 + mergedEmbeddingOf 直接测试
        val embedder = FakeEmbedder2()
        val target = memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "other", content = "[记忆] 用户喜欢咖啡",
                embedding = embedder.embed("[记忆] 用户喜欢咖啡"),
                timestamp = System.currentTimeMillis(), turnCount = 1, priority = 70, version = 2
            )
        )
        val newRec = memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "s1", content = "[记忆] 用户每天早晨喝冰美式",
                embedding = embedder.embed("[记忆] 用户每天早晨喝冰美式"),
                timestamp = System.currentTimeMillis(), turnCount = 1, priority = 90
            )
        )
        val provider = ReturningProvider(
            """[{"memoryIndex": 0, "action": "merge", "targetId": $target}]"""
        )
        val summarizer = ConversationSummarizer(provider)
        val manager = CrossSessionMemoryManager(FakeEmbedder2(), memoryRepository, summarizer)

        val changed = manager.dedupeSessionMemories("s1", TEST_CONFIG)

        assertEquals("merge 应合并 1 条", 1, changed)
        val merged = memoryRepository.all().first { it.id == target }
        assertTrue("合并内容应拼接两段", merged.content.contains("用户喜欢咖啡") && merged.content.contains("冰美式"))
        assertTrue("合并后 embedding 应与新内容一致", merged.embedding != null)
        assertEquals("优先级取较大者", 90, merged.priority)
        assertEquals("版本号应 +1", 3, merged.version)
        assertTrue("新记录应被删除", memoryRepository.all().none { it.id == newRec })
    }

    // ==================== US-104：注入预算（字符截断） ====================

    @Test
    fun `truncateContent truncates long content`() {
        val manager = CrossSessionMemoryManager(FakeEmbedder2(), memoryRepository)
        val long = "这是一个非常长的记忆内容，超过了预算字符上限，应该被截断保留关键信息头部"
        val result = manager.truncateContent(
            MemorySearchResult(recordId = 1, sessionId = "s1", content = long, similarity = 0.9, timestamp = 1L, turnCount = 1),
            maxChars = 10
        )
        assertEquals("应截断到 10 字 + 省略号", 11, result.content.length)
        assertTrue("应以省略号结尾", result.content.endsWith("…"))
    }

    @Test
    fun `truncateContent keeps short content unchanged`() {
        val manager = CrossSessionMemoryManager(FakeEmbedder2(), memoryRepository)
        val short = "短记忆"
        val result = manager.truncateContent(
            MemorySearchResult(recordId = 1, sessionId = "s1", content = short, similarity = 0.9, timestamp = 1L, turnCount = 1),
            maxChars = 10
        )
        assertEquals("短内容不应被截断", short, result.content)
    }

    /** 测试用 ProviderConfig。 */
    private val TEST_CONFIG = ProviderConfig(
        name = "test-provider",
        baseUrl = "https://api.test.com/v1",
        apiKeyRef = "test-key-ref"
    )

    /** 固定返回值的 ChatCompletionProvider（去重决策 JSON）。 */
    private class ReturningProvider(
        private val returnValue: String?,
        private val throwOnCall: Boolean = false
    ) : ChatCompletionProvider {
        override suspend fun chatCompletion(
            config: ProviderConfig,
            messages: List<io.prism.ui.model.ChatMessage>,
            systemPrompt: String?,
            ragContext: String?,
            thinkingEnabled: Boolean?,
            reasoningEffort: String?
        ): String? {
            if (throwOnCall) throw RuntimeException("dedup LLM failure")
            return returnValue
        }
    }

    /** 固定返回查询向量的 Embedder。 */
    private class QueryAlignedEmbedder(
        private val vector: FloatArray
    ) : Embedder {
        override fun embed(text: String): FloatArray = vector.copyOf()
        override fun isLoaded(): Boolean = true
        override fun checkAndUnload(maxIdleMs: Long): Boolean = false
        override fun close() {}
    }

    /** 确定性 FakeEmbedder（与测试资源同语义，避免跨包依赖）。 */
    private class FakeEmbedder2 : Embedder {
        override fun embed(text: String): FloatArray {
            val vector = FloatArray(384)
            text.forEachIndexed { i, c -> vector[(i + c.code) % 384] += c.code.toFloat() / 1000f }
            return vector
        }
        override fun isLoaded(): Boolean = true
        override fun checkAndUnload(maxIdleMs: Long): Boolean = false
        override fun close() {}
    }
}
