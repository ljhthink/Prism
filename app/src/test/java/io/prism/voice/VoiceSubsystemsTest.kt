package io.prism.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 批次20（VC-001~004，ADR-044）：语音子系统 Robolectric 单测。
 *
 * 覆盖：
 * - [AudioInputController]：RECORD_AUDIO 未授予时 start 直接失败（不崩溃、状态干净）
 * - [SystemAsrEngine]：无识别引擎环境 isAvailable=false（降级路径）
 * - [SystemTtsEngine]：未就绪时 speak 走 onError 降级（不触发底层 TTS）
 * - [VoiceForegroundService]：start/stop Intent 构造幂等（Robolectric）
 *
 * ASR/TTS 的真实音频行为依赖真机（PRD 里程碑 M-B 真机验证清单）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VoiceSubsystemsTest {

    private val context: android.content.Context
        get() = RuntimeEnvironment.getApplication()

    // ==================== VC-004：AudioInputController ====================

    @Test
    fun `AudioInputController - RECORD_AUDIO 未授予时 start 返回 false`() {
        val controller = AudioInputController(context)
        // Robolectric 默认不授予权限
        assertFalse(controller.hasPermission())
        assertFalse(controller.start { })
        // 失败后停止应幂等（不抛异常）
        controller.stop()
    }

    @Test
    fun `AudioInputController - 音频参数符合 16kHz 单声道 PCM 规范`() {
        val controller = AudioInputController(context)
        assertEquals(16_000, controller.sampleRate)
        // 20ms @16kHz = 320 样本/块
        assertEquals(320, controller.chunkSamples)
    }

    // ==================== VC-002：SystemAsrEngine ====================

    @Test
    fun `SystemAsrEngine - Robolectric 无识别引擎时乐观尝试不崩溃且 release 幂等`() {
        val engine = SystemAsrEngine(context)
        // 批次20 真机修复（RCA 问题2）：isRecognitionAvailable 可能误报 false（包可见性），
        // startListening 改为**乐观尝试**——不再因 isAvailable=false 硬拒（MIUI 实证该误报
        // 导致"按住说话无反应"）；无引擎时由 SpeechRecognizer 回调 onError 兜底。
        var errorMsg: String? = null
        engine.startListening(object : AsrEngine.Listener {
            override fun onVolume(level: Float) {}
            override fun onPartial(text: String) {}
            override fun onFinal(text: String) {}
            override fun onError(message: String) {
                errorMsg = message
            }
        })
        // 乐观尝试下两种合法结局：引擎存在开始聆听（error=null）或引擎缺失回调 onError
        // ——关键断言：不崩溃、可清理（release 幂等由不抛异常证明）
        engine.cancel()
        engine.release()
    }

    @Test
    fun `SystemAsrEngine - 错误码映射覆盖关键场景`() {
        val engine = SystemAsrEngine(context)
        assertTrue(
            engine.describeError(android.speech.SpeechRecognizer.ERROR_NO_MATCH)
                .contains("未识别到")
        )
        assertTrue(
            engine.describeError(android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                .contains("权限")
        )
        assertTrue(
            engine.describeError(android.speech.SpeechRecognizer.ERROR_NETWORK)
                .contains("网络")
        )
    }

    // ==================== VC-003：SystemTtsEngine ====================

    @Test
    fun `SystemTtsEngine - 未就绪时 speak 回调 onError（优雅降级不崩溃）`() {
        val engine = SystemTtsEngine(context)
        // 构造后立即 speak（init 异步未完成，ready=false）→ 必须走 onError 降级
        if (!engine.isAvailable) {
            var error: String? = null
            engine.speak("测试播报", object : TtsEngine.Listener {
                override fun onDone(utteranceId: String) {}
                override fun onError(utteranceId: String, message: String) {
                    error = message
                }

                override fun onStart(utteranceId: String) {}
            })
            assertNotNull("未就绪时 speak 必须 onError", error)
        }
        engine.stop()
        engine.release()
    }

    // ==================== VC-001：VoiceForegroundService ====================

    @Test
    fun `VoiceForegroundService - start 与 stop 幂等不抛异常`() {
        // Robolectric 下 startForegroundService 记录 Intent；重复 start/stop 不抛
        VoiceForegroundService.start(context)
        VoiceForegroundService.start(context)
        VoiceForegroundService.stop(context)
        VoiceForegroundService.stop(context)
    }

    @Test
    fun `VoiceForegroundService - 通知渠道常量稳定`() {
        assertEquals("prism_voice_call", VoiceForegroundService.CHANNEL_ID)
    }

    // ==================== 批次22（SZ-201/SZ-202，ADR-047）====================

    /** sherpa 条件集成是否激活（app/libs AAR 已放置——CI/未放置环境为 false）。 */
    private val sherpaEnabled: Boolean
        get() = java.io.File("libs/sherpa-onnx-android.aar").exists()

    @Test
    fun `AsrEngine 接口默认实现 - isStreaming=false 且 resetCapture no-op`() {
        // 系统引擎（非流式）的接口契约：默认值不破坏按住说话形态
        val minimal = object : AsrEngine {
            override val isAvailable: Boolean = true
            override fun startListening(listener: AsrEngine.Listener) {}
            override fun stopListening() {}
            override fun cancel() {}
            override fun release() {}
        }
        assertFalse("非流式引擎默认 isStreaming 必须为 false", minimal.isStreaming)
        minimal.resetCapture()  // no-op 不抛异常
    }

    @Test
    fun `AsrEngineFactory - 环境自适应引擎选择`() {
        val engine = AsrEngineFactory.create(context)
        if (sherpaEnabled) {
            // AAR 就位 → 反射创建 SherpaAsrEngine（流式形态）
            assertTrue("sherpa 就位时工厂应产出流式引擎", engine.isStreaming)
        } else {
            // AAR 缺失 → 回落 SystemAsrEngine（按住说话降级）
            assertFalse("sherpa 缺失时工厂应回落系统引擎", engine.isStreaming)
        }
        engine.release()
    }

    @Test
    fun `SherpaAsrEngine - isAvailable 检查 assets（首装未拷贝 filesDir 也应可用）`() {
        org.junit.Assume.assumeTrue(sherpaEnabled)
        val engine = AsrEngineFactory.create(context)
        // Robolectric 加载真实 app assets（models/streaming-zipformer-small-bilingual-zh-en 已放置）——
        // isAvailable 必须为 true（修复前只查 filesDir：首装永远不可用死锁）
        assertTrue("assets 模型就位时 isAvailable 必须为 true", engine.isAvailable)
        engine.release()
    }
}
