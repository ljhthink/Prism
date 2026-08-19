package io.prism.phonecontrol

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PhoneControlAccessibilityService.computeScreenshotScale] 单元测试（v1 US-203，截图降采样）。
 *
 * **application 指定**：`application = android.app.Application::class` 避免 Robolectric 按
 * AndroidManifest 加载 [io.prism.PrismApplication]（ObjectBox native 在 Windows JVM 不可用，
 * 见 PdfDocumentParserTest 注释）。
 *
 * 覆盖：达标不缩放 / 超长边等比缩放 / 非法尺寸兜底 1.0。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhoneControlAccessibilityServiceScreenshotTest {

    private fun service(): PhoneControlAccessibilityService =
        Robolectric.buildService(PhoneControlAccessibilityService::class.java).get()

    @Test
    fun `scale returns 1 for screenshot within limit`() {
        val svc = service()
        // 1080x1920 最长边 ≤1024？否；先测已达标（如 720x1280 最长边 1280 >1024 → 缩放）
        // 达标用例：最长边 ≤ 1024
        assertEquals(1f, svc.computeScreenshotScale(720, 1024), 0f)
        assertEquals(1f, svc.computeScreenshotScale(1024, 720), 0f)
        assertEquals(1f, svc.computeScreenshotScale(1, 1), 0f)
    }

    @Test
    fun `scale down tall screenshot to max edge`() {
        val svc = service()
        // 1080x2400（常见全面屏截图）→ 最长边 2400 > 1024 → 缩放因子 = 1024/2400
        val expected = 1024f / 2400f
        assertEquals(expected, svc.computeScreenshotScale(1080, 2400), 1e-4f)
    }

    @Test
    fun `scale down wide screenshot to max edge`() {
        val svc = service()
        // 横屏截图 2400x1080 → 最长边 2400 → 缩放因子 = 1024/2400
        assertEquals(1024f / 2400f, svc.computeScreenshotScale(2400, 1080), 1e-4f)
    }

    @Test
    fun `scale returns 1 for invalid dimensions`() {
        val svc = service()
        assertEquals(1f, svc.computeScreenshotScale(0, 0), 0f)
        assertEquals(1f, svc.computeScreenshotScale(-1, 100), 0f)
    }
}
