package io.prism.crossapp

/**
 * 跨 App 调用用户确认请求数据类（M6，ADR-016）。
 *
 * 扩展 M4 [io.prism.fs.UiConfirmationGate.PendingConfirm] 的语义，携带更丰富的
 * 确认信息供 UI 展示：目标 App 名称 / 操作类型 / 内容预览 / 目标 scheme / 安装状态。
 *
 * **与 UiConfirmationGate 的关系**（ADR-016 R8 缓解）：
 * - [io.prism.fs.UiConfirmationGate] 负责"用户允许执行工具"（布尔确认）
 * - 本类仅作为 [io.prism.fs.ToolConfirmationGate.confirm] 的 arguments 上下文补充，
 *   由 UI 层从 arguments 提取展示信息，不引入第二个确认对话框
 *
 * @property appDisplayName 目标 App 用户可见名称（如 `微信`）
 * @property actionType 操作类型（OPEN 打开 / SHARE 分享 / PICK 选取）
 * @property contentPreview 内容预览（如 `分享文本：Hello World` / `打开微信扫一扫`）
 * @property targetScheme 目标 scheme（如 `weixin://scanqrcode`），用于 UI 展示
 * @property isAppInstalled 目标 App 是否已安装（影响降级提示展示）
 */
data class CrossAppConfirmationRequest(
    val appDisplayName: String,
    val actionType: ActionType,
    val contentPreview: String,
    val targetScheme: String,
    val isAppInstalled: Boolean
) {
    /**
     * 跨 App 操作类型。
     *
     * - [OPEN]：Deep Link 跳转打开目标 App
     * - [SHARE]：Share Sheet 分享内容到目标 App
     * - [PICK]：系统 Picker 选取媒体/文档
     */
    enum class ActionType { OPEN, SHARE, PICK }
}
