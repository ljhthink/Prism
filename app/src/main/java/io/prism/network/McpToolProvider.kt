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
     * 调用 MCP Server 上的指定工具。
     *
     * @param config 目标 MCP Server 配置
     * @param name 工具名称
     * @param arguments 工具参数（JSON 对象）
     * @return 工具调用结果文本（连接失败时返回错误描述）
     */
    suspend fun callTool(config: McpServerConfig, name: String, arguments: Map<String, Any?>): String
}