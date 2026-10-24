package io.prism.phonecontrol.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-065（US-3114）：执行面后端抽象 / flag 阶梯 / 降级顺序纯逻辑单测（JVM）。
 *
 * 真实建屏、截图、输入注入依赖 shell 进程与真机（见 docs/reports/2026-10-21-qq-hidden-vd-poc.md），
 * 此处固化不依赖 Android framework 的决策逻辑，防止实现漂移。
 */
class DisplayBackendTest {

    // ==================== flag 阶梯（与 PoC / HiddenDisplayFlags 单一来源） ====================

    @Test
    fun `完整 flag 组合与 PoC 真机验证值一致 0x1FEC1`() {
        assertEquals(0x1FEC1, HiddenDisplayFlags.FULL)
    }

    @Test
    fun `最小基座 - PUBLIC+TOUCH+ROTATES+SYSTEM_DECORATIONS`() {
        assertEquals(0x2C1, HiddenDisplayFlags.BASE)
        assertEquals(1, HiddenDisplayFlags.BASE and HiddenDisplayFlags.PUBLIC)
        assertEquals(0, HiddenDisplayFlags.BASE and HiddenDisplayFlags.TRUSTED)
    }

    @Test
    fun `阶梯四级 - 完整到最小逐级剥离且顺序稳定`() {
        val ladder = HiddenDisplayFlags.LADDER
        assertEquals(4, ladder.size)
        assertEquals(HiddenDisplayFlags.FULL, ladder[0])
        // 第二级：仅去掉 TRUSTED（保留扩展位）
        assertEquals(0x1FAC1, ladder[1])
        assertEquals(0, ladder[1] and HiddenDisplayFlags.TRUSTED)
        assertEquals(HiddenDisplayFlags.BASE, ladder[1] and HiddenDisplayFlags.BASE)
        // 第三级：最小 + TRUSTED
        assertEquals(0x6C1, ladder[2])
        assertEquals(HiddenDisplayFlags.TRUSTED, ladder[2] and HiddenDisplayFlags.TRUSTED)
        // 第四级：最小
        assertEquals(HiddenDisplayFlags.BASE, ladder[3])
    }

    // ==================== 后端能力与降级顺序 ====================

    @Test
    fun `隐藏后端 - 主屏不可见且未创建时无尺寸无错误`() {
        val backend = RemoteShellBackend()
        assertEquals(DisplayBackend.Kind.HIDDEN, backend.kind)
        assertFalse("隐藏后端不得声明主屏可见", backend.visibleOnMainScreen)
        assertNull(backend.frameSize())
        assertNull(backend.lastError())
    }

    @Test
    fun `兼容后端 - 明示主屏可能出现小窗（UI 诚实说明依据）`() {
        assertEquals(DisplayBackend.Kind.LEGACY, OverlayLegacyBackend.kind)
        assertTrue("legacy 后端必须声明主屏可能可见", OverlayLegacyBackend.visibleOnMainScreen)
    }

    @Test
    fun `backendPlan - 开关开且 Shizuku 就绪时隐藏优先、legacy 兜底`() {
        assertEquals(
            listOf(DisplayBackend.Kind.HIDDEN, DisplayBackend.Kind.LEGACY),
            VirtualDisplayManager.backendPlan(hiddenEnabled = true, shizukuReady = true)
        )
    }

    @Test
    fun `backendPlan - 开关关或 Shizuku 未就绪时直接 legacy（不白等失败重绑定）`() {
        assertEquals(
            listOf(DisplayBackend.Kind.LEGACY),
            VirtualDisplayManager.backendPlan(hiddenEnabled = false, shizukuReady = true)
        )
        assertEquals(
            listOf(DisplayBackend.Kind.LEGACY),
            VirtualDisplayManager.backendPlan(hiddenEnabled = true, shizukuReady = false)
        )
        assertEquals(
            listOf(DisplayBackend.Kind.LEGACY),
            VirtualDisplayManager.backendPlan(hiddenEnabled = false, shizukuReady = false)
        )
    }
}
