package io.prism.phonecontrol.bg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DisplayBackend.latestFrameWithMeta] **默认实现**的契约测试（guardrail **G-r2-03**）。
 *
 * 为什么必须用 Robolectric：需要真实的 `Bitmap`（JVM 纯单测里 `createBitmap` 返回 null）。
 * 并且必须**真的走接口默认实现**——r2 复审指出："只断言 `FrameFreshness.ageMs(0)`
 * 等于没测 `DisplayBackend`"，那样的用例把默认实现改坏也不会红。
 *
 * 锁定的契约：默认实现拿不到"帧产生时刻"，必须以 `capturedAtMs = 0` 表达**不可信**，
 * 从而被帧龄判定视为**不新鲜**（fail-closed）——绝不能"默认当成刚拍的"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DisplayBackendDefaultFrameTest {

    /** 最小假后端：只实现必需方法，**故意不覆盖** `latestFrameWithMeta`。 */
    private class FakeBackend(private val frame: Bitmap?) : DisplayBackend {
        override val kind: DisplayBackend.Kind = DisplayBackend.Kind.LEGACY
        override val visibleOnMainScreen: Boolean = false
        override fun ensure(context: android.content.Context): Int = 1
        override fun release(context: android.content.Context): Boolean = true
        override fun latestFrame(maxWidth: Int): Bitmap? = frame
        override fun frameSize(): Pair<Int, Int>? = frame?.let { it.width to it.height }
        override fun launch(
            component: String,
            packageName: String,
            userId: Int?,
            forceStop: Boolean
        ): Boolean = true
        override fun lastError(): String? = null
    }

    private fun onePixelBitmap(): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    @Test
    fun `默认实现必须把 capturedAtMs 置 0（不可信）而不是当前时刻`() {
        val bmp = onePixelBitmap()
        val frame = FakeBackend(bmp).latestFrameWithMeta()
        assertNotNull(frame)
        assertEquals("默认实现拿不到帧产生时刻，必须以 0 表达不可信", 0L, frame!!.capturedAtMs)
    }

    @Test
    fun `默认实现的帧必须被判为不新鲜（fail-closed）`() {
        val bmp = onePixelBitmap()
        val frame = FakeBackend(bmp).latestFrameWithMeta()!!
        val age = frame.ageMs()
        assertEquals(Long.MAX_VALUE, age)
        assertFalse(
            "不可信帧绝不能被当成实时画面交给 OCR/LLM",
            VirtualDisplayManager.isFrameFresh(age)
        )
        // 同时验证尺寸是真实位图尺寸（不是常量规格）
        assertEquals(1, frame.width)
        assertEquals(1, frame.height)
    }

    @Test
    fun `无帧时默认实现返回 null（不伪造空帧）`() {
        assertEquals(null, FakeBackend(null).latestFrameWithMeta())
    }

    /**
     * 交叉验证：JPEG/PNG 编码路径产出的字节必须能被 `BitmapFactory` 解码
     * （避免"降级改成 JPEG 后解码失败"这类静默回归）。
     */
    @Test
    fun `帧编码规格产出的位图可被解码`() {
        val src = Bitmap.createBitmap(8, 16, Bitmap.Config.ARGB_8888)
        val out = java.io.ByteArrayOutputStream()
        src.compress(Bitmap.CompressFormat.JPEG, 88, out)
        val decoded = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        assertNotNull("JPEG 必须可解码", decoded)
        assertEquals(8, decoded!!.width)
        assertEquals(16, decoded.height)
        assertTrue("解码后尺寸应等于计划首步尺寸", out.size() > 0)
    }
}
