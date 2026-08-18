package io.prism.memory

import io.prism.data.MemoryRecord
import io.prism.data.MemoryRepository
import io.prism.data.MemorySearchResult
import io.prism.data.MyObjectBox
import io.prism.data.ProviderConfig
import io.prism.embedding.Embedder
import io.prism.embedding.FakeEmbedder
import io.prism.network.ChatCompletionProvider
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import io.objectbox.BoxStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * ac-verifier 补充边缘场景测试（TKN-M5-PHASEC-ACCEPTANCE-001）。
 *
 * 补充主 Agent 测试未覆盖的分支与边界：
 * - maxMemories 边界值（0 / -1 / >20，coerceIn 行为）
 * - 部分轮次对 embed 失败（部分成功部分失败）
 * - topK=1 最小边界
 * - 特殊字符内容（换行 / Unicode / 空内容）
 * - 仅 ASSISTANT 消息（无 USER → 0 对）
 * - TOOL 消息穿插（过滤后正常配对）
 * - 超长消息内容
 * - save 失败（非 embed 失败）
 * - formatMemoriesAsContext 单条结果
 */
class CrossSessionMemoryManagerSupplementaryTest {

    private lateinit var boxStore: BoxStore
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var embedder: Embedder
    private lateinit var manager: CrossSessionMemoryManager
    private lateinit var tempDir: java.io.File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("prism-memory-supp-test").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        memoryRepository = MemoryRepository(boxStore)
        embedder = FakeEmbedder()
        manager = CrossSessionMemoryManager(embedder, memoryRepository, retrievalThreshold = 0.0)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    // ==================== maxMemories 边界值（coerceIn 行为） ====================

