package io.prism.config

import androidx.datastore.preferences.core.emptyPreferences
import io.prism.security.FakePreferenceDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * UserRulesRepository 单元测试（UXR8 N1，ADR-030）。
 *
 * 验证「关于我」+「如何回答」双字段的 DataStore 持久化：
 * - 默认双字段为空
 * - 设置/读取往返（含 trim）
 * - Flow 响应新值
 * - 长度上限 fail-fast（BR-security-005）
 * - [UserRules.toSystemPromptSection] 纯函数（最高优先级层格式 + 空降级）
 */
class UserRulesRepositoryTest {

    private lateinit var dataStore: FakePreferenceDataStore
    private lateinit var repository: UserRulesRepository

    @Before
    fun setUp() {
        dataStore = FakePreferenceDataStore(emptyPreferences())
        repository = UserRulesRepository(dataStore)
    }

    @Test
    fun `default rules are empty`() = runBlocking {
        val rules = repository.getRules()
        assertEquals("默认「关于我」应为空", "", rules.aboutMe)
        assertEquals("默认「如何回答」应为空", "", rules.howToRespond)
        assertTrue("默认应视为空白（不注入 userRules 层）", rules.isBlank)
    }

    @Test
    fun `set and read back both fields trimmed`() = runBlocking {
        repository.setRules(" 我是 Python 开发者 ", " 用中文简洁回答 ")
        val rules = repository.getRules()
        assertEquals("「关于我」应 trim 后存储", "我是 Python 开发者", rules.aboutMe)
        assertEquals("「如何回答」应 trim 后存储", "用中文简洁回答", rules.howToRespond)
        assertFalse("配置后不应视为空白", rules.isBlank)
    }

    @Test
    fun `flow emits persisted rules`() = runBlocking {
        repository.setRules("关于我A", "回答A")
        val emitted = repository.rules().first()
        assertEquals("关于我A", emitted.aboutMe)
        assertEquals("回答A", emitted.howToRespond)
    }

    @Test
    fun `clear fields by saving empty strings`() = runBlocking {
        repository.setRules("背景", "偏好")
        repository.setRules("", "")
        val rules = repository.getRules()
        assertEquals("清空后「关于我」应为空", "", rules.aboutMe)
        assertEquals("清空后「如何回答」应为空", "", rules.howToRespond)
    }

    @Test
    fun `overlong field rejected fail fast`() = runBlocking {
        try {
            repository.setRules("x".repeat(UserRulesRepository.MAX_RULE_LEN + 1), "")
            fail("超长「关于我」应被拒绝（BR-security-005）")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("字符"))
        }
        // 拒绝后原值应保留（未写入部分写）
        repository.setRules("已有值", "")
        try {
            repository.setRules("", "y".repeat(UserRulesRepository.MAX_RULE_LEN + 1))
            fail("超长「如何回答」应被拒绝")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals("拒绝超长写入后旧值应保留", "已有值", repository.getRules().aboutMe)
    }

    @Test
    fun `boundary length exactly max is accepted`() = runBlocking {
        val about = "a".repeat(UserRulesRepository.MAX_RULE_LEN)
        val respond = "b".repeat(UserRulesRepository.MAX_RULE_LEN)
        repository.setRules(about, respond)
        val rules = repository.getRules()
        assertEquals(about, rules.aboutMe)
        assertEquals(respond, rules.howToRespond)
    }

    // ==================== toSystemPromptSection 纯函数 ====================

    @Test
    fun `toSystemPromptSection null when both blank`() {
        assertNull(UserRulesRepository.UserRules().toSystemPromptSection())
        assertNull(UserRulesRepository.UserRules(aboutMe = "  ").toSystemPromptSection())
    }

    @Test
    fun `toSystemPromptSection includes aboutMe only`() {
        val section = UserRulesRepository.UserRules(aboutMe = "我是后端开发者").toSystemPromptSection()
        assertTrue(section.orEmpty().contains("用户规则"))
        assertTrue(section.orEmpty().contains("除安全限制外最高优先级"))
        assertTrue(section.orEmpty().contains("关于我：我是后端开发者"))
        assertFalse(section.orEmpty().contains("如何回答"))
    }

    @Test
    fun `toSystemPromptSection includes both fields in order`() {
        val section = UserRulesRepository.UserRules(
            aboutMe = "背景",
            howToRespond = "用中文简洁回答"
        ).toSystemPromptSection()
        val s = section.orEmpty()
        assertTrue("应含「关于我」", s.contains("关于我：背景"))
        assertTrue("应含「如何回答」", s.contains("如何回答：用中文简洁回答"))
        assertTrue("「关于我」应在「如何回答」之前", s.indexOf("关于我") < s.indexOf("如何回答"))
    }
}
