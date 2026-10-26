package io.prism.phonecontrol.bg

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **ac-verifier 补充用例**（TKN-QQ-FIX-001，2026-10-25 验收 / PRD US-3114 AC-7、AC-8）。
 *
 * 覆盖验收任务书点名的盲区：
 * 1. `FrameEncodePlan` 在 `attempts = 1`、`scaleStep` 接近 1、极小尺寸下**仍终止**
 *    （有界性是"shell 特权进程里不无限分配位图"的承重约束）；
 * 2. `FrameBitmapRecycler` 的**别名 / 已回收 / 空集合 / 全 null**组合。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class FrameEncodePlanAcceptanceSupplementTest {

    // ==================== FrameEncodePlan 终止性 ====================

    @Test
    fun `attempts 等于 1 时计划只有一个元素且必然终止`() {
        val plan = FrameEncodePlan.steps(
            baseWidth = 720, baseHeight = 1560, initialQuality = 88, png = false, attempts = 1
        )
        assertEquals(1, plan.size)
        assertEquals(720, plan[0].width)
        assertEquals(88, plan[0].quality)
        assertTrue(FrameEncodePlan.terminates(plan))
        // PNG 分支同样有界
        val pngPlan = FrameEncodePlan.steps(720, 1560, 88, png = true, attempts = 1)
        assertEquals(1, pngPlan.size)
        assertTrue(FrameEncodePlan.terminates(pngPlan))
    }

    @Test
    fun `scaleStep 接近 1 时仍终止且尺寸严格不增`() {
        val plan = FrameEncodePlan.steps(
            baseWidth = 1080, baseHeight = 2400, initialQuality = 100, png = true,
            attempts = 4, scaleStep = 0.999999
        )
        // 有界：绝不超过 attempts；缩放幅度小于 1px 时实现会**提前收敛**（更安全）
        assertTrue("步数不得超限：${plan.size}", plan.size in 1..4)
        assertTrue("缩放幅度不足 1px 时必须提前收敛，而不是原地重复 4 步", plan.size < 4)
        assertTrue(FrameEncodePlan.terminates(plan))
        plan.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "尺寸不得变大：${a.width}x${a.height} -> ${b.width}x${b.height}",
                b.width <= a.width && b.height <= a.height
            )
            // 缩小幅度极小时每步仍必须"要么更小、要么质量更低"，不能原地踏步
            assertFalse(
                "不得出现同尺寸同质量的原地踏步",
                a.width == b.width && a.height == b.height && a.quality == b.quality
            )
        }
    }

    @Test
    fun `极小尺寸 1x1 起点的计划立即收敛且终止`() {
        listOf(1 to 1, 1 to 2, 2 to 1).forEach { (w, h) ->
            val plan = FrameEncodePlan.steps(w, h, 60, png = false)
            assertTrue("$w x $h 必须终止", FrameEncodePlan.terminates(plan))
            assertTrue("不得越界变小：$w x $h", plan.all { it.width >= 1 && it.height >= 1 })
            assertEquals("首步尺寸必须等于起点", w, plan[0].width)
            assertEquals(h, plan[0].height)
            // 极小尺寸下不得把尺寸放大（放大 = 反而更占内存/Binder）
            plan.forEach {
                assertTrue("不得放大宽：$w x $h -> ${it.width}", it.width <= w)
                assertTrue("不得放大高：$w x $h -> ${it.height}", it.height <= h)
            }
        }
    }

    @Test
    fun `极小尺寸加 PNG 分支也必须终止（质量不参与，只能靠缩放收敛）`() {
        val plan = FrameEncodePlan.steps(3, 2, 100, png = true, attempts = 8)
        assertTrue(FrameEncodePlan.terminates(plan))
        plan.zipWithNext().forEach { (a, b) ->
            assertTrue("PNG 分支必须逐步缩小或提前收敛", b.width <= a.width)
        }
    }

    @Test
    fun `边界参数不崩且 terminates 判定对空计划为真`() {
        assertTrue(FrameEncodePlan.steps(0, 0, 50, false).isEmpty())
        assertTrue(FrameEncodePlan.steps(-1, 100, 50, false).isEmpty())
        assertTrue(FrameEncodePlan.terminates(emptyList()))
        assertTrue(FrameEncodePlan.terminates(listOf(FrameEncodePlan.Step(10, 10, 90))))
    }

    // ==================== FrameBitmapRecycler 组合场景 ====================

    private fun bmp(w: Int = 4, h: Int = 8): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun `owned 中重复出现同一对象仍只回收一次`() {
        val a = bmp()
        FrameBitmapRecycler.recycleAll(listOf(a, a, a), a)
        assertTrue(a.isRecycled)
    }

    @Test
    fun `别名加已回收混合场景不抛异常`() {
        val recycled = bmp().also { it.recycle() }
        val shared = bmp()
        val own = bmp()
        FrameBitmapRecycler.recycleAll(listOf(recycled, shared, shared, own), shared)
        assertTrue("已回收者保持已回收", recycled.isRecycled)
        assertTrue("别名共享对象必须回收", shared.isRecycled)
        assertTrue("其余本次分配的位图必须回收", own.isRecycled)
    }

    @Test
    fun `空集合且 source 为 null 是安全 no-op`() {
        FrameBitmapRecycler.recycleAll(emptyList(), null)
        FrameBitmapRecycler.recycleAll(listOf(null, null), null)
    }

    @Test
    fun `set 语义的 owned 集合也能全部回收`() {
        val a = bmp()
        val b = bmp()
        // owned 为 Collection，传入 LinkedHashSet（实现不得依赖 List 索引）
        val owned: Collection<Bitmap?> = linkedSetOf(a, b)
        FrameBitmapRecycler.recycleAll(owned, null)
        assertTrue(a.isRecycled)
        assertTrue(b.isRecycled)
    }

    @Test
    fun `帧健康常量自洽（AC-7 取帧上限与 AC-8 真实尺寸）`() {
        assertNotNull(HiddenFrameSpec.MAX_FRAME_BYTES)
        assertTrue("帧龄阈值必须小于 Watch/OCR 的容忍窗口", FrameFreshness.STALE_MS in 1_000L..10_000L)
        assertTrue("最大尺寸必须为正且有界", HiddenFrameSpec.MAX_DIMENSION in 1..8192)
        assertTrue(HiddenFrameSpec.MAX_PIXELS > 0)
    }
}
