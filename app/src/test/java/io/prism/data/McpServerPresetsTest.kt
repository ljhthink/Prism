package io.prism.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * McpServerPresets 预设模板元数据测试（O2，PRD UXR8）。
 *
 * 验证：每个预设模板（本地 + 远程）都有功能描述；远程模板都有 Key 获取指引；
 * 按名称查找（忽略大小写）命中；自建名称查不到返回 null。
 */
class McpServerPresetsTest {

    @Test
    fun `every preset has meta with non-blank description`() {
        // O2 验收：预设列表 UI 展示功能描述——每个预设都必须有非空 description
        McpServerPresets.all.forEach { preset ->
            val meta = McpServerPresets.findMetaByName(preset.name)
            assertNotNull("预设 ${preset.name} 缺少元数据", meta)
            assertTrue(
                "预设 ${preset.name} 的 description 不应为空",
                meta!!.description.isNotBlank()
            )
        }
    }

    @Test
    fun `every remote preset has key hint for api key guidance`() {
        // O2 验收：远程模板展示「去哪找 API Key」——每个远程预设都必须有非空 keyHint
        McpServerPresets.remotePresets.forEach { preset ->
            val meta = McpServerPresets.findMetaByName(preset.name)
            assertNotNull("远程预设 ${preset.name} 缺少元数据", meta)
            assertTrue(
                "远程预设 ${preset.name} 的 keyHint 不应为空（用户需知道去哪获取 Key）",
                meta!!.keyHint.isNotBlank()
            )
        }
    }

    @Test
    fun `local preset key hint is optional and empty by default`() {
        // 本地内置零配置：keyHint 默认为空（无需 Key）
        McpServerPresets.localPresets.forEach { preset ->
            val meta = McpServerPresets.findMetaByName(preset.name)
            assertNotNull(meta)
            assertEquals("本地预设 ${preset.name} 无需 keyHint", "", meta!!.keyHint)
        }
    }

    @Test
    fun `findMetaByName is case-insensitive and trims input`() {
        // 从预设创建的 Server 与预设同名（大小写可能被用户改过），查找须忽略大小写
        assertNotNull(McpServerPresets.findMetaByName("GitHub"))
        assertNotNull(McpServerPresets.findMetaByName("github"))
        assertNotNull(McpServerPresets.findMetaByName("  GITHUB  "))
        assertNotNull(McpServerPresets.findMetaByName("跨 App 调用"))
        assertEquals("GitHub", McpServerPresets.findMetaByName("github")!!.name)
    }

    @Test
    fun `findMetaByName returns null for custom server names`() {
        // 用户自建 Server（非预设来源）查不到元数据，UI 回退既有展示
        assertNull(McpServerPresets.findMetaByName("我的私有 MCP"))
        assertNull(McpServerPresets.findMetaByName(""))
        assertNull(McpServerPresets.findMetaByName("   "))
    }

    @Test
    fun `preset meta descriptions are user-oriented not technical params`() {
        // O2 用户诉求：「用户不知道这些工具有什么用」——description 应是自然语言用途说明
        //（非 URL/协议参数），抽查关键预设
        assertTrue(McpServerPresets.findMetaByName("Filesystem")!!.description.contains("文件"))
        assertTrue(McpServerPresets.findMetaByName("Fetch")!!.description.contains("网页"))
        assertTrue(McpServerPresets.findMetaByName("GitHub")!!.description.contains("仓库"))
        // keyHint 应包含可操作指引（域名），抽查
        assertTrue(McpServerPresets.findMetaByName("GitHub")!!.keyHint.contains("github.com"))
        assertTrue(McpServerPresets.findMetaByName("Notion")!!.keyHint.contains("notion"))
    }

    // ==================== O3（PRD UXR8，D-2/D-9）：新增 MCP 模板 ====================

