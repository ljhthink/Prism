package io.prism.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 端侧 OCR 文字提取器接口（v1 US-302，方案 B OCR 兜底）。
 *
 * 纯文本模型旁路失败时，对含文字图片（截图/票据/文档照片）提取文字注入文本模型。
 * 实现依赖注入以便 JVM 单测 mock。
 *
 * 注意：**不使用 `fun interface`**——Kotlin fun interface 不支持 suspend SAM 转换
 * （fun interface 的抽象方法不能是挂起函数），调用方以 lambda 包装即可。
 */
interface OcrTextExtractor {
    /**
     * 从图片 data URL 提取文字。
     *
     * @param imageDataUrl 图片 data URL（`data:image/...;base64,...`）
     * @return 提取到的文字（去首尾空白）；无文字 / 解析失败返回 null
     */
    suspend fun extractText(imageDataUrl: String): String?

    /**
     * 从图片 data URL 提取文字元素（含屏幕坐标）。v1 批次11（D9 + A/B）：
     * 供纯文本模型在 UI 树受限时基于 OCR 文字+坐标执行 tap/swipe。
     *
     * **v1 批次11（A 致命修复，坐标空间还原）**：OCR 运行在**降采样位图**上（截图最长边
     * ≤1024px），原始实现直接返回降采样像素坐标 → tap 在全屏空间执行时整体缩放错位
     * （差约 2.3 倍），导致"点不到目标"。本接口新增 [screenWidth]/[screenHeight] 屏幕原始
     * 尺寸，实现内把坐标还原到屏幕空间（与 UI 树 bounds / tap 同一坐标系）。
     *
     * **v1 批次11（B 质量）**：实现按**行级**聚合文本（`line.text` 整行 + 行包围盒），
     * 替代碎片化 element 粒度；附置信度过滤。
     *
     * @param imageDataUrl 图片 data URL
     * @param screenWidth 屏幕原始宽度（截图未降采样前的物理像素宽；≤0 时不缩放）
     * @param screenHeight 屏幕原始高度（≤0 时不缩放）
     * @return 文字元素列表（屏幕空间坐标）；无文字/失败返回空列表
     */
    suspend fun extractElements(
        imageDataUrl: String,
        screenWidth: Int = 0,
        screenHeight: Int = 0
    ): List<OcrElement> = emptyList()

    /**
     * 从图片 data URL 检测「非文字视觉区域」（图标/按钮候选）。v1 批次11（F，D12）：
     * 无障碍树为空且目标为纯图标按钮（无文字标签）时，OCR 无文本可锚定。本方法用
     * 纯像素启发式（边缘显著度 + 连通域 + 排除 OCR 文字框）近似图标候选，输出与
     * [extractElements] 同坐标系（屏幕空间）的占位元素（text="图标"），供纯文本模型
     * 结合邻近文字推断其用途并用编号/坐标 tap。
     *
     * @param imageDataUrl 图片 data URL
     * @param ocrElements [extractElements] 已返回的文字元素（用于排除文字区域）
     * @param screenWidth 屏幕原始宽度（≤0 时不缩放）
     * @param screenHeight 屏幕原始高度（≤0 时不缩放）
     * @return 图标候选元素列表（text="图标"）；无候选/失败返回空列表
     */
    suspend fun detectIcons(
        imageDataUrl: String,
        ocrElements: List<OcrElement>,
        screenWidth: Int = 0,
        screenHeight: Int = 0
    ): List<OcrElement> = emptyList()
}

/**
 * OCR 元素（文字 + 屏幕坐标 + 包围盒）。v1 批次11（D9/A/B）：供纯文本模型在 UI 树受限时，
 * 基于 OCR 文字 + 坐标执行 tap/swipe（微信/WebView/Flutter 等无障碍树为空场景）。
 *
 * @property text 识别到的文字（图标候选为固定占位"图标"）
 * @property centerX / centerY 元素中心点坐标（**屏幕空间**，与截图降采样后比例一致时需
 *    经 [extractElements] 的 [screenWidth]/[screenHeight] 还原；可直接用于 tap）
 * @property confidence 置信度（0~1；可能为 null）
 * @property left/top/right/bottom 包围盒（屏幕空间；供图标检测排除文字区域 / 未来区域级操作）
 */
