package io.prism.memory

import io.prism.data.MemoryRepository
import io.prism.data.MyObjectBox
import io.prism.embedding.FakeEmbedder
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import io.objectbox.BoxStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * UXR9 US-904 AC-1 补充测试（ac-verifier，TKN-UXR9-ACCEPTANCE-001）。
 *
 * 主 Agent 补充测试聚焦 AC-2（LLM 摘要）/AC-3（检索阈值）/M-2/M-3，但 **AC-1 重要性筛选
 * 核心函数 `isImportantTurnPair` 无直接单测**，且现有 saveSessionMemories 用例全部使用
 * 「重要」消息（问题词/长度 ≥8），未验证**寒暄/确认/一次性问答确实被跳过**。
 *
 * 本文件补充覆盖：
 * - isImportantTurnPair 等价类：寒暄/确认/继续 → 不重要；偏好/身份/任务信号词 → 重要；
 *   实质问题（长度/疑问词）→ 重要；超短纯回应 → 不重要；空 → 不重要
 * - 边界值：MIN_IMPORTANT_LEN=8 的 7/8 字边界
 * - 集成：仅寒暄会话 → saveSessionMemories 返回 0（不入库）；重要会话 → 入库
 * - 集成：寒暄 + 重要混合会话 → 仅重要轮次入库
 */
class CrossSessionMemoryManagerUxr9Test {

    private lateinit var boxStore: BoxStore
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var manager: CrossSessionMemoryManager

    @Before
    fun setUp() {
        val tempDir = Files.createTempDirectory("prism-uxr9-memory-test").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        memoryRepository = MemoryRepository(boxStore)
        manager = CrossSessionMemoryManager(FakeEmbedder(), memoryRepository, retrievalThreshold = 0.0)
    }

    @After
    fun tearDown() {
        boxStore.close()
    }

    // ==================== isImportantTurnPair 等价类（AC-1） ====================

    @Test
    fun `isImportantTurnPair returns false for greetings and confirmations`() {
        // 寒暄/确认/继续 → 不重要（不入 L2）
        assertFalse("你好 应不重要", manager.isImportantTurnPair("你好"))
        assertFalse("好的，谢谢 应不重要", manager.isImportantTurnPair("好的，谢谢"))
        assertFalse("嗯嗯 应不重要", manager.isImportantTurnPair("嗯嗯"))
        assertFalse("继续 应不重要", manager.isImportantTurnPair("继续"))
        assertFalse("没问题 应不重要", manager.isImportantTurnPair("没问题"))
        assertFalse("再见 应不重要", manager.isImportantTurnPair("再见"))
    }

    @Test
    fun `isImportantTurnPair returns true for preference and identity keywords`() {
        // 偏好/身份/任务信号词 → 重要
        assertTrue("我喜欢 应重要", manager.isImportantTurnPair("我喜欢用简洁的语言回答"))
        assertTrue("我叫 应重要", manager.isImportantTurnPair("我叫张三，是一名软件工程师"))
        assertTrue("请记住 应重要", manager.isImportantTurnPair("请记住我最常用的编程语言是 Kotlin"))
        assertTrue("下次 应重要", manager.isImportantTurnPair("下次请用中文回答"))
        assertTrue("我计划 应重要", manager.isImportantTurnPair("我计划学习机器学习的知识"))
    }

    @Test
    fun `isImportantTurnPair returns true for substantive questions`() {
        // 实质问题（疑问词或足够长）→ 重要
        assertTrue("疑问词 应重要", manager.isImportantTurnPair("什么是 Kotlin 协程？"))
        assertTrue("如何 应重要", manager.isImportantTurnPair("如何配置 MCP 服务器？"))
        assertTrue("长问题 应重要", manager.isImportantTurnPair("帮我分析一下这个项目的架构设计思路"))
    }

    @Test
    fun `isImportantTurnPair boundary at MIN_IMPORTANT_LEN 8`() {
        // 边界值：无信号词/疑问词时，长度 ≥8 重要；<8 不重要
        val seven = "一二三四五六七"   // 7 字
        val eight = "一二三四五六七八" // 8 字
        assertFalse("7 字纯内容应不重要", manager.isImportantTurnPair(seven))
        assertTrue("8 字纯内容应重要", manager.isImportantTurnPair(eight))
    }

    @Test
    fun `isImportantTurnPair returns false for empty and short responses`() {
        assertFalse("空文本 应不重要", manager.isImportantTurnPair(""))
        assertFalse("空白 应不重要", manager.isImportantTurnPair("   "))
        assertFalse("超短纯回应 应不重要", manager.isImportantTurnPair("好的"))
        assertFalse("单字 应不重要", manager.isImportantTurnPair("嗯"))
    }

    @Test
    fun `isImportantTurnPair matches keywords case-insensitively for latin phrases`() {
        assertFalse("OK 应不重要（归一化命中）", manager.isImportantTurnPair("OK"))
        assertFalse("thanks 应不重要（归一化命中）", manager.isImportantTurnPair("Thanks"))
    }

    // ==================== saveSessionMemories 集成（AC-1：寒暄不入库） ====================

    @Test
    fun `saveSessionMemories skips greeting only session`() = runBlocking {
        // 全寒暄会话：isImportantTurnPair 全部 false → 0 条入库
        val messages = listOf(
            ChatMessage(1, Role.USER, "你好", 1000L),
            ChatMessage(2, Role.ASSISTANT, "你好！有什么可以帮你？", 2000L),
            ChatMessage(3, Role.USER, "好的，谢谢", 3000L),
            ChatMessage(4, Role.ASSISTANT, "不客气", 4000L)
        )
        val saved = manager.saveSessionMemories("s1", messages)
        assertEquals("寒暄会话应保存 0 条", 0, saved)
        assertEquals("仓库不应有记录", 0L, memoryRepository.count())
    }

    @Test
    fun `saveSessionMemories keeps only important turns in mixed session`() = runBlocking {
        // 混合会话：寒暄轮次被跳过，仅实质问答入库
        val messages = listOf(
            ChatMessage(1, Role.USER, "你好", 1000L),
            ChatMessage(2, Role.ASSISTANT, "你好！", 2000L),
            ChatMessage(3, Role.USER, "什么是 Kotlin 协程？", 3000L),
            ChatMessage(4, Role.ASSISTANT, "协程是轻量级线程", 4000L),
            ChatMessage(5, Role.USER, "好的谢谢", 5000L),
            ChatMessage(6, Role.ASSISTANT, "不客气", 6000L)
        )
        val saved = manager.saveSessionMemories("s1", messages)
        assertEquals("仅实质问答应保存 1 条", 1, saved)
        val records = memoryRepository.getBySession("s1")
        assertEquals("仓库应有 1 条", 1, records.size)
        assertTrue("记录应为重要轮次内容", records[0].content.contains("Kotlin 协程"))
        assertFalse("记录不应含寒暄内容", records[0].content.contains("你好"))
    }

    @Test
    fun `saveSessionMemories stores preference turn in session`() = runBlocking {
        // 偏好轮次（用户告知偏好）→ 重要，入库
        val messages = listOf(
            ChatMessage(1, Role.USER, "我喜欢用简洁直接的回答方式", 1000L),
            ChatMessage(2, Role.ASSISTANT, "好的，我会保持简洁", 2000L)
        )
        val saved = manager.saveSessionMemories("s1", messages)
        assertEquals("偏好轮次应保存 1 条", 1, saved)
    }
}
