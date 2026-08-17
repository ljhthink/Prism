package io.prism.ui.chat

import io.prism.data.SkillConfig
import io.prism.data.SkillSource
import io.prism.skill.SkillManifest
import io.prism.skill.SkillRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mergeSystemPrompt userRules 全层优先级补充测试（UXR8 N1，ADR-030）—— ac-verifier TKN-UXR8-B3-ACCEPTANCE-001。
 *
 * 补盲区：主 Agent 的 [ConversationViewModelPhaseDTest] 已覆盖 userRules 在 persona 之后、RAG 之前、
 * null/空白跳过。本文件补充 **userRules 与 L1/L2/L3/Skill 全层顺序**（ADR-030 要求
 * 「除安全限制外最高优先级」，即 userRules 必须位于全部自动记忆层与 Skill 索引之前）。
 */
class ConversationViewModelUserRulesOrderTest {

    private fun makeSkillEntry(name: String): SkillRegistry.SkillEntry = SkillRegistry.SkillEntry(
        config = SkillConfig(
            id = 0L, name = name, displayName = name, source = SkillSource.LOCAL_BUILTIN,
            sourceUri = null, skillDir = "/skills/$name", isEnabled = true, isInstalled = true, version = "1.0.0"
        ),
        manifest = SkillManifest(name = name, description = "Test skill $name", version = "1.0.0", tools = null, body = "")
    )

    private val userRulesSection = "[用户规则 · 除安全限制外最高优先级]\n关于我：后端开发者"

    @Test
    fun `mergeSystemPrompt userRules has highest priority over all memory layers and skills`() {
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "RAG-GROUNDING",
            l1Summary = "L1-SUMMARY",
            l2Memories = "L2-MEMORIES",
            l3Profiles = "L3-PROFILES",
            userRules = userRulesSection,
            enabledSkills = listOf(makeSkillEntry("skillA"))
        )
        val personaIdx = result.indexOf(ConversationViewModel.DEFAULT_PERSONA)
        val rulesIdx = result.indexOf("用户规则")
        val ragIdx = result.indexOf("RAG-GROUNDING")
        val l1Idx = result.indexOf("L1-SUMMARY")
        val l2Idx = result.indexOf("L2-MEMORIES")
        val l3Idx = result.indexOf("L3-PROFILES")
        val skillIdx = result.indexOf("skillA")

        // 全层顺序：persona → userRules → RAG → L1 → L2 → L3 → Skill
        assertTrue("persona 应为第一层", personaIdx in 0 until rulesIdx)
        assertTrue("userRules 应在 RAG 之前", rulesIdx < ragIdx)
        assertTrue("userRules 应在 L1 之前", rulesIdx < l1Idx)
        assertTrue("userRules 应在 L2 之前", rulesIdx < l2Idx)
        assertTrue("userRules 应在 L3 之前", rulesIdx < l3Idx)
        assertTrue("userRules 应在 Skill 索引之前", rulesIdx < skillIdx)
        // 自动记忆层相对顺序不回归（ADR-015：RAG → L1 → L2 → L3）
        assertTrue("RAG 应在 L1 之前", ragIdx < l1Idx)
        assertTrue("L1 应在 L2 之前", l1Idx < l2Idx)
        assertTrue("L2 应在 L3 之前", l2Idx < l3Idx)
        assertTrue("L3 应在 Skill 之前", l3Idx < skillIdx)
    }

    @Test
    fun `mergeSystemPrompt injects userRules even when rag and all memory layers null`() {
        // userRules 单独存在（无 RAG/记忆）时仍注入（最高优先级层不依赖其他层）
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null, userRules = userRulesSection, enabledSkills = emptyList()
        )
        assertTrue("userRules 应注入", result.contains("用户规则"))
        assertTrue("userRules 应在 persona 之后", result.indexOf(ConversationViewModel.DEFAULT_PERSONA) < result.indexOf("用户规则"))
        assertFalse("无 RAG 时不应含 RAG 标记", result.contains("RAG-GROUNDING"))
    }

    @Test
    fun `mergeSystemPrompt userRules appears exactly once`() {
        // 防重复注入：userRules 层应仅出现一次
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "RAG", userRules = userRulesSection,
            l1Summary = "L1", enabledSkills = emptyList()
        )
        assertEquals(1, result.split("除安全限制外最高优先级").size - 1)
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected.toLong(), actual.toLong())
    }
}
