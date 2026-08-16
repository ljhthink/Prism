package io.prism.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProfileNaturalLanguageParser 单元测试（O1/PRD UXR8，BR-testing-004 纯 JVM）。
 *
 * **覆盖目标**：
 * - [ProfileNaturalLanguageParser.deriveKey]：类别关键词命中（tone/language_pref/tech_stack/expertise）
 *   + 兜底 hash（同句去重 / 异句新增）+ trim/lowercase normalize
 * - [ProfileNaturalLanguageParser.stableHash]：稳定性 + 8 位 hex 格式 + 负 hash 无符号化
 * - key 长度约束：deriveKey 输出 ≤ MemoryManagementViewModel.MAX_PROFILE_KEY_LEN（50）
 */
class ProfileNaturalLanguageParserTest {

    // ==================== deriveKey：类别关键词命中 ====================

    @Test
    fun `deriveKey maps tone style sentence to tone`() {
        assertEquals("tone", ProfileNaturalLanguageParser.deriveKey("我喜欢简洁的回复"))
        assertEquals("tone", ProfileNaturalLanguageParser.deriveKey("回答详细一点，别太简短"))
        assertEquals("tone", ProfileNaturalLanguageParser.deriveKey("语气轻松幽默些"))
    }

    @Test
    fun `deriveKey maps language preference sentence to language_pref`() {
        assertEquals("language_pref", ProfileNaturalLanguageParser.deriveKey("请用中文回答"))
        assertEquals("language_pref", ProfileNaturalLanguageParser.deriveKey("回复用中文，方便阅读"))
        assertEquals("language_pref", ProfileNaturalLanguageParser.deriveKey("英文回复也可以"))
    }

    @Test
    fun `deriveKey maps tech stack sentence to tech_stack`() {
        assertEquals("tech_stack", ProfileNaturalLanguageParser.deriveKey("我是 Python 开发者"))
        assertEquals("tech_stack", ProfileNaturalLanguageParser.deriveKey("主要写 Kotlin 和 Java"))
        assertEquals("tech_stack", ProfileNaturalLanguageParser.deriveKey("做前端的"))
    }

    @Test
    fun `deriveKey maps expertise sentence to expertise`() {
        assertEquals("expertise", ProfileNaturalLanguageParser.deriveKey("我是编程新手，请多解释基础概念"))
        assertEquals("expertise", ProfileNaturalLanguageParser.deriveKey("算资深工程师了"))
    }

    @Test
    fun `deriveKey normalizes trim and lowercase before matching`() {
        // 大小写 + 首尾空白 normalize 后仍命中
        assertEquals("tech_stack", ProfileNaturalLanguageParser.deriveKey("  我用 PYTHON  "))
    }

    // ==================== deriveKey：兜底 hash ====================

    @Test
    fun `deriveKey falls back to pref hash when no category matched`() {
        val key = ProfileNaturalLanguageParser.deriveKey("我养了三只猫，别提猫毛过敏")
        assertTrue("未命中类别应以 pref_ 前缀兜底：$key", key.startsWith("pref_"))
        assertEquals("兜底 key 长度应为 pref_ + 8 位 hex（13）", 13, key.length)
    }

    @Test
    fun `deriveKey returns same key for identical sentence - upsert dedup`() {
        val sentence = "每天回复限制在五条以内"  // 无类别关键词
        val key1 = ProfileNaturalLanguageParser.deriveKey(sentence)
        val key2 = ProfileNaturalLanguageParser.deriveKey(sentence)
        assertEquals("同句应生成相同 key（upsert 去重语义）", key1, key2)
    }

    @Test
    fun `deriveKey returns different keys for different sentences`() {
        val key1 = ProfileNaturalLanguageParser.deriveKey("我养了三只猫")
        val key2 = ProfileNaturalLanguageParser.deriveKey("我喜欢周末爬山")
        assertTrue("异句兜底 key 应不同（hash 碰撞概率忽略）", key1 != key2)
    }

    @Test
    fun `deriveKey output length within MAX_PROFILE_KEY_LEN`() {
        val longKey = ProfileNaturalLanguageParser.deriveKey("这是一条特别长的偏好描述".repeat(50))
        assertTrue(
            "deriveKey 输出不应超过 MAX_PROFILE_KEY_LEN(50)：${longKey.length}",
            longKey.length <= 50
        )
    }

    // ==================== stableHash ====================

    @Test
    fun `stableHash is deterministic for same input`() {
        assertEquals(
            ProfileNaturalLanguageParser.stableHash("稳定性测试"),
            ProfileNaturalLanguageParser.stableHash("稳定性测试")
        )
    }

    @Test
    fun `stableHash produces 8 char lowercase hex`() {
        val hash = ProfileNaturalLanguageParser.stableHash("任意输入")
        assertTrue("hash 应为 8 位：$hash", hash.length == 8)
        assertTrue("hash 应为小写 hex：$hash", hash.all { it in "0123456789abcdef" })
    }

    @Test
    fun `stableHash handles negative hashCode as unsigned`() {
        // 构造负 hash 输入（多数中文字符串 hashCode 为负），验证无符号化后仍为合法 hex
        val hash = ProfileNaturalLanguageParser.stableHash("中文哈希为负数")
        assertTrue("负 hash 无符号化后应为合法 hex：$hash", hash.all { it in "0123456789abcdef" })
    }
}
