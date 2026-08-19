package io.prism.vision

import androidx.datastore.preferences.core.emptyPreferences
import io.prism.config.VisionBypassConfigRepository
import io.prism.data.ProviderConfig
import io.prism.security.FakePreferenceDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ac-verifier 补充测试（TKN-V1-B2-ACCEPTANCE-001，US-301/302）—— 熔断边界值 + 降级链状态决策表补盲。
 *
 * 既有 [VisionBypassTest] 覆盖主路径（云端成功/OCR 兜底/双失败/未授权/无配置/熔断/云端异常/无授权 OCR）；
 * 本文件补充：
 * - 熔断边界值分析：恰 2 次可用 / 恰 3 次熔断 / 计数钳制不溢出 / 满额后成功清零
 * - 降级链决策表：云端失败且 OCR 成功仍计熔断（防限流试探）、熔断+未授权下 OCR 仍可达、
 *   自动开关关闭下 OCR 仍可达、无视觉配置下 OCR 仍可达
 */
class VisionBypassSupplementTest {

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

    // ==================== 熔断边界值分析（AC4） ====================

    @Test
    fun `circuit breaker stays available at exactly 2 failures`() = runBlocking {
        config.setConsent(true)
        repeat(2) { config.recordFailure() }
        assertTrue("连续 2 次失败仍应可用（阈值=3）", config.isBypassAvailable())
    }

    @Test
    fun `circuit breaker disables at exactly 3 failures`() = runBlocking {
        config.setConsent(true)
        repeat(3) { config.recordFailure() }
        assertFalse("连续 3 次失败应熔断", config.isBypassAvailable())
    }

    @Test
    fun `circuit breaker counter caps at max without overflow`() = runBlocking {
        config.setConsent(true)
        repeat(VisionBypassConfigRepository.MAX_FAILURES + 5) { config.recordFailure() }
        assertEquals(
            "计数不应超过 MAX_FAILURES（coerceAtMost 钳制）",
            VisionBypassConfigRepository.MAX_FAILURES,
            config.getConsecutiveFailures()
        )
        assertFalse("计数钳制后仍熔断", config.isBypassAvailable())
    }

    @Test
    fun `success resets failures to zero even when at max`() = runBlocking {
        config.setConsent(true)
        repeat(VisionBypassConfigRepository.MAX_FAILURES) { config.recordFailure() }
        config.resetFailures()
        assertEquals("成功清零应恢复为 0", 0, config.getConsecutiveFailures())
        assertTrue("清零后旁路恢复可用", config.isBypassAvailable())
    }

    // ==================== 降级链状态决策表（AC5） ====================

    @Test
    fun `cloud failure counts failure even when ocr succeeds`() = runBlocking {
        config.setConsent(true)
        val orchestrator = VisionBypassOrchestrator(
            config = config,
            cloudDescriber = { _, _, _ -> null },
            ocrExtractor = { "OCR 文字" }
        )
        val result = orchestrator.resolve("data:image/png;base64,yyy", "q", visionConfig)
        assertTrue(result is VisionBypassResult.Ocr)
        assertEquals(
            "OCR 成功但云端失败也应计熔断（防反复试探云端限流）",
            1, config.getConsecutiveFailures()
        )
    }

    @Test
    fun `circuit broken and no consent still allows ocr fallback`() = runBlocking {
        // 熔断 3 次 + 未授权 → 云端跳过；OCR 本地兜底仍可达（AC5 / guardrail M-4）
        repeat(VisionBypassConfigRepository.MAX_FAILURES) { config.recordFailure() }
        val orchestrator = VisionBypassOrchestrator(
            config = config,
            cloudDescriber = { _, _, _ -> throw AssertionError("熔断+未授权不应调用云端") },
            ocrExtractor = { "熔断后 OCR 兜底文字" }
        )
        val result = orchestrator.resolve("data:image/jpeg;base64,z", "q", visionConfig)
        assertTrue("熔断+未授权下 OCR 仍应可用", result is VisionBypassResult.Ocr)
    }

