package io.prism.data

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

/**
 * MCP Server 配置实体 —— 定义可连接的 MCP Server 端点。
 *
 * 每条记录对应一个用户可连接的 MCP Server（如 Filesystem、GitHub、Context7 等），
 * 供 AI 通过 MCP 协议调用其工具（listTools / callTool）。
 *
 * **字段说明**：
 * - [id] 主键
 * - [name] Server 显示名称（如 "Filesystem"、"GitHub"）
 * - [serverType] Server 类型："LOCAL"（内置本地 Server）/ "REMOTE"（远程 Server）
 * - [transport] 传输类型："STREAMABLE_HTTP"（本期仅支持）/ "SSE"（预留）
 * - [baseUrl] MCP Server 端点 URL（如 "https://mcp.context7.com/mcp"）
 * - [apiKeyRef] API Key 引用标识，对应 [io.prism.security.ApiKeyRepository] 中存储的 key
 *   （不存储明文 API Key，明文由 Keystore 加密保护）
 * - [headers] 自定义 HTTP 请求头（如 {"Authorization": "Bearer ..."}）
 * - [isEnabled] 是否启用（启用后 AI 可调用其工具）
 * - [createdAt] 创建时间戳（毫秒）
 *
 * **类型转换**：
 * - [headers] 通过 [StringMapConverter] 序列化为 String 存储
 *
 * ADR-005 5.2：@Entity 实体模式仿 ProviderConfig（US-004）。
 *
 * @see StringMapConverter
 */
@Entity
data class McpServerConfig(
    @Id var id: Long = 0,
    var name: String,
    var serverType: String = McpServerType.REMOTE,
    var transport: String = McpTransport.STREAMABLE_HTTP,
    var baseUrl: String,
    var apiKeyRef: String = "",
    @Convert(converter = StringMapConverter::class, dbType = String::class)
    var headers: Map<String, String> = emptyMap(),
    var isEnabled: Boolean = false,
    var createdAt: Long = System.currentTimeMillis()
)

/** MCP Server 类型常量。 */
object McpServerType {
    const val LOCAL = "LOCAL"
    const val REMOTE = "REMOTE"
}

/** MCP 传输类型常量。 */
object McpTransport {
    const val STREAMABLE_HTTP = "STREAMABLE_HTTP"
    const val SSE = "SSE"
}