package io.prism.fs

/**
 * 用户确认门禁接口 —— 每个文件工具处理器调用前强制确认（ADR-006 5.4，PRD AC-3）。
 *
 * 门禁置于**服务器工具处理器入口**（而非客户端），使确认逻辑与传输层解耦，对任何调用方
 * （含远程 Client）均生效。SAF 初授权只控「可见范围」，不控单次操作，故每次工具调用都需确认。
 *
 * @see io.prism.fs.UiConfirmationGate 生产实现（经 SharedFlow 挂起等待 UI 响应）
 * @see io.prism.fs.ToolConfirmationGate 配套的测试 fake（directly 返回 true/false）
 */
fun interface ToolConfirmationGate {

    /**
     * 请求用户确认一次工具调用。
     *
     * @param toolName 工具名称（如 "read_file"）
     * @param arguments 工具参数（JSON 对象，供 UI 展示）
     * @return true 表示用户允许执行；false 表示用户拒绝（调用方应返回 isError 结果）
     */
    suspend fun confirm(toolName: String, arguments: Map<String, Any?>): Boolean
}