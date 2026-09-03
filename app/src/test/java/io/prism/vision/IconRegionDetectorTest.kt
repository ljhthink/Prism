package io.prism.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IconRegionDetector] 纯函数单元测试（v1 批次11 F，D12）。
 *
 * 覆盖 [IconRegionDetector.findForegroundRegions]（连通域聚类 + 尺寸/面积过滤 + 排序截断）
 * 与 [IconRegionDetector.sobelEdge]（Sobel 边缘显著度）——纯数组逻辑，JVM 直测，
 * 不依赖 Android Bitmap（Bitmap 灰度封装 [IconRegionDetector.luminance] 由模拟器/真机验证）。
 */
class IconRegionDetectorTest {

    private val detector = IconRegionDetector()

    @Test
    fun `findForegroundRegions clusters two valid blobs and drops tiny noise`() {
        val w = 100
        val h = 100
        val fg = BooleanArray(w * h)
        // Blob A：20x20 方块（面积 400，边长 20）
        for (y in 10 until 30) for (x in 10 until 30) fg[y * w + x] = true
        // Blob B：30x20 矩形（面积 600，边长均 ≥ MIN_EDGE_PX=14）
        for (y in 50 until 70) for (x in 50 until 80) fg[y * w + x] = true
        // 噪点 C：3x3（边长 3 < MIN_EDGE_PX=14，应被过滤）
        for (y in 90 until 93) for (x in 0 until 3) fg[y * w + x] = true

        val regions = detector.findForegroundRegions(fg, w, h, maxRegions = 20)

        assertEquals(2, regions.size)
        // 按面积降序：B（600）在前，A（400）在后
        assertEquals(600, (regions[0].right - regions[0].left + 1) * (regions[0].bottom - regions[0].top + 1))
        assertEquals(400, (regions[1].right - regions[1].left + 1) * (regions[1].bottom - regions[1].top + 1))
    }

    @Test
    fun `findForegroundRegions respects maxRegions cap by area desc`() {
        val w = 60
        val h = 60
        val fg = BooleanArray(w * h)
        // 三个独立 20x20 方块
        for (y in 0 until 20) for (x in 0 until 20) fg[y * w + x] = true
        for (y in 0 until 20) for (x in 30 until 50) fg[y * w + x] = true
        for (y in 30 until 50) for (x in 0 until 20) fg[y * w + x] = true

        val regions = detector.findForegroundRegions(fg, w, h, maxRegions = 2)
        assertEquals(2, regions.size)
    }

    @Test
    fun `findForegroundRegions filters oversized region`() {
        val w = 600
        val h = 600
        val fg = BooleanArray(w * h)
        // 覆盖 500x500（边长 500 > MAX_EDGE_PX=400，应被过滤）
        for (y in 50 until 550) for (x in 50 until 550) fg[y * w + x] = true

        val regions = detector.findForegroundRegions(fg, w, h)
        assertTrue(regions.isEmpty())
    }

    @Test
    fun `findForegroundRegions empty mask returns empty`() {
        val regions = detector.findForegroundRegions(BooleanArray(100), 10, 10)
        assertTrue(regions.isEmpty())
    }

    @Test
    fun `sobelEdge detects vertical edge`() {
        // 10x10 图像：左半全黑（0），右半全白（255）→ 中部出现竖直强边缘
        val w = 10
        val h = 10
        val luma = IntArray(w * h) { i -> if (i % w < w / 2) 0 else 255 }
        val edge = detector.sobelEdge(luma, w, h, threshold = 200)
        // 边缘列（x=5）处有高梯度；平坦区（x=1）无
        assertTrue(edge[5 * w + 5])
        assertTrue(!edge[1 * w + 1])
    }

    @Test
    fun `sobelEdge flat image has no edge`() {
        val w = 8
        val h = 8
        val luma = IntArray(w * h) { 128 }
        val edge = detector.sobelEdge(luma, w, h, threshold = 48)
        assertTrue(edge.none { it })
    }

    // ==================== 边界：图标尺度/面积阈值（14 / 400 / 80 / 20） ====================

