package io.prism.vision

import io.prism.phonecontrol.PhoneControlAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1 批次11（A/C）OCR 坐标缩放还原 + 文本锚点相似度 —— 纯函数单元测试。
 *
 * - [MlKitOcrTextExtractor.scaledOcrElement]：降采样空间 → 屏幕空间坐标还原（A 致命修复）。
 * - [PhoneControlAccessibilityService.textSimilarity]：文本锚点模糊匹配（C，UI 树 + OCR 双通道）。
 */
class OcrElementScalingTest {

    // ==================== A：坐标缩放还原 ====================

    @Test
    fun `scaledOcrElement scales coordinates to screen space`() {
        // 降采样位图 461x1024 → 屏幕 1080x2400（scale ≈ 2.34）
        val el = MlKitOcrTextExtractor.scaledOcrElement(
            text = "搜索",
            left = 100, top = 200, right = 160, bottom = 240,
            scaleX = 1080f / 461f,
            scaleY = 2400f / 1024f,
            confidence = 0.9f
        )
        // 中心 (130, 220) × scale → (304, 515)
        assertEquals(304, el.centerX)
        assertEquals(515, el.centerY)
        assertEquals(234, el.left)
        assertEquals(468, el.top)
        assertEquals(374, el.right)
        assertEquals(562, el.bottom)
        assertEquals("搜索", el.text)
        assertEquals(0.9f, el.confidence ?: 0f, 0.001f)
    }

    @Test
    fun `scaledOcrElement no scale when screen size unknown`() {
        // scaleX/scaleY 传 1f（调用方未传屏幕尺寸时）→ 坐标原样（向后兼容）
        val el = MlKitOcrTextExtractor.scaledOcrElement(
            text = "x", left = 10, top = 20, right = 30, bottom = 40,
            scaleX = 1f, scaleY = 1f, confidence = null
        )
        assertEquals(20, el.centerX)
        assertEquals(30, el.centerY)
        assertEquals(10, el.left)
        assertEquals(20, el.top)
        assertEquals(30, el.right)
        assertEquals(40, el.bottom)
    }

    @Test
    fun `scaledOcrElement downscale below 1 halves coordinates`() {
        // 缩小场景（降采样位图反比屏幕更大，罕见但需正确）scale=0.5
        val el = MlKitOcrTextExtractor.scaledOcrElement(
            text = "t", left = 100, top = 100, right = 200, bottom = 200,
            scaleX = 0.5f, scaleY = 0.5f, confidence = null
        )
        // 中心 (150,150) × 0.5 = (75,75)
        assertEquals(75, el.centerX)
        assertEquals(75, el.centerY)
        assertEquals(50, el.left)
        assertEquals(50, el.top)
        assertEquals(100, el.right)
        assertEquals(100, el.bottom)
    }

    @Test
    fun `scaledOcrElement truncates fractional pixels toward zero`() {
        // .5 边界：toInt() 截断（非四舍五入）—— 中心 150.5 × 2 = 301.0 → 301；150.75 × 2 = 301.5 → 301
        val el = MlKitOcrTextExtractor.scaledOcrElement(
            text = "t", left = 150, top = 151, right = 151, bottom = 152,
            scaleX = 2f, scaleY = 2f, confidence = null
        )
        assertEquals(301, el.centerX)
        assertEquals(303, el.centerY)
        assertEquals(300, el.left)
        assertEquals(302, el.top)
        assertEquals(302, el.right)
        assertEquals(304, el.bottom)
    }

    @Test
    fun `scaledOcrElement zero size box stays degenerate`() {
        // 包围盒零尺寸（left==right 或 top==bottom）：中心仍等于该点×scale，不崩溃
        val el = MlKitOcrTextExtractor.scaledOcrElement(
            text = "dot", left = 100, top = 100, right = 100, bottom = 100,
            scaleX = 2f, scaleY = 3f, confidence = 0.5f
        )
        assertEquals(200, el.centerX)
        assertEquals(300, el.centerY)
        assertEquals(0.5f, el.confidence ?: 0f, 0.001f)
    }

    @Test
    fun `scaledOcrElement full screen boundaries stay in range`() {
        // 全屏边界：降采样 1024x1024 → 1080x2400，包围盒顶到角 → 还原坐标不越出屏幕
        val scaleX = 1080f / 1024f
        val scaleY = 2400f / 1024f
        val el = MlKitOcrTextExtractor.scaledOcrElement(
            text = "corner", left = 1020, top = 1015, right = 1024, bottom = 1024,
            scaleX = scaleX, scaleY = scaleY, confidence = null
        )
        assertTrue(el.centerX <= 1080)
        assertTrue(el.centerY <= 2400)
        assertTrue(el.right <= 1080)
        assertTrue(el.bottom <= 2400)
    }

