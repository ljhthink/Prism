package io.prism.phonecontrol.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧健康纯逻辑单测（2026-10-25 整改；PRD US-3114 AC-7/AC-8）。
 *
 * 覆盖：帧新鲜度判定、服务端帧元信息解析、唤醒命令形态。
 * （Bitmap 相关路径依赖 Android 图形栈，真机覆盖，不在 JVM 断言。）
 */
class DisplayFrameHealthTest {

    @Test
    fun `帧龄在阈值内视为新鲜`() {
        assertTrue(FrameFreshness.isFresh(0L))
        assertTrue(FrameFreshness.isFresh(FrameFreshness.STALE_MS - 1))
        assertTrue(VirtualDisplayManager.isFrameFresh(FrameFreshness.STALE_MS - 1))
    }

    @Test
    fun `帧龄达到阈值或无帧视为冻结`() {
        assertFalse(FrameFreshness.isFresh(FrameFreshness.STALE_MS))
        assertFalse(FrameFreshness.isFresh(Long.MAX_VALUE))
        assertFalse(FrameFreshness.isFresh(-1L))
        assertFalse(VirtualDisplayManager.isFrameFresh(VirtualDisplayManager.FRAME_STALE_MS))
    }

    @Test
    fun `帧龄计算_时钟回拨归零_无元信息视为最旧`() {
        assertEquals(500L, FrameFreshness.ageMs(1_000L, 1_500L))
        assertEquals(0L, FrameFreshness.ageMs(1_000L, 500L))
        assertEquals(Long.MAX_VALUE, FrameFreshness.ageMs(0L, 9_999L))
    }

    // ==================== ac-verifier D-02：静态画面不是冻结 ====================

    @Test
    fun `屏幕亮着且显示面正常时旧帧不得判为冻结（D-02 核心）`() {
        val stale = FrameFreshness.STALE_MS + 10_000L // 13s 未重绘
        assertFalse(
            "静态页面 13s 不重绘是正常现象，不能触发重建（重建会重启目标应用）",
            FrameFreshness.isStallSuspicious(ageMs = stale, interactive = true, displayOn = true)
        )
        assertFalse(
            "Watch 也不该提示「可能已冻结」",
            FrameFreshness.shouldHintStale(ageMs = stale, interactive = true, displayOn = true)
        )
    }

    @Test
    fun `熄屏或显示面 OFF 时旧帧才算可疑（真实冻结场景）`() {
        val stale = FrameFreshness.STALE_MS + 1L
        assertTrue(
            "熄屏后隐藏屏停更是本轮真机实证的冻结形态",
            FrameFreshness.isStallSuspicious(ageMs = stale, interactive = false, displayOn = true)
        )
        assertTrue(
            "执行面 display OFF 同样应触发自愈",
            FrameFreshness.isStallSuspicious(ageMs = stale, interactive = true, displayOn = false)
        )
    }

    @Test
    fun `帧新鲜时无论环境如何都不算可疑`() {
        listOf(true to true, false to true, true to false, false to false).forEach { (i, d) ->
            assertFalse(
                "interactive=$i displayOn=$d",
                FrameFreshness.isStallSuspicious(0L, i, d)
            )
        }
    }

    @Test
    fun `解析帧元信息`() {
        val info = ShizukuShell.parseHiddenFrameInfo("OK:720:1560:1234")
        assertEquals(720, info?.width)
        assertEquals(1560, info?.height)
        assertEquals(1234L, info?.ageMs)
    }

    @Test
    fun `帧元信息非法一律返回 null`() {
        assertNull(ShizukuShell.parseHiddenFrameInfo(null))
        assertNull(ShizukuShell.parseHiddenFrameInfo(""))
        assertNull(ShizukuShell.parseHiddenFrameInfo("ERR:no-frame"))
        assertNull(ShizukuShell.parseHiddenFrameInfo("OK:0:1560:10"))
        assertNull(ShizukuShell.parseHiddenFrameInfo("OK:720:1560"))
        assertNull(ShizukuShell.parseHiddenFrameInfo("OK:720:1560:-1"))
        assertNull(ShizukuShell.parseHiddenFrameInfo("OK:a:b:c"))
    }

    @Test
    fun `唤醒命令必须用 input keyevent 且不带 display 参数`() {
        val cmd = ShizukuShell.buildWakeUpCommand().toList()
        assertEquals(listOf("input", "keyevent", "224"), cmd)
        assertFalse(cmd.contains("-d"))
    }

    // ==================== guardrail G-04 / G-05 回归锁 ====================

    @Test
    fun `无帧龄元信息的帧一律判不新鲜（fail-closed 的纯逻辑部分）`() {
        // 契约层（DisplayBackend 默认实现真的把 capturedAtMs 置 0）由
        // DisplayBackendDefaultFrameTest（Robolectric）覆盖；此处只锁纯逻辑。
        val fallbackAge = FrameFreshness.ageMs(0L)
        assertEquals(Long.MAX_VALUE, fallbackAge)
        assertFalse(FrameFreshness.isFresh(fallbackAge))
    }

    @Test
    fun `帧编码降级常量与 Binder 上限自洽`() {
        // 900KB 必须明显低于 Binder 1MB 事务上限（留余量），否则降级再多次也传不回来
        assertTrue(HiddenFrameSpec.MAX_FRAME_BYTES <= 900_000)
        assertTrue(HiddenFrameSpec.MAX_FRAME_BYTES < 1_048_576)
        // 降级阶梯必须有界：attempts 不能无限试下去（每次尝试都在 shell 特权进程里分配位图）
        assertTrue(HiddenFrameSpec.ENCODE_ATTEMPTS in 1..8)
        assertTrue(HiddenFrameSpec.MIN_QUALITY in 1..95)
        assertTrue(HiddenFrameSpec.SCALE_STEP > 0.0)
        assertTrue(HiddenFrameSpec.SCALE_STEP < 1.0)
        // 帧缓冲数：2 会 stall（本轮修复点），也不该无节制放大
        assertTrue(HiddenFrameSpec.MAX_IMAGES in 3..8)
        // 消费节流必须存在（否则每帧都做 4.5MB 拷贝，拖住生产端）
        assertTrue(HiddenFrameSpec.MIN_INTERVAL_MS in 16..200)
    }
}
