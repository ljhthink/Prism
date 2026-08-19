package io.prism.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mergeSystemPrompt 手机操控安全声明测试（v1 US-202）—— 补充验证：
 * - [ConversationViewModel.PHONE_CONTROL_GUIDANCE] 存在且包含不可信数据源声明
 * - 传入 phoneControlGuidance 时注入，且位于 persona 之后、RAG 之前（安全层优先级）
 * - null/空白 phoneControlGuidance 时跳过（向后兼容既有测试）
 */
class ConversationViewModelPhoneControlGuidanceTest {

    private val guidance = ConversationViewModel.PHONE_CONTROL_GUIDANCE

    @Test
    fun `phone control guidance declares untrusted UI text`() {
        assertTrue(guidance.contains("不可信"))
        assertTrue(guidance.contains("phone_control"))
        assertTrue(guidance.contains("take_over"))
        assertTrue(guidance.contains("硬拦截"))
    }

    @Test
    fun `mergeSystemPrompt injects guidance after persona and before rag`() {
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "RAG-GROUNDING",
            phoneControlGuidance = guidance,
            enabledSkills = emptyList()
        )
        val personaIdx = result.indexOf(ConversationViewModel.DEFAULT_PERSONA)
        val guideIdx = result.indexOf("手机操控安全声明")
        val ragIdx = result.indexOf("RAG-GROUNDING")
        assertTrue("persona 应在声明之前", personaIdx in 0 until guideIdx)
        assertTrue("声明应在 RAG 之前（安全层优先）", guideIdx < ragIdx)
    }

    @Test
    fun `mergeSystemPrompt injects guidance even when rag null`() {
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null,
            phoneControlGuidance = guidance,
            enabledSkills = emptyList()
        )
        assertTrue(result.contains("手机操控安全声明"))
    }

    @Test
    fun `mergeSystemPrompt skips blank guidance`() {
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null,
            phoneControlGuidance = "  ",
            enabledSkills = emptyList()
        )
        assertFalse(result.contains("手机操控安全声明"))
    }

    @Test
    fun `mergeSystemPrompt skips null guidance for backward compat`() {
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null,
            phoneControlGuidance = null,
            enabledSkills = emptyList()
        )
        assertFalse(result.contains("手机操控安全声明"))
    }
}
