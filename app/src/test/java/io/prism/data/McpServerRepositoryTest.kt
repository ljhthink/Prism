package io.prism.data

import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * McpServerRepository CRUD 单元测试（US-008 数据层）。
 *
 * 验证内容：
 * 1. McpServerConfig 持久化到 ObjectBox（含 headers @Convert 类型转换）
 * 2. 配置列表增删改查
 * 3. 启用/停用切换
 * 4. 从预设模板创建
 * 5. 预设模板（本地 + 远程）
 */
class McpServerRepositoryTest {

    private lateinit var boxStore: BoxStore
    private lateinit var repository: McpServerRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "mcp-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        repository = McpServerRepository(boxStore)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun save_assigns_positive_id() {
        val config = McpServerConfig(name = "Test", baseUrl = "https://test.mcp")
        val id = repository.save(config)
        assertTrue("应分配正数 id", id > 0)
    }

    @Test
    fun get_returns_persisted_config() {
        val config = McpServerConfig(
            name = "Context7",
            baseUrl = "https://mcp.context7.com/mcp",
            apiKeyRef = "context7",
            headers = mapOf("CONTEXT7_API_KEY_HEADER" to "CONTEXT7_API_KEY")
        )
        val id = repository.save(config)

        val retrieved = repository.get(id)
        assertNotNull("应能读取已保存的配置", retrieved)
        assertEquals("Context7", retrieved!!.name)
        assertEquals("https://mcp.context7.com/mcp", retrieved.baseUrl)
        assertEquals("context7", retrieved.apiKeyRef)
        assertEquals(mapOf("CONTEXT7_API_KEY_HEADER" to "CONTEXT7_API_KEY"), retrieved.headers)
    }

    @Test
    fun save_update_existing_config() {
        val config = McpServerConfig(name = "Test", baseUrl = "https://old.mcp")
        val id = repository.save(config)

        config.id = id
        config.baseUrl = "https://new.mcp"
        repository.save(config)

        assertEquals("https://new.mcp", repository.get(id)!!.baseUrl)
    }

    @Test
    fun getAll_returns_sorted_by_createdAt() {
        val first = repository.save(McpServerConfig(name = "A", baseUrl = "https://a.mcp"))
        val second = repository.save(McpServerConfig(name = "B", baseUrl = "https://b.mcp"))
        val all = repository.getAll()
        assertEquals(listOf(first, second), all.map { it.id })
    }

    @Test
    fun remove_deletes_config() {
        val id = repository.save(McpServerConfig(name = "T", baseUrl = "https://t.mcp"))
        repository.remove(id)
        assertNull("删除后不应再存在", repository.get(id))
    }

    @Test
    fun setEnabled_toggles_flag() {
        val id = repository.save(McpServerConfig(name = "T", baseUrl = "https://t.mcp"))
        assertFalse("默认停用", repository.get(id)!!.isEnabled)

        repository.setEnabled(id, true)
        assertTrue("启用后应为 true", repository.get(id)!!.isEnabled)

        repository.setEnabled(id, false)
        assertFalse("停用后应为 false", repository.get(id)!!.isEnabled)
    }

    @Test
    fun createFromPreset_creates_new_config() {
        val preset = McpServerConfig(
            name = "GitHub",
            serverType = McpServerType.REMOTE,
            baseUrl = "https://api.githubcopilot.com/mcp",
            apiKeyRef = "github"
        )
        val id = repository.createFromPreset(preset)
        assertEquals("GitHub", repository.get(id)!!.name)
        assertEquals(McpServerType.REMOTE, repository.get(id)!!.serverType)
    }

    @Test
    fun presets_contain_local_and_remote() {
        assertTrue("应存在本地模板", McpServerPresets.localPresets.any { it.serverType == McpServerType.LOCAL })
        assertTrue("应存在远程模板", McpServerPresets.remotePresets.any { it.serverType == McpServerType.REMOTE })
        assertEquals(McpServerPresets.localPresets.size + McpServerPresets.remotePresets.size, McpServerPresets.all.size)
    }

    @Test
    fun findByName_matches_exact_name() {
        repository.save(McpServerConfig(name = "Context7", baseUrl = "https://c.mcp"))
        assertNotNull(repository.findByName("Context7"))
        assertNull(repository.findByName("NotExist"))
    }
}