    @Test
    fun `remote presets include n8n and bocha templates`() {
        // O3 验收：n8n 模板可一键添加；v1 批次8 US-001：新增 Bocha 模板
        // v1 批次9 US-906：移除 Firecrawl/TrendsMCP/Slack/Asana/Exa（海外端点国内不可达）
        // v1 批次9 US-910：新增 Gitee/聚合数据/天行数据/智谱 Web Search/高德地图
        // v1 批次15 US-1508：新增 Scrapling/crawl4ai（自建抓取中转）
        val names = McpServerPresets.remotePresets.map { it.name }
        assertTrue("应含 n8n 模板", names.contains("n8n"))
        assertTrue("应含 Bocha 模板", names.contains("Bocha"))
        assertTrue("应含 Gitee 模板", names.contains("Gitee"))
        assertTrue("应含聚合数据模板", names.contains("聚合数据"))
        assertTrue("应含天行数据模板", names.contains("天行数据"))
        assertTrue("应含智谱 Web Search 模板", names.contains("智谱 Web Search"))
        assertTrue("应含高德地图模板", names.contains("高德地图"))
        assertTrue("应含 Scrapling 模板", names.contains("Scrapling"))
        assertTrue("应含 crawl4ai 模板", names.contains("crawl4ai"))
        // 形态 A 合计 14 个（GitHub/Notion/Context7/Sentry/Stripe/n8n/Bocha + 5 国内新模板
        // + Scrapling/crawl4ai 自建中转）
        assertEquals(14, McpServerPresets.remotePresets.size)
        assertEquals(20, McpServerPresets.all.size)
    }

    @Test
    fun `domestic templates carry domestic network note and key hints`() {
        // v1 批次9 US-910：国内模板应标注"国内直连"且 keyHint 指向注册页
        val domestic = listOf("Gitee", "聚合数据", "天行数据", "智谱 Web Search", "高德地图")
        domestic.forEach { name ->
            val meta = McpServerPresets.findMetaByName(name)
            assertNotNull("国内模板 $name 应存在元数据", meta)
            assertTrue("国内模板 $name 应标注国内直连", meta!!.networkNote.contains("国内"))
            assertTrue("国内模板 $name 应有 keyHint", meta.keyHint.isNotBlank())
        }
    }

    @Test
    fun `brave template removed due to discontinued free tier and overseas endpoint`() {
        // v1 批次8 US-004：Brave 免费档 2025 年底取消 + 海外端点国内不可达 → 从模板移除
        assertNull("Brave 模板应已移除", McpServerPresets.findByName("Brave"))
        assertNull("Brave 元数据应已移除", McpServerPresets.findMetaByName("Brave"))
    }

    @Test
    fun `removed overseas templates are gone from presets and meta`() {
        // v1 批次9 US-906（B5）：Slack/Asana/Exa/Firecrawl/TrendsMCP 海外端点国内不可达，
        // 用户真机无法注册使用 → 全部移除配置与元数据，避免试错成本
        val removed = listOf("Slack", "Asana", "Exa", "Firecrawl", "TrendsMCP")
        removed.forEach { name ->
            assertNull("模板 $name 应已移除", McpServerPresets.findByName(name))
            assertNull("元数据 $name 应已移除", McpServerPresets.findMetaByName(name))
        }
    }

    @Test
    fun `bocha template uses official streamable http endpoint and api key ref`() {
        // v1 批次8 US-001：博查官方远程 MCP（mcp.bocha.cn/mcp，Streamable HTTP + Bearer Key）
        val bocha = McpServerPresets.findByName("Bocha")!!
        assertEquals("https://mcp.bocha.cn/mcp", bocha.baseUrl)
        assertEquals("bocha", bocha.apiKeyRef)
        assertEquals(McpServerType.REMOTE, bocha.serverType)
        assertEquals(McpTransport.STREAMABLE_HTTP, bocha.transport)
        val meta = McpServerPresets.findMetaByName("Bocha")!!
        assertTrue("Bocha 描述应体现 AI 搜索", meta.description.contains("搜索"))
        assertTrue("Bocha keyHint 应指向 open.bocha.cn", meta.keyHint.contains("open.bocha.cn"))
        assertEquals("Bocha 为国内端点，不应有海外提示", "", meta.networkNote)
    }

    @Test
    fun `overseas remote templates carry network note for domestic users`() {
        // v1 批次8 US-004：海外端点标注"国内网络可能不可用"，降低试错成本
        // v1 批次9 US-906：仅保留仍在模板中的海外端点（GitHub/Notion/Context7/Sentry/Stripe）
        val overseas = listOf("GitHub", "Notion", "Context7", "Sentry", "Stripe")
        overseas.forEach { name ->
            val meta = McpServerPresets.findMetaByName(name)
            assertNotNull("海外模板 $name 应存在元数据", meta)
            assertTrue(
                "海外模板 $name 应标注国内网络不可用（networkNote 非空）",
                meta!!.networkNote.contains("国内网络")
            )
        }
    }

