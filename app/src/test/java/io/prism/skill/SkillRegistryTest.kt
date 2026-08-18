package io.prism.skill

import io.prism.data.SkillConfig
import io.prism.data.SkillSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [SkillRegistry] 单元测试（ADR-013 5.3，US-022 AC-5）。
 *
 * **测试策略**（test-architect skill 等价类/边界值）：
 * - 纯函数覆盖：[SkillRegistry.dedupByPriority] / [SkillRegistry.parseToEntry] /
 *   [SkillRegistry.scanDirectory] / [SkillRegistry.computeSyncDiff] /
 *   [SkillRegistry.mergeWithPersistedState] / [SkillRegistry.filterEnabledSkills]
 * - 不依赖 Android [android.content.Context] / [io.prism.data.SkillRepository] /
 *   ObjectBox，纯 JVM 可执行（companion object 内部函数，US-022 AC-5 可测性补强）
 * - [SkillRegistry.scanDirectory] 用 JUnit [TemporaryFolder] 模拟文件系统
 * - [SkillRegistry.scanBuiltin] 依赖 AssetManager，按项目惯例受限通过（US-002/003/008 同模式）
 *
 * **覆盖矩阵**：
 * | 函数 | 等价类 | 边界值 |
 * |---|---|---|
 * | dedupByPriority | 空 / 无冲突 / 三源冲突 / 两源冲突 | 单一来源 |
 * | parseToEntry | 合法 / 缺失 frontmatter / 非法 YAML / REMOTE sourceUri | — |
 * | scanDirectory | 不存在目录 / 空目录 / 合法子目录 / 缺 SKILL.md / 非法 SKILL.md / REMOTE sourceUri | — |
 * | computeSyncDiff | 全新增 / 全更新 / 标记缺失 / 已卸载跳过 / 混合 / toInsert（内置默认启用，用户/远程默认禁用） | — |
 * | mergeWithPersistedState | 继承 isEnabled / 未持久化 / 空 / 部分重叠 | — |
 * | filterEnabledSkills | 空 / 全启用 / 全禁用 / 混合（isEnabled × isInstalled 四象限） | — |
 */
class SkillRegistryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ============ 辅助构造 ============

    private fun makeConfig(
        name: String = "test-skill",
        source: String = SkillSource.LOCAL_BUILTIN,
        isEnabled: Boolean = false,
        isInstalled: Boolean = true,
        id: Long = 0,
        version: String = "1.0.0",
        isHidden: Boolean = false
    ): SkillConfig = SkillConfig(
        id = id,
        name = name,
        displayName = name,
        source = source,
        sourceUri = null,
        skillDir = "/skills/$name",
        isEnabled = isEnabled,
        isInstalled = isInstalled,
        isHidden = isHidden,
        version = version
    )

    private fun makeManifest(name: String = "test-skill", version: String? = "1.0.0"): SkillManifest =
        SkillManifest(name = name, description = "Test skill for unit testing", version = version, body = "")

    private fun makeEntry(
        name: String = "test-skill",
        source: String = SkillSource.LOCAL_BUILTIN,
        isEnabled: Boolean = false,
        isInstalled: Boolean = true,
        id: Long = 0,
        manifestVersion: String? = "1.0.0",
        isHidden: Boolean = false
    ): SkillRegistry.SkillEntry = SkillRegistry.SkillEntry(
        config = makeConfig(name, source, isEnabled, isInstalled, id, version = manifestVersion ?: "0.0.0", isHidden = isHidden),
        manifest = makeManifest(name, version = manifestVersion)
    )

    private val validSkillMd = """
        ---
        name: translator
        description: 中英互译翻译助手
        version: 1.0.0
        ---
        # 翻译助手
        使用说明...
    """.trimIndent()

    private val validSkillMdAlpha = """
        ---
        name: alpha
        description: Alpha skill
        ---
        body
    """.trimIndent()

    // ============ dedupByPriority ============

    @Test
    fun `dedupByPriority returns empty list for empty input`() {
        val result = SkillRegistry.dedupByPriority(emptyList())
        assertTrue("Should be empty", result.isEmpty())
    }

    @Test
    fun `dedupByPriority returns single entry when no conflict`() {
        val entry = makeEntry("alpha", SkillSource.LOCAL_BUILTIN)
        val result = SkillRegistry.dedupByPriority(listOf(entry))
        assertEquals(1, result.size)
        assertEquals("alpha", result[0].config.name)
    }

    @Test
    fun `dedupByPriority keeps LOCAL_USER over REMOTE and LOCAL_BUILTIN`() {
        val builtin = makeEntry("dup", SkillSource.LOCAL_BUILTIN)
        val remote = makeEntry("dup", SkillSource.REMOTE)
        val user = makeEntry("dup", SkillSource.LOCAL_USER)
        val result = SkillRegistry.dedupByPriority(listOf(builtin, remote, user))
        assertEquals(1, result.size)
        assertEquals(
            "Should keep LOCAL_USER (highest priority)",
            SkillSource.LOCAL_USER,
            result[0].config.source
        )
    }

    @Test
    fun `dedupByPriority keeps REMOTE over LOCAL_BUILTIN`() {
        val builtin = makeEntry("dup", SkillSource.LOCAL_BUILTIN)
        val remote = makeEntry("dup", SkillSource.REMOTE)
        val result = SkillRegistry.dedupByPriority(listOf(builtin, remote))
        assertEquals(1, result.size)
        assertEquals(SkillSource.REMOTE, result[0].config.source)
    }

    @Test
    fun `dedupByPriority preserves distinct names sorted alphabetically`() {
        val c = makeEntry("charlie", SkillSource.LOCAL_BUILTIN)
        val a = makeEntry("alpha", SkillSource.LOCAL_BUILTIN)
        val b = makeEntry("bravo", SkillSource.LOCAL_BUILTIN)
        val result = SkillRegistry.dedupByPriority(listOf(c, a, b))
        assertEquals(listOf("alpha", "bravo", "charlie"), result.map { it.config.name })
    }

    @Test
    fun `dedupByPriority handles single source with multiple distinct names`() {
        val entries = listOf(
            makeEntry("a", SkillSource.LOCAL_USER),
            makeEntry("b", SkillSource.LOCAL_USER)
        )
        val result = SkillRegistry.dedupByPriority(entries)
        assertEquals(2, result.size)
    }

    // ============ parseToEntry ============

    @Test
    fun `parseToEntry returns SkillEntry for valid SKILL_md`() {
        val entry = SkillRegistry.parseToEntry(
            content = validSkillMd,
            source = SkillSource.LOCAL_BUILTIN,
            sourceUri = null,
            skillDir = "assets://skills/builtin/translator"
        )
        assertNotNull("Should parse valid SKILL.md", entry)
        assertEquals("translator", entry!!.config.name)
        assertEquals("翻译助手", entry.config.displayName) // 从 body 第一个一级标题 # 提取
        assertEquals(SkillSource.LOCAL_BUILTIN, entry.config.source)
        assertEquals("1.0.0", entry.config.version)
        assertTrue("isInstalled default true", entry.config.isInstalled)
        assertTrue("isEnabled default false", !entry.config.isEnabled)
        assertEquals("translator", entry.manifest.name)
        assertTrue(
            "manifest body should contain '# 翻译助手'",
            entry.manifest.body.contains("# 翻译助手")
        )
    }

    @Test
    fun `parseToEntry returns null for invalid SKILL_md missing frontmatter`() {
        val entry = SkillRegistry.parseToEntry(
            content = "no frontmatter at all",
            source = SkillSource.LOCAL_BUILTIN,
            sourceUri = null,
            skillDir = "/test"
        )
        assertNull("Should return null for missing frontmatter", entry)
    }

    @Test
    fun `parseToEntry returns null for invalid YAML`() {
        val entry = SkillRegistry.parseToEntry(
            content = "---\nname: [invalid\n---\nbody",
            source = SkillSource.LOCAL_BUILTIN,
            sourceUri = null,
            skillDir = "/test"
        )
        assertNull("Should return null for invalid YAML", entry)
    }

    @Test
    fun `parseToEntry returns null for missing required name field`() {
        val content = """
            ---
            description: missing name field
            ---
            body
        """.trimIndent()
        val entry = SkillRegistry.parseToEntry(
            content = content,
            source = SkillSource.LOCAL_BUILTIN,
            sourceUri = null,
            skillDir = "/test"
        )
        assertNull("Should return null for missing name", entry)
    }

    @Test
    fun `parseToEntry sets sourceUri for REMOTE source`() {
        val entry = SkillRegistry.parseToEntry(
            content = validSkillMd,
            source = SkillSource.REMOTE,
            sourceUri = "https://example.com/skill.md",
            skillDir = "/remote/translator"
        )
        assertNotNull(entry)
        assertEquals("https://example.com/skill.md", entry!!.config.sourceUri)
        assertEquals(SkillSource.REMOTE, entry.config.source)
    }

    @Test
    fun `parseToEntry derives displayName from description first line`() {
        val content = """
            ---
            name: multi-line
            description: 首行作为显示名，这是较长的描述
            ---
            body
        """.trimIndent()
        val entry = SkillRegistry.parseToEntry(
            content = content,
            source = SkillSource.LOCAL_BUILTIN,
            sourceUri = null,
            skillDir = "/test"
        )
        assertNotNull(entry)
        assertEquals("首行作为显示名，这是较长的描述", entry!!.config.displayName)
    }

    @Test
    fun `parseToEntry prefers first body heading for displayName`() {
        val content = """
            ---
            name: humanizer-zh
            description: 中文人性化改写，消除 AI 语感痕迹
            ---
            # 中文人性化改写
            ## 使用说明
        """.trimIndent()
        val entry = SkillRegistry.parseToEntry(
            content = content,
            source = SkillSource.LOCAL_BUILTIN,
            sourceUri = null,
            skillDir = "/test"
        )
        assertNotNull(entry)
        assertEquals("中文人性化改写", entry!!.config.displayName)
    }

    // ============ findFirstHeading ============

    @Test
    fun `findFirstHeading returns first H1 heading`() {
        assertEquals("中文人性化改写", SkillRegistry.findFirstHeading("# 中文人性化改写\n## 使用说明"))
    }

    @Test
    fun `findFirstHeading ignores leading blank lines`() {
        assertEquals("标题", SkillRegistry.findFirstHeading("\n\n# 标题\n正文"))
    }

    @Test
    fun `findFirstHeading returns null for empty body`() {
        assertNull(SkillRegistry.findFirstHeading(""))
    }

    @Test
    fun `findFirstHeading returns null for body without H1`() {
        assertNull(SkillRegistry.findFirstHeading("普通段落\n## 二级标题"))
    }

    @Test
    fun `findFirstHeading returns null for H2 heading only`() {
        assertNull(SkillRegistry.findFirstHeading("## 二级标题\n正文"))
    }

    @Test
    fun `findFirstHeading returns null for bare hash text`() {
        assertNull(SkillRegistry.findFirstHeading("#\n正文"))
    }

    // ============ scanDirectory ============

    @Test
    fun `scanDirectory returns empty for non-existent directory`() {
        val result = SkillRegistry.scanDirectory(
            File(tempFolder.root, "nonexistent"),
            SkillSource.LOCAL_USER
        )
        assertTrue("Non-existent dir should return empty", result.isEmpty())
    }

    @Test
    fun `scanDirectory returns empty for empty directory`() {
        val emptyDir = tempFolder.newFolder("empty-skills")
        val result = SkillRegistry.scanDirectory(emptyDir, SkillSource.LOCAL_USER)
        assertTrue("Empty dir should return empty", result.isEmpty())
    }

    @Test
    fun `scanDirectory parses valid skill subdirectory`() {
        val skillsRoot = tempFolder.newFolder("skills")
        val skillDir = File(skillsRoot, "translator").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText(validSkillMd)

        val result = SkillRegistry.scanDirectory(skillsRoot, SkillSource.LOCAL_USER)
        assertEquals(1, result.size)
        assertEquals("translator", result[0].config.name)
        assertEquals(SkillSource.LOCAL_USER, result[0].config.source)
        assertTrue(
            "skillDir should be absolute path containing translator",
            result[0].config.skillDir.contains("translator")
        )
    }

    @Test
    fun `scanDirectory skips subdirectory missing SKILL_md`() {
        val skillsRoot = tempFolder.newFolder("skills")
        File(skillsRoot, "empty-dir").mkdirs() // 无 SKILL.md
        val skillDir = File(skillsRoot, "translator").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText(validSkillMd)

        val result = SkillRegistry.scanDirectory(skillsRoot, SkillSource.LOCAL_USER)
        assertEquals(1, result.size)
        assertEquals("translator", result[0].config.name)
    }

    @Test
    fun `scanDirectory skips subdirectory with invalid SKILL_md`() {
        val skillsRoot = tempFolder.newFolder("skills")
        val badDir = File(skillsRoot, "bad").apply { mkdirs() }
        File(badDir, "SKILL.md").writeText("invalid content without frontmatter")
        val goodDir = File(skillsRoot, "good").apply { mkdirs() }
        File(goodDir, "SKILL.md").writeText(validSkillMd)

        val result = SkillRegistry.scanDirectory(skillsRoot, SkillSource.LOCAL_USER)
        assertEquals(1, result.size)
        assertEquals("translator", result[0].config.name)
    }

    @Test
    fun `scanDirectory sets sourceUri to dirname for REMOTE source`() {
        val skillsRoot = tempFolder.newFolder("skills")
        val skillDir = File(skillsRoot, "downloaded-skill").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText(validSkillMd)

        val result = SkillRegistry.scanDirectory(skillsRoot, SkillSource.REMOTE)
        assertEquals(1, result.size)
        // sourceUri for REMOTE = skillDir.name（目录名），非 URL
        assertEquals("downloaded-skill", result[0].config.sourceUri)
    }

    @Test
    fun `scanDirectory sets sourceUri to null for LOCAL_USER source`() {
        val skillsRoot = tempFolder.newFolder("skills")
        val skillDir = File(skillsRoot, "user-skill").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText(validSkillMd)

        val result = SkillRegistry.scanDirectory(skillsRoot, SkillSource.LOCAL_USER)
        assertEquals(1, result.size)
        assertNull(result[0].config.sourceUri)
    }

    @Test
    fun `scanDirectory parses multiple valid skill subdirectories`() {
        val skillsRoot = tempFolder.newFolder("skills")
        val dir1 = File(skillsRoot, "translator").apply { mkdirs() }
        File(dir1, "SKILL.md").writeText(validSkillMd)
        val dir2 = File(skillsRoot, "alpha").apply { mkdirs() }
        File(dir2, "SKILL.md").writeText(validSkillMdAlpha)

        val result = SkillRegistry.scanDirectory(skillsRoot, SkillSource.LOCAL_USER)
        assertEquals(2, result.size)
        val names = result.map { it.config.name }.sorted()
        assertEquals(listOf("alpha", "translator"), names)
    }

    @Test
    fun `scanDirectory ignores non-directory files in root`() {
        val skillsRoot = tempFolder.newFolder("skills")
        File(skillsRoot, "stray-file.txt").writeText("not a skill dir")
        val skillDir = File(skillsRoot, "translator").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText(validSkillMd)

        val result = SkillRegistry.scanDirectory(skillsRoot, SkillSource.LOCAL_USER)
        assertEquals(1, result.size)
        assertEquals("translator", result[0].config.name)
    }

    // ============ computeSyncDiff ============

    @Test
    fun `computeSyncDiff returns all as toInsert when existing is empty`() {
        val discovered = listOf(
            makeEntry("alpha", SkillSource.LOCAL_BUILTIN),
            makeEntry("bravo", SkillSource.LOCAL_BUILTIN)
        )
        val diff = SkillRegistry.computeSyncDiff(discovered, emptyMap())
        assertEquals(2, diff.toInsert.size)
        assertEquals(0, diff.toUpdate.size)
        assertEquals(0, diff.toMarkUninstalled.size)
        // R4（ADR-032）：内置 Skill 首次安装默认启用（开箱即用，LLM 首次即感知 Skills）
        assertTrue("内置 Skill 首次安装应默认启用", diff.toInsert.all { it.isEnabled })
    }

    @Test
    fun `computeSyncDiff keeps user and remote skills disabled by default on insert`() {
        // R4（ADR-032）：仅内置 Skill 默认启用；用户自建 / 远程下载 Skill 仍需用户主动启用
        val discovered = listOf(
            makeEntry("user-skill", SkillSource.LOCAL_USER),
            makeEntry("remote-skill", SkillSource.REMOTE)
        )
        val diff = SkillRegistry.computeSyncDiff(discovered, emptyMap())
        assertEquals(2, diff.toInsert.size)
        assertTrue("用户自建 Skill 不应默认启用", diff.toInsert.none { it.isEnabled })
    }

    @Test
    fun `computeSyncDiff returns all as toUpdate when all exist`() {
        val existing = mapOf(
            "alpha" to makeConfig("alpha", SkillSource.LOCAL_BUILTIN, isEnabled = true, id = 1L, version = "0.9.0"),
            "bravo" to makeConfig("bravo", SkillSource.LOCAL_BUILTIN, isEnabled = false, id = 2L, version = "0.9.0")
        )
        val discovered = listOf(
            makeEntry("alpha", SkillSource.LOCAL_BUILTIN),
            makeEntry("bravo", SkillSource.LOCAL_BUILTIN)
        )
        val diff = SkillRegistry.computeSyncDiff(discovered, existing)
        assertEquals(0, diff.toInsert.size)
        assertEquals(2, diff.toUpdate.size)
        assertEquals(0, diff.toMarkUninstalled.size)

        // 验证保留 id + isEnabled
        val alphaUpdate = diff.toUpdate.find { it.name == "alpha" }!!
        assertEquals(1L, alphaUpdate.id)
        assertTrue("isEnabled should be preserved from existing", alphaUpdate.isEnabled)
        assertEquals("1.0.0", alphaUpdate.version) // 来自 discovered manifest
        assertTrue("isInstalled should be set to true", alphaUpdate.isInstalled)
    }

    @Test
    fun `computeSyncDiff marks missing skills as uninstalled`() {
        val existing = mapOf(
            "alpha" to makeConfig("alpha", SkillSource.LOCAL_BUILTIN, isInstalled = true, id = 1L),
            "deleted" to makeConfig("deleted", SkillSource.LOCAL_BUILTIN, isInstalled = true, id = 2L)
        )
        val discovered = listOf(makeEntry("alpha", SkillSource.LOCAL_BUILTIN))
        val diff = SkillRegistry.computeSyncDiff(discovered, existing)
        assertEquals(0, diff.toInsert.size)
        assertEquals(1, diff.toUpdate.size)
        assertEquals(1, diff.toMarkUninstalled.size)
        assertEquals("deleted", diff.toMarkUninstalled[0].name)
    }

    @Test
    fun `computeSyncDiff does not mark already-uninstalled skills`() {
        val existing = mapOf(
            "already-gone" to makeConfig("already-gone", SkillSource.LOCAL_BUILTIN, isInstalled = false, id = 1L)
        )
        val discovered = emptyList<SkillRegistry.SkillEntry>()
        val diff = SkillRegistry.computeSyncDiff(discovered, existing)
        assertEquals(0, diff.toMarkUninstalled.size)
    }

    @Test
    fun `computeSyncDiff handles mixed scenario`() {
        val existing = mapOf(
            "update-me" to makeConfig("update-me", SkillSource.LOCAL_BUILTIN, isEnabled = true, id = 1L, version = "0.9.0"),
            "mark-missing" to makeConfig("mark-missing", SkillSource.LOCAL_BUILTIN, isInstalled = true, id = 2L)
        )
        val discovered = listOf(
            makeEntry("update-me", SkillSource.LOCAL_BUILTIN),
            makeEntry("new-skill", SkillSource.LOCAL_BUILTIN)
        )
        val diff = SkillRegistry.computeSyncDiff(discovered, existing)
        assertEquals(1, diff.toInsert.size)
        assertEquals("new-skill", diff.toInsert[0].name)
        assertEquals(1, diff.toUpdate.size)
        assertEquals("update-me", diff.toUpdate[0].name)
        assertEquals(1, diff.toMarkUninstalled.size)
        assertEquals("mark-missing", diff.toMarkUninstalled[0].name)
    }

    @Test
    fun `computeSyncDiff toInsert builtin has isEnabled=true by default`() {
        // R4（ADR-032）：内置 Skill 首次安装默认启用（LLM 开箱即感知 Skills）
        val discovered = listOf(makeEntry("new", SkillSource.LOCAL_BUILTIN))
        val diff = SkillRegistry.computeSyncDiff(discovered, emptyMap())
        assertEquals(1, diff.toInsert.size)
        assertTrue("Builtin skill should default to isEnabled=true (R4)", diff.toInsert[0].isEnabled)
        assertEquals("New skill should have id=0 (unassigned)", 0L, diff.toInsert[0].id)
    }

    @Test
    fun `computeSyncDiff toUpdate preserves isEnabled from existing`() {
        val existing = mapOf(
            "keep-enabled" to makeConfig("keep-enabled", SkillSource.LOCAL_BUILTIN, isEnabled = true, id = 1L),
            "keep-disabled" to makeConfig("keep-disabled", SkillSource.LOCAL_BUILTIN, isEnabled = false, id = 2L)
        )
        val discovered = listOf(
            makeEntry("keep-enabled", SkillSource.LOCAL_BUILTIN),
            makeEntry("keep-disabled", SkillSource.LOCAL_BUILTIN)
        )
        val diff = SkillRegistry.computeSyncDiff(discovered, existing)
        assertEquals(2, diff.toUpdate.size)
        val enabled = diff.toUpdate.find { it.name == "keep-enabled" }!!
        assertTrue("keep-enabled should preserve isEnabled=true", enabled.isEnabled)
        val disabled = diff.toUpdate.find { it.name == "keep-disabled" }!!
        assertTrue("keep-disabled should preserve isEnabled=false", !disabled.isEnabled)
    }

    @Test
    fun `computeSyncDiff returns empty diff for empty discovered and empty existing`() {
        val diff = SkillRegistry.computeSyncDiff(emptyList(), emptyMap())
        assertTrue(diff.toInsert.isEmpty())
        assertTrue(diff.toUpdate.isEmpty())
        assertTrue(diff.toMarkUninstalled.isEmpty())
    }

    // ============ mergeWithPersistedState ============

    @Test
    fun `mergeWithPersistedState inherits isEnabled from persisted`() {
        val discovered = listOf(makeEntry("alpha", SkillSource.LOCAL_BUILTIN, isEnabled = false))
        val persisted = mapOf(
            "alpha" to makeConfig("alpha", SkillSource.LOCAL_BUILTIN, isEnabled = true, id = 5L, isInstalled = true)
        )
        val result = SkillRegistry.mergeWithPersistedState(discovered, persisted)
        assertEquals(1, result.size)
        assertTrue("Should inherit isEnabled=true from persisted", result[0].config.isEnabled)
        assertEquals("Should inherit id from persisted", 5L, result[0].config.id)
    }

    @Test
    fun `mergeWithPersistedState keeps entry as-is when not persisted`() {
        val discovered = listOf(makeEntry("orphan", SkillSource.LOCAL_BUILTIN, isEnabled = false))
        val result = SkillRegistry.mergeWithPersistedState(discovered, emptyMap())
        assertEquals(1, result.size)
        assertEquals("orphan", result[0].config.name)
        assertTrue("Should keep original isEnabled=false", !result[0].config.isEnabled)
    }

    @Test
    fun `mergeWithPersistedState returns empty for empty discovered`() {
        val persisted = mapOf("x" to makeConfig("x"))
        val result = SkillRegistry.mergeWithPersistedState(emptyList(), persisted)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `mergeWithPersistedState handles partial overlap`() {
        val discovered = listOf(
            makeEntry("alpha", SkillSource.LOCAL_BUILTIN),
            makeEntry("bravo", SkillSource.LOCAL_BUILTIN)
        )
        val persisted = mapOf(
            "alpha" to makeConfig("alpha", SkillSource.LOCAL_BUILTIN, isEnabled = true, id = 1L)
        )
        val result = SkillRegistry.mergeWithPersistedState(discovered, persisted)
        assertEquals(2, result.size)
        val alpha = result.find { it.config.name == "alpha" }!!
        assertTrue("alpha should inherit isEnabled", alpha.config.isEnabled)
        assertEquals(1L, alpha.config.id)
        val bravo = result.find { it.config.name == "bravo" }!!
        assertTrue("bravo should keep original isEnabled=false", !bravo.config.isEnabled)
        assertEquals("bravo should keep original id=0", 0L, bravo.config.id)
    }

    // ============ filterEnabledSkills ============

    @Test
    fun `filterEnabledSkills returns empty for empty input`() {
        val result = SkillRegistry.filterEnabledSkills(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `filterEnabledSkills returns only enabled and installed`() {
        val skills = listOf(
            makeEntry("enabled-installed", SkillSource.LOCAL_BUILTIN, isEnabled = true, isInstalled = true),
            makeEntry("disabled-installed", SkillSource.LOCAL_BUILTIN, isEnabled = false, isInstalled = true),
            makeEntry("enabled-uninstalled", SkillSource.LOCAL_BUILTIN, isEnabled = true, isInstalled = false),
            makeEntry("disabled-uninstalled", SkillSource.LOCAL_BUILTIN, isEnabled = false, isInstalled = false)
        )
        val result = SkillRegistry.filterEnabledSkills(skills)
        assertEquals(1, result.size)
        assertEquals("enabled-installed", result[0].config.name)
    }

    @Test
    fun `filterEnabledSkills returns all when all enabled and installed`() {
        val skills = listOf(
            makeEntry("a", SkillSource.LOCAL_BUILTIN, isEnabled = true, isInstalled = true),
            makeEntry("b", SkillSource.LOCAL_BUILTIN, isEnabled = true, isInstalled = true)
        )
        val result = SkillRegistry.filterEnabledSkills(skills)
        assertEquals(2, result.size)
    }

    @Test
    fun `filterEnabledSkills returns empty when all disabled`() {
        val skills = listOf(
            makeEntry("a", SkillSource.LOCAL_BUILTIN, isEnabled = false, isInstalled = true)
        )
        val result = SkillRegistry.filterEnabledSkills(skills)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `filterEnabledSkills returns empty when all uninstalled`() {
        val skills = listOf(
            makeEntry("a", SkillSource.LOCAL_BUILTIN, isEnabled = true, isInstalled = false)
        )
        val result = SkillRegistry.filterEnabledSkills(skills)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `filterEnabledSkills excludes hidden skill even if enabled and installed`() {
        val skills = listOf(
            makeEntry("deleted", SkillSource.LOCAL_BUILTIN, isEnabled = true, isInstalled = true, isHidden = true),
            makeEntry("normal", SkillSource.LOCAL_BUILTIN, isEnabled = true, isInstalled = true, isHidden = false)
        )
        val result = SkillRegistry.filterEnabledSkills(skills)
        assertEquals(1, result.size)
        assertEquals("normal", result[0].config.name)
    }

    @Test
    fun `filterEnabledSkills excludes only hidden and keeps enabled installed visible`() {
        val skills = listOf(
            makeEntry("hidden-enabled", SkillSource.LOCAL_USER, isEnabled = true, isInstalled = true, isHidden = true),
            makeEntry("visible-enabled", SkillSource.LOCAL_USER, isEnabled = true, isInstalled = true, isHidden = false),
            makeEntry("visible-disabled", SkillSource.LOCAL_USER, isEnabled = false, isInstalled = true, isHidden = false)
        )
        val result = SkillRegistry.filterEnabledSkills(skills)
        assertEquals(1, result.size)
        assertEquals("visible-enabled", result[0].config.name)
    }

    // ============ hiddenNameSet / filterOutHidden / disposeMissingConfigAction（guardrail MEDIUM#2）============

    @Test
    fun `hiddenNameSet returns names of hidden skills only`() {
        val configs = listOf(
            makeConfig("a", isHidden = true),
            makeConfig("b", isHidden = false),
            makeConfig("c", isHidden = true)
        )
        assertEquals(setOf("a", "c"), SkillRegistry.hiddenNameSet(configs))
    }

    @Test
    fun `hiddenNameSet returns empty for no hidden skills`() {
        val configs = listOf(makeConfig("a", isHidden = false), makeConfig("b", isHidden = false))
        assertTrue(SkillRegistry.hiddenNameSet(configs).isEmpty())
    }

    @Test
    fun `filterOutHidden removes hidden entries and keeps others`() {
        val entries = listOf(
            makeEntry("deleted", SkillSource.LOCAL_BUILTIN, isHidden = true),
            makeEntry("keep", SkillSource.LOCAL_BUILTIN, isHidden = false)
        )
        val result = SkillRegistry.filterOutHidden(entries, setOf("deleted"))
        assertEquals(1, result.size)
        assertEquals("keep", result[0].config.name)
    }

    @Test
    fun `filterOutHidden returns all entries when hiddenNames empty`() {
        val entries = listOf(
            makeEntry("a", SkillSource.LOCAL_BUILTIN),
            makeEntry("b", SkillSource.LOCAL_BUILTIN)
        )
        assertEquals(2, SkillRegistry.filterOutHidden(entries, emptySet()).size)
    }

    @Test
    fun `filterOutHidden returns empty when all entries hidden`() {
        val entries = listOf(makeEntry("a", SkillSource.LOCAL_BUILTIN, isHidden = true))
        assertTrue(SkillRegistry.filterOutHidden(entries, setOf("a")).isEmpty())
    }

    @Test
    fun `disposeMissingConfigAction keeps hidden config`() {
        val config = makeConfig("hidden", SkillSource.LOCAL_BUILTIN, isHidden = true)
        assertEquals(
            SkillRegistry.DisposeAction.KEEP_HIDDEN,
            SkillRegistry.disposeMissingConfigAction(config)
        )
    }

    @Test
    fun `disposeMissingConfigAction purges non-hidden builtin`() {
        val config = makeConfig("removed-builtin", SkillSource.LOCAL_BUILTIN, isHidden = false)
        assertEquals(
            SkillRegistry.DisposeAction.PURGE_BUILTIN,
            SkillRegistry.disposeMissingConfigAction(config)
        )
    }

    @Test
    fun `disposeMissingConfigAction marks user and remote as uninstalled`() {
        val user = makeConfig("user-skill", SkillSource.LOCAL_USER, isHidden = false)
        val remote = makeConfig("remote-skill", SkillSource.REMOTE, isHidden = false)
        assertEquals(
            SkillRegistry.DisposeAction.MARK_UNINSTALLED,
            SkillRegistry.disposeMissingConfigAction(user)
        )
        assertEquals(
            SkillRegistry.DisposeAction.MARK_UNINSTALLED,
            SkillRegistry.disposeMissingConfigAction(remote)
        )
    }

    // ============ SyncDiff 数据类 ============

    @Test
    fun `SyncDiff data class holds three lists`() {
        val diff = SkillRegistry.SyncDiff(
            toInsert = listOf(makeConfig("a")),
            toUpdate = listOf(makeConfig("b")),
            toMarkUninstalled = listOf(makeConfig("c"))
        )
        assertEquals(1, diff.toInsert.size)
        assertEquals(1, diff.toUpdate.size)
        assertEquals(1, diff.toMarkUninstalled.size)
        assertEquals("a", diff.toInsert[0].name)
        assertEquals("b", diff.toUpdate[0].name)
        assertEquals("c", diff.toMarkUninstalled[0].name)
    }
}
