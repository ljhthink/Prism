package io.prism.vision

import androidx.datastore.preferences.core.emptyPreferences
import io.prism.config.VisionBypassConfigRepository
import io.prism.data.ProviderConfig
import io.prism.security.FakePreferenceDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1 US-301/302：视觉旁路（云端 + OCR 兜底 + 熔断）单元测试。
 */
class VisionBypassTest {

    private lateinit var configDataStore: FakePreferenceDataStore
    private lateinit var config: VisionBypassConfigRepository

    private val visionConfig = ProviderConfig(
        name = "vision", baseUrl = "https://api.vision.com/v1", apiKeyRef = "key",
        models = listOf("glm-4v-plus"), isVisionFallback = true
    )

    @Before
    fun setUp() {
        configDataStore = FakePreferenceDataStore(emptyPreferences())
        config = VisionBypassConfigRepository(configDataStore)
    }

    // ==================== VisionBypassConfigRepository ====================

    @Test
    fun `config defaults to no consent, auto enabled, zero failures`() = runBlocking {
        assertFalse("默认未授权", config.isConsentGiven())
        assertTrue("默认自动旁路开启", config.isAutoBypassEnabled())
        assertEquals("默认 0 次失败", 0, config.getConsecutiveFailures())
        assertFalse("未授权则不可用", config.isBypassAvailable())
    }

    @Test
    fun `config circuit breaker after max failures disables bypass`() = runBlocking {
        config.setConsent(true)
        assertTrue(config.isBypassAvailable())
        // 连续 3 次失败 → 熔断
        repeat(VisionBypassConfigRepository.MAX_FAILURES) { config.recordFailure() }
        assertFalse("熔断后不可用", config.isBypassAvailable())
        // 成功清零 → 恢复
        config.resetFailures()
        assertTrue("清零后恢复", config.isBypassAvailable())
    }

    // ==================== VisionBypassOrchestrator 降级链 ====================

    @Test
    fun `resolve cloud success returns Cloud and resets failures`() = runBlocking {
        config.setConsent(true)
        config.recordFailure()
        var cloudCalled = 0
        val orchestrator = VisionBypassOrchestrator(
            config = config,
            cloudDescriber = { _, _, _ -> cloudCalled++; "图片描述内容" },
            ocrExtractor = { null }
        )
        val result = orchestrator.resolve("data:image/jpeg;base64,xxx", "这是什么", visionConfig)
        assertTrue(result is VisionBypassResult.Cloud)
        assertEquals("图片描述内容", (result as VisionBypassResult.Cloud).description)
        assertEquals(1, cloudCalled)
        assertEquals("成功应清零失败计数", 0, config.getConsecutiveFailures())
    }

    @Test
    fun `resolve falls back to OCR when cloud fails`() = runBlocking {
        config.setConsent(true)
        val orchestrator = VisionBypassOrchestrator(
            config = config,
            cloudDescriber = { _, _, _ -> null },
            ocrExtractor = { "提取到的票据文字 123" }
        )
        val result = orchestrator.resolve("data:image/png;base64,yyy", "发票多少钱", visionConfig)
        assertTrue(result is VisionBypassResult.Ocr)
        assertEquals("提取到的票据文字 123", (result as VisionBypassResult.Ocr).text)
        assertEquals("云端失败应计 1 次", 1, config.getConsecutiveFailures())
    }

    @Test
    fun `resolve unavailable when both cloud and ocr fail`() = runBlocking {
        config.setConsent(true)
        val orchestrator = VisionBypassOrchestrator(
            config = config,
            cloudDescriber = { _, _, _ -> null },
            ocrExtractor = { null }
        )
        assertEquals(VisionBypassResult.Unavailable, orchestrator.resolve("data:image/jpeg;base64,z", "q", visionConfig))
        assertEquals("应计失败", 1, config.getConsecutiveFailures())
    }

    @Test
    fun `resolve unavailable without vision config or consent`() = runBlocking {
        val orchestrator = VisionBypassOrchestrator(
            config = config,
            cloudDescriber = { _, _, _ -> "不应被调用" },
            ocrExtractor = { null } // 无 OCR 文字
        )
        // 未授权 → 云端跳过，OCR 无文字 → Unavailable
        assertEquals(VisionBypassResult.Unavailable, orchestrator.resolve("data:image/jpeg;base64,z", "q", visionConfig))
        // 无视觉配置 → 云端跳过，OCR 无文字 → Unavailable
        config.setConsent(true)
        assertEquals(VisionBypassResult.Unavailable, orchestrator.resolve("data:image/jpeg;base64,z", "q", null))
        // 熔断 → 云端跳过，OCR 无文字 → Unavailable
        repeat(VisionBypassConfigRepository.MAX_FAILURES) { config.recordFailure() }
        assertEquals(VisionBypassResult.Unavailable, orchestrator.resolve("data:image/jpeg;base64,z", "q", visionConfig))
    }

    @Test
    fun `resolve uses OCR fallback even without consent`() = runBlocking {
        // guardrail M-4：OCR 本地兜底无需授权/视觉配置（云端不可用也可达）
        val orchestrator = VisionBypassOrchestrator(
            config = config, // 未授权
            cloudDescriber = { _, _, _ -> throw AssertionError("未授权不应调用云端") },
            ocrExtractor = { "离线 OCR 文字" }
        )
        // 未授权 + 无视觉配置 → 云端跳过，OCR 兜底成功
        val result = orchestrator.resolve("data:image/jpeg;base64,z", "q", null)
        assertTrue("未授权也应走 OCR 兜底", result is VisionBypassResult.Ocr)
        assertEquals("离线 OCR 文字", (result as VisionBypassResult.Ocr).text)
    }

    @Test
    fun `resolve cloud exception degrades to ocr`() = runBlocking {
        config.setConsent(true)
        val orchestrator = VisionBypassOrchestrator(
            config = config,
            cloudDescriber = { _, _, _ -> throw RuntimeException("视觉 API 故障") },
            ocrExtractor = { "OCR 兜底文字" }
        )
        val result = orchestrator.resolve("data:image/jpeg;base64,z", "q", visionConfig)
        assertTrue("云端异常应降级 OCR", result is VisionBypassResult.Ocr)
    }

    // ==================== 提示词构造 ====================

    @Test
    fun `prompt builders produce non-empty sensible prompts`() {
        val sys = VisionBypassOrchestrator.buildVisionSystemPrompt()
        assertTrue(sys.isNotBlank())
        assertTrue("系统提示应要求转录文字", sys.contains("文字"))
        assertTrue("系统提示应要求描述场景", sys.contains("场景") || sys.contains("描述"))

        val userWithText = VisionBypassOrchestrator.buildVisionUserPrompt("这是什么水果")
        assertTrue(userWithText.contains("这是什么水果"))
        val userBlank = VisionBypassOrchestrator.buildVisionUserPrompt("")
        assertTrue(userBlank.isNotBlank())
    }

    @Test
    fun `injection prefixes defined`() {
        assertEquals("【图片内容】", VisionBypassOrchestrator.IMAGE_DESC_PREFIX)
        assertEquals("【图片文字】", VisionBypassOrchestrator.IMAGE_OCR_PREFIX)
    }
}
