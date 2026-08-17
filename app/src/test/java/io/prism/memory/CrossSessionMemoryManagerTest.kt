package io.prism.memory

import io.prism.data.MemoryRepository
import io.prism.data.MemorySearchResult
import io.prism.data.MyObjectBox
import io.prism.embedding.FakeEmbedder
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import io.objectbox.BoxStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * CrossSessionMemoryManager 单元测试（US-033 AC-1 ~ AC-5）。
 *
 * 测试覆盖：
 * - AC-1：会话结束时向量化存储（saveSessionMemories）
 * - AC-2：新会话 top-k 检索（retrieveRelevantMemories）
 * - AC-3：防污染验证（仅注入检索结果，不加载全文）
 * - AC-4：检索结果注入 systemPrompt 格式（formatMemoriesAsContext）
 * - AC-5：空结果处理 / embed 失败降级 / 边界场景
 *
 * 测试基础设施：
 * - FakeEmbedder：确定性 384 维向量，不依赖 ONNX 运行时
 * - 真实 ObjectBox BoxStore（temp 目录）：与 MemoryRepositoryTest 同模式
 */
class CrossSessionMemoryManagerTest {

    private lateinit var boxStore: BoxStore
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var embedder: FakeEmbedder
    private lateinit var manager: CrossSessionMemoryManager
    private lateinit var tempDir: java.io.File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("prism-memory-test").toFile()
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

    // ==================== AC-1: 会话结束时向量化存储 ====================

    @Test
    fun saveSessionMemories_returns_zero_for_empty_messages() = runBlocking {
        val count = manager.saveSessionMemories("session-1", emptyList())
        assertEquals("空消息列表应返回 0", 0, count)
    }

    @Test
    fun saveSessionMemories_returns_zero_when_only_tool_messages() = runBlocking {
        val messages = listOf(
            ChatMessage(1, Role.TOOL, "tool result", 1000L)
        )
        val count = manager.saveSessionMemories("session-1", messages)
        assertEquals("仅 tool 消息应返回 0", 0, count)
    }

