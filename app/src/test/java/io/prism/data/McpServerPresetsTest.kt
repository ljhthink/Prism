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
    fun `remote presets include firecrawl n8n and trendsmcp templates`() {
        // O3 验收：Firecrawl / n8n / TrendsMCP 模板可一键添加（存在于远程预设 + 端点核实）
        val names = McpServerPresets.remotePresets.map { it.name }
        assertTrue("应含 Firecrawl 模板", names.contains("Firecrawl"))
        assertTrue("应含 n8n 模板", names.contains("n8n"))
        assertTrue("应含 TrendsMCP 模板", names.contains("TrendsMCP"))
        // 形态 A 合计 12 个（9 原有 + 3 新增）
        assertEquals(12, McpServerPresets.remotePresets.size)
        assertEquals(18, McpServerPresets.all.size)
    }

    @Test
    fun `new templates use official verified endpoints and api key refs`() {
        // 端点经官方文档核实（2026-08-16）：
        // - Firecrawl: docs.firecrawl.dev/mcp-server → https://mcp.firecrawl.dev/v2/mcp
        // - n8n: docs.n8n.io/advanced-ai/mcp/accessing-n8n-mcp-server → 实例级 <instance>.n8n.co/mcp（占位）
        // - TrendsMCP: trendsmcp.ai/docs/api → https://api.trendsmcp.ai/mcp（Bearer）
        val firecrawl = McpServerPresets.findByName("Firecrawl")!!
        assertEquals("https://mcp.firecrawl.dev/v2/mcp", firecrawl.baseUrl)
        assertEquals("firecrawl", firecrawl.apiKeyRef)
        assertEquals(McpServerType.REMOTE, firecrawl.serverType)
        assertEquals(McpTransport.STREAMABLE_HTTP, firecrawl.transport)

        val n8n = McpServerPresets.findByName("n8n")!!
        assertEquals("https://your-instance.n8n.co/mcp", n8n.baseUrl)
        assertEquals("n8n", n8n.apiKeyRef)

        val trends = McpServerPresets.findByName("TrendsMCP")!!
        assertEquals("https://api.trendsmcp.ai/mcp", trends.baseUrl)
        assertEquals("trendsmcp", trends.apiKeyRef)
    }

    @Test
    fun `new templates have meta descriptions and actionable key hints`() {
        // O2+O3：新模板有功能描述 + Key 指引；n8n 的 keyHint 必须提示用户改占位 Base URL
        val firecrawl = McpServerPresets.findMetaByName("Firecrawl")!!
        assertTrue(firecrawl.description.contains("网页"))
        assertTrue(firecrawl.keyHint.contains("firecrawl.dev"))

        val n8n = McpServerPresets.findMetaByName("n8n")!!
        assertTrue(n8n.description.contains("工作流"))
        assertTrue("n8n 指引必须提示修改占位 URL", n8n.keyHint.contains("Base URL"))

        val trends = McpServerPresets.findMetaByName("TrendsMCP")!!
        assertTrue(trends.description.contains("趋势"))
        assertTrue(trends.keyHint.contains("trendsmcp.ai"))
    }

    @Test
    fun `new template api key refs are unique across presets`() {
        // apiKeyRef 是 Keystore 加密键——重复会导致两个 Server 共用/互相覆盖同一 Key
        val refs = McpServerPresets.remotePresets.map { it.apiKeyRef }
        assertEquals("apiKeyRef 应全量唯一", refs.size, refs.distinct().size)
    }
}
