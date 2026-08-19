package io.prism.memory

import androidx.datastore.preferences.core.emptyPreferences
import io.prism.data.MemoryRecord
import io.prism.data.MemoryRepository
import io.prism.data.MemorySearchResult
import io.prism.data.MyObjectBox
import io.prism.data.ProviderConfig
import io.prism.embedding.Embedder
import io.prism.network.ChatCompletionProvider
import io.prism.security.FakePreferenceDataStore
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import io.objectbox.BoxStore
import kotlinx.coroutines.flow.first
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
 * v1 记忆深度优化批次1（US-101~104）验收补充测试（ac-verifier）。
 *
 * 补齐验收任务要求的极端/边缘场景：
 * - soft-decay 阈值边界（score 恰好 = 阈值 / 低于阈值）
 * - 容量回收时 priority 相同但 timestamp 不同的排序（tie-break）
 * - 去重候选池为空 / targetId 引用不存在记录（fail-closed）
 * - JSON 解析特殊字符（引号/反斜杠）
 * - parseDedupDecisions 负索引
 * - MemoryConfigRepository 新增 7 项配置可读写（US-103 AC-5）
 * - 混合检索性能（1000 条规模 P95 ≤ 50ms，US-102 AC-3）
 */
class CrossSessionMemoryV1EdgeSupplementTest {

    private lateinit var boxStore: BoxStore
    private lateinit var memoryRepository: MemoryRepository

    @Before
    fun setUp() {
        val tempDir = Files.createTempDirectory("prism-v1-edge-supp").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        memoryRepository = MemoryRepository(boxStore)
    }

    @After
    fun tearDown() {
        boxStore.close()
    }

    // ==================== US-103 AC-3：soft-decay 阈值边界 ====================