    @Test
    fun `new templates use official verified endpoints and api key refs`() {
        // 端点经官方文档核实（2026-08-16）：
        // - n8n: docs.n8n.io/advanced-ai/mcp/accessing-n8n-mcp-server → 实例级 <instance>.n8n.co/mcp（占位）
        val n8n = McpServerPresets.findByName("n8n")!!
        assertEquals("https://your-instance.n8n.co/mcp", n8n.baseUrl)
        assertEquals("n8n", n8n.apiKeyRef)
        assertEquals(McpServerType.REMOTE, n8n.serverType)
        assertEquals(McpTransport.STREAMABLE_HTTP, n8n.transport)
    }

    @Test
    fun `new templates have meta descriptions and actionable key hints`() {
        // O2+O3：新模板有功能描述 + Key 指引；n8n 的 keyHint 必须提示用户改占位 Base URL
        val n8n = McpServerPresets.findMetaByName("n8n")!!
        assertTrue(n8n.description.contains("工作流"))
        assertTrue("n8n 指引必须提示修改占位 URL", n8n.keyHint.contains("Base URL"))
    }

    @Test
    fun `new template api key refs are unique across presets`() {
        // apiKeyRef 是 Keystore 加密键——重复会导致两个 Server 共用/互相覆盖同一 Key
        val refs = McpServerPresets.remotePresets.map { it.apiKeyRef }
        assertEquals("apiKeyRef 应全量唯一", refs.size, refs.distinct().size)
    }

    // ==================== v1 批次15（US-1508）：自建抓取中转模板 ====================

    @Test
    fun `scrapling and crawl4ai templates are findable by name`() {
        // US-1508 验收：findByName 命中两个新模板，配置为远程 Streamable HTTP
        val scrapling = McpServerPresets.findByName("Scrapling")
        val crawl4ai = McpServerPresets.findByName("crawl4ai")
        assertNotNull("应能按名找到 Scrapling 模板", scrapling)
        assertNotNull("应能按名找到 crawl4ai 模板", crawl4ai)
        scrapling!!.let {
            assertEquals(McpServerType.REMOTE, it.serverType)
            assertEquals(McpTransport.STREAMABLE_HTTP, it.transport)
            assertEquals("scrapling", it.apiKeyRef)
        }
        crawl4ai!!.let {
            assertEquals(McpServerType.REMOTE, it.serverType)
            assertEquals(McpTransport.STREAMABLE_HTTP, it.transport)
            assertEquals("crawl4ai", it.apiKeyRef)
        }
        // findByName 忽略大小写（既有契约）
        assertEquals("Scrapling", McpServerPresets.findByName("scrapling")!!.name)
    }

    @Test
    fun `selfhost fetch relay templates use lan placeholder endpoint without fabricated port`() {
        // US-1508：端点占位 http://<PC-IP>:<PORT>/mcp，用户自建后替换；
        // 不虚构官方端点/默认端口（Scrapling 端点与端口完全以自建部署为准）
        val selfhost = listOf("Scrapling", "crawl4ai")
        selfhost.forEach { name ->
            val preset = McpServerPresets.findByName(name)!!
            assertEquals(
                "$name 端点应为局域网占位符",
                "http://<PC-IP>:<PORT>/mcp",
                preset.baseUrl
            )
            assertTrue(
                "$name 端点应为 http 局域网占位（用户自建）",
                preset.baseUrl.startsWith("http://") && preset.baseUrl.contains("<PC-IP>")
            )
        }
        // Scrapling 不虚构默认端口：baseUrl 无具体端口号（仅 <PORT> 占位）
        val scrapling = McpServerPresets.findByName("Scrapling")!!
        assertTrue(
            "Scrapling 端点不应虚构默认端口（仅 <PORT> 占位）",
            scrapling.baseUrl.contains("<PORT>") && !scrapling.baseUrl.contains("1123") && !scrapling.baseUrl.contains("8080")
        )
    }