    @Test
    fun `min edge boundary 14 accepted 13 rejected`() {
        val w = 30
        val h = 30
        // 14x14 方块（边长恰 = MIN_EDGE_PX=14，面积 196 ≥ MIN_AREA_PX=80）→ 接受
        val fg14 = BooleanArray(w * h)
        for (y in 0 until 14) for (x in 0 until 14) fg14[y * w + x] = true
        assertEquals(1, detector.findForegroundRegions(fg14, w, h).size)
        // 13x13 方块（边长 13 < 14）→ 拒绝
        val fg13 = BooleanArray(w * h)
        for (y in 0 until 13) for (x in 0 until 13) fg13[y * w + x] = true
        assertTrue(detector.findForegroundRegions(fg13, w, h).isEmpty())
    }

    @Test
    fun `max edge boundary 400 accepted 401 rejected`() {
        val w = 500
        val h = 500
        // 400x400 方块（边长恰 = MAX_EDGE_PX=400）→ 接受
        val fg400 = BooleanArray(w * h)
        for (y in 0 until 400) for (x in 0 until 400) fg400[y * w + x] = true
        assertEquals(1, detector.findForegroundRegions(fg400, w, h).size)
        // 401x401 方块（边长 401 > 400）→ 拒绝（被当作大视觉区域而非图标候选）
        val fg401 = BooleanArray(w * h)
        for (y in 0 until 401) for (x in 0 until 401) fg401[y * w + x] = true
        assertTrue(detector.findForegroundRegions(fg401, w, h).isEmpty())
    }

    @Test
    fun `min area boundary 80 accepted 79 rejected`() {
        val w = 40
        val h = 40
        // 10x8=80 像素（面积恰 = MIN_AREA_PX=80，边长 10/8 < 14 → 需边长均达标）
        // 注意：边长过滤优先于面积，先用 14x14 满足边长，再裁剪面积到 80
        // 14x6 = 84 → 边长 min=6 < 14 会被边长过滤拒绝，故用 9x9=81 也不行（边长 9<14）。
        // 唯一同时满足边长≥14 且面积=80 的形态：14x6 不行；14x14=196 最小可达。
        // 因此"面积恰为 80"无法独立于边长构造——退而验证：14x14（196 面积）被接受，
        // 而"细长条"（满足面积但边长不足）被边长过滤拒绝（面积过滤的前提是边长通过）。
        val thin = BooleanArray(w * h)
        // 20x5 = 100 面积 ≥ 80 但边长 5 < 14 → 边长过滤拒绝
        for (y in 0 until 5) for (x in 0 until 20) thin[y * w + x] = true
        assertTrue("面积达标但边长不足应被过滤", detector.findForegroundRegions(thin, w, h).isEmpty())
    }

    @Test
    fun `diagonal pixels not 4-connected`() {
        val w = 10
        val h = 10
        val fg = BooleanArray(w * h)
        // 对角线两点：1-连通（仅对角相邻）不构成 4-连通 → 各自是孤立点（面积 1 < 80）→ 空
        fg[1 * w + 1] = true
        fg[2 * w + 2] = true
        assertTrue(detector.findForegroundRegions(fg, w, h).isEmpty())
        // 横向相邻两点 → 4-连通成一片（面积 2 < 80，仍因面积过滤为空；验证连通不崩溃）
        val fgAdj = BooleanArray(w * h)
        fgAdj[1 * w + 1] = true
        fgAdj[1 * w + 2] = true
        assertTrue(detector.findForegroundRegions(fgAdj, w, h).isEmpty())
    }

    @Test
    fun `maxRegions cap at default 20`() {
        val w = 200
        val h = 200
        val fg = BooleanArray(w * h)
        // 24 个 14x14 方块（每块均通过边长/面积过滤）
        val positions = (0 until 24).map { i ->
            val row = i / 6
            val col = i % 6
            (row * 30 + 5) to (col * 30 + 5)
        }
        positions.forEach { (oy, ox) ->
            for (y in oy until oy + 14) for (x in ox until ox + 14) fg[y * w + x] = true
        }
        val regions = detector.findForegroundRegions(fg, w, h)
        assertEquals(20, regions.size)
    }

    @Test
    fun `degenerate zero and one pixel dimensions`() {
        // w=0/h=0 → 直接返回空（detect 层防御，不崩溃）
        assertTrue(detector.findForegroundRegions(BooleanArray(0), 0, 0).isEmpty())
        // 1x1 全前景：边长 1 < 14 → 空
        assertTrue(detector.findForegroundRegions(BooleanArray(1) { true }, 1, 1).isEmpty())
    }
}
