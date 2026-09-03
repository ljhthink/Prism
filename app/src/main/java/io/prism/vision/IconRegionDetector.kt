package io.prism.vision

import android.graphics.Bitmap

/**
 * 屏幕图标区域检测器（v1 批次11 F，D12）—— 纯像素启发式，零新依赖、零端侧模型。
 *
 * **背景**：无障碍树为空（微信/WebView/Flutter）且目标为纯图标按钮（无文字标签）时，OCR 无
 * 文本可锚定。OmniParser（YOLO 图标检测）/ DroidRun（MobileNet 300+ icons）用端侧模型，
 * 移动端成本高。本类用「边缘显著度 + 连通域 + 排除 OCR 文字框」近似图标候选：告诉纯文本
 * LLM"屏幕上有哪些非文字的视觉显著区域（含坐标）"，模型可结合邻近文字推断其用途。
 *
 * **算法**：
 * 1. 灰度化 [luminance]
 * 2. Sobel 边缘显著度 [sobelEdge]（图标/按钮有强边缘，平坦背景无）
 * 3. 排除 OCR 文字包围盒（[detect] 的 excludeBoxes 参数）
 * 4. 4-连通域聚类 [findForegroundRegions] + 尺寸/面积过滤 + 按面积取前 N
 *
 * **可测性**：连通域/过滤核心 [findForegroundRegions] 与 Sobel [sobelEdge] 为纯函数
 * （输入灰度数组 + 尺寸 + 参数），JVM 可直接测；Bitmap 灰度封装 [luminance]。
 *
 * **局限性**（文档明示）：纯启发式无法识别图标"含义"（无模型），且大图/渐变/照片区域可能
 * 被误报为候选——仅作**候选坐标提示**，实际点击仍以文本锚点（tap text）为主路径。
 */
class IconRegionDetector {

    /**
     * 从 Bitmap 检测图标候选区域（位图像素空间）。
     *
     * @param bitmap 截图位图（建议降采样后，最长边 ≤1024px）
     * @param excludeBoxes 需排除的像素区域（OCR 文字包围盒，位图像素空间 `[l,t,r,b]`）
     * @param maxRegions 最大候选数（按面积降序取前 N）
     * @return 图标候选矩形列表（按面积降序）；无候选返回空列表
     */
    fun detect(
        bitmap: Bitmap,
        excludeBoxes: List<IntArray> = emptyList(),
        maxRegions: Int = DEFAULT_MAX_REGIONS
    ): List<IconRegion> {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return emptyList()
        val luma = luminance(bitmap)
        val edge = sobelEdge(luma, w, h, EDGE_THRESHOLD)
        // 前景 = 边缘显著 且 不在文字框内
        val foreground = BooleanArray(w * h)
        for (y in 0 until h) {
            val rowBase = y * w
            for (x in 0 until w) {
                val idx = rowBase + x
                if (edge[idx] && !insideAnyBox(x, y, excludeBoxes)) foreground[idx] = true
            }
        }
        return findForegroundRegions(foreground, w, h, maxRegions)
    }