data class OcrElement(
    val text: String,
    val centerX: Int,
    val centerY: Int,
    val confidence: Float? = null,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

/**
 * ML Kit 文字识别实现（v1 US-302，bundled 中文模型，离线）。
 *
 * **选型**（调研结论）：Google ML Kit Text Recognition v2 bundled 中文
 * （`com.google.mlkit:text-recognition-chinese:16.0.1`）——中文识别质量优、零 NDK、
 * 离线、不依赖 GMS/Firebase（F-Droid 友好），优于 Tesseract（停维护/中文弱）与
 * PaddleOCR 端侧（无官方 Gradle 库/体积大）。
 *
 * **线程**：ML Kit `Tasks.await` 阻塞，包装在 [Dispatchers.IO]。
 *
 * **v1 批次11（A）坐标还原**：OCR 在降采样位图上执行，返回坐标须按
 * `screenW/bitmapW`、`screenH/bitmapH` 还原到屏幕空间，否则 tap 整体错位。
 *
 * **v1 批次11（F）图标检测**：委托 [IconRegionDetector] 做纯像素启发式检测。
 *
 * @param context 应用上下文
 */
class MlKitOcrTextExtractor(
    private val context: Context
) : OcrTextExtractor {

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /** 图标区域检测器（v1 批次11 F，D12）。 */
    private val iconDetector = IconRegionDetector()

    override suspend fun extractText(imageDataUrl: String): String? = withContext(Dispatchers.IO) {
        val bitmap = decodeImageDataUrl(imageDataUrl) ?: return@withContext null
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            val text = result.text.trim()
            if (text.isBlank()) null else text
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // BR-error-handling-007：协程取消必须重抛
        } catch (e: Exception) {
            Log.w(TAG, "extractText: OCR 失败（${e::class.simpleName}）")
            null
        }
    }

    /**
     * 从图片 data URL 提取文字元素（含屏幕坐标）。v1 批次11（D9 + A/B）。
     *
     * 供纯文本模型（deepseek 等）在 UI 树受限（微信/WebView/Flutter）时，基于 OCR 文字 +
     * 坐标执行 tap/swipe。
     *
     * **v1 批次11（A 致命修复）**：ML Kit 在**降采样位图**上识别，坐标初始为降采样像素空间；
     * 传入屏幕原始尺寸 [screenWidth]/[screenHeight] 后按比例还原到屏幕空间（与
     * `captureScreenshot()` 降采样前同一坐标系，可直接用于 tap）。未传屏幕尺寸时返回
     * 降采样坐标（向后兼容）。
     *
     * **v1 批次11（B 质量）**：改为**行级**聚合（[Text.Line.text] 整行 + 行包围盒中心），
     * 避免碎片化 element 粒度导致纯文本模型无法匹配目标；按置信度过滤明显噪声。
     *
     * @param imageDataUrl 图片 data URL
     * @param screenWidth 屏幕原始宽度（≤0 不缩放）
     * @param screenHeight 屏幕原始高度（≤0 不缩放）
     * @return 文字元素列表（按 y 从上到下）；无文字 / 失败返回空列表
     */
    override suspend fun extractElements(
        imageDataUrl: String,
        screenWidth: Int,
        screenHeight: Int
    ): List<OcrElement> = withContext(Dispatchers.IO) {
        val bitmap = decodeImageDataUrl(imageDataUrl) ?: return@withContext emptyList()
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            // A：计算降采样空间 → 屏幕空间的缩放因子（bitmap 为降采样后尺寸）
            val scaleX = if (screenWidth > 0) screenWidth.toFloat() / bitmap.width else 1f
            val scaleY = if (screenHeight > 0) screenHeight.toFloat() / bitmap.height else 1f
            result.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    val text = line.text.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    // B：置信度过滤（line 级置信度；ML Kit 部分版本可能抛异常，容错取 null）
                    val conf = try { line.confidence } catch (_: Exception) { null }
                    if (conf != null && conf < MIN_CONFIDENCE) return@mapNotNull null
                    val box = line.boundingBox ?: return@mapNotNull null
                    scaledOcrElement(text, box.left, box.top, box.right, box.bottom, scaleX, scaleY, conf)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "extractElements: OCR 失败（${e::class.simpleName}）")
            emptyList()
        }
    }

    /**
     * 从图片 data URL 检测非文字视觉区域（图标/按钮候选）。v1 批次11（F，D12）。
     *
     * 委托 [IconRegionDetector] 做纯像素启发式（边缘显著度连通域 + 排除 OCR 文字框），
     * 返回与 [extractElements] 同一坐标系（屏幕空间）的占位元素（text="图标"）。
     *
     * @param imageDataUrl 图片 data URL
     * @param ocrElements [extractElements] 已返回的文字元素（屏幕空间包围盒，用于排除文字区域）
     * @param screenWidth 屏幕原始宽度（≤0 不缩放）
     * @param screenHeight 屏幕原始高度（≤0 不缩放）
     * @return 图标候选元素列表；无候选/失败返回空列表
     */
    override suspend fun detectIcons(
        imageDataUrl: String,
        ocrElements: List<OcrElement>,
        screenWidth: Int,
        screenHeight: Int
    ): List<OcrElement> = withContext(Dispatchers.IO) {
        val bitmap = decodeImageDataUrl(imageDataUrl) ?: return@withContext emptyList()
        try {
            // 图标检测在降采样位图上进行；文字排除框需从屏幕空间映射回降采样空间
            val invScaleX = if (screenWidth > 0) bitmap.width.toFloat() / screenWidth else 1f
            val invScaleY = if (screenHeight > 0) bitmap.height.toFloat() / screenHeight else 1f
            val textBoxes = ocrElements.mapNotNull { el ->
                if (el.right > el.left && el.bottom > el.top) {
                    intArrayOf(
                        (el.left * invScaleX).toInt(),
                        (el.top * invScaleY).toInt(),
                        (el.right * invScaleX).toInt(),
                        (el.bottom * invScaleY).toInt()
                    )
                } else {
                    null
                }
            }
            val regions = iconDetector.detect(bitmap, textBoxes, MAX_ICONS)
            val scaleX = if (screenWidth > 0) screenWidth.toFloat() / bitmap.width else 1f
            val scaleY = if (screenHeight > 0) screenHeight.toFloat() / bitmap.height else 1f
            regions.map { r ->
                OcrElement(
                    text = ICON_PLACEHOLDER_TEXT,
                    centerX = (r.centerX() * scaleX).toInt(),
                    centerY = (r.centerY() * scaleY).toInt(),
                    confidence = null,
                    left = (r.left * scaleX).toInt(),
                    top = (r.top * scaleY).toInt(),
                    right = (r.right * scaleX).toInt(),
                    bottom = (r.bottom * scaleY).toInt()
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "detectIcons: 图标检测失败（${e::class.simpleName}）")
            emptyList()
        }
    }

    /**
     * 解码图片 data URL 为 Bitmap（纯工具，可测）。
     *
     * @param imageDataUrl `data:image/jpeg;base64,<payload>` 或裸 base64
     * @return 解码后的 Bitmap；失败返回 null
     */
    internal fun decodeImageDataUrl(imageDataUrl: String): Bitmap? {
        return try {
            val base64 = if (imageDataUrl.contains(",")) {
                imageDataUrl.substringAfter(',')
            } else {
                imageDataUrl
            }
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "decodeImageDataUrl: 图片解码失败（${e::class.simpleName}）")
            null
        }
    }

    companion object {
        private const val TAG = "MlKitOcr"

        /** 图标候选占位文本（纯文本模型据此识别"这是非文字可点击区域"）。 */
        internal const val ICON_PLACEHOLDER_TEXT = "图标"

        /** 单次图标检测最大候选数（防上下文膨胀）。 */
        internal const val MAX_ICONS = 20

        /** 行级置信度下限（低于则视为噪声丢弃；null 置信度不过滤）。 */
        private const val MIN_CONFIDENCE = 0.15f

        /**
         * v1 批次11（A）：把降采样空间的包围盒缩放为屏幕空间 [OcrElement]（纯函数可测）。
         *
         * OCR 在降采样位图（最长边 ≤1024px）上识别，坐标须按 `scaleX = screenW/bitmapW`、
         * `scaleY = screenH/bitmapH` 还原到屏幕空间（与 tap/UI 树 bounds 同坐标系），
         * 否则 tap 整体缩放错位。中心取包围盒中心×缩放（与 ML Kit boundingBox.centerX 一致）。
         *
         * @param text 识别文本
         * @param left/top/right/bottom 包围盒（降采样像素空间）
         * @param scaleX/scaleY 缩放因子（屏幕空间/降采样空间；≤0 时为 1 不缩放）
         * @param confidence 置信度
         * @return 屏幕空间的 [OcrElement]
         */
        internal fun scaledOcrElement(
            text: String,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            scaleX: Float,
            scaleY: Float,
            confidence: Float?
        ): OcrElement = OcrElement(
            text = text,
            centerX = (((left + right) / 2f) * scaleX).toInt(),
            centerY = (((top + bottom) / 2f) * scaleY).toInt(),
            confidence = confidence,
            left = (left * scaleX).toInt(),
            top = (top * scaleY).toInt(),
            right = (right * scaleX).toInt(),
            bottom = (bottom * scaleY).toInt()
        )
    }
}
