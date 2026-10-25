package io.prism.phonecontrol.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FrameEncodePlan] 单测（guardrail **G-r2-03**）。
 *
 * 这些断言是"降级逻辑被删回 `takeIf { size <= 900KB }`"时会**变红**的那种：
 * 不只是常量范围，而是**行为**（步数、单调性、终止性）。
 */
class FrameEncodePlanTest {

    @Test
    fun `JPEG 计划先降质再降尺寸且步数等于上限`() {
        val plan = FrameEncodePlan.steps(
            baseWidth = 720, baseHeight = 1560, initialQuality = 88, png = false
        )
        assertEquals("步数必须等于配置上限（有界，不在 shell 进程里无限试）",
            HiddenFrameSpec.ENCODE_ATTEMPTS, plan.size)
        assertEquals(720, plan[0].width)
        assertEquals(1560, plan[0].height)
        assertEquals(88, plan[0].quality)
        // 第一步只降质、尺寸不变
        assertEquals(720, plan[1].width)
        assertTrue("第一步应降质", plan[1].quality < plan[0].quality)
        // 质量触底后开始缩尺寸
        assertTrue("后续必须缩小", plan.last().width < plan[0].width)
        assertTrue("后续必须缩小", plan.last().height < plan[0].height)
    }

    @Test
    fun `计划必然终止且尺寸单调不增`() {
        val plan = FrameEncodePlan.steps(
            baseWidth = 720, baseHeight = 1560, initialQuality = 90, png = false
        )
        assertTrue("计划必须终止（否则 shell 进程里会死循环分配位图）",
            FrameEncodePlan.terminates(plan))
        plan.zipWithNext().forEach { (a, b) ->
            assertTrue("尺寸不得变大：${a.width}x${a.height} -> ${b.width}x${b.height}",
                b.width <= a.width && b.height <= a.height)
        }
    }

    @Test
    fun `PNG 不走质量分支而是直接缩小（质量对 PNG 无意义）`() {
        val plan = FrameEncodePlan.steps(
            baseWidth = 720, baseHeight = 1560, initialQuality = 100, png = true
        )
        assertEquals(720, plan[0].width)
        assertEquals(100, plan[0].quality)
        // 第二步就应缩小（而非降质）
        assertTrue("PNG 第二步必须缩小", plan[1].width < plan[0].width)
        assertEquals("PNG 质量恒定", 100, plan[1].quality)
        assertTrue(FrameEncodePlan.terminates(plan))
    }

    @Test
    fun `极小尺寸不会死循环（尺寸钳到 1x1 后提前终止）`() {
        val plan = FrameEncodePlan.steps(
            baseWidth = 2, baseHeight = 2, initialQuality = 60, png = false
        )
        assertTrue("计划必须终止", FrameEncodePlan.terminates(plan))
        assertTrue("必须提前收敛到 1x1", plan.last().width in 1..2)
        assertTrue(plan.last().height in 1..2)
    }

    @Test
    fun `非法输入返回空计划而不是抛异常`() {
        assertTrue(FrameEncodePlan.steps(0, 100, 80, false).isEmpty())
        assertTrue(FrameEncodePlan.steps(100, 0, 80, false).isEmpty())
        assertTrue(FrameEncodePlan.steps(100, 100, 80, false, attempts = 0).isEmpty())
        // 空计划在 terminates 中视为"无循环"，安全
        assertTrue(FrameEncodePlan.terminates(emptyList()))
    }

    @Test
    fun `质量入参越界被钳制（AIDL 契约面防御）`() {
        val high = FrameEncodePlan.steps(100, 100, 500, false)
        assertEquals(100, high[0].quality)
        val low = FrameEncodePlan.steps(100, 100, -5, false)
        assertEquals(1, low[0].quality)
    }

    @Test
    fun `每一步要么降质要么缩小（不存在原地踏步）`() {
        val plan = FrameEncodePlan.steps(
            baseWidth = 1080, baseHeight = 2400, initialQuality = 95, png = false, attempts = 6
        )
        assertTrue(FrameEncodePlan.terminates(plan))
        assertFalse("至少要有一次缩放（否则只是反复降质，体积降不下来）",
            plan.all { it.width == plan[0].width && it.height == plan[0].height })
    }
}