    /**
     * 位图灰度化（纯工具）：BT.601 亮度公式。
     *
     * @param bitmap 位图
     * @return 每像素亮度 `0..255`（行主序）
     */
    internal fun luminance(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            (0.299f * r + 0.587f * g + 0.114f * b).toInt()
        }
    }

    /**
     * Sobel 边缘显著度（纯函数可测）：返回每像素是否超过 [threshold] 的边缘点。
     *
     * @param luma 灰度数组（[luminance] 输出）
     * @param w 宽度
     * @param h 高度
     * @param threshold 梯度幅值阈值
     * @return 边缘掩码（行主序；边缘为 true）
     */
    internal fun sobelEdge(luma: IntArray, w: Int, h: Int, threshold: Int): BooleanArray {
        val edge = BooleanArray(w * h)
        for (y in 1 until h - 1) {
            val rowBase = y * w
            for (x in 1 until w - 1) {
                val idx = rowBase + x
                val gx = luma[idx - w + 1] - luma[idx - w - 1] +
                    2 * luma[idx + 1] - 2 * luma[idx - 1] +
                    luma[idx + w + 1] - luma[idx + w - 1]
                val gy = luma[idx + w - 1] - luma[idx - w - 1] +
                    2 * luma[idx + w] - 2 * luma[idx - w] +
                    luma[idx + w + 1] - luma[idx - w + 1]
                val mag = kotlin.math.sqrt((gx.toLong() * gx + gy.toLong() * gy).toFloat())
                if (mag >= threshold) edge[idx] = true
            }
        }
        return edge
    }

    /**
     * 前景连通域聚类 + 尺寸/面积过滤（纯函数可测，JVM 直测）。
     *
     * @param foreground 前景掩码（行主序；true 为候选前景像素）
     * @param w 宽度
     * @param h 高度
     * @param maxRegions 最大候选数（按面积降序取前 N）
     * @return 连通域矩形列表（按面积降序）；无候选返回空列表
     */
    internal fun findForegroundRegions(
        foreground: BooleanArray,
        w: Int,
        h: Int,
        maxRegions: Int = DEFAULT_MAX_REGIONS
    ): List<IconRegion> {
        val visited = BooleanArray(w * h)
        val regions = mutableListOf<IconRegion>()
        val stack = ArrayDeque<Int>()
        for (y in 0 until h) {
            val rowBase = y * w
            for (x in 0 until w) {
                val idx = rowBase + x
                if (!foreground[idx] || visited[idx]) continue
                // 4-连通域 BFS
                var minX = x; var maxX = x; var minY = y; var maxY = y
                var count = 0
                stack.clear()
                stack.addLast(idx); visited[idx] = true
                while (stack.isNotEmpty()) {
                    val cur = stack.removeLast()
                    count++
                    val cx = cur % w
                    val cy = cur / w
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                    if (cx > 0) { val n = cur - 1; if (foreground[n] && !visited[n]) { visited[n] = true; stack.addLast(n) } }
                    if (cx < w - 1) { val n = cur + 1; if (foreground[n] && !visited[n]) { visited[n] = true; stack.addLast(n) } }
                    if (cy > 0) { val n = cur - w; if (foreground[n] && !visited[n]) { visited[n] = true; stack.addLast(n) } }
                    if (cy < h - 1) { val n = cur + w; if (foreground[n] && !visited[n]) { visited[n] = true; stack.addLast(n) } }
                }
                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                // 图标尺度过滤：排除整屏边缘带 / 极小噪点
                if (bw < MIN_EDGE_PX || bh < MIN_EDGE_PX) continue
                if (bw > MAX_EDGE_PX || bh > MAX_EDGE_PX) continue
                if (count < MIN_AREA_PX) continue
                regions.add(IconRegion(minX, minY, maxX, maxY))
            }
        }
        return regions
            .sortedByDescending { (it.right - it.left + 1) * (it.bottom - it.top + 1) }
            .take(maxRegions)
    }

    /** 判断像素 (x,y) 是否落在任一排除框内（文字包围盒）。 */
    private fun insideAnyBox(x: Int, y: Int, boxes: List<IntArray>): Boolean =
        boxes.any { b -> b.size >= 4 && x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3] }

    companion object {
        /** 图标候选矩形（位图像素空间 [l,t,r,b]）。 */
        data class IconRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
            fun centerX(): Int = (left + right) / 2
            fun centerY(): Int = (top + bottom) / 2
        }

        /** 单次检测最大候选数（防上下文膨胀，与 [MlKitOcrTextExtractor.MAX_ICONS] 对齐）。 */
        internal const val DEFAULT_MAX_REGIONS = 20

        /** Sobel 梯度幅值阈值（低于视为平坦背景）。 */
        private const val EDGE_THRESHOLD = 48

        /** 图标候选最小边长（px，位图像素空间；过滤噪点）。 */
        private const val MIN_EDGE_PX = 14

        /** 图标候选最大边长（px；过滤整屏横幅/大图——大视觉区域归屏幕理解而非图标候选）。 */
        private const val MAX_EDGE_PX = 400

        /** 图标候选最小面积（px²；过滤孤立边缘点）。 */
        private const val MIN_AREA_PX = 80
    }
}