    /**
     * 边界值：maxMemories=0 → coerceIn(1, 20)=1，应保存 1 条。
     */
    @Test
    fun saveSessionMemories_maxMemories_zero_coerces_to_1() = runBlocking {
        val messages = (1..3).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "问题$i 的详细分析", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "回答$i 的关键结论", i.toLong() * 1000 + 500)
            )
        }
        val count = manager.saveSessionMemories("session-1", messages, maxMemories = 0)
        assertEquals("maxMemories=0 应 coerceIn 到 1，保存 1 条", 1, count)
        assertEquals("仓库应有 1 条记录", 1L, memoryRepository.count())
    }

    /**
     * 边界值：maxMemories=-1 → coerceIn(1, 20)=1，应保存 1 条。
     */
    @Test
    fun saveSessionMemories_maxMemories_negative_coerces_to_1() = runBlocking {
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答：协程是轻量级线程", 2000L)
        )
        val count = manager.saveSessionMemories("session-1", messages, maxMemories = -1)
        assertEquals("maxMemories=-1 应 coerceIn 到 1，保存 1 条", 1, count)
    }

    /**
     * 边界值：maxMemories=100 → coerceIn(1, 20)=20，应最多保存 20 条。
     */
    @Test
    fun saveSessionMemories_maxMemories_exceeds_upper_bound_coerces_to_20() = runBlocking {
        val messages = (1..25).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "问题$i 的详细分析", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "回答$i 的关键结论", i.toLong() * 1000 + 500)
            )
        }
        val count = manager.saveSessionMemories("session-1", messages, maxMemories = 100)
        assertEquals("maxMemories=100 应 coerceIn 到 20，保存 20 条", 20, count)
        assertEquals("仓库应有 20 条记录", 20L, memoryRepository.count())
    }

    /**
     * 边界值：maxMemories=1（最小合法值），应只保存第一个轮次对。
     */
    @Test
    fun saveSessionMemories_maxMemories_1_saves_only_first_pair() = runBlocking {
        val messages = (1..3).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "问题$i 的详细分析", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "回答$i 的关键结论", i.toLong() * 1000 + 500)
            )
        }
        val count = manager.saveSessionMemories("session-1", messages, maxMemories = 1)
        assertEquals("maxMemories=1 应保存 1 条", 1, count)
        val records = memoryRepository.getBySession("session-1")
        assertTrue("应保存第一个轮次对", records[0].content.contains("问题1 的详细分析"))
    }

    // ==================== 部分失败场景 ====================

    /**
     * 部分轮次对 embed 失败：第一个成功，第二个失败，第三个成功。
     * 期望：返回 2（跳过失败的轮次）。
     */
    @Test
    fun saveSessionMemories_partial_embed_failure_skips_only_failed() = runBlocking {
        // 使用可控失败的 FakeEmbedder：第二次调用抛异常
        embedder = ControllableFakeEmbedder(failOnCallIndices = setOf(2))
        manager = CrossSessionMemoryManager(embedder, memoryRepository, retrievalThreshold = 0.0)
        val messages = (1..3).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "问题$i 的详细分析", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "回答$i 的关键结论", i.toLong() * 1000 + 500)
            )
        }
        val count = manager.saveSessionMemories("session-1", messages)
        assertEquals("应保存 2 条（跳过第 2 个轮次对）", 2, count)
        assertEquals("仓库应有 2 条记录", 2L, memoryRepository.count())
    }

    // ==================== topK 边界值 ====================

    /**
     * topK=1 最小边界：只返回 1 条最相似结果。
     */
    @Test
    fun retrieveRelevantMemories_topK_1_returns_single_result() = runBlocking {
        val messages = (1..5).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "话题$i 的深入探讨", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "话题$i 的核心结论", i.toLong() * 1000 + 500)
            )
        }
        manager.saveSessionMemories("session-1", messages)
        val results = manager.retrieveRelevantMemories("话题", topK = 1)
        assertEquals("topK=1 应返回 1 条结果", 1, results.size)
    }

    /**
     * topK 大于仓库记录数：返回不超过仓库实际记录数。
     */
    @Test
    fun retrieveRelevantMemories_topK_exceeds_record_count() = runBlocking {
        val messages = listOf(
            ChatMessage(1, Role.USER, "这是唯一待解决的问题", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答：这是唯一的关键答案", 2000L)
        )
        manager.saveSessionMemories("session-1", messages)
        val results = manager.retrieveRelevantMemories("问题", topK = 10)
        assertTrue("topK=10 但仓库仅 1 条，结果应 ≤ 1", results.size <= 1)
    }

    // ==================== 特殊字符内容 ====================

    /**
     * formatMemoriesAsContext 处理含换行符的内容。
     */
    @Test
    fun formatMemoriesAsContext_handles_multiline_content() {
        val results = listOf(
            MemorySearchResult(1, "s1", "[用户] 多行\n问题\n[助手] 多行\n回答", 0.9, 1000L, 1)
        )
        val result = manager.formatMemoriesAsContext(results)!!
        assertTrue("应包含前缀", result.startsWith("相关历史对话："))
        assertTrue("应保留换行内容", result.contains("多行\n问题"))
    }

    /**
     * formatMemoriesAsContext 处理含 Unicode 字符的内容。
     */
    @Test
    fun formatMemoriesAsContext_handles_unicode_content() {
        val results = listOf(
            MemorySearchResult(1, "s1", "[用户] 日本語テスト 🎌 [助手] 中文测试 ✅", 0.9, 1000L, 1)
        )
        val result = manager.formatMemoriesAsContext(results)!!
        assertTrue("应保留 Unicode", result.contains("日本語テスト"))
        assertTrue("应保留 emoji", result.contains("🎌"))
        assertTrue("应保留中文", result.contains("中文测试"))
    }

    /**
     * formatMemoriesAsContext 处理单条结果。
     */
    @Test
    fun formatMemoriesAsContext_single_result() {
        val results = listOf(
            MemorySearchResult(1, "s1", "[用户] 问题\n[助手] 回答", 0.95, 1000L, 1)
        )
        val result = manager.formatMemoriesAsContext(results)!!
        assertTrue("单条结果应编号 1.", result.contains("1. "))
        assertTrue("应包含前缀和内容", result == "相关历史对话：\n1. [用户] 问题\n[助手] 回答")
    }

    /**
     * formatMemoriesAsContext 处理多条结果（验证编号连续性和分隔）。
     */
    @Test
    fun formatMemoriesAsContext_multiple_results_separator() {
        val results = (1..5).map { i ->
            MemorySearchResult(i.toLong(), "s$i", "内容$i", 0.9 - i * 0.1, i.toLong() * 1000, i)
        }
        val result = manager.formatMemoriesAsContext(results)!!
        for (i in 1..5) {
            assertTrue("应包含编号 $i.", result.contains("$i. 内容$i"))
        }
    }

    // ==================== 仅 ASSISTANT 消息 ====================

    /**
     * 仅 ASSISTANT 消息（无 USER）→ filterKeyMessages 保留，但 groupIntoTurnPairs 无法配对 → 0 对。
     */
    @Test
    fun saveSessionMemories_only_assistant_messages_returns_zero() = runBlocking {
        val messages = listOf(
            ChatMessage(1, Role.ASSISTANT, "欢迎使用 Prism 智能助手", 1000L),
            ChatMessage(2, Role.ASSISTANT, "继续之前的对话分析", 2000L)
        )
        val count = manager.saveSessionMemories("session-1", messages)
        assertEquals("仅 ASSISTANT 消息应返回 0", 0, count)
        assertEquals("仓库不应有记录", 0L, memoryRepository.count())
    }

    // ==================== TOOL 消息穿插 ====================

    /**
     * TOOL 消息穿插在 USER-ASSISTANT 之间：应被过滤，USER-ASSISTANT 正常配对。
     */
    @Test
    fun saveSessionMemories_tool_messages_interleaved_filtered() = runBlocking {
        val messages = listOf(
            ChatMessage(1, Role.USER, "如何配置 MCP 服务器？", 1000L),
            ChatMessage(2, Role.TOOL, "工具结果", 1500L),
            ChatMessage(3, Role.ASSISTANT, "MCP 配置在设置页面完成", 2000L),
            ChatMessage(4, Role.USER, "Prism 支持哪些功能？", 3000L),
            ChatMessage(5, Role.ASSISTANT, "Prism 支持多种模型和工具", 4000L)
        )
        val count = manager.saveSessionMemories("session-1", messages)
        assertEquals("TOOL 穿插应被过滤，正常配对 2 组", 2, count)
    }

    // ==================== 超长消息内容 ====================

    /**
     * 超长消息内容（10000 字符）：应正常处理不崩溃。
     */
    @Test
    fun saveSessionMemories_very_long_content() = runBlocking {
        val longContent = "A".repeat(10000)
        val messages = listOf(
            ChatMessage(1, Role.USER, longContent, 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答：这是超长内容的回复", 2000L)
        )
        val count = manager.saveSessionMemories("session-1", messages)
        assertEquals("超长内容应正常保存 1 条", 1, count)
        val records = memoryRepository.getBySession("session-1")
        assertTrue("内容应完整保留", records[0].content.contains(longContent))
    }

    /**
     * 超长消息检索：超长查询消息应正常处理。
     */
    @Test
    fun retrieveRelevantMemories_very_long_query() = runBlocking {
        val messages = listOf(
            ChatMessage(1, Role.USER, "Kotlin 协程", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答：协程的基本介绍", 2000L)
        )
        manager.saveSessionMemories("session-1", messages)
        val longQuery = "Kotlin".repeat(1000)
        val results = manager.retrieveRelevantMemories(longQuery, topK = 3)
        assertTrue("超长查询应返回结果（不崩溃）", results.isNotEmpty())
    }

    // ==================== filterKeyMessages 补充 ====================

    /**
     * filterKeyMessages：全部为空 content 消息 → 返回空列表。
     */
    @Test
    fun filterKeyMessages_all_empty_content_returns_empty() {
        val messages = listOf(
            ChatMessage(1, Role.USER, "", 1000L),
            ChatMessage(2, Role.ASSISTANT, "", 2000L),
            ChatMessage(3, Role.USER, "  ", 3000L)
        )
        val filtered = manager.filterKeyMessages(messages)
        assertTrue("全部空 content 应返回空列表", filtered.isEmpty())
    }

    /**
     * filterKeyMessages：TOOL + 空 content 混合 → 仅保留非空 USER/ASSISTANT。
     */
    @Test
    fun filterKeyMessages_mixed_tool_and_empty() {
        val messages = listOf(
            ChatMessage(1, Role.USER, "这是一个有效的测试问题", 1000L),
            ChatMessage(2, Role.TOOL, "工具结果", 2000L),
            ChatMessage(3, Role.ASSISTANT, "", 3000L),
            ChatMessage(4, Role.ASSISTANT, "回答：这是一个有效的回复", 4000L)
        )
        val filtered = manager.filterKeyMessages(messages)
        assertEquals("应保留 2 条（有效 USER + 有效 ASSISTANT）", 2, filtered.size)
    }

    // ==================== groupIntoTurnPairs 补充 ====================

    /**
     * groupIntoTurnPairs：USER 后跟 TOOL（已被 filter 过滤，但直接调用 internal 函数测试防御性）。
     * TOOL 在 groupIntoTurnPairs 中不是 USER 也不是 ASSISTANT，while 循环 j++ 跳过。
     */
    @Test
    fun groupIntoTurnPairs_tool_between_user_assistant() {
        // 注意：此测试直接调用 internal 函数，模拟 filterKeyMessages 未过滤的情况
        // TOOL 角色不在 USER/ASSISTANT 中，while 循环 j++ 跳过
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.TOOL, "工具结果", 2000L),
            ChatMessage(3, Role.ASSISTANT, "回答：协程是轻量级线程", 3000L)
        )
        val pairs = manager.groupIntoTurnPairs(messages)
        assertEquals("TOOL 穿插时应配对 USER-ASSISTANT", 1, pairs.size)
        assertEquals("什么是 Kotlin 协程？", pairs[0].first.content)
        assertEquals("回答：协程是轻量级线程", pairs[0].second.content)
    }

    /**
     * groupIntoTurnPairs：全部为 ASSISTANT 消息 → 0 对。
     */
    @Test
    fun groupIntoTurnPairs_all_assistants_returns_empty() {
        val messages = listOf(
            ChatMessage(1, Role.ASSISTANT, "回答：这是第一条回复", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答：这是第二条回复", 2000L)
        )
        val pairs = manager.groupIntoTurnPairs(messages)
        assertTrue("全部 ASSISTANT 应返回空列表", pairs.isEmpty())
    }

    // ==================== formatTurnPair 补充 ====================

    /**
     * formatTurnPair：空 content 的消息（虽然 filter 会过滤，但直接测试纯函数）。
     */
    @Test
    fun formatTurnPair_empty_content() {
        val user = ChatMessage(1, Role.USER, "", 1000L)
        val assistant = ChatMessage(2, Role.ASSISTANT, "", 2000L)
        val formatted = manager.formatTurnPair(user to assistant)
        assertTrue("应包含标签", formatted.contains("[用户]"))
        assertTrue("应包含标签", formatted.contains("[助手]"))
    }

    // ==================== 集成场景补充 ====================

    /**
     * 集成：多会话保存后检索，验证跨会话记忆能被检索到。
     *
     * 注：FakeEmbedder 基于字符哈希生成向量，不保证语义相似性排序正确。
     * 本测试仅验证检索机制可用（返回结果且为有效片段），不验证语义排序。
     */
    @Test
    fun multiple_sessions_save_then_retrieve() = runBlocking {
        // 会话 1：Kotlin 话题
        manager.saveSessionMemories("s1", listOf(
            ChatMessage(1, Role.USER, "Kotlin 协程怎么用", 1000L),
            ChatMessage(2, Role.ASSISTANT, "使用 launch", 2000L)
        ))
        // 会话 2：Python 话题
        manager.saveSessionMemories("s2", listOf(
            ChatMessage(3, Role.USER, "Python 异步编程", 3000L),
            ChatMessage(4, Role.ASSISTANT, "使用 asyncio", 4000L)
        ))
        // 检索：FakeEmbedder 不保证语义排序，仅验证检索机制可用
        val results = manager.retrieveRelevantMemories("Kotlin", topK = 3)
        assertTrue("应检索到结果（跨会话记忆可用）", results.isNotEmpty())
        assertTrue("结果应 ≤ topK", results.size <= 3)
        // 每条结果应是有效片段（含 [用户]/[助手] 标签）
        results.forEach { result ->
            assertTrue("每条结果应是格式化的轮次对", result.content.contains("[用户]"))
            assertTrue("每条结果应是格式化的轮次对", result.content.contains("[助手]"))
        }
    }

    /**
     * 集成：保存→检索→格式化完整链路，验证格式正确性。
     */
    @Test
    fun save_retrieve_format_full_chain() = runBlocking {
        val messages = (1..3).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "话题$i 讨论内容", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "话题$i 回答内容", i.toLong() * 1000 + 500)
            )
        }
        manager.saveSessionMemories("session-1", messages)

        val results = manager.retrieveRelevantMemories("话题", topK = 2)
        assertEquals("应检索到 2 条", 2, results.size)

        val context = manager.formatMemoriesAsContext(results)
        assertNotNull("应返回非 null", context)
        assertTrue("应以前缀开头", context!!.startsWith("相关历史对话："))
        assertTrue("应包含编号 1.", context.contains("1. "))
        assertTrue("应包含编号 2.", context.contains("2. "))
        assertTrue("不应包含编号 3.", !context.contains("3. "))
    }

    /**
     * 集成：空仓库检索后格式化 → null（无记忆需注入）。
     */
    @Test
    fun empty_repo_retrieve_then_format_returns_null() = runBlocking {
        val results = manager.retrieveRelevantMemories("任何问题", topK = 3)
        assertTrue("空仓库检索应返回空列表", results.isEmpty())
        val context = manager.formatMemoriesAsContext(results)
        assertNull("空结果格式化应返回 null", context)
    }

    /**
     * 集成：防污染验证 — topK=1 只返回 1 条，不返回全部历史。
     */
    @Test
    fun anti_pollution_topK_1_returns_only_one_fragment() = runBlocking {
        val messages = (1..10).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "话题$i 的极限测试", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "回答$i 的极限测试", i.toLong() * 1000 + 500)
            )
        }
        manager.saveSessionMemories("session-1", messages)
        assertEquals("仓库应有 10 条", 10L, memoryRepository.count())

        val results = manager.retrieveRelevantMemories("话题", topK = 1)
        assertEquals("防污染：topK=1 只返回 1 条", 1, results.size)
        // 单条结果不应包含其他会话内容
        val returnedContent = results[0].content
        val allContents = memoryRepository.getBySession("session-1").map { it.content }
        val otherContents = allContents.filter { it != returnedContent }
        // 返回的内容只是其中一个片段，不是全部历史拼接
        otherContents.forEach { other ->
            assertTrue(
                "防污染：返回结果不应包含其他片段内容",
                !returnedContent.contains(other)
            )
        }
    }

    // ==================== UXR9 US-904 AC-2：LLM 摘要入库（失败降级规则抽取） ====================

    @Test
    fun `saveSessionMemories with summarizer stores single summary record`() = runBlocking {
        val completionProvider = FakeCompletionProvider("用户询问了 Kotlin 协程的基础概念")
        val summarizer = ConversationSummarizer(completionProvider)
        val summaryManager = CrossSessionMemoryManager(embedder, memoryRepository, summarizer)
        // Q-MED-3 门槛：重要轮次 ≥ MIN_SUMMARY_TURNS(3) 才触发 LLM 摘要
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "协程是轻量级线程，支持挂起", 2000L),
            ChatMessage(3, Role.USER, "Kotlin 的挂起函数如何工作？", 3000L),
            ChatMessage(4, Role.ASSISTANT, "挂起函数可在不阻塞线程的情况下暂停", 4000L),
            ChatMessage(5, Role.USER, "Flow 和协程的区别是什么？", 5000L),
            ChatMessage(6, Role.ASSISTANT, "Flow 是冷数据流，协程是并发原语", 6000L)
        )
        val saved = summaryManager.saveSessionMemories("s1", messages, providerConfig = TEST_CONFIG)
        assertEquals("摘要模式应保存 1 条摘要记录", 1, saved)
        val records = memoryRepository.getBySession("s1")
        assertEquals("仓库应只有 1 条摘要记录", 1, records.size)
        assertTrue("记录应以摘要前缀开头", records[0].content.startsWith(CrossSessionMemoryManager.MEMORY_SUMMARY_PREFIX))
        assertTrue("记录应含摘要文本", records[0].content.contains("Kotlin 协程"))
        assertEquals("summarizer 应被调用 1 次", 1, completionProvider.callCount)
    }

    @Test
    fun `saveSessionMemories with fewer than MIN_SUMMARY_TURNS skips summarizer`() = runBlocking {
        // Q-MED-3（guardrail TKN-UXR9-GUARDRAIL-003）：重要轮次 < 3 不触发 LLM 摘要，
        // 直接逐对存储（BYOK 成本保护）
        val completionProvider = FakeCompletionProvider("不应被调用")
        val summarizer = ConversationSummarizer(completionProvider)
        val summaryManager = CrossSessionMemoryManager(embedder, memoryRepository, summarizer)
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "协程是轻量级线程", 2000L),
            ChatMessage(3, Role.USER, "好的，谢谢", 3000L),
            ChatMessage(4, Role.ASSISTANT, "不客气", 4000L)
        )
        // 仅 1 个重要轮次（第 3/4 条为寒暄被过滤）→ 不触发摘要，逐对存储 1 条
        val saved = summaryManager.saveSessionMemories("s1", messages, providerConfig = TEST_CONFIG)
        assertEquals("门槛内应逐对存储 1 条", 1, saved)
        assertEquals("不应触发 LLM 摘要", 0, completionProvider.callCount)
        val records = memoryRepository.getBySession("s1")
        assertEquals("仓库应 1 条轮次对", 1, records.size)
        assertFalse("记录不应是摘要前缀", records[0].content.startsWith(CrossSessionMemoryManager.MEMORY_SUMMARY_PREFIX))
    }

    @Test
    fun `saveSessionMemories summary failure falls back to pair storage`() = runBlocking {
        // UXR11 U5（ADR-033）语义：LLM 记忆抽取**抛异常**（调用失败）→ 降级为规则抽取（逐对存储）。
        // 注意与「LLM 成功但返回空/无」区分：后者（无价值记忆）→ return 0 不落库。
        val completionProvider = FakeCompletionProvider(null, throwOnCall = true)
        val summarizer = ConversationSummarizer(completionProvider)
        val summaryManager = CrossSessionMemoryManager(embedder, memoryRepository, summarizer)
        // Q-MED-3 门槛：≥3 重要轮次才触发抽取 → 抽取失败后逐对存储 3 条
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "协程是轻量级线程", 2000L),
            ChatMessage(3, Role.USER, "挂起函数怎么理解？", 3000L),
            ChatMessage(4, Role.ASSISTANT, "挂起函数不阻塞线程", 4000L),
            ChatMessage(5, Role.USER, "Flow 与协程区别？", 5000L),
            ChatMessage(6, Role.ASSISTANT, "Flow 是冷数据流", 6000L)
        )
        val saved = summaryManager.saveSessionMemories("s1", messages, providerConfig = TEST_CONFIG)
        assertEquals("抽取失败应降级为逐对存储，保存 3 条轮次对", 3, saved)
        val records = memoryRepository.getBySession("s1")
        assertEquals("降级应存 3 条轮次对", 3, records.size)
        assertFalse("降级记录不应是记忆前缀", records[0].content.startsWith(CrossSessionMemoryManager.MEMORY_SUMMARY_PREFIX))
    }

    @Test
    fun `saveSessionMemories without providerConfig skips summary`() = runBlocking {
        val completionProvider = FakeCompletionProvider("不应被调用")
        val summarizer = ConversationSummarizer(completionProvider)
        val summaryManager = CrossSessionMemoryManager(embedder, memoryRepository, summarizer)
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "协程是轻量级线程", 2000L)
        )
        summaryManager.saveSessionMemories("s1", messages) // 不传 providerConfig
        assertEquals("未传 providerConfig 不应调用 LLM", 0, completionProvider.callCount)
        assertEquals("应逐对存储 1 条", 1L, memoryRepository.count())
    }

    // ==================== UXR9 US-904 AC-3：检索相似度阈值过滤 ====================

    @Test
    fun `retrieval threshold filters low similarity memories`() = runBlocking {
        val qEmbedder = FakeEmbedder()
        val query = "话题相关"
        val queryVector = qEmbedder.embed(query)
        // 相关记忆：与查询向量完全一致 → 相似度 1.0 → 通过 0.4 阈值
        memoryRepository.save(
            MemoryRecord(sessionId = "s1", content = "相关记忆A", embedding = queryVector, timestamp = 1000L, turnCount = 1)
        )
        // 无关记忆：查询向量取反 → 相似度 -1.0 → 被 0.4 阈值过滤
        memoryRepository.save(
            MemoryRecord(
                sessionId = "s1", content = "无关记忆B",
                embedding = FloatArray(384) { -queryVector[it] },
                timestamp = 2000L, turnCount = 2
            )
        )
        val thresholdManager = CrossSessionMemoryManager(qEmbedder, memoryRepository) // 默认阈值 0.4
        val results = thresholdManager.retrieveRelevantMemories(query, topK = 10)
        assertEquals("低相似度记忆应被阈值过滤", 1, results.size)
        assertTrue("应命中相关记忆", results[0].content.contains("相关记忆A"))
        assertTrue("命中记忆相似度应 ≥ 阈值 0.4", results[0].similarity >= CrossSessionMemoryManager.MEMORY_RETRIEVAL_THRESHOLD)
    }

    // ==================== UXR9 guardrail 修复验证（TKN-UXR9-GUARDRAIL-001） ====================

    @Test
    fun `summary save failure falls through to pair storage`() = runBlocking {
        // M-2（CWE-754）：摘要 LLM 成功但摘要记录入库失败（第 1 次 embed 抛异常）→
        // 必须降级为逐对存储（不得 return 0 丢弃全部记忆）
        val completionProvider = FakeCompletionProvider("用户询问了 Kotlin 协程")
        val summarizer = ConversationSummarizer(completionProvider)
        val failingEmbedder = ControllableFakeEmbedder(failOnCallIndices = setOf(1)) // 摘要 embed 失败
        val summaryManager = CrossSessionMemoryManager(failingEmbedder, memoryRepository, summarizer)
        // Q-MED-3 门槛：≥3 重要轮次才触发摘要 → 摘要 embed（第 1 次）失败 → 降级逐对存储 3 条
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "协程是轻量级线程", 2000L),
            ChatMessage(3, Role.USER, "挂起函数怎么理解？", 3000L),
            ChatMessage(4, Role.ASSISTANT, "挂起函数不阻塞线程", 4000L),
            ChatMessage(5, Role.USER, "Flow 与协程区别？", 5000L),
            ChatMessage(6, Role.ASSISTANT, "Flow 是冷数据流", 6000L)
        )
        val saved = summaryManager.saveSessionMemories("s1", messages, providerConfig = TEST_CONFIG)
        assertEquals("摘要入库失败应降级为逐对存储，保存 3 条轮次对", 3, saved)
        val records = memoryRepository.getBySession("s1")
        assertEquals("应存 3 条轮次对", 3, records.size)
        assertFalse("降级记录不应是摘要前缀", records[0].content.startsWith(CrossSessionMemoryManager.MEMORY_SUMMARY_PREFIX))
        assertTrue("降级记录应为轮次对格式", records[0].content.contains("[助手]"))
    }

    @Test
    fun `filterKeyMessages excludes system notices`() {
        // M-3（CWE-20）：系统提示消息（isSystemNotice=true）不得进入 L2 记忆链路
        val messages = listOf(
            ChatMessage(1, Role.USER, "⚠️ 图片编码失败，请重试", 1000L).copy(isSystemNotice = true),
            ChatMessage(2, Role.USER, "什么是 Kotlin 协程？", 2000L),
            ChatMessage(3, Role.ASSISTANT, "协程是轻量级线程", 3000L),
            ChatMessage(4, Role.TOOL, "tool", 4000L)
        )
        val filtered = manager.filterKeyMessages(messages)
        assertEquals("系统提示 + TOOL 应被排除，仅保留 2 条", 2, filtered.size)
        assertTrue("应保留正常用户消息", filtered.none { it.isSystemNotice })
        assertEquals(listOf(2L, 3L), filtered.map { it.id })
    }

    companion object {
        /** 摘要 LLM 调用的 Provider 配置（AC-2 测试）。 */
        private val TEST_CONFIG = ProviderConfig(
            name = "test-provider",
            baseUrl = "https://api.test.com/v1",
            apiKeyRef = "test-key-ref",
            models = listOf("test-model"),
            headers = emptyMap()
        )
    }
}

/** 测试用 [ChatCompletionProvider] fake（AC-2 摘要路径）。 */
private class FakeCompletionProvider(
    private val returnValue: String? = null,
    private val throwOnCall: Boolean = false
) : ChatCompletionProvider {
    var callCount: Int = 0
        private set

    override suspend fun chatCompletion(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        ragContext: String?,
        thinkingEnabled: Boolean?,
        reasoningEffort: String?
    ): String? {
        callCount++
        if (throwOnCall) throw RuntimeException("test LLM failure")
        return returnValue
    }
}

/**
 * 可控失败的 FakeEmbedder —— 指定调用序号抛异常。
 */
private class ControllableFakeEmbedder(
    private val failOnCallIndices: Set<Int>
) : io.prism.embedding.Embedder {

    private var callIndex = 0

    override fun embed(text: String): FloatArray {
        callIndex++
        if (callIndex in failOnCallIndices) {
            throw io.prism.embedding.EmbeddingException(
                io.prism.embedding.EmbeddingException.Stage.INFERENCE,
                "Controlled failure on call $callIndex"
            )
        }
        val vector = FloatArray(384)
        if (text.isEmpty()) return vector
        text.forEachIndexed { charIndex, char ->
            val dimIndex = (charIndex + char.code) % 384
            vector[dimIndex] = vector[dimIndex] + char.code.toFloat() / 1000f
        }
        return vector
    }

    override fun isLoaded(): Boolean = true
    override fun checkAndUnload(maxIdleMs: Long): Boolean = false
    override fun close() {}
}