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
 * Stripe / Asana / Brave / Exa（ADR-001 3.6，共 9 个）+ Firecrawl / n8n / TrendsMCP
 *（O3/PRD UXR8，D-2/D-9，共 3 个），合计 12 个。
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
        ),
        // O3（PRD UXR8，D-2/D-9）：以下 3 个模板端点与认证方式经官方文档核实（2026-08-16）。
        McpServerConfig(
            name = "Firecrawl",
            serverType = McpServerType.REMOTE,
            // 官方托管 MCP（docs.firecrawl.dev/mcp-server）：/v2/mcp + Bearer API Key
            baseUrl = "https://mcp.firecrawl.dev/v2/mcp",
            apiKeyRef = "firecrawl"
        ),
        McpServerConfig(
            name = "n8n",
            serverType = McpServerType.REMOTE,
            // 实例级端点（docs.n8n.io/advanced-ai/mcp/accessing-n8n-mcp-server）：
            // URL 因用户实例而异（占位符），添加时需改为自己的实例地址 + MCP Access Token
            baseUrl = "https://your-instance.n8n.co/mcp",
            apiKeyRef = "n8n"
        ),
        McpServerConfig(
            name = "TrendsMCP",
            serverType = McpServerType.REMOTE,
            // TrendRadar 的托管化等价物（D-3/D-9，PRD 网络调研）：
            // api.trendsmcp.ai/mcp + Bearer（免费 100 请求/月，跨 25+ 平台趋势）
            baseUrl = "https://api.trendsmcp.ai/mcp",
            apiKeyRef = "trendsmcp"
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

    /**
     * O2（PRD UXR8，D-10）：按名称查找预设模板元数据（功能描述 + Key 获取指引）。
     *
     * 元数据**不持久化**到 [McpServerConfig] 实体（避免 schema 膨胀与文案陈旧——
     * 描述随版本更新时无需数据迁移），UI 层按 Server 名称动态查找。用户自建
     * （非预设来源）的 Server 查找不到返回 null，UI 回退既有展示。
     *
     * @param name Server 名称（不区分大小写，与预设名一致即命中，如从预设创建的 Server）
     * @return 模板元数据；非预设来源返回 null
     */
    fun findMetaByName(name: String): McpPresetMeta? = presetMeta[name.trim().lowercase()]

    /** 预设元数据表（key = 小写预设名）。 */
    private val presetMeta: Map<String, McpPresetMeta> = listOf(
        // ---- 形态 B：内置本地 Server ----
        McpPresetMeta(
            name = "Filesystem",
            description = "读写本机指定目录的文件（创建/编辑/搜索），供 AI 直接操作文档"
        ),
        McpPresetMeta(
            name = "Fetch",
            description = "抓取任意网页内容转为文本，供 AI 阅读链接与在线资料"
        ),
        McpPresetMeta(
            name = "Memory",
            description = "轻量知识图谱记忆（实体/关系存储），供 AI 跨轮次记住结构化信息"
        ),
        McpPresetMeta(
            name = "Sequential Thinking",
            description = "分步推理工作区，供 AI 对复杂问题逐步思考与自我修正"
        ),
        McpPresetMeta(
            name = "Time",
            description = "获取当前日期时间与时区换算，供 AI 感知实时时间"
        ),
        McpPresetMeta(
            name = "跨 App 调用",
            description = "拉起微信/支付宝/淘宝/抖音/微博等 App 并跳转指定页面（分享/搜索/导航）"
        ),
        // ---- 形态 A：预设远程 Server 模板 ----
        McpPresetMeta(
            name = "GitHub",
            description = "搜索/读取仓库、Issue、PR 与代码，让 AI 直接调研开源项目",
            keyHint = "到 github.com/settings/tokens 生成 Fine-grained PAT（只读权限即可）"
        ),
        McpPresetMeta(
            name = "Notion",
            description = "搜索/读写 Notion 页面与数据库，让 AI 管理你的笔记与文档",
            keyHint = "到 notion.so/profile/integrations 创建内部集成，复制 Internal Secret"
        ),
        McpPresetMeta(
            name = "Context7",
            description = "获取各类库/框架的最新官方文档上下文，提升 AI 编码答案准确性",
            keyHint = "到 context7.com/dashboard 注册后生成 API Key"
        ),
        McpPresetMeta(
            name = "Slack",
            description = "搜索消息、读取频道与发送通知，让 AI 协作你的团队沟通",
            keyHint = "到 api.slack.com/apps 创建应用，为 MCP 启用后复制 Bot Token"
        ),
        McpPresetMeta(
            name = "Sentry",
            description = "查询错误事件、Issue 与性能数据，让 AI 帮你定位线上故障",
            keyHint = "到 sentry.io 设置 → Auth Tokens 创建（需 org:read 与 project 读权限）"
        ),
        McpPresetMeta(
            name = "Stripe",
            description = "查询支付/客户/订阅等财务数据，让 AI 分析你的收款记录",
            keyHint = "到 dashboard.stripe.com → 开发者 → API 密钥复制受限密钥（rk_/sk_）"
        ),
        McpPresetMeta(
            name = "Asana",
            description = "查询/创建任务与项目，让 AI 管理你的团队待办",
            keyHint = "到 asana.com → 开发者控制台创建 Personal Access Token"
        ),
        McpPresetMeta(
            name = "Brave",
            description = "Brave 搜索引擎 API（网页/图片/新闻），联网搜索备选通道",
            keyHint = "到 brave.com/search/api 免费注册订阅后生成 API Key"
        ),
        McpPresetMeta(
            name = "Exa",
            description = "语义搜索引擎，按意图而非关键词检索网页，适合深度调研",
            keyHint = "到 exa.ai 注册后在 Dashboard 生成 API Key"
        ),
        // ---- O3（PRD UXR8，D-2/D-9）：新增模板 ----
        McpPresetMeta(
            name = "Firecrawl",
            description = "抓取任意网页转为干净的 Markdown/JSON（应对 JS 渲染与反爬），让 AI 读取结构化网页数据",
            keyHint = "到 firecrawl.dev/app/api-keys 生成（免费 1000 页/月）"
        ),
        McpPresetMeta(
            name = "n8n",
            description = "连接你的 n8n 工作流实例：搜索/运行/创建自动化工作流，让 AI 驱动你的自动化任务",
            keyHint = "Base URL 改为你的实例地址（如 https://xxx.n8n.co/mcp）；到实例 Settings → MCP 复制 Access Token"
        ),
        McpPresetMeta(
            name = "TrendsMCP",
            description = "跨 25+ 平台热榜与趋势监控（Google/YouTube/TikTok/Reddit/X/GitHub 等），舆情与热点调研",
            keyHint = "到 trendsmcp.ai 注册免费获取 API Key（100 请求/月，无需信用卡）"
        )
    ).associateBy { it.name.lowercase() }
}

/**
 * O2（PRD UXR8）：MCP 预设模板元数据 —— 功能描述 + API Key 获取指引。
 *
 * 非持久化（不进 [McpServerConfig] 实体），随 [McpServerPresets] 版本更新。
 * UI 用途：预设列表展示 description（用户知道每个工具是干什么的）；
 * 添加远程 Server 时展示 keyHint（用户知道去哪找 API Key）。
 *
 * @param name 预设名（与 [McpServerConfig.name] 一致，查找时忽略大小写）
 * @param description 一句话功能描述（「做什么用」，非技术参数）
 * @param keyHint API Key 获取指引（远程模板必填，本地内置为空）
 */
data class McpPresetMeta(
    val name: String,
    val description: String,
    val keyHint: String = ""
)