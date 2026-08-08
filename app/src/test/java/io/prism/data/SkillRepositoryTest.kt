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
 * SkillRepository CRUD 单元测试（US-020 数据层，ADR-013 5.1）。
 *
 * 验证内容：
 * 1. SkillConfig 持久化到 ObjectBox（含 dependsOnMcpServers @Convert 类型转换）
 * 2. 配置列表增删改查
 * 3. 启用/停用切换 + 安装状态标记
 * 4. 按 name 查找 + 已启用列表过滤
 * 5. 更新时间戳自动刷新
 */
class SkillRepositoryTest {

    private lateinit var boxStore: BoxStore
    private lateinit var repository: SkillRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "skill-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        repository = SkillRepository(boxStore)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun save_assigns_positive_id() {
        val config = SkillConfig(name = "translator", displayName = "翻译", skillDir = "/skills/translator")
        val id = repository.save(config)
        assertTrue("应分配正数 id", id > 0)
    }

    @Test
    fun get_returns_persisted_config() {
        val config = SkillConfig(
            name = "code-reviewer",
            displayName = "代码审查",
            source = SkillSource.LOCAL_BUILTIN,
            skillDir = "/skills/builtin/code-reviewer",
            version = "1.0.0",
            dependsOnMcpServers = listOf("filesystem")
        )
        val id = repository.save(config)

        val retrieved = repository.get(id)
        assertNotNull("应能取回已保存配置", retrieved)
        assertEquals("code-reviewer", retrieved!!.name)
        assertEquals("代码审查", retrieved.displayName)
        assertEquals(SkillSource.LOCAL_BUILTIN, retrieved.source)
        assertEquals("1.0.0", retrieved.version)
        assertEquals(listOf("filesystem"), retrieved.dependsOnMcpServers)
        assertFalse("默认未启用", retrieved.isEnabled)
        assertTrue("默认已安装", retrieved.isInstalled)
    }

    @Test
    fun findByName_returns_matching_config() {
        repository.save(SkillConfig(name = "translator", displayName = "翻译", skillDir = "/a"))
        repository.save(SkillConfig(name = "summarizer", displayName = "总结", skillDir = "/b"))

        val found = repository.findByName("translator")
        assertNotNull("应能按 name 查找", found)
        assertEquals("翻译", found!!.displayName)

        assertNull("不存在的 name 返回 null", repository.findByName("nonexistent"))
    }

    @Test
    fun getAll_returns_sorted_by_createdAt() {
        val first = SkillConfig(name = "first", displayName = "第一", skillDir = "/a")
        Thread.sleep(5)
        val second = SkillConfig(name = "second", displayName = "第二", skillDir = "/b")
        repository.save(first)
        repository.save(second)

        val all = repository.getAll()
        assertEquals(2, all.size)
        assertEquals("first", all[0].name)
        assertEquals("second", all[1].name)
    }

    @Test
    fun setEnabled_persists_and_updates_flow() {
        val id = repository.save(
            SkillConfig(name = "rewriter", displayName = "改写", skillDir = "/r", isEnabled = false)
        )

        repository.setEnabled(id, true)

        val updated = repository.get(id)
        assertTrue("启用状态应已持久化", updated!!.isEnabled)
        assertTrue("updatedAt 应刷新", updated.updatedAt >= updated.createdAt)
        assertTrue("skills StateFlow 应反映启用状态", repository.skills.value.any { it.isEnabled })
    }

    @Test
    fun setInstalled_marks_installation_status() {
        val id = repository.save(
            SkillConfig(name = "remote-skill", displayName = "远程", skillDir = "/r", isInstalled = true)
        )

        repository.setInstalled(id, false)

        val updated = repository.get(id)
        assertFalse("安装状态应已标记为 false", updated!!.isInstalled)
    }

    @Test
    fun getEnabled_returns_only_enabled_and_installed() {
        repository.save(SkillConfig(name = "enabled-installed", displayName = "A", skillDir = "/a", isEnabled = true, isInstalled = true))
        repository.save(SkillConfig(name = "enabled-uninstalled", displayName = "B", skillDir = "/b", isEnabled = true, isInstalled = false))
        repository.save(SkillConfig(name = "disabled", displayName = "C", skillDir = "/c", isEnabled = false, isInstalled = true))

        val enabled = repository.getEnabled()
        assertEquals(1, enabled.size)
        assertEquals("enabled-installed", enabled[0].name)
    }

    @Test
    fun remove_deletes_config() {
        val id = repository.save(SkillConfig(name = "temp", displayName = "临时", skillDir = "/t"))
        assertEquals(1, repository.getAll().size)

        repository.remove(id)

        assertNull(repository.get(id))
        assertTrue(repository.getAll().isEmpty())
    }

    @Test
    fun removeAll_clears_all() {
        repository.save(SkillConfig(name = "a", displayName = "A", skillDir = "/a"))
        repository.save(SkillConfig(name = "b", displayName = "B", skillDir = "/b"))

        repository.removeAll()

        assertTrue(repository.getAll().isEmpty())
        assertTrue(repository.skills.value.isEmpty())
    }

    @Test
    fun save_updates_existing_config() {
        val id = repository.save(SkillConfig(name = "translator", displayName = "翻译", skillDir = "/a"))
        val original = repository.get(id)!!
        val originalUpdatedAt = original.updatedAt

        Thread.sleep(5)
        original.displayName = "智能翻译"
        repository.save(original)

        val updated = repository.get(id)
        assertEquals("智能翻译", updated!!.displayName)
        assertTrue("updatedAt 应刷新", updated.updatedAt > originalUpdatedAt)
    }

    @Test
    fun dependsOnMcpServers_roundtrips_through_converter() {
        val deps = listOf("filesystem", "github", "context7")
        val id = repository.save(
            SkillConfig(name = "meeting-notes", displayName = "纪要", skillDir = "/m", dependsOnMcpServers = deps)
        )

        val retrieved = repository.get(id)
        assertEquals("StringListConverter 应正确往返", deps, retrieved!!.dependsOnMcpServers)
    }

    @Test
    fun empty_dependsOnMcpServers_roundtrips() {
        val id = repository.save(
            SkillConfig(name = "pure-prompt", displayName = "纯提示", skillDir = "/p", dependsOnMcpServers = emptyList())
        )

        val retrieved = repository.get(id)
        assertTrue("空列表应正确往返", retrieved!!.dependsOnMcpServers.isEmpty())
    }
}