    @Test
    fun `selfhost fetch relay templates have complete non-blank metadata`() {
        // US-1508 验收：元数据（description/keyHint/networkNote）非空完整
        val selfhost = listOf("Scrapling", "crawl4ai")
        selfhost.forEach { name ->
            val meta = McpServerPresets.findMetaByName(name)
            assertNotNull("自建中转模板 $name 应有元数据", meta)
            meta!!.let {
                assertTrue("$name description 不应为空", it.description.isNotBlank())
                assertTrue("$name keyHint 不应为空", it.keyHint.isNotBlank())
                assertTrue("$name networkNote 不应为空", it.networkNote.isNotBlank())
            }
        }
    }

    @Test
    fun `selfhost fetch relay key hints guide deployment and auth`() {
        // Scrapling：端点与端口以自建部署为准 + 仅支持 Streamable HTTP + 默认无认证
        val scrapling = McpServerPresets.findMetaByName("Scrapling")!!
        assertTrue("Scrapling keyHint 应说明内置 MCP Server", scrapling.keyHint.contains("Scrapling 内置 MCP Server"))
        assertTrue("Scrapling keyHint 应说明端点以自建部署配置为准", scrapling.keyHint.contains("自建部署配置"))
        assertTrue("Scrapling keyHint 应声明仅支持 Streamable HTTP", scrapling.keyHint.contains("Streamable HTTP"))
        assertTrue("Scrapling keyHint 应说明默认无认证", scrapling.keyHint.contains("默认无认证"))
        assertTrue("Scrapling keyHint 应指引开启 API key 时填入对应 header", scrapling.keyHint.contains("API key"))
        // crawl4ai：官方 Docker 默认端口 11235 + 端点以实际部署为准 + 可选 JWT/密钥认证
        val crawl4ai = McpServerPresets.findMetaByName("crawl4ai")!!
        assertTrue("crawl4ai keyHint 应说明官方 Docker 默认端口 11235", crawl4ai.keyHint.contains("11235"))
        assertTrue("crawl4ai keyHint 应说明端点以实际部署为准", crawl4ai.keyHint.contains("实际部署为准"))
        assertTrue("crawl4ai keyHint 应说明可选 JWT/密钥认证", crawl4ai.keyHint.contains("JWT"))
    }

    @Test
    fun `selfhost fetch relay descriptions carry architecture redline semantics`() {
        // US-1508：description 说明用途与架构红线语义——服务端抓取目标 URL 并返回
        // 渲染后正文/Markdown，手机端不接触目标站 cookie（禁止 cookie 回传复放）
        val selfhost = listOf("Scrapling", "crawl4ai")
        selfhost.forEach { name ->
            val desc = McpServerPresets.findMetaByName(name)!!.description
            assertTrue("$name description 应说明自建抓取中转", desc.contains("自建抓取中转"))
            assertTrue("$name description 应说明反反爬 + JS 渲染", desc.contains("反反爬") && desc.contains("JS 渲染"))
            assertTrue("$name description 应说明返回渲染后的正文/Markdown", desc.contains("渲染后的正文/Markdown"))
            assertTrue("$name description 应说明目标 URL 由服务端抓取", desc.contains("服务端抓取目标 URL"))
            assertTrue("$name description 应说明手机端不接触目标站 cookie", desc.contains("不接触目标站 cookie"))
        }
    }

    @Test
    fun `selfhost fetch relay templates carry lan note not overseas note`() {
        // US-1508：自建服务不标「海外端点」，networkNote 明示「需自行部署」+ 局域网同网要求
        val selfhost = listOf("Scrapling", "crawl4ai")
        selfhost.forEach { name ->
            val note = McpServerPresets.findMetaByName(name)!!.networkNote
            assertTrue("$name networkNote 应标注本地局域网自建服务", note.contains("本地局域网自建服务"))
            assertTrue("$name networkNote 应明示需自行部署", note.contains("需自行部署"))
            assertTrue("$name networkNote 应要求与手机同一网络", note.contains("同一网络"))
            assertTrue(
                "$name 为自建服务，不应标注海外端点或国内直连",
                !note.contains("海外") && !note.contains("国内直连")
            )
        }
    }
}
