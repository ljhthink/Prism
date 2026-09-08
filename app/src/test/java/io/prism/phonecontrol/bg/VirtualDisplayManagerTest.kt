package io.prism.phonecontrol.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次20（BG-001/003，ADR-045）：[VirtualDisplayManager] 纯逻辑单测（JVM）。
 *
 * 虚拟屏创建/帧捕获依赖 Android framework（真机/Robolectric 验证），此处覆盖：
 * - mode 参数归一化（[VirtualDisplayManager.normalizeMode]）——协议兼容性的第一道闸
 * - 平台支持判定
 */
class VirtualDisplayManagerTest {

    // ==================== normalizeMode（BG-003 协议兼容闸） ====================

    @Test
    fun `normalizeMode - null 与空串归一为 foreground（向后兼容不带 mode 的调用）`() {
        assertEquals(VirtualDisplayManager.MODE_FOREGROUND, VirtualDisplayManager.normalizeMode(null))
        assertEquals(VirtualDisplayManager.MODE_FOREGROUND, VirtualDisplayManager.normalizeMode(""))
    }

    @Test
    fun `normalizeMode - foreground 大小写与空白容错`() {
        assertEquals(VirtualDisplayManager.MODE_FOREGROUND, VirtualDisplayManager.normalizeMode("foreground"))
        assertEquals(VirtualDisplayManager.MODE_FOREGROUND, VirtualDisplayManager.normalizeMode(" Foreground "))
        assertEquals(VirtualDisplayManager.MODE_FOREGROUND, VirtualDisplayManager.normalizeMode("前台"))
    }

    @Test
    fun `normalizeMode - background 大小写与中文别名`() {
        assertEquals(VirtualDisplayManager.MODE_BACKGROUND, VirtualDisplayManager.normalizeMode("background"))
        assertEquals(VirtualDisplayManager.MODE_BACKGROUND, VirtualDisplayManager.normalizeMode("BACKGROUND"))
        assertEquals(VirtualDisplayManager.MODE_BACKGROUND, VirtualDisplayManager.normalizeMode(" 后台 "))
    }

    @Test
    fun `normalizeMode - 非法值返回 null（执行器直接拒绝，防拼错悄然回退前台）`() {
        assertNull(VirtualDisplayManager.normalizeMode("bg"))
        assertNull(VirtualDisplayManager.normalizeMode("前台模式"))
        assertNull(VirtualDisplayManager.normalizeMode("0"))
        assertNull(VirtualDisplayManager.normalizeMode("background2"))
    }

    // ==================== 模式常量与支持判定 ====================

    @Test
    fun `模式常量 - 协议字面量稳定`() {
        assertEquals("foreground", VirtualDisplayManager.MODE_FOREGROUND)
        assertEquals("background", VirtualDisplayManager.MODE_BACKGROUND)
    }

    @Test
    fun `未创建虚拟屏时 isCreated 为 false 且 frameSize 为 null`() {
        // JVM 单测未走 ensureDisplay（Android framework 依赖），初始态判定
        assertFalse(VirtualDisplayManager.isCreated)
        assertNull(VirtualDisplayManager.frameSize())
        assertEquals(-1, VirtualDisplayManager.currentDisplayId)
    }

    // ==================== isOverlaySizeMatch（修复轮七：差集法防误报闸） ====================

    @Test
    fun `isOverlaySizeMatch - 规格尺寸 720x1560 命中`() {
        assertTrue(VirtualDisplayManager.isOverlaySizeMatch(720, 1560))
    }

    @Test
    fun `isOverlaySizeMatch - 主屏投屏等其它尺寸不命中（防误报用户自开副屏）`() {
        assertFalse(VirtualDisplayManager.isOverlaySizeMatch(1080, 2254))
        assertFalse(VirtualDisplayManager.isOverlaySizeMatch(1920, 1080))
        assertFalse(VirtualDisplayManager.isOverlaySizeMatch(720, 1559))
        assertFalse(VirtualDisplayManager.isOverlaySizeMatch(0, 0))
    }

    // ==================== isOurOverlaySpec（修复轮八：启动对账判据） ====================

    @Test
    fun `isOurOverlaySpec - 本应用规格精确命中（含尾随空白容错）`() {
        assertTrue(VirtualDisplayManager.isOurOverlaySpec("720x1560/280"))
        assertTrue(VirtualDisplayManager.isOurOverlaySpec(" 720x1560/280\n"))
    }

    @Test
    fun `isOurOverlaySpec - 用户自开的其它规格不命中（防误删用户配置）`() {
        assertFalse(VirtualDisplayManager.isOurOverlaySpec(""))
        assertFalse(VirtualDisplayManager.isOurOverlaySpec("1920x1080/240"))
        assertFalse(VirtualDisplayManager.isOurOverlaySpec("720x1560/280;1080x1920/320"))
        assertFalse(VirtualDisplayManager.isOurOverlaySpec("none"))
    }
}
