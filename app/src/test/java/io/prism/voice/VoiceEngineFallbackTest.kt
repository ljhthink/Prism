package io.prism.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次23-A（ADR-048，A5）：降级决策纯函数单测 —— [shouldFallbackToOnDevice]。
 *
 * 覆盖真实引擎错误文案口径（AliCloudAsrEngine.mapTaskFailure / MiniMaxTtsEngine
 * PROBE_ERROR_MESSAGE / transport 层文案），固化"哪些错误触发层②/层③回退端侧"的契约：
 * - 回退：探测失败文案（含"已回退"）、网络类（连接失败/异常/网络/超时/断开）、
 *   ASR task-failed 未细分兜底文案；
 * - 不回退：鉴权失败、限流、Key 未配置、麦克风权限、MiniMax 合成任务失败、空白文案。
 */
class VoiceEngineFallbackTest {

    // ==================== 触发回退（true） ====================

    @Test
    fun `TTS 启动探测超时文案含已回退标记 - 触发回退`() {
        // MiniMaxTtsEngine.PROBE_ERROR_MESSAGE 原文
        assertTrue(
            shouldFallbackToOnDevice(
                VoiceEngineKind.TTS,
                "云端 TTS 连接失败（网络或 Key 问题），已回退"
            )
        )
    }

    @Test
    fun `ASR WS 连接异常 - 触发回退`() {
        // AliCloudAsrEngine.onFailure 文案
        assertTrue(
            shouldFallbackToOnDevice(
                VoiceEngineKind.ASR,
                "云端识别连接异常（SocketTimeoutException），请检查网络后重试"
            )
        )
    }

    @Test
    fun `ASR 连接失败类文案 - 触发回退`() {
        assertTrue(shouldFallbackToOnDevice(VoiceEngineKind.ASR, "云端识别连接失败"))
    }

    @Test
    fun `ASR 会话中断开类文案 - 触发回退`() {
        assertTrue(shouldFallbackToOnDevice(VoiceEngineKind.ASR, "连接已断开"))
    }

    @Test
    fun `ASR task-failed 未细分兜底文案 - 触发回退`() {
        // AliCloudAsrEngine.mapTaskFailure else 分支
        assertTrue(
            shouldFallbackToOnDevice(VoiceEngineKind.ASR, "云端识别失败（Unknown：服务端未提供失败详情）")
        )
    }

    // ==================== 不触发回退（false） ====================

    @Test
    fun `ASR 鉴权失败 - 不回退`() {
        // 配置问题：回退掩盖不了，文案原样上抛引导用户修 Key
        assertFalse(
            shouldFallbackToOnDevice(
                VoiceEngineKind.ASR,
                "云端识别鉴权失败（DashScope Key 无效或未开通语音识别服务）"
            )
        )
    }

    @Test
    fun `ASR 限流配额 - 不回退`() {
        assertFalse(
            shouldFallbackToOnDevice(VoiceEngineKind.ASR, "云端识别请求受限（限流或配额不足，请稍后重试）")
        )
    }

    @Test
    fun `ASR Key 未配置 - 不回退`() {
        assertFalse(
            shouldFallbackToOnDevice(VoiceEngineKind.ASR, "云端语音识别未配置 DashScope Key（请先在设置中配置）")
        )
    }

    @Test
    fun `ASR 录音权限类错误 - 不回退`() {
        // 端侧引擎同样需要麦克风权限：回退无效
        assertFalse(shouldFallbackToOnDevice(VoiceEngineKind.ASR, "录音初始化失败（请检查麦克风权限）"))
    }

    @Test
    fun `TTS 未配置 - 不回退`() {
        assertFalse(shouldFallbackToOnDevice(VoiceEngineKind.TTS, "云端 TTS 未配置（缺少 API Key 或 GroupId）"))
    }

    @Test
    fun `TTS 合成任务失败 - 不回退`() {
        // 单句失败：本轮文字呈现即可，不切引擎
        assertFalse(shouldFallbackToOnDevice(VoiceEngineKind.TTS, "MiniMax 合成任务失败"))
        assertFalse(shouldFallbackToOnDevice(VoiceEngineKind.TTS, "MiniMax 错误（code=1027）：threshold exceeded"))
        assertFalse(shouldFallbackToOnDevice(VoiceEngineKind.TTS, "音频分片 hex 解码失败"))
    }

    @Test
    fun `空白错误文案 - 不回退`() {
        assertFalse(shouldFallbackToOnDevice(VoiceEngineKind.ASR, ""))
        assertFalse(shouldFallbackToOnDevice(VoiceEngineKind.ASR, "   "))
    }

    @Test
    fun `ASR 兜底前缀不误伤 TTS 类别`() {
        // "云端识别失败（…）"是 ASR task-failed 专属兜底；TTS 类别不匹配该前缀
        assertFalse(shouldFallbackToOnDevice(VoiceEngineKind.TTS, "云端识别失败（Unknown：详情）"))
    }

    @Test
    fun `错误文案含空白前后缀仍可匹配`() {
        // 引擎文案可能带首尾空白：trim 后匹配
        assertTrue(shouldFallbackToOnDevice(VoiceEngineKind.ASR, "  云端识别连接异常（IO）  "))
    }
}
