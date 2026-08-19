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
}

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
 * @param context 应用上下文
 */
class MlKitOcrTextExtractor(
    private val context: Context
) : OcrTextExtractor {

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

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
    }
}
