package io.prism.network

import io.prism.data.McpServerConfig
import io.prism.data.McpServerType

/**
 * MCP 工具提供者路由 —— 按 [McpServerConfig.serverType] 分发到本地或远程实现（ADR-006 5.6）。
 *
 * [McpToolProvider] 是单一依赖倒置接口，UI 层无需感知本地 / 远程差异；本类集中路由，
 * 使 CapabilitiesViewModel 仅注入一个 [McpToolProvider] 即可透明处理两种 Server。
 *
 * - LOCAL → [localProvider]（[LocalMcpToolProvider]）
 * - REMOTE → [remoteProvider]（[McpClientManager]）
 */
class McpToolProviderDispatcher(
    private val localProvider: McpToolProvider,
    private val remoteProvider: McpToolProvider
) : McpToolProvider {

    override suspend fun listTools(config: McpServerConfig): List<String> =
        if (config.serverType == McpServerType.LOCAL) localProvider.listTools(config)
        else remoteProvider.listTools(config)

    // B-1 修复（guardrail TKN-P17-GUARDRAIL-001）：原实现未覆写 describeTools，
    // 继承了接口默认 emptyList()，导致生产链路 mcpToolProviderDispatcher.describeTools 恒空，
    // MCP 工具（time 等）无法注入 LLM（Bug-3 实际未修复）。此处按 serverType 分发到本地/远程实现。
    override suspend fun describeTools(config: McpServerConfig): List<ToolDefinition> =
        if (config.serverType == McpServerType.LOCAL) localProvider.describeTools(config)
        else remoteProvider.describeTools(config)

    override suspend fun callTool(
        config: McpServerConfig,
        name: String,
        arguments: Map<String, Any?>
    ): String =
        if (config.serverType == McpServerType.LOCAL) localProvider.callTool(config, name, arguments)
        else remoteProvider.callTool(config, name, arguments)
}