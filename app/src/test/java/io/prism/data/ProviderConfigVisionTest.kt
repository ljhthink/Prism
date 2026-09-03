package io.prism.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProviderConfig.detectVisionSupport] 单元测试（v1 批次13 B，多模态模型名启发式）。
 */
class ProviderConfigVisionTest {

    @Test
    fun `detects vision capable model names`() {
        // 用户重点关注的 glm-4.6v-flash 必须识别
        assertTrue(ProviderConfig.detectVisionSupport("glm-4.6v-flash"))
        assertTrue(ProviderConfig.detectVisionSupport("glm-4v-plus"))
        assertTrue(ProviderConfig.detectVisionSupport("gpt-4o"))
        assertTrue(ProviderConfig.detectVisionSupport("gemini-2.5-pro"))
        assertTrue(ProviderConfig.detectVisionSupport("qwen2.5-vl-72b"))
        assertTrue(ProviderConfig.detectVisionSupport("claude-sonnet-4"))
        assertTrue(ProviderConfig.detectVisionSupport("kimi-k2.6"))
    }

    @Test
    fun `does not detect text only models`() {
        assertFalse(ProviderConfig.detectVisionSupport("deepseek-chat"))
        assertFalse(ProviderConfig.detectVisionSupport("deepseek-reasoner"))
        assertFalse(ProviderConfig.detectVisionSupport("glm-4-flash"))
        assertFalse(ProviderConfig.detectVisionSupport("llama-3.1-70b"))
        assertFalse(ProviderConfig.detectVisionSupport("qwen2.5-7b"))
    }

    @Test
    fun `handles null and blank`() {
        assertFalse(ProviderConfig.detectVisionSupport(null))
        assertFalse(ProviderConfig.detectVisionSupport(""))
        assertFalse(ProviderConfig.detectVisionSupport("   "))
    }
}
