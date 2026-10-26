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

    /**
     * 2026-10-26 真机反馈：用户配置 `deepseek-v4-flash` 实际支持多模态，
     * 但旧白名单不含 deepseek 家族，导致 vision=false，QQ 收图只能走 OCR。
     *
     * 官方依据（Models & Pricing / Vision）：
     * `deepseek-flash`（V4.1-Flash-1017）支持 Vision；旧名 `deepseek-v4-flash` /
     * `deepseek-v4-flash-vision-exp` 仍被接受并由同一模型服务；`deepseek-v4-pro` 不支持。
     */
    @Test
    fun `deepseek flash family supports vision but pro does not`() {
        assertTrue(ProviderConfig.detectVisionSupport("deepseek-v4-flash"))
        assertTrue(ProviderConfig.detectVisionSupport("deepseek-flash"))
        assertTrue(ProviderConfig.detectVisionSupport("deepseek-v4-flash-vision-exp"))
        assertTrue(ProviderConfig.detectVisionSupport("DeepSeek-V4-Flash"))

        // 反例：不能因为"名字里有 deepseek"就当成支持视觉
        assertFalse(ProviderConfig.detectVisionSupport("deepseek-v4-pro"))
        assertFalse(ProviderConfig.detectVisionSupport("deepseek-pro"))
        assertFalse(ProviderConfig.detectVisionSupport("deepseek-v3"))
        assertFalse(ProviderConfig.detectVisionSupport("deepseek-r1"))
        assertFalse(ProviderConfig.detectVisionSupport("deepseek-coder"))
    }

    @Test
    fun `handles null and blank`() {
        assertFalse(ProviderConfig.detectVisionSupport(null))
        assertFalse(ProviderConfig.detectVisionSupport(""))
        assertFalse(ProviderConfig.detectVisionSupport("   "))
    }
}
