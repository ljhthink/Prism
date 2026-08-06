package io.prism.data

/**
 * 预设 MCP Server 模板 —— 开箱即用的本地与远程 Server。
 *
 * 用户可基于这些模板创建 McpServerConfig，填入 API Key（远程）后即可使用。
 * 模板中的 [McpServerConfig.apiKeyRef] 使用 Server 标识符（如 "context7"），
 * 对应 [io.prism.security.ApiKeyRepository] 中存储的加密 API Key。
 *
 * **形态 B（内置本地 Server，零配置）**：Filesystem / Fetch / Memory / Sequential Thinking / Time / 跨 App，共 6 个。
 * **形态 A（预设远程 Server 模板，用户填 Key 一键添加）**：GitHub / Notion / Context7 / Slack / Sentry /
 * Stripe / Asana / Brave / Exa，共 9 个（ADR-001 3.6）。
 *
 * ADR-001 3.6：MCP 预设方案（形态 A+B 组合，零后端）。
 */
object McpServerPresets {

    /** 内置本地 Server —— 零配置、开箱即用。 */
    private val local = listOf(
        McpServerConfig(name = "Filesystem", serverType = McpServerType.LOCAL, baseUrl = ""),
        McpServerConfig(name = "Fetch", serverType = McpServerType.LOCAL, baseUrl = ""),
        McpServerConfig(name = "Memory", serverType = McpServerType.LOCAL, baseUrl = ""),
        McpServerConfig(name = "Sequential Thinking", serverType = McpServerType.LOCAL, baseUrl = ""),
        McpServerConfig(name = "Time", serverType = McpServerType.LOCAL, baseUrl = ""),
        McpServerConfig(name = "跨 App 调用", serverType = McpServerType.LOCAL, baseUrl = "")
    )

    /** 预设远程 Server 模板 —— 用户填 Key 一键添加（端点经官方文档核实，2026-08-06）。 */
    private val remote = listOf(
        McpServerConfig(
            name = "GitHub",
            serverType = McpServerType.REMOTE,
            baseUrl = "https://api.githubcopilot.com/mcp",
            apiKeyRef = "github"
        ),
        McpServerConfig(
            name = "Notion",
            serverType = McpServerType.REMOTE,
            baseUrl = "https://mcp.notion.com/mcp",
            apiKeyRef = "notion"
        ),
        McpServerConfig(
            name = "Context7",
            serverType = McpServerType.REMOTE,
            baseUrl = "https://mcp.context7.com/mcp",
            apiKeyRef = "context7",
            headers = mapOf("CONTEXT7_API_KEY_HEADER" to "CONTEXT7_API_KEY")
        ),
        McpServerConfig(
            name = "Slack",
            serverType = McpServerType.REMOTE,
            baseUrl = "https://mcp.slack.com/mcp",
            apiKeyRef = "slack"
        ),
        McpServerConfig(
            name = "Sentry",
            serverType = McpServerType.REMOTE,
            baseUrl = "https://mcp.sentry.dev/mcp",
            apiKeyRef = "sentry"
        ),
        McpServerConfig(
            name = "Stripe",
            serverType = McpServerType.REMOTE,
            baseUrl = "https://mcp.stripe.com",
            apiKeyRef = "stripe"
        ),
        McpServerConfig(
            name = "Asana",
            serverType = McpServerType.REMOTE,
            baseUrl = "https://mcp.asana.com/v2/mcp",
            apiKeyRef = "asana"
        ),
        McpServerConfig(
            name = "Brave",
            serverType = McpServerType.REMOTE,
            baseUrl = "https://mcp.brave.com/mcp",
            apiKeyRef = "brave"
        ),
        McpServerConfig(
            name = "Exa",
            serverType = McpServerType.REMOTE,
            baseUrl = "https://mcp.exa.ai/mcp",
            apiKeyRef = "exa"
        )
    )

    /** 全部预设模板。 */
    val all: List<McpServerConfig> = local + remote

    /** 内置本地 Server 模板。 */
    val localPresets: List<McpServerConfig> = local

    /** 预设远程 Server 模板。 */
    val remotePresets: List<McpServerConfig> = remote

    /**
     * 按名称查找预设模板。
     *
     * @param name Server 名称（不区分大小写）
     * @return 匹配的预设模板，未找到返回 null
     */
    fun findByName(name: String): McpServerConfig? =
        all.find { it.name.equals(name, ignoreCase = true) }
}