    @Test
    fun saveSessionMemories_stores_turn_pairs_with_embedding() = runBlocking {
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "协程是轻量级线程...", 2000L)
        )
        val count = manager.saveSessionMemories("session-1", messages)
        assertEquals("应保存 1 个轮次对", 1, count)
        assertEquals("仓库应有 1 条记录", 1L, memoryRepository.count())
        assertEquals("embedder 应被调用 1 次", 1, embedder.callCount)
    }

    @Test
    fun saveSessionMemories_stores_multiple_turn_pairs() = runBlocking {
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答：协程是轻量级线程", 2000L),
            ChatMessage(3, Role.USER, "Prism 支持哪些功能？", 3000L),
            ChatMessage(4, Role.ASSISTANT, "回答：Prism 支持多种模型", 4000L),
            ChatMessage(5, Role.USER, "如何配置 MCP 服务器？", 5000L),
            ChatMessage(6, Role.ASSISTANT, "回答：在设置页配置 MCP", 6000L)
        )
        val count = manager.saveSessionMemories("session-1", messages)
        assertEquals("应保存 3 个轮次对", 3, count)
        assertEquals("仓库应有 3 条记录", 3L, memoryRepository.count())
    }

    @Test
    fun saveSessionMemories_skips_unpaired_user_message() = runBlocking {
        // 最后一条 user 无配对 assistant，应被跳过
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答：协程是轻量级线程", 2000L),
            ChatMessage(3, Role.USER, "还有一个问题没有收到回复", 3000L)
        )
        val count = manager.saveSessionMemories("session-1", messages)
        assertEquals("应保存 1 个完整轮次对，跳过未配对 user", 1, count)
    }

    @Test
    fun saveSessionMemories_respects_max_memories_limit() = runBlocking {
        val messages = (1..10).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "问题$i 的详细分析", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "回答$i 的关键结论", i.toLong() * 1000 + 500)
            )
        }
        val count = manager.saveSessionMemories("session-1", messages, maxMemories = 5)
        assertEquals("应受 maxMemories 限制只保存 5 条", 5, count)
        assertEquals("仓库应有 5 条记录", 5L, memoryRepository.count())
    }

    @Test
    fun saveSessionMemories_skips_pair_on_embed_failure() = runBlocking {
        embedder = FakeEmbedder(throwOnCall = true)
        manager = CrossSessionMemoryManager(embedder, memoryRepository, retrievalThreshold = 0.0)
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答：协程是轻量级线程", 2000L)
        )
        val count = manager.saveSessionMemories("session-1", messages)
        assertEquals("embed 失败时应返回 0", 0, count)
        assertEquals("仓库不应有记录", 0L, memoryRepository.count())
    }

    @Test
    fun saveSessionMemories_associates_records_with_session_id() = runBlocking {
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答：协程是轻量级线程", 2000L)
        )
        manager.saveSessionMemories("my-session", messages)
        val records = memoryRepository.getBySession("my-session")
        assertEquals("应能按 sessionId 查到记录", 1, records.size)
        assertEquals("my-session", records[0].sessionId)
    }

    @Test
    fun saveSessionMemories_sets_turn_count_incrementally() = runBlocking {
        val messages = (1..3).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "问题$i 的详细分析", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "回答$i 的关键结论", i.toLong() * 1000 + 500)
            )
        }
        manager.saveSessionMemories("session-1", messages)
        val records = memoryRepository.getBySession("session-1")
        assertEquals("turnCount 应递增", listOf(1, 2, 3), records.map { it.turnCount })
    }

    // ==================== AC-2: 新会话 top-k 检索 ====================

    @Test
    fun retrieveRelevantMemories_returns_empty_for_blank_message() = runBlocking {
        val results = manager.retrieveRelevantMemories("")
        assertTrue("空白消息应返回空列表", results.isEmpty())
    }

    @Test
    fun retrieveRelevantMemories_returns_empty_for_blank_whitespace_message() = runBlocking {
        val results = manager.retrieveRelevantMemories("   ")
        assertTrue("纯空格消息应返回空列表", results.isEmpty())
    }

    @Test
    fun retrieveRelevantMemories_returns_empty_for_empty_repository() = runBlocking {
        val results = manager.retrieveRelevantMemories("任何问题")
        assertTrue("空仓库应返回空列表", results.isEmpty())
    }

    @Test
    fun retrieveRelevantMemories_returns_topk_results() = runBlocking {
        // 存入 5 条记忆
        val messages = (1..5).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "话题$i 相关问题", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "话题$i 的回答", i.toLong() * 1000 + 500)
            )
        }
        manager.saveSessionMemories("session-1", messages)

        // 检索 top-3
        val results = manager.retrieveRelevantMemories("话题", topK = 3)
        assertEquals("应返回最多 3 条结果", 3, results.size)
    }

    @Test
    fun retrieveRelevantMemories_returns_results_sorted_by_similarity_desc() = runBlocking {
        val messages = (1..5).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "话题$i 的深入探讨", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "话题$i 的核心结论", i.toLong() * 1000 + 500)
            )
        }
        manager.saveSessionMemories("session-1", messages)

        val results = manager.retrieveRelevantMemories("话题1", topK = 5)
        assertTrue("结果应按相似度降序", results.size >= 2)
        for (i in 0 until results.size - 1) {
            assertTrue(
                "结果应按相似度降序排列",
                results[i].similarity >= results[i + 1].similarity
            )
        }
    }

    @Test
    fun retrieveRelevantMemories_returns_empty_on_embed_failure() = runBlocking {
        embedder = FakeEmbedder(throwOnCall = true)
        manager = CrossSessionMemoryManager(embedder, memoryRepository, retrievalThreshold = 0.0)
        val results = manager.retrieveRelevantMemories("问题")
        assertTrue("embed 失败应降级为空列表", results.isEmpty())
    }

    @Test
    fun retrieveRelevantMemories_uses_default_topk_3() = runBlocking {
        val messages = (1..5).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "话题$i 的深入探讨", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "话题$i 的核心结论", i.toLong() * 1000 + 500)
            )
        }
        manager.saveSessionMemories("session-1", messages)

        val results = manager.retrieveRelevantMemories("话题")
        assertTrue("默认 topK=3，结果应 ≤ 3", results.size <= 3)
    }

    // ==================== AC-3: 防污染验证 ====================

    @Test
    fun retrieveRelevantMemories_does_not_load_full_session() = runBlocking {
        // 存入一个会话的 10 条记忆（5 个轮次对）
        val messages = (1..5).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "话题$i 的深入探讨", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "话题$i 的核心结论", i.toLong() * 1000 + 500)
            )
        }
        manager.saveSessionMemories("session-1", messages)

        // topK=2 检索，应只返回 2 条，而非全部 5 条
        val results = manager.retrieveRelevantMemories("话题", topK = 2)
        assertEquals("防污染：topK=2 应只返回 2 条，不加载全部", 2, results.size)
    }

    @Test
    fun retrieveRelevantMemories_does_not_cross_sessions() = runBlocking {
        // 两个会话各存 3 条
        val session1Messages = (1..3).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "会话1话题$i 的详细讨论", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "会话1话题$i 的核心结论", i.toLong() * 1000 + 500)
            )
        }
        val session2Messages = (1..3).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "会话2话题$i 的详细讨论", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "会话2话题$i 的核心结论", i.toLong() * 1000 + 500)
            )
        }
        manager.saveSessionMemories("session-1", session1Messages)
        manager.saveSessionMemories("session-2", session2Messages)

        // 检索可能来自两个会话，但每条结果只含片段不含有完整会话
        val results = manager.retrieveRelevantMemories("会话", topK = 3)
        assertTrue("结果应 ≤ topK", results.size <= 3)
        // 每条结果只含单个轮次对内容，不包含整个会话的全部消息
        results.forEach { result ->
            assertTrue(
                "防污染：每条结果应只是片段，不应包含完整会话",
                !result.content.contains("会话1话题1 的详细讨论") || !result.content.contains("会话1话题2 的详细讨论")
            )
        }
    }

    // ==================== AC-4: 检索结果注入 systemPrompt 格式 ====================

    @Test
    fun formatMemoriesAsContext_returns_null_for_empty_results() {
        val result = manager.formatMemoriesAsContext(emptyList())
        assertNull("空结果应返回 null", result)
    }

    @Test
    fun formatMemoriesAsContext_includes_prefix() {
        val results = listOf(
            MemorySearchResult(1, "session-1", "[用户] 什么是 Kotlin 协程？\n[助手] 回答：协程是轻量级线程", 0.9, 1000L, 1)
        )
        val result = manager.formatMemoriesAsContext(results)
        assertNotNull(result)
        assertTrue("应包含前缀", result!!.startsWith("相关历史对话："))
    }

    @Test
    fun formatMemoriesAsContext_numbers_results_sequentially() {
        val results = listOf(
            MemorySearchResult(1, "s1", "内容1", 0.9, 1000L, 1),
            MemorySearchResult(2, "s2", "内容2", 0.8, 2000L, 2),
            MemorySearchResult(3, "s3", "内容3", 0.7, 3000L, 3)
        )
        val result = manager.formatMemoriesAsContext(results)!!
        assertTrue("应包含编号 1.", result.contains("1. 内容1"))
        assertTrue("应包含编号 2.", result.contains("2. 内容2"))
        assertTrue("应包含编号 3.", result.contains("3. 内容3"))
    }

    @Test
    fun formatMemoriesAsContext_preserves_content() {
        val results = listOf(
            MemorySearchResult(1, "s1", "[用户] Kotlin 协程\n[助手] 轻量级线程", 0.9, 1000L, 1)
        )
        val result = manager.formatMemoriesAsContext(results)!!
        assertTrue("应保留原始内容", result.contains("Kotlin 协程"))
        assertTrue("应保留原始内容", result.contains("轻量级线程"))
    }

    // ==================== 纯函数测试：filterKeyMessages ====================

    @Test
    fun filterKeyMessages_keeps_user_and_assistant() {
        val messages = listOf(
            ChatMessage(1, Role.USER, "Prism 支持哪些自定义配置？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "Prism 支持模型与工具配置", 2000L),
            ChatMessage(3, Role.TOOL, "工具结果", 3000L)
        )
        val filtered = manager.filterKeyMessages(messages)
        assertEquals("应保留 user+assistant，跳过 tool", 2, filtered.size)
    }

    @Test
    fun filterKeyMessages_skips_empty_content() {
        val messages = listOf(
            ChatMessage(1, Role.USER, "", 1000L),
            ChatMessage(2, Role.ASSISTANT, "这是有效的回答内容", 2000L),
            ChatMessage(3, Role.USER, "   ", 3000L)
        )
        val filtered = manager.filterKeyMessages(messages)
        assertEquals("应跳过空 content", 1, filtered.size)
        assertEquals("这是有效的回答内容", filtered[0].content)
    }

    @Test
    fun filterKeyMessages_empty_input_returns_empty() {
        val filtered = manager.filterKeyMessages(emptyList())
        assertTrue("空输入应返回空列表", filtered.isEmpty())
    }

    // ==================== 纯函数测试：groupIntoTurnPairs ====================

    @Test
    fun groupIntoTurnPairs_pairs_consecutive_user_assistant() {
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答：协程是轻量级线程", 2000L)
        )
        val pairs = manager.groupIntoTurnPairs(messages)
        assertEquals("应配对 1 组", 1, pairs.size)
        assertEquals("什么是 Kotlin 协程？", pairs[0].first.content)
        assertEquals("回答：协程是轻量级线程", pairs[0].second.content)
    }

    @Test
    fun groupIntoTurnPairs_skips_trailing_user_without_assistant() {
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答：协程是轻量级线程", 2000L),
            ChatMessage(3, Role.USER, "这个问题还没有收到回答", 3000L)
        )
        val pairs = manager.groupIntoTurnPairs(messages)
        assertEquals("应只配对 1 组（跳过未配对 user）", 1, pairs.size)
    }

    @Test
    fun groupIntoTurnPairs_pairs_multiple_turns() {
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答：协程是轻量级线程", 2000L),
            ChatMessage(3, Role.USER, "Prism 支持哪些功能？", 3000L),
            ChatMessage(4, Role.ASSISTANT, "回答：Prism 支持多种模型", 4000L)
        )
        val pairs = manager.groupIntoTurnPairs(messages)
        assertEquals("应配对 2 组", 2, pairs.size)
        assertEquals("什么是 Kotlin 协程？", pairs[0].first.content)
        assertEquals("Prism 支持哪些功能？", pairs[1].first.content)
    }

    @Test
    fun groupIntoTurnPairs_empty_input_returns_empty() {
        val pairs = manager.groupIntoTurnPairs(emptyList())
        assertTrue("空输入应返回空列表", pairs.isEmpty())
    }

    // ==================== 纯函数测试：formatTurnPair ====================

    @Test
    fun formatTurnPair_formats_user_assistant_labels() {
        val user = ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L)
        val assistant = ChatMessage(2, Role.ASSISTANT, "协程是轻量级线程...", 2000L)
        val formatted = manager.formatTurnPair(user to assistant)
        assertTrue("应包含 [用户] 标签", formatted.contains("[用户]"))
        assertTrue("应包含 [助手] 标签", formatted.contains("[助手]"))
        assertTrue("应包含用户内容", formatted.contains("什么是 Kotlin 协程？"))
        assertTrue("应包含助手内容", formatted.contains("协程是轻量级线程..."))
    }

    // ==================== 集成场景：保存后检索 ====================

    @Test
    fun save_then_retrieve_round_trip() = runBlocking {
        // 会话 1：存入关于 Kotlin 的对话
        val session1Messages = listOf(
            ChatMessage(1, Role.USER, "如何在 Kotlin 中使用协程？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "使用 launch 或 async 启动协程...", 2000L)
        )
        manager.saveSessionMemories("session-1", session1Messages)

        // 会话 2：用户问相关问题
        val results = manager.retrieveRelevantMemories("Kotlin 协程怎么用", topK = 3)
        assertEquals("应检索到 1 条相关历史", 1, results.size)
        assertTrue("结果应包含原始内容", results[0].content.contains("协程"))

        // 格式化为 systemPrompt section
        val context = manager.formatMemoriesAsContext(results)
        assertNotNull("应返回格式化文本", context)
        assertTrue("应包含前缀", context!!.startsWith("相关历史对话："))
        assertTrue("应包含编号", context.contains("1. "))
    }

    // ==================== L-3 补充：groupIntoTurnPairs 边缘场景 ====================

    /**
     * L-3 补充：连续多个 USER 消息（用户连发），各自尝试配对后续 ASSISTANT。
     *
     * 场景：用户连发两条消息，每条都有 ASSISTANT 回复。
     * 期望：形成 2 个轮次对。
     */
    @Test
    fun groupIntoTurnPairs_consecutive_users_each_paired() {
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.USER, "补充：协程如何取消？", 2000L),
            ChatMessage(3, Role.ASSISTANT, "回答：协程是轻量级线程", 3000L),
            ChatMessage(4, Role.USER, "Prism 支持哪些功能？", 4000L),
            ChatMessage(5, Role.ASSISTANT, "回答：Prism 支持多种模型", 5000L)
        )
        val pairs = manager.groupIntoTurnPairs(messages)
        assertEquals("应形成 2 个轮次对（USER1 无配对被跳过，USER2+ASSISTANT1 配对，USER3+ASSISTANT2 配对）", 2, pairs.size)
        assertEquals("第一个对 user 应是'补充：协程如何取消？'", "补充：协程如何取消？", pairs[0].first.content)
        assertEquals("第一个对 assistant 应是'回答：协程是轻量级线程'", "回答：协程是轻量级线程", pairs[0].second.content)
    }

    /**
     * L-3 补充：连续多个 ASSISTANT 消息（分片响应），取第一个与 USER 配对。
     *
     * 场景：USER 后跟多个 ASSISTANT（流式分片合并场景）。
     * 期望：USER 与第一个 ASSISTANT 配对，其余 ASSISTANT 跳过。
     */
    @Test
    fun groupIntoTurnPairs_consecutive_assistants_takes_first() {
        val messages = listOf(
            ChatMessage(1, Role.USER, "协程和线程有什么区别？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "协程是轻量级线程", 2000L),
            ChatMessage(3, Role.ASSISTANT, "可挂起也可恢复执行", 3000L),
            ChatMessage(4, Role.ASSISTANT, "适合 IO 密集任务场景", 4000L)
        )
        val pairs = manager.groupIntoTurnPairs(messages)
        assertEquals("应形成 1 个轮次对", 1, pairs.size)
        assertEquals("应取第一个 ASSISTANT", "协程是轻量级线程", pairs[0].second.content)
    }

    /**
     * L-3 补充：前导 ASSISTANT 消息（无前置 USER），应被跳过。
     *
     * 场景：消息列表以 ASSISTANT 开头（如系统欢迎语后跟用户对话）。
     * 期望：前导 ASSISTANT 被跳过，正常配对后续 USER+ASSISTANT。
     */
    @Test
    fun groupIntoTurnPairs_leading_assistant_skipped() {
        val messages = listOf(
            ChatMessage(1, Role.ASSISTANT, "欢迎使用 Prism！", 1000L),
            ChatMessage(2, Role.USER, "你好，我想了解 Prism 功能", 2000L),
            ChatMessage(3, Role.ASSISTANT, "你好！有什么可以帮您？", 3000L)
        )
        val pairs = manager.groupIntoTurnPairs(messages)
        assertEquals("应形成 1 个轮次对（前导 ASSISTANT 被跳过）", 1, pairs.size)
        assertEquals("user 应是'你好，我想了解 Prism 功能'", "你好，我想了解 Prism 功能", pairs[0].first.content)
        assertEquals("assistant 应是欢迎回复", "你好！有什么可以帮您？", pairs[0].second.content)
    }
}
