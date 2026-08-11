package io.prism.crossapp

import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.URLEncoder
import java.util.Locale

/**
 * 跨 App 调用核心入口（M6，ADR-016）。
 *
 * 组合 [SchemeRegistry]（配置查询）+ [AppAvailabilityChecker]（安装检测）+
 * [AppLauncherBridge]（ActivityResult 桥接），提供三个核心能力：
 *
 * 1. [launchApp]：Deep Link 跳转打开目标 App
 * 2. [shareContent]：Share Sheet 分享文本/链接
 * 3. [pickMedia]：系统 Picker 选取媒体/文档
 *
 * **降级策略**（ADR-016 R1/R4/R7 缓解）：
 * - 目标 App 未安装：返回失败描述，提示 fallbackUrl 或手动打开
 * - scheme 跳转失败（ActivityNotFoundException）：由 UI 层捕获，回灌"未安装"消息
 * - 超时（30s）：由 [AppLauncherBridge] 兜底，返回"跨 App 调用超时"
 *
 * **结果文本格式**（回灌给 LLM，与 M4 SkillExecutor 降级文案一致）：
 * - 成功：`"已打开微信"` / `"已分享文本"` / `"已选取照片"`
 * - 失败：`"未安装微信，可访问 https://weixin.qq.com/ 或手动打开"` / `"跨 App 调用超时"`
 */
open class CrossAppLauncher(
    private val schemeRegistry: SchemeRegistry,
    private val availabilityChecker: AppAvailabilityChecker,
    private val bridge: AppLauncherBridge
) {
    // 注：M6 Phase B 将 launchApp/shareContent/pickMedia 标记 open 以支持
    // CrossAppLocalToolExecutor 单元测试注入 fake 子类（与 SkillExecutor.open 模式一致，BR-testing-004）
    companion object {
        private const val TAG = "CrossAppLauncher"
    }

    /**
     * Deep Link 跳转打开目标 App。
     *
     * **流程**：
     * 1. 从 [schemeRegistry] 查询 appId 配置；无匹配返回失败
     * 2. 检测 App 安装状态；未安装返回失败 + fallbackUrl 提示
     * 3. 解析 action scheme（[DeepLinkLauncher.resolveAction]）
     * 4. 替换模板占位符（如 `{itemId}` → 实际值，URL 编码，BR-security-006）
     * 5. 构造 ACTION_VIEW Intent（[DeepLinkLauncher.buildIntent]）
     * 6. 通过 [bridge.requestIntent] 发送，等待 ActivityResult
     *
     * @param appId 目标 App 标识符（如 `wechat`）
     * @param action 功能名（如 `scan`）；null 时使用默认 action
     * @param params 模板替换参数（如 `mapOf("itemId" to "123456")`），
     *   值将经 [URLEncoder.encode] 编码后替换 scheme 中的 `{key}` 占位符（BR-security-006）
     * @return 结果文本（成功/失败描述，回灌给 LLM）
     */
    open suspend fun launchApp(
        appId: String,
        action: String? = null,
        params: Map<String, String> = emptyMap()
    ): String {
        val entry = schemeRegistry.getAppById(appId)
            ?: return "未找到应用配置: $appId"

        val installed = availabilityChecker.isAppInstalled(entry)
        if (!installed) {
            val fallbackHint = entry.fallbackUrl?.let { "，可访问 $it 或手动打开" } ?: "，请手动打开"
            return "未安装${entry.displayName}$fallbackHint"
        }

        val rawScheme = DeepLinkLauncher.resolveAction(entry, action)
        val resolvedScheme = resolveTemplates(rawScheme, params)
        val intent = DeepLinkLauncher.buildIntent(resolvedScheme)
        Log.d(TAG, "launchApp: ${entry.displayName} action=$action scheme=${intent.data}")
        return bridge.requestIntent(intent)
    }

    /**
     * Share Sheet 分享文本内容。
     *
     * @param text 待分享文本
     * @param chooserTitle chooser 标题（默认 "分享到..."）
     * @return 结果文本（成功/失败描述）
     */
    open suspend fun shareContent(
        text: String,
        chooserTitle: String = ShareSheetLauncher.DEFAULT_CHOOSER_TITLE
    ): String {
        val intent = ShareSheetLauncher.buildShareTextIntent(text, chooserTitle)
        Log.d(TAG, "shareContent: text length=${text.length}")
        return bridge.requestIntent(intent)
    }

    /**
     * 系统 Picker 选取媒体/文档。
     *
     * @param mediaType 媒体类型（`photo` 照片 / `document` 文档）
     * @param mimeType MIME 类型（photo 默认 [MediaPicker.PHOTO_MIME_TYPE]，document 默认 [MediaPicker.DOCUMENT_MIME_TYPE]）
     * @param allowMultiple 是否允许多选（仅文档选取有效，默认 false）
     * @return 结果文本（成功/失败描述）
     */
    open suspend fun pickMedia(
        mediaType: String,
        mimeType: String? = null,
        allowMultiple: Boolean = false
    ): String {
        val intent: Intent = when (mediaType.lowercase(Locale.ROOT)) {
            "photo", "image" -> {
                MediaPicker.buildPhotoPickerIntent(mimeType ?: MediaPicker.PHOTO_MIME_TYPE)
            }
            "document", "file" -> {
                MediaPicker.buildDocumentPickerIntent(
                    mimeType ?: MediaPicker.DOCUMENT_MIME_TYPE,
                    allowMultiple
                )
            }
            else -> {
                return "不支持的媒体类型: $mediaType（支持 photo / document）"
            }
        }
        Log.d(TAG, "pickMedia: type=$mediaType mimeType=${intent.type}")
        return bridge.requestIntent(intent)
    }

    /**
     * 获取所有已配置的 App 列表（供 LLM 工具描述展示）。
     *
     * @return App 配置列表
     */
    fun getConfiguredApps(): List<AppSchemeEntry> = schemeRegistry.getAllApps()

    /**
     * 获取指定 App 的配置（供 UI 确认对话框展示 App 名称）。
     *
     * @param appId App 标识符
     * @return App 配置；无匹配时 null
     */
    fun getAppConfig(appId: String): AppSchemeEntry? = schemeRegistry.getAppById(appId)

    /**
     * 取消所有待处理的跨 App 调用（Activity 销毁时调用）。
     */
    fun cancelAll() = bridge.cancelAll()

    /**
     * 替换 scheme 字符串中的模板占位符（BR-security-006）。
     *
     * **安全要求**：用户/LLM 提供的参数值必须经 [URLEncoder.encode] 编码后再替换，
     * 防止 URI 注入（额外查询参数注入、scheme 切换）。
     *
     * 示例：`taobao://item?id={itemId}` + `mapOf("itemId" to "123&evil")`
     *       → `taobao://item?id=123%26evil`（`&` 被编码，无法注入额外参数）
     *
     * @param scheme 含 `{key}` 占位符的原始 scheme
     * @param params 替换参数 Map；空时原样返回
     * @return 替换后的 scheme（占位符已编码填充）
     */
    internal fun resolveTemplates(scheme: String, params: Map<String, String>): String {
        if (params.isEmpty()) return scheme
        var result = scheme
        for ((key, value) in params) {
            val encoded = URLEncoder.encode(value, "UTF-8")
            result = result.replace("{$key}", encoded)
        }
        return result
    }
}
