package io.prism.crossapp

import android.content.Intent
import android.net.Uri

/**
 * Share Sheet 分享 Intent 构造器（M6，ADR-016）。
 *
 * 纯函数 object，负责构造 [Intent.ACTION_SEND] Intent + [Intent.createChooser]，
 * 由 [CrossAppLocalToolExecutor] 通过 [AppLauncherBridge.requestIntent] 发送到 UI 层。
 *
 * **支持类型**：
 * - 文本分享（`text/plain`）
 * - 图片分享（MIME 为 image 类型，需提供内容 Uri）
 *
 * **设计原则**（Karpathy Guidelines §2 简洁优先）：
 * - 只负责构造 Intent，不负责启动
 * - 纯函数，无副作用，可纯 JVM 测试
 */
object ShareSheetLauncher {

    /** 默认 chooser 标题。 */
    const val DEFAULT_CHOOSER_TITLE = "分享到..."

    /**
     * 构造文本分享 Intent（带 chooser）。
     *
     * @param text 待分享文本
     * @param chooserTitle chooser 标题（默认 [DEFAULT_CHOOSER_TITLE]）
     * @return [Intent.ACTION_SEND] + chooser Intent，带 [Intent.FLAG_ACTIVITY_NEW_TASK]
     */
    fun buildShareTextIntent(
        text: String,
        chooserTitle: String = DEFAULT_CHOOSER_TITLE
    ): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return Intent.createChooser(sendIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 构造图片分享 Intent（带 chooser）。
     *
     * @param imageUri 图片内容 Uri（调用方需确保 Uri 可被其他 App 读取，如通过 FileProvider）
     * @param chooserTitle chooser 标题（默认 [DEFAULT_CHOOSER_TITLE]）
     * @return [Intent.ACTION_SEND] + chooser Intent
     */
    fun buildShareImageIntent(
        imageUri: Uri,
        chooserTitle: String = DEFAULT_CHOOSER_TITLE
    ): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return Intent.createChooser(sendIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
