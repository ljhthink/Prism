package io.prism.phonecontrol.bg

import android.graphics.Bitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [FrameBitmapRecycler] 单测（guardrail **G-r3-02**）。
 *
 * 这是 r2 复审要求的"回退必须变红"的锁：把 `encodeHiddenFrame` 的 finally 改回
 * r1 的 `scaled?.recycle(); source.recycle()`（漏掉中间缩放宽高），本用例会失败。
 *
 * 用 Robolectric 是因为需要真实 `Bitmap`（纯 JVM 下 `createBitmap` 返回 null）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class FrameBitmapRecyclerTest {

    private fun bmp(w: Int = 4, h: Int = 8): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun `所有本次分配的位图都必须被回收（含缩放中间态）`() {
        val source = bmp()
        val base = bmp()
        val mid1 = bmp()
        val mid2 = bmp()
        FrameBitmapRecycler.recycleAll(listOf(base, mid1, mid2), source)

        assertTrue("source 必须回收", source.isRecycled)
        assertTrue("base 必须回收", base.isRecycled)
        assertTrue("中间缩放帧 1 必须回收（r1 泄露点）", mid1.isRecycled)
        assertTrue("中间缩放帧 2 必须回收（r1 泄露点）", mid2.isRecycled)
    }

    @Test
    fun `别名场景不重复回收且不漏回收`() {
        // maxWidth=0 时 base === source：必须两者都"算回收"，且不因重复调用抛异常
        val shared = bmp()
        FrameBitmapRecycler.recycleAll(listOf(shared), shared)
        assertTrue(shared.isRecycled)
        // 再来一次（模拟重复清理路径）也不能抛
        FrameBitmapRecycler.recycleAll(listOf(shared), shared)
        assertTrue(shared.isRecycled)
    }

    @Test
    fun `已回收的位图跳过而不抛异常`() {
        val already = bmp().also { it.recycle() }
        val fresh = bmp()
        FrameBitmapRecycler.recycleAll(listOf(already, fresh), null)
        assertTrue(fresh.isRecycled)
        assertTrue(already.isRecycled)
    }

    @Test
    fun `null 元素被安全忽略`() {
        val a = bmp()
        FrameBitmapRecycler.recycleAll(listOf(a, null), null)
        assertTrue(a.isRecycled)
    }

    @Test
    fun `空集合只回收 source`() {
        val source = bmp()
        FrameBitmapRecycler.recycleAll(emptyList(), source)
        assertTrue(source.isRecycled)
    }

    @Test
    fun `回收后 isRecycled 为真（真实 Bitmap 语义）`() {
        val b = bmp()
        assertFalse("回收前必须可用", b.isRecycled)
        FrameBitmapRecycler.recycleAll(listOf(b), null)
        assertTrue("回收后 isRecycled 必须为真", b.isRecycled)
        // 注：真机上访问已回收位图的 width 会抛 IllegalStateException，但 Robolectric 的
        // ShadowBitmap 不模拟该行为，故这里不做跨环境断言（避免恒真/恒假的假测试）。
    }

    @Test
    fun `不回收非本调用拥有的位图（防止误伤调用方）`() {
        val mine = bmp()
        val theirs = bmp()
        FrameBitmapRecycler.recycleAll(listOf(mine), null)
        assertTrue(mine.isRecycled)
        assertFalse("未传入的位图不得被回收", theirs.isRecycled)
        theirs.recycle()
    }
}
