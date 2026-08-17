package io.prism.config

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UserRulesRepository 补充边缘测试（UXR8 N1，ADR-030）—— ac-verifier TKN-UXR8-B3-ACCEPTANCE-001。
 *
 * 补盲区：主 Agent 的 [UserRulesRepositoryTest] 已覆盖默认空 / 往返 / 超长 fail-fast /
 * 边界长度 / toSystemPromptSection（全空 / only aboutMe / 双字段）。本文件补充：
 * - toSystemPromptSection **仅「如何回答」** 分支（路径覆盖 of [UserRules.toSystemPromptSection]）
 * - 空白字段混合时的空白判定（isBlank 语义）
 */
class UserRulesRepositoryEdgeTest {

    // ==================== toSystemPromptSection 纯函数路径补充 ====================

    @Test
    fun `toSystemPromptSection includes howToRespond only when aboutMe blank`() {
        val section = UserRulesRepository.UserRules(aboutMe = "", howToRespond = "用中文简洁回答").toSystemPromptSection()
        assertTrue("howToRespond-only 不应为 null", section != null)
        val s = section.orEmpty()
        assertTrue("应含「用户规则」头", s.contains("用户规则"))
        assertTrue("应含「除安全限制外最高优先级」", s.contains("除安全限制外最高优先级"))
        assertTrue("应含「如何回答」", s.contains("如何回答：用中文简洁回答"))
        assertFalse("aboutMe 空白时不应输出「关于我」小节", s.contains("关于我"))
    }

    @Test
    fun `toSystemPromptSection treats whitespace only aboutMe as blank`() {
        // aboutMe 为空白字符串时视为 blank（isBlank 语义），不应输出「关于我：」空小节
        val section = UserRulesRepository.UserRules(aboutMe = "   ", howToRespond = "回答").toSystemPromptSection()
        val s = section.orEmpty()
        assertFalse("空白 aboutMe 不应产生「关于我」小节", s.contains("关于我："))
        assertTrue("仍应输出「如何回答」", s.contains("如何回答：回答"))
    }

    @Test
    fun `toSystemPromptSection both fields blank returns null even after trim semantics`() {
        // 全空白（含换行/制表）→ isBlank=true → null（调用方跳过注入）
        assertNull(UserRulesRepository.UserRules(aboutMe = "\n\t ", howToRespond = "  \n").toSystemPromptSection())
    }

    @Test
    fun `isBlank only true when both fields blank`() {
        assertTrue(UserRulesRepository.UserRules().isBlank)
        assertFalse(UserRulesRepository.UserRules(aboutMe = "x").isBlank)
        assertFalse(UserRulesRepository.UserRules(howToRespond = "x").isBlank)
        assertFalse(UserRulesRepository.UserRules(aboutMe = "x", howToRespond = "y").isBlank)
    }

    @Test
    fun `toSystemPromptSection preserves multiline field content`() {
        // 用户规则可含多行（如「1. 先给结论\n2. 再解释」），输出不应破坏其结构
        val s = UserRulesRepository.UserRules(aboutMe = "后端", howToRespond = "1. 先给结论\n2. 再解释").toSystemPromptSection()
        assertTrue("应保留多行内容", s.orEmpty().contains("1. 先给结论\n2. 再解释"))
    }

    @Test
    fun `setRules trims only outer whitespace not internal`() = runBlocking {
        val dataStore = io.prism.security.FakePreferenceDataStore(androidx.datastore.preferences.core.emptyPreferences())
        val repo = UserRulesRepository(dataStore)
        repo.setRules("  关于我  ", "  回答 A  B  ")
        val rules = repo.getRules()
        assertEquals("外层空格应 trim", "关于我", rules.aboutMe)
        assertEquals("内部空格应保留", "回答 A  B", rules.howToRespond)
    }

    @Test
    fun `setRules rejects when both fields overlong simultaneously`() = runBlocking {
        val dataStore = io.prism.security.FakePreferenceDataStore(androidx.datastore.preferences.core.emptyPreferences())
        val repo = UserRulesRepository(dataStore)
        repo.setRules("已有", "已有回答")
        try {
            repo.setRules("x".repeat(UserRulesRepository.MAX_RULE_LEN + 1), "y".repeat(UserRulesRepository.MAX_RULE_LEN + 1))
            throw AssertionError("双字段同时超长应被拒绝（BR-security-005）")
        } catch (e: IllegalArgumentException) {
            // expected：任一字段超长即整次拒绝
        }
        // 拒绝后旧值保留（原子性：不产生部分写入）
        val rules = repo.getRules()
        assertEquals("已有", rules.aboutMe)
        assertEquals("已有回答", rules.howToRespond)
    }
}
