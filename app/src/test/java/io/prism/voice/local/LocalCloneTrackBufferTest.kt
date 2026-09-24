package io.prism.voice.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AudioTrack 缓冲区尺寸的**不变量**用例（TKN-LVC-011 / BUG-20260920-004）。
 *
 * 真机缺陷：缓冲区随音频长度线性增长（14s→662KB、19.5s→916KB），触发
 * `AudioFlinger: BUFFER TIMEOUT: remove track from active list` 与长时间 `threadloop_write` 阻塞
 * ⇒ 长音频播到一半无声。本类钉住三条不变量：① 与音频长度无关；② 有偏好上限；
 * ③ **不得低于设备下限**（否则音轨可能非法而完全无声）。
 */
class LocalCloneTrackBufferTest {

    /** 真机实测 `getMinBufferSize(24000, MONO, PCM_16BIT)` ≈ 3856B（日志 getMinFrameCount=1928）。 */
    private val deviceMin = 3_856

    @Test
    fun `量级 —— 必须是几百字节到几十 KB，绝不能是历史缺陷的数百 KB`() {
        val bytes = localCloneTrackBufferBytes(deviceMin, 24_000)
        assertEquals("24kHz 目标 200ms = 9600B > 设备下限 3856 ⇒ 取 9600", 9_600, bytes)
        // 历史缺陷值：14s@24k 单声道 16bit = 672,000B；19.5s = 936,000B
        assertTrue("不得接近历史缺陷量级（实际 $bytes）", bytes < 100_000)
    }

    @Test
    fun `与音频长度无关 —— 该函数不含长度入参，故对同一设备参数恒定`() {
        val a = localCloneTrackBufferBytes(deviceMin, 24_000)
        val b = localCloneTrackBufferBytes(deviceMin, 24_000)
        assertEquals(a, b)
        // 若有人把 `pcm.size * 2` 塞回来，必然表现为"随长度变化"——本用例以恒定值证伪该回归
        assertTrue("固定值不得随调用次序漂移（若把 pcm.size 塞回来必然漂移）", a == b && a == 9_600)
    }

    @Test
    fun `设备下限优先于我们的偏好上限 —— 压穿下限会导致音轨非法（无声）`() {
        // 若设备要求 64KB，我们不得压到 32KB：max(minBuffer, min(target, cap)) = 65536
        assertEquals(65_536, localCloneTrackBufferBytes(65_536, 48_000))
        assertEquals(32_768, localCloneTrackBufferBytes(32_768, 24_000))
    }

    @Test
    fun `上限生效于偏好值 —— 高采样率也不得超过 32KB`() {
        assertEquals("常量本身须为 32KB", 32_768, MAX_TRACK_BUFFER_BYTES)
        assertEquals(32_768, localCloneTrackBufferBytes(1, 192_000))
        assertEquals(32_768, localCloneTrackBufferBytes(deviceMin, 384_000))
    }

    @Test
    fun `异常入参必须有界且恒正`() {
        assertEquals("非法采样率退化为设备下限", 4_096, localCloneTrackBufferBytes(4_096, 0))
        assertEquals("负的 minBuffer（getMinBufferSize 错误码）不得产出非正数", 1, localCloneTrackBufferBytes(-1, 0))
        assertTrue(localCloneTrackBufferBytes(0, 0) > 0)
    }
}
