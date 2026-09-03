package io.prism.data

/**
 * 预设 MCP Server 模板 —— 开箱即用的本地与远程 Server。
 *
 * 用户可基于这些模板创建 McpServerConfig，填入 API Key（远程）后即可使用。
 * 模板中的 [McpServerConfig.apiKeyRef] 使用 Server 标识符（如 "context7"），
 * 对应 [io.prism.security.ApiKeyRepository] 中存储的加密 API Key。
 *
 * **形态 B（内置本地 Server，零配置）**：Filesystem / Fetch / Memory / Sequential Thinking / Time / 跨 App，共 6 个。
 * **形态 A（预设远程 Server 模板，用户填 Key 一键添加）**：GitHub / Notion / Context7 / Sentry /
 * Stripe / n8n（ADR-001 3.6 + O3/PRD UXR8）+ Bocha（v1 批次8 US-001）+ Gitee / 聚合数据 /
 * 天行数据 / 智谱 Web Search / 高德地图（v1 批次9 US-910）+ Scrapling / crawl4ai
 * （v1 批次15 US-1508，PC/NAS 自建抓取中转），合计 14 个。
 * v1 批次9（US-906）：移除 Slack/Asana/Exa/Firecrawl/TrendsMCP 模板（海外端点国内不可达，
 * 用户真机无法注册使用，保留徒增试错成本）；Brave 已于 v1 批次8 US-004 移除（免费档取消）。
 * v1 批次15（US-1508）：Scrapling / crawl4ai 为**用户自建**服务的远程模板（非官方托管端点，
 * 不标「海外端点」），端点占位 `http://<PC-IP>:<PORT>/mcp` 由用户部署后替换；架构红线语义
 * 见两模板 description（服务端抓取目标 URL，手机端不接触目标站 cookie）。
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
        // O3（PRD UXR8，D-2/D-9）：n8n 端点与认证方式经官方文档核实（2026-08-16）。
        McpServerConfig(
            name = "n8n",
            serverType = McpServerType.REMOTE,
            // 实例级端点（docs.n8n.io/advanced-ai/mcp/accessing-n8n-mcp-server）：
            // URL 因用户实例而异（占位符），添加时需改为自己的实例地址 + MCP Access Token
            baseUrl = "https://your-instance.n8n.co/mcp",
            apiKeyRef = "n8n"
        ),
        // v1 批次8（PRD MCP/API 增强，US-001）：博查 Bocha —— DeepSeek 官方联网搜索供应方，
        // 国内可直连（数据不出海、合规）、AI 原生（语义重排 + 引用来源）。远程 Streamable HTTP
        // MCP 端点 mcp.bocha.cn/mcp + Bearer API Key（开放平台 open.bocha.cn 获取）。
        McpServerConfig(
            name = "Bocha",
            serverType = McpServerType.REMOTE,
            baseUrl = "https://mcp.bocha.cn/mcp",
            apiKeyRef = "bocha"
        ),
        // ---- v1 批次9（US-910，prd-open-box US-005）：国内可用远程模板 ----
        // Gitee MCP（官方 oschina/mcp-gitee）：GitHub 的国内替代，官方托管 remote，
        // Bearer PAT（gitee.com/profile/personal_access_tokens 免费生成），29 工具。
        McpServerConfig(
            name = "Gitee",
            serverType = McpServerType.REMOTE,
            baseUrl = "https://api.gitee.com/mcp",
            apiKeyRef = "gitee"
        ),
        // 聚合数据 MCP（juhe.cn）：天气/新闻/AQI/快递一站式，Streamable HTTP + Bearer JWT。
        McpServerConfig(
            name = "聚合数据",
            serverType = McpServerType.REMOTE,
            baseUrl = "https://mcp.juhe.cn/mcp",
            apiKeyRef = "juhe"
        ),
        // 天行数据 MCP：微博/抖音/头条热搜（替代海外 TrendsMCP 国内场景）。
        McpServerConfig(
            name = "天行数据",
            serverType = McpServerType.REMOTE,
            baseUrl = "https://apis.tianapi.com/mcp",
            apiKeyRef = "tianapi"
        ),
        // 智谱 Web Search MCP（官方）：一 Key 复用 GLM 生态，多引擎（自研/Bing/搜狗/夸克/Jina）。
        // 认证经 McpClientManager.resolveHeaders 统一注入 `Authorization: Bearer <Key>` 头
        //（guardrail M-2：不用 URL query，规避 CWE-598 凭据入 URL；keyHint 与元数据一致）。
        // 注：智谱官方 mcp-broker 亦支持 URL query 认证（?Authorization=<Key>），若真机实测
        // Bearer 头不被接受，需在此模板单独配置（见 prd-v1-b9-fixes §8 待真机验证）。
        McpServerConfig(
            name = "智谱 Web Search",
            serverType = McpServerType.REMOTE,
            baseUrl = "https://open.bigmodel.cn/api/mcp-broker/proxy/web-search/mcp",
            apiKeyRef = "zhipu"
        ),
        // 高德地图 MCP（官方）：地理编码/逆地理编码/POI 搜索/路线规划/天气，Streamable HTTP + key。
        McpServerConfig(
            name = "高德地图",
            serverType = McpServerType.REMOTE,
            baseUrl = "https://mcp.amap.com/mcp",
            apiKeyRef = "amap"
        ),
        // ---- v1 批次15（US-1508，prd-search-fetch-enhancement C1）：自建抓取中转模板 ----
        // Scrapling / crawl4ai 均为**用户自建**的 PC/NAS 抓取中转（反反爬 + JS 渲染），
        // 非官方托管端点：不标「海外端点」，端点占位 http://<PC-IP>:<PORT>/mcp 由用户
        // 部署后替换（不虚构默认端口/地址）。架构红线语义见各模板元数据 description：
        // 目标 URL 由服务端抓取，手机端不接触目标站 cookie（禁止 cookie 回传复放）。
        // 认证：默认无认证（apiKeyRef 已设但未存 Key 时不注入鉴权头，无影响）；
        // 部署开启 API key/JWT 时存入 Key 即经 Authorization: Bearer 头注入（resolveHeaders），
        // 其他 header 方案走编辑弹层自定义请求头。
        McpServerConfig(
            name = "Scrapling",
            serverType = McpServerType.REMOTE,
            baseUrl = "http://<PC-IP>:<PORT>/mcp",
            apiKeyRef = "scrapling"
        ),
        McpServerConfig(
            name = "crawl4ai",
            serverType = McpServerType.REMOTE,
            baseUrl = "http://<PC-IP>:<PORT>/mcp",
            apiKeyRef = "crawl4ai"
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
            keyHint = "到 github.com/settings/tokens 生成 Fine-grained PAT（只读权限即可）",
            networkNote = "海外端点，国内网络可能不可用"
        ),
        McpPresetMeta(
            name = "Notion",
            description = "搜索/读写 Notion 页面与数据库，让 AI 管理你的笔记与文档",
            keyHint = "到 notion.so/profile/integrations 创建内部集成，复制 Internal Secret",
            networkNote = "海外端点，国内网络可能不可用"
        ),
        McpPresetMeta(
            name = "Context7",
            description = "获取各类库/框架的最新官方文档上下文，提升 AI 编码答案准确性",
            keyHint = "到 context7.com/dashboard 注册后生成 API Key",
            networkNote = "海外端点，国内网络可能不可用"
        ),
        McpPresetMeta(
            name = "Sentry",
            description = "查询错误事件、Issue 与性能数据，让 AI 帮你定位线上故障",
            keyHint = "到 sentry.io 设置 → Auth Tokens 创建（需 org:read 与 project 读权限）",
            networkNote = "海外端点，国内网络可能不可用"
        ),
        McpPresetMeta(
            name = "Stripe",
            description = "查询支付/客户/订阅等财务数据，让 AI 分析你的收款记录",
            keyHint = "到 dashboard.stripe.com → 开发者 → API 密钥复制受限密钥（rk_/sk_）",
            networkNote = "海外端点，国内网络可能不可用"
        ),
        // ---- O3（PRD UXR8，D-2/D-9）：n8n ----
        McpPresetMeta(
            name = "n8n",
            description = "连接你的 n8n 工作流实例：搜索/运行/创建自动化工作流，让 AI 驱动你的自动化任务",
            keyHint = "Base URL 改为你的实例地址（如 https://xxx.n8n.co/mcp）；到实例 Settings → MCP 复制 Access Token",
            networkNote = "实例地址需自建/自托管，海外默认端点国内可能不可用"
        ),
        // ---- v1 批次8（PRD MCP/API 增强，US-001）：博查 Bocha ----
        McpPresetMeta(
            name = "Bocha",
            description = "AI 专用世界知识搜索（DeepSeek 官方）：语义重排 + 引用来源，覆盖天气/新闻/百科/医疗等垂直领域",
            keyHint = "到 open.bocha.cn 注册获取 API Key（DeepSeek 官方联网搜索供应方，国内直连）"
        ),
        // ---- v1 批次9（US-910，prd-open-box US-005）：国内可用远程模板元数据 ----
        McpPresetMeta(
            name = "Gitee",
            description = "搜索/读取 Gitee 仓库、Issue、PR 与代码，让 AI 调研国内开源项目（GitHub 的国内替代）",
            keyHint = "到 gitee.com/profile/personal_access_tokens 生成 Personal Access Token（免费）",
            networkNote = "国内直连"
        ),
        McpPresetMeta(
            name = "聚合数据",
            description = "一站式生活数据：天气预报 / 新闻头条 / 空气质量 / 快递查询等，让 AI 获取结构化生活资讯",
            keyHint = "到 juhe.cn 注册后在控制台生成 API Key（免费 50 次/天）",
            networkNote = "国内直连"
        ),
        McpPresetMeta(
            name = "天行数据",
            description = "微博/抖音/今日头条等中文平台热搜与生活数据，让 AI 掌握实时热点（替代海外 TrendsMCP）",
            keyHint = "到 tianapi.com 注册获取 API Key（免费注册）",
            networkNote = "国内直连"
        ),
        McpPresetMeta(
            name = "智谱 Web Search",
            description = "联网搜索（智谱官方）：意图增强 + 多引擎（自研/Bing/搜狗/夸克/Jina），带摘要与引用，一个 Key 复用 GLM 生态",
            // guardrail M-2：McpClientManager.resolveHeaders 以 `Authorization: Bearer <key>`
            // 头注入（不拼 URL query，避免 CWE-598 凭据入 URL）。keyHint 与实际机制保持一致。
            keyHint = "到 bigmodel.cn 注册获取 API Key；认证经 Authorization Bearer 头注入",
            networkNote = "国内直连"
        ),
        McpPresetMeta(
            name = "高德地图",
            description = "地理编码/逆地理编码/POI 搜索/路线规划/天气，让 AI 处理出行与位置查询",
            keyHint = "到 lbs.amap.com 注册 Web 服务 Key（个人免费额度）",
            networkNote = "国内直连"
        ),
        // ---- v1 批次15（US-1508）：自建抓取中转模板元数据 ----
        // 自建服务：不标「海外端点」，networkNote 明示需自行部署 + 局域网可达；
        // description 承载架构红线语义（服务端抓取目标 URL，手机端不接触目标站 cookie）。
        McpPresetMeta(
            name = "Scrapling",
            description = "PC/NAS 自建抓取中转（Scrapling 反反爬 + JS 渲染）：服务端抓取目标 URL 并返回渲染后的正文/Markdown，手机端不接触目标站 cookie",
            keyHint = "Scrapling 内置 MCP Server，端点与端口以你自建部署配置为准（将 Base URL 占位替换为你的服务地址），Prism 仅支持 Streamable HTTP 传输；默认无认证，如部署时开启 API key 则填入对应 header（Bearer 型可直接存入 Key 字段，其他请在自定义请求头添加）。注意：Android 仅放行 localhost 明文 http，局域网 http 端点会被系统拦截——请用 adb reverse 端口转发（端点填 http://127.0.0.1:<端口>）或 https 反向代理",
            networkNote = "本地局域网自建服务（需自行部署），需与手机同一网络；明文 http 见 keyHint 系统限制说明"
        ),
        McpPresetMeta(
            name = "crawl4ai",
            description = "PC/NAS 自建抓取中转（crawl4ai 反反爬 + JS 渲染）：服务端抓取目标 URL 并返回渲染后的正文/Markdown，手机端不接触目标站 cookie",
            keyHint = "crawl4ai 官方 Docker 默认端口 11235，端点以实际部署为准（将 Base URL 占位替换为你的服务地址）；可选 JWT/密钥认证——启用后填入 Key 字段（经 Authorization Bearer 头注入），或按部署配置在自定义请求头添加。注意：Android 仅放行 localhost 明文 http，局域网 http 端点会被系统拦截——请用 adb reverse 端口转发（端点填 http://127.0.0.1:<端口>）或 https 反向代理",
            networkNote = "本地局域网自建服务（需自行部署），需与手机同一网络；明文 http 见 keyHint 系统限制说明"
        )
    ).associateBy { it.name.lowercase() }
}

/**
 * O2（PRD UXR8）：MCP 预设模板元数据 —— 功能描述 + API Key 获取指引 + 网络可用性提示。
 *
 * 非持久化（不进 [McpServerConfig] 实体），随 [McpServerPresets] 版本更新。
 * UI 用途：预设列表展示 description（用户知道每个工具是干什么的）；
 * 添加远程 Server 时展示 keyHint（用户知道去哪找 API Key）；
 * 海外端点展示 networkNote（国内网络可能不可用，降低试错成本，US-004）。
 *
 * @param name 预设名（与 [McpServerConfig.name] 一致，查找时忽略大小写）
 * @param description 一句话功能描述（「做什么用」，非技术参数）
 * @param keyHint API Key 获取指引（远程模板必填，本地内置为空）
 * @param networkNote 网络可用性提示（海外端点标"国内网络可能不可用"，国内/本地为空）
 */
data class McpPresetMeta(
    val name: String,
    val description: String,
    val keyHint: String = "",
    val networkNote: String = ""
)