    @Test
    fun `auto bypass off disables cloud but ocr still works`() = runBlocking {
        config.setConsent(true)
        config.setAutoBypassEnabled(false)
        val orchestrator = VisionBypassOrchestrator(
            config = config,
            cloudDescriber = { _, _, _ -> throw AssertionError("自动开关关闭不应调用云端") },
            ocrExtractor = { "OCR 兜底" }
        )
        val result = orchestrator.resolve("data:image/jpeg;base64,z", "q", visionConfig)
        assertTrue("自动开关关闭下 OCR 仍应可用", result is VisionBypassResult.Ocr)
    }

    @Test
    fun `no vision config with consent skips cloud and allows ocr`() = runBlocking {
        config.setConsent(true)
        val orchestrator = VisionBypassOrchestrator(
            config = config,
            cloudDescriber = { _, _, _ -> throw AssertionError("无视觉配置不应调用云端") },
            ocrExtractor = { "OCR 文字" }
        )
        val result = orchestrator.resolve("data:image/jpeg;base64,z", "q", null)
        assertTrue("无视觉配置下 OCR 仍应可用", result is VisionBypassResult.Ocr)
    }

    // ==================== v1 批次7（Issue 3，guardrail B-1 修订）：专用视觉 Provider ====================

    @Test
    fun `dedicated provider with circuit tripped but consent given still runs cloud`() = runBlocking {
        // 专用 Provider 跳过熔断：consent 已授（激活路径 saveProvider 自动授 true），即使连续失败
        // 熔断满 3 次，专用 Provider 仍每次可重试 Cloud（修"激活了却永远只 OCR"，真机 dedicated=true 仍走 OCR 根因之一）。
        config.setConsent(true)
        repeat(VisionBypassConfigRepository.MAX_FAILURES) { config.recordFailure() }
        var cloudCalled = false
        val orchestrator = VisionBypassOrchestrator(
            config = config,
            cloudDescriber = { _, _, _ -> cloudCalled = true; "学校描述" },
            ocrExtractor = { "OCR 文字" }
        )

        val result = orchestrator.resolve("data:image/jpeg;base64,z", "q", visionConfig, isDedicated = true)

        assertTrue("专用 Provider 应跳过熔断走 Cloud", result is VisionBypassResult.Cloud)
        assertTrue("应调用云端描述器", cloudCalled)
    }

    @Test
    fun `dedicated provider respects explicit consent revocation and falls back to ocr`() = runBlocking {
        // guardrail B-1 隐私铁门：用户在设置页显式关闭图片外发授权后（consent=false），
        // 专用 Provider **不得再外发图片**——即使熔断未触发、云端可达，也应落到 OCR/不可用。
        var cloudCalled = false
        val orchestrator = VisionBypassOrchestrator(
            config = config, // consent 默认 false（未授/已撤销）
            cloudDescriber = { _, _, _ -> cloudCalled = true; "不应外发" },
            ocrExtractor = { "本地 OCR 文字" }
        )

        val result = orchestrator.resolve("data:image/jpeg;base64,z", "q", visionConfig, isDedicated = true)

        assertTrue("授权撤销后专用 Provider 也不能走 Cloud", result is VisionBypassResult.Ocr)
        assertFalse("授权撤销后不得调用云端描述器（图片不得外发）", cloudCalled)
    }

    @Test
    fun `dedicated provider still respects auto bypass off switch`() = runBlocking {
        // 安全网：即使用户显式配置专用 Provider，`autoBypass` 开关关闭时仍不自动走 Cloud
        config.setAutoBypassEnabled(false)
        var cloudCalled = false
        val orchestrator = VisionBypassOrchestrator(
            config = config,
            cloudDescriber = { _, _, _ -> cloudCalled = true; "描述" },
            ocrExtractor = { "OCR 文字" }
        )
        val result = orchestrator.resolve("data:image/jpeg;base64,z", "q", visionConfig, isDedicated = true)
        assertTrue("自动旁路关闭时专用 Provider 也应落到 OCR", result is VisionBypassResult.Ocr)
        assertFalse("不应调用云端", cloudCalled)
    }
}