    // ==================== C：文本锚点相似度 ====================

    @Test
    fun `textSimilarity exact match is 1`() {
        assertEquals(1f, PhoneControlAccessibilityService.textSimilarity("搜索", "搜索"), 0.001f)
    }

    @Test
    fun `textSimilarity substring target contains query`() {
        // 目标"搜索"含查询"搜"（查询是目标子串）→ 0.95
        assertEquals(0.95f, PhoneControlAccessibilityService.textSimilarity("搜", "搜索"), 0.001f)
    }

    @Test
    fun `textSimilarity ignores whitespace inserted by OCR`() {
        // OCR 常在中文字符间插空格：查询"搜索" vs 目标"搜 索"
        assertTrue(PhoneControlAccessibilityService.textSimilarity("搜索", "搜 索") > 0.9f)
    }

    @Test
    fun `textSimilarity fuzzy high overlap`() {
        // 轻微误读："崩坏星穹铁道" vs "崩坏星弹道"（字符级 Dice 高重叠）
        val score = PhoneControlAccessibilityService.textSimilarity("崩坏星穹铁道", "崩坏星弹道")
        assertTrue(score > PhoneControlAccessibilityService.TEXT_MATCH_THRESHOLD)
    }

    @Test
    fun `textSimilarity low overlap rejected`() {
        // 无关联文本低于阈值
        val score = PhoneControlAccessibilityService.textSimilarity("发送", "设置")
        assertTrue(score < PhoneControlAccessibilityService.TEXT_MATCH_THRESHOLD)
    }

    @Test
    fun `textSimilarity blank returns 0`() {
        assertEquals(0f, PhoneControlAccessibilityService.textSimilarity("", "搜索"), 0.001f)
        assertEquals(0f, PhoneControlAccessibilityService.textSimilarity("搜索", ""), 0.001f)
    }

    @Test
    fun `textSimilarity case insensitive`() {
        assertEquals(1f, PhoneControlAccessibilityService.textSimilarity("Send", "send"), 0.001f)
    }

    @Test
    fun `threshold constant is defined`() {
        assertTrue(PhoneControlAccessibilityService.TEXT_MATCH_THRESHOLD > 0f)
        assertNull(null as String?)
    }

    // ==================== C-边界：文本相似度阈值（0.6） ====================

    @Test
    fun `textSimilarity exactly at threshold is not adopted`() {
        // 边界：Dice 恰为 0.6（q="abcd" 4 字符 ∩ t="abcefg" 6 字符 = {a,b,c} → 2*3/10=0.6）
        // findNodeByTextNid 采纳条件为 score > threshold（严格大于），恰好等于 0.6 不采纳。
        val score = PhoneControlAccessibilityService.textSimilarity("abcd", "abcefg")
        assertEquals(0.6f, score, 0.0001f)
        assertTrue("恰等于阈值不应采纳（需严格大于）", score <= PhoneControlAccessibilityService.TEXT_MATCH_THRESHOLD)
    }

    @Test
    fun `textSimilarity just above threshold is adopted`() {
        // 略高于 0.6：q="abc"（3 字符）∩ t="abcxy"（5 字符）= 3 → 2*3/8=0.75
        val score = PhoneControlAccessibilityService.textSimilarity("abc", "abcxy")
        assertTrue("0.75 应高于阈值", score > PhoneControlAccessibilityService.TEXT_MATCH_THRESHOLD)
    }

    @Test
    fun `textSimilarity query contains target returns 0_8 score`() {
        // 查询含目标（LLM 锚点更长，如目标=按钮"设置"、查询="打开设置"）
        assertEquals(0.8f, PhoneControlAccessibilityService.textSimilarity("打开设置", "设置"), 0.001f)
    }

    @Test
    fun `textSimilarity target contains query returns 0_95 score`() {
        // 目标含查询（查询=子串，如查询="搜索"、目标="搜索按钮"）
        assertEquals(0.95f, PhoneControlAccessibilityService.textSimilarity("搜索", "搜索按钮"), 0.001f)
    }

    @Test
    fun `textSimilarity long disjoint strings return low score`() {
        // 长文本低重叠：防"长度即重要"误匹配
        val score = PhoneControlAccessibilityService.textSimilarity(
            "今天天气怎么样",
            "元数据文件读写性能基准测试报告"
        )
        assertTrue(score < PhoneControlAccessibilityService.TEXT_MATCH_THRESHOLD)
    }
}
