package io.prism.network

import io.prism.data.McpServerConfig

/**
 * MCP 工具调用抽象 —— 依赖倒置接口（对齐 [ChatStreamProvider] 模式，ADR-005 5.3）。
 *
 * 定义 MCP Server 的工具发现与调用能力，具体实现由 [McpClientManager] 提供，
 * 便于测试注入 fake 实现。
 */
interface McpToolProvider {

    /**
     * 列出 MCP Server 上可用的工具名称。
     *
     * @param config 目标 MCP Server 配置
     * @return 工具名称列表（连接失败时返回空列表）
     */
    suspend fun listTools(config: McpServerConfig): List<String>

    /**
     * 返回 MCP Server 上可用的工具定义（DEF-008，Bug-3）。
     *
     * 供 [ConversationViewModel] 注入到 LLM `tools` 列表，使 LLM 能感知并调用 MCP 工具。
     * 默认返回空列表（向后兼容：未实现 describeTools 的 Provider 不注入任何工具）。
     *
     * @param config 目标 MCP Server 配置
     * @return 工具定义列表（可注入 LLM tools；连接失败/无工具时返回空列表）
     */
    suspend fun describeTools(config: McpServerConfig): List<ToolDefinition> = emptyList()

    /**
     * 调用 MCP Server 上的指定工具。
     *
     * @param config 目标 MCP Server 配置
     * @param name 工具名称
     * @param arguments 工具参数（JSON 对象）
     * @return 工具调用结果文本（连接失败时返回错误描述）
     */
    suspend fun callTool(config: McpServerConfig, name: String, arguments: Map<String, Any?>): String

    /**
     * 连接诊断（v1 批次16 US-1602）：带错误分类的连接测试。
     *
     * 与 [listTools] 的区别：失败时**不静默**，返回结构化错误类别与用户可读原因，
     * 供设置页「测试连接」展示具体失败层（网络/认证/协议等）。
     * 默认实现委托 [listTools]（「空列表」语义上无法与失败区分，归为 UNKNOWN——
     * 远程实现 [McpClientManager] 已覆盖透出真实分类；「连接成功但 0 工具」场景
     * 由覆盖实现以 success=true + toolCount=0 表达）。
     */
    suspend fun diagnose(config: McpServerConfig): McpConnectionDiagnostic {
        val tools = listTools(config)
        return if (tools.isNotEmpty()) {
            McpConnectionDiagnostic(success = true, toolCount = tools.size)
        } else {
            McpConnectionDiagnostic(success = false, errorKind = McpErrorKind.UNKNOWN, errorMessage = "无法获取工具列表")
        }
    }
}

/** MCP 错误类别（US-1602）：设置页「测试连接」展示具体失败层。 */
enum class McpErrorKind {
    /** 成功。 */
    NONE,

    /** 连接被拒绝（服务未启动 / 端口错误 / adb reverse 未设置）。 */
    CONNECTION_REFUSED,

    /** 连接超时。 */
    TIMEOUT,

    /** 明文 http 被系统拦截（仅 localhost 放行；见 BR-network-004）。 */
    PLAINTEXT_BLOCKED,

    /** TLS/证书错误。 */
    TLS,

    /** 认证失败（Key 缺失/错误，401/403）。 */
    AUTH,

    /** MCP 协议错误（服务端返回非预期响应）。 */
    PROTOCOL,

    /** 连接中断（链路重置 / unexpected end of stream——常见于 USB 重插后陈旧连接）。 */
    NETWORK,

    /** Base URL 非法。 */
    INVALID_URL,

    /** 未知错误。 */
    UNKNOWN
}

/**
 * 连接诊断结果（US-1602）。
 *
 * @param success 连接与工具发现是否成功
 * @param toolCount 成功时的工具数量
 * @param errorKind 失败时的错误类别
 * @param errorMessage 用户可读的失败原因（已脱敏，不含 Key/内部路径）
 */
data class McpConnectionDiagnostic(
    val success: Boolean,
    val toolCount: Int = 0,
    val errorKind: McpErrorKind = McpErrorKind.NONE,
    val errorMessage: String? = null
)