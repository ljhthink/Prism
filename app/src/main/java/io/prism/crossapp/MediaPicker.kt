package io.prism.crossapp

import android.content.Intent

/**
 * 系统 Picker Intent 构造器（M6，ADR-016）。
 *
 * 纯函数 object，负责构造 [Intent.ACTION_OPEN_DOCUMENT] / [Intent.ACTION_PICK] Intent，
 * 由 [CrossAppLocalToolExecutor] 通过 [AppLauncherBridge.requestIntent] 发送到 UI 层。
 *
 * **支持的选取类型**：
 * - 照片选取（MIME 见 [PHOTO_MIME_TYPE] 常量，ACTION_PICK）
 * - 文档选取（任意 MIME，ACTION_OPEN_DOCUMENT，支持多选）
 *
 * **设计原则**（Karpathy Guidelines §2 简洁优先）：
 * - 只负责构造 Intent，不负责 launcher 注册（launcher 在 Compose 层 rememberLauncherForActivityResult）
 * - 纯函数，无副作用，可纯 JVM 测试
 *
 * **Android 13+ Photo Picker 说明**：
 * Android 13+ 提供系统级 Photo Picker（ActivityResultContracts.PickVisualMedia），
 * 但本实现统一用 ACTION_PICK + StartActivityForResult，兼容 Android 8.0+（API 26+）。
 * 未来可扩展为版本自适应（Android 13+ 用 PickVisualMedia，低版本用 ACTION_PICK）。
 */
object MediaPicker {

    /** 照片选取默认 MIME 类型。 */
    const val PHOTO_MIME_TYPE = "image/*"

    /** 文档选取默认 MIME 类型。 */
    const val DOCUMENT_MIME_TYPE = "*/*"

    /**
     * 构造照片选取 Intent。
     *
     * @param mimeType MIME 类型（默认 [PHOTO_MIME_TYPE]）
     * @return [Intent.ACTION_PICK] Intent，带 [Intent.FLAG_ACTIVITY_NEW_TASK]
     */
    fun buildPhotoPickerIntent(mimeType: String = PHOTO_MIME_TYPE): Intent {
        return Intent(Intent.ACTION_PICK).apply {
            type = mimeType
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 构造文档选取 Intent。
     *
     * @param mimeType MIME 类型（默认 [DOCUMENT_MIME_TYPE]，即所有类型）
     * @param allowMultiple 是否允许多选（默认 false）
     * @return [Intent.ACTION_OPEN_DOCUMENT] Intent，带 [Intent.CATEGORY_OPENABLE] 和 [Intent.FLAG_ACTIVITY_NEW_TASK]
     */
    fun buildDocumentPickerIntent(
        mimeType: String = DOCUMENT_MIME_TYPE,
        allowMultiple: Boolean = false
    ): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = mimeType
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