    @Test
    fun `soft decay keeps memory when score exactly equals threshold`() = runBlocking {
        val queryVector = FloatArray(384) { if (it == 0) 1f else 0f }
        val now = System.currentTimeMillis()
        // priority=20、age=0（时间戳置为未来，coerceAtLeast(0L) 保证 age=0）、accessCount=0
        // → recallScore = 20 * exp(0) * (1+0) = 20.0 = 阈值（>= 语义保留）
        memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "s1", content = "边界记忆恰好等于阈值",
                embedding = queryVector, timestamp = now + 1000L, turnCount = 1, priority = 20
            )
        )
        val config = MemoryConfigRepository(FakePreferenceDataStore(emptyPreferences()))
        val manager = CrossSessionMemoryManager(
            embedder = QueryAlignedEmbedder(queryVector),
            memoryRepository = memoryRepository,
            retrievalThreshold = 0.4,
            memoryConfig = config
        )
        val results = manager.retrieveRelevantMemories("查询", topK = 5)
        // score = 20.0 >= 20.0 → 应注入
        assertEquals("score==threshold 应保留注入（>= 语义）", 1, results.size)
        assertEquals("注入的应为边界记忆", "边界记忆恰好等于阈值", results[0].content)
    }

    @Test
    fun `computeRecallScore exactly at threshold boundary`() {
        val manager = CrossSessionMemoryManager(FakeEmbedder2(), memoryRepository)
        val now = System.currentTimeMillis()
        // 构造 score 精确等于 20.0：priority=20、age=0、accessCount=0
        val score = manager.computeRecallScore(
            priority = 20, accessCount = 0, timestamp = now, now = now,
            lambda = MemoryConfigRepository.DEFAULT_DECAY_LAMBDA,
            alpha = MemoryConfigRepository.DEFAULT_DECAY_ALPHA
        )
        val threshold = MemoryConfigRepository.DEFAULT_DECAY_THRESHOLD
        assertTrue(
            "score 应恰好等于阈值（≥ 判定保留）",
            score >= threshold
        )
        // 边界下方：priority=19 → score=19 < 20 → 应排除
        val below = manager.computeRecallScore(
            priority = 19, accessCount = 0, timestamp = now, now = now,
            lambda = MemoryConfigRepository.DEFAULT_DECAY_LAMBDA,
            alpha = MemoryConfigRepository.DEFAULT_DECAY_ALPHA
        )
        assertTrue("score 应低于阈值", below < threshold)
    }

    // ==================== US-103 AC-4：容量回收 tie-break（priority 相同，timestamp 不同） ====================

    @Test
    fun `capacity eviction with same priority evicts oldest first`() {
        val now = System.currentTimeMillis()
        // 4 条 priority 全相同（50），timestamp 从旧到新：t0 < t1 < t2 < t3
        val ids = (1..4).map { i ->
            memoryRepository.save(
                MemoryRecord(
                    id = 0, sessionId = "s$i", content = "同分记忆$i",
                    embedding = FloatArray(384) { if (it == 0) 1f else 0f },
                    timestamp = now - (4 - i) * 1000L, turnCount = 1, priority = 50
                )
            )
        }
        assertEquals(4L, memoryRepository.count())
        val evicted = memoryRepository.evictIfOverLimit(2)
        assertEquals("应回收 2 条", 2L, evicted)
        // priority 相同 → 按 timestamp 升序回收最旧的 2 条（id 分配序 = 插入序 = 时间序）
        val remaining = memoryRepository.all().map { it.id }.toSet()
        assertFalse("最旧记录应被回收", remaining.contains(ids[0]))
        assertFalse("次旧记录应被回收", remaining.contains(ids[1]))
        assertTrue("最新两条应保留", remaining.contains(ids[2]) && remaining.contains(ids[3]))
    }

    // ==================== US-103 AC-1/AC-2：去重候选池为空 / targetId 引用不存在记录 ====================

    @Test
    fun `dedupe with empty candidate pool treats update as no-op fail-closed`() = runBlocking {
        // 新增记忆内容为纯拼音/无命中 token（与任何已有记忆向量都不相似）→ 候选池为空
        val newRec = memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "s1", content = "[记忆] 用户喜欢骑自行车",
                embedding = FloatArray(384) { if (it == 7) 1f else 0f },
                timestamp = System.currentTimeMillis(), turnCount = 1, priority = 60
            )
        )
        // LLM 幻觉返回一个不存在的 targetId（99999）
        val provider = ReturningProvider(
            """[{"memoryIndex": 0, "action": "update", "targetId": 99999}]"""
        )
        val summarizer = ConversationSummarizer(provider)
        val manager = CrossSessionMemoryManager(FakeEmbedder2(), memoryRepository, summarizer)

        val changed = manager.dedupeSessionMemories("s1", TEST_CONFIG)

        assertEquals("targetId 不在候选池（空池）应不生效", 0, changed)
        assertTrue("新记忆应保留", memoryRepository.all().any { it.id == newRec })
    }

    @Test
    fun `dedupe with no existing records stores all`() = runBlocking {
        // 库中只有本会话 1 条记忆（无其他候选），候选池为空
        memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "s1", content = "[记忆] 用户喜欢喝美式咖啡",
                timestamp = System.currentTimeMillis(), turnCount = 1, priority = 80
            )
        )
        // LLM 对所有新记忆返回 store
        val provider = ReturningProvider("""[{"memoryIndex": 0, "action": "store"}]""")
        val summarizer = ConversationSummarizer(provider)
        val manager = CrossSessionMemoryManager(FakeEmbedder2(), memoryRepository, summarizer)

        val changed = manager.dedupeSessionMemories("s1", TEST_CONFIG)

        assertEquals("store 不产生变更", 0, changed)
        assertEquals("新记忆应保留", 1L, memoryRepository.count())
    }

    // ==================== US-101 AC-1：JSON 解析特殊字符 ====================

    @Test
    fun `parseMemories handles json special chars in content`() = runBlocking {
        // content 内含转义引号与反斜杠
        val raw = """[{"content": "用户说\"好的\"使用\\反斜杠路径", "type": "persona", "priority": 80}]"""
        val provider = FakeCompletionProvider(raw)
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(ChatMessage(1, Role.USER, "test", 1000L))
        val result = summarizer.extractMemories(messages, TEST_CONFIG)
        assertEquals(1, result?.size)
        assertEquals("用户说\"好的\"使用\\反斜杠路径", result?.get(0)?.content)
    }

    @Test
    fun `parseMemories json object wrapper falls to line parse without crash`() = runBlocking {
        // C-6（guardrail 低危已知项，未修复）：JSON 对象包裹 {"memories": [...]} 落入行式解析，
        // 原始 JSON 行被当作一条记忆（数据质量问题，非安全/非 AC 要求）。
        // 此处记录实际行为：不崩溃、且单条内容截断在上限内。
        val raw = """{"memories": [{"content": "用户喜欢 Kotlin"}]}"""
        val result = ConversationSummarizer(FakeCompletionProvider("[]")).parseMemories(raw)
        // 行式解析产出一条：原始 JSON 行（首字符为 '{'）
        assertEquals("对象包裹应落入行式降级产出一条", 1, result.size)
        assertTrue("降级内容为首字符 { 的原始 JSON 行", result[0].content.trimStart().startsWith("{"))
        assertTrue("内容长度受单条上限约束", result[0].content.length <= ConversationSummarizer.MAX_MEMORY_ITEM_CHARS)
    }

    // ==================== US-103 AC-1：parseDedupDecisions 负索引 ====================

    @Test
    fun `parseDedupDecisions drops negative memoryIndex`() {
        val summarizer = ConversationSummarizer(FakeCompletionProvider("[]"))
        val decisions = summarizer.parseDedupDecisions(
            """[{"memoryIndex": -1, "action": "skip", "targetId": 1}, {"memoryIndex": 0, "action": "store"}]""",
            batchSize = 1
        )
        assertEquals("负索引应被丢弃，仅保留合法决策", 1, decisions?.size)
        assertEquals(0, decisions?.get(0)?.memoryIndex)
    }

    @Test
    fun `parseDedupDecisions drops non numeric targetId`() {
        val summarizer = ConversationSummarizer(FakeCompletionProvider("[]"))
        val decisions = summarizer.parseDedupDecisions(
            """[{"memoryIndex": 0, "action": "update", "targetId": "abc"}]""",
            batchSize = 1
        )
        assertEquals(1, decisions?.size)
        assertEquals("非数字 targetId 应为 null（fail-closed 不越权）", null, decisions?.get(0)?.targetId)
    }

    // ==================== US-103 AC-5：MemoryConfigRepository 新增配置可读写 ====================

    @Test
    fun `new config items read write roundtrip`() = runBlocking {
        val repo = MemoryConfigRepository(FakePreferenceDataStore(emptyPreferences()))
        // 默认值
        assertEquals(true, repo.isDedupEnabled())
        assertEquals(10000, repo.getMemoryCapacity())
        assertEquals(0.01, repo.getDecayLambda(), 0.0001)
        assertEquals(0.5, repo.getDecayAlpha(), 0.0001)
        assertEquals(20.0, repo.getDecayThreshold(), 0.0001)
        assertEquals(5, repo.getInjectionMaxResults())
        assertEquals(200, repo.getInjectionMaxChars())
        // 写后读回
        repo.setDedupEnabled(false)
        assertEquals(false, repo.isDedupEnabled())
        repo.setMemoryCapacity(500)
        assertEquals(500, repo.getMemoryCapacity())
        repo.setDecayLambda(0.05)
        assertEquals(0.05, repo.getDecayLambda(), 0.0001)
        repo.setDecayAlpha(1.0)
        assertEquals(1.0, repo.getDecayAlpha(), 0.0001)
        repo.setDecayThreshold(30.0)
        assertEquals(30.0, repo.getDecayThreshold(), 0.0001)
        repo.setInjectionMaxResults(3)
        assertEquals(3, repo.getInjectionMaxResults())
        repo.setInjectionMaxChars(100)
        assertEquals(100, repo.getInjectionMaxChars())
    }

    @Test
    fun `new config items reject invalid values`() = runBlocking {
        val repo = MemoryConfigRepository(FakePreferenceDataStore(emptyPreferences()))
        val thrown = mutableListOf<Boolean>()
        // 容量 ≤ 0
        try { repo.setMemoryCapacity(0); thrown.add(false) } catch (e: IllegalArgumentException) { thrown.add(true) }
        // 注入条数 ≤ 0
        try { repo.setInjectionMaxResults(0); thrown.add(false) } catch (e: IllegalArgumentException) { thrown.add(true) }
        // 注入字符 ≤ 0
        try { repo.setInjectionMaxChars(0); thrown.add(false) } catch (e: IllegalArgumentException) { thrown.add(true) }
        // 衰减系数 < 0
        try { repo.setDecayLambda(-0.1); thrown.add(false) } catch (e: IllegalArgumentException) { thrown.add(true) }
        try { repo.setDecayAlpha(-1.0); thrown.add(false) } catch (e: IllegalArgumentException) { thrown.add(true) }
        try { repo.setDecayThreshold(-1.0); thrown.add(false) } catch (e: IllegalArgumentException) { thrown.add(true) }
        assertEquals("全部非法值应抛 IllegalArgumentException", listOf(true, true, true, true, true, true), thrown)
    }

    @Test
    fun `config repository defaults match production constants`() {
        assertEquals(MemoryConfigRepository.DEFAULT_DEDUP_ENABLED, true)
        assertEquals(MemoryConfigRepository.DEFAULT_MEMORY_CAPACITY, 10000)
        assertEquals(MemoryConfigRepository.DEFAULT_DECAY_LAMBDA, 0.01, 0.0001)
        assertEquals(MemoryConfigRepository.DEFAULT_DECAY_ALPHA, 0.5, 0.0001)
        assertEquals(MemoryConfigRepository.DEFAULT_DECAY_THRESHOLD, 20.0, 0.0001)
        assertEquals(MemoryConfigRepository.DEFAULT_INJECTION_MAX_RESULTS, 5)
        assertEquals(MemoryConfigRepository.DEFAULT_INJECTION_MAX_CHARS, 200)
    }

    // ==================== US-102 AC-3：混合检索性能（1000 条规模，P95 ≤ 50ms） ====================

    @Test
    fun `injection budget caps results at default 5`() = runBlocking {
        // US-104 AC-1：注入条数上限（默认 5）——即使 topK 更大、命中更多也仅注入 5 条
        val queryVector = FloatArray(384) { if (it == 0) 1f else 0f }
        val now = System.currentTimeMillis()
        (1..8).forEach { i ->
            memoryRepository.save(
                MemoryRecord(
                    id = 0, sessionId = "s$i", content = "预算测试记忆 $i Kotlin",
                    embedding = queryVector, timestamp = now, turnCount = i, priority = 80
                )
            )
        }
        val config = MemoryConfigRepository(FakePreferenceDataStore(emptyPreferences()))
        val manager = CrossSessionMemoryManager(
            embedder = QueryAlignedEmbedder(queryVector),
            memoryRepository = memoryRepository,
            retrievalThreshold = 0.4,
            memoryConfig = config
        )
        val results = manager.retrieveRelevantMemories("Kotlin", topK = 10)
        assertEquals("注入条数应被预算截断到 5", 5, results.size)
    }

    @Test
    fun `injection budget character truncation does not mutate library`() = runBlocking {
        // US-104 AC-2：截断为展示层，不影响库内容
        val queryVector = FloatArray(384) { if (it == 0) 1f else 0f }
        val now = System.currentTimeMillis()
        val longContent = "这是一条超过默认 200 字预算上限的超长记忆内容，用于验证展示层截断不影响库中原文完整性。".repeat(6)
        memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "s1", content = longContent,
                embedding = queryVector, timestamp = now, turnCount = 1, priority = 80
            )
        )
        val config = MemoryConfigRepository(FakePreferenceDataStore(emptyPreferences()))
        val manager = CrossSessionMemoryManager(
            embedder = QueryAlignedEmbedder(queryVector),
            memoryRepository = memoryRepository,
            retrievalThreshold = 0.4,
            memoryConfig = config
        )
        val results = manager.retrieveRelevantMemories("预算", topK = 5)
        assertEquals(1, results.size)
        assertTrue("注入内容应被截断（≤ 200 + 省略号）", results[0].content.length <= 201)
        assertEquals("库中原文应保持不变", longContent, memoryRepository.all().first().content)
    }

    @Test
    fun `retrieval accessCount increment does not bump mutationVersion`() = runBlocking {
        // US-102 AC-4：命中记忆 accessCount +1 但【不触发 FTS 重建】（mutationVersion 不变）
        val queryVector = FloatArray(384) { if (it == 0) 1f else 0f }
        memoryRepository.save(
            MemoryRecord(
                id = 0, sessionId = "s1", content = "用户喜欢 Kotlin 协程",
                embedding = queryVector, timestamp = System.currentTimeMillis(), turnCount = 1
            )
        )
        val v0 = memoryRepository.mutationVersion
        val manager = CrossSessionMemoryManager(
            embedder = QueryAlignedEmbedder(queryVector),
            memoryRepository = memoryRepository,
            retrievalThreshold = 0.4,
            keywordIndex = InMemoryMemoryKeywordIndex()
        )
        manager.retrieveRelevantMemories("Kotlin", topK = 5)
        val updated = memoryRepository.all().first()
        assertEquals("命中后 accessCount 应自增", 1L, updated.accessCount)
        assertEquals(
            "accessCount 自增不应递增 mutationVersion（不触发 FTS 重建）",
            v0, memoryRepository.mutationVersion
        )
    }

    @Test
    fun `hybrid retrieval p95 under 50ms with 1000 records`() = runBlocking {
        val now = System.currentTimeMillis()
        // 插入 1000 条记忆（oneHot 向量 + 中文内容）
        for (i in 0 until 1000) {
            memoryRepository.save(
                MemoryRecord(
                    id = 0, sessionId = "s$i",
                    content = "用户第 $i 条记忆：喜欢 Kotlin 协程与 Flow 编程模型",
                    embedding = FloatArray(384) { if (it == i % 384) 1f else 0f },
                    timestamp = now - i * 1000L, turnCount = i, priority = 50
                )
            )
        }
        assertEquals(1000L, memoryRepository.count())

        val queryVector = FloatArray(384) { if (it == 0) 1f else 0f }
        val keywordIndex = InMemoryMemoryKeywordIndex()
        val manager = CrossSessionMemoryManager(
            embedder = QueryAlignedEmbedder(queryVector),
            memoryRepository = memoryRepository,
            retrievalThreshold = 0.4,
            keywordIndex = keywordIndex
        )

        // 预热（触发 FTS/BM25 重建）
        repeat(3) { manager.retrieveRelevantMemories("Kotlin 协程", topK = 5) }

        val iters = 30
        val latenciesUs = LongArray(iters)
        var failures = 0
        repeat(iters) { i ->
            val start = System.nanoTime()
            try {
                val r = manager.retrieveRelevantMemories("Kotlin 协程", topK = 5)
                if (r.isEmpty()) failures++
            } catch (e: Exception) {
                failures++
            }
            latenciesUs[i] = (System.nanoTime() - start) / 1_000
        }

        latenciesUs.sort()
        val p95 = latenciesUs[iters * 95 / 100].coerceAtMost(latenciesUs[iters - 1])
        println(
            "PERF_BASELINE|op=retrieveRelevantMemories_hybrid|records=1000|iters=$iters|" +
                "min=${latenciesUs[0]}us|p50=${latenciesUs[iters / 2]}us|" +
                "p95=${p95}us|p99=${latenciesUs[iters * 99 / 100].coerceAtMost(latenciesUs[iters - 1])}us|" +
                "max=${latenciesUs[iters - 1]}us|failures=$failures"
        )
        assertTrue("混合检索 P95 应 ≤ 50ms（千条规模）", p95 <= 50_000)
        assertEquals("混合检索应无失败", 0, failures)
    }

    /** 测试用 ProviderConfig。 */
    private val TEST_CONFIG = ProviderConfig(
        name = "test-provider",
        baseUrl = "https://api.test.com/v1",
        apiKeyRef = "test-key-ref"
    )

    /** 固定返回值的 ChatCompletionProvider。 */
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
            if (throwOnCall) throw RuntimeException("LLM failure")
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

    /** 确定性 FakeEmbedder（基于文本内容生成向量）。 */
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

    /** 固定返回值的 ChatCompletionProvider（摘要抽取）。 */
    private class FakeCompletionProvider(
        private val returnValue: String?,
        private val throwOnCall: Boolean = false,
        private val throwCancellation: Boolean = false
    ) : ChatCompletionProvider {
        override suspend fun chatCompletion(
            config: ProviderConfig,
            messages: List<ChatMessage>,
            systemPrompt: String?,
            ragContext: String?,
            thinkingEnabled: Boolean?,
            reasoningEffort: String?
        ): String? {
            if (throwCancellation) throw kotlinx.coroutines.CancellationException("cancel")
            if (throwOnCall) throw RuntimeException("llm failure")
            return returnValue
        }
    }
}
