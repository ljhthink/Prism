package io.prism.ui.capabilities

import io.prism.data.SkillConfig
import io.prism.data.SkillSource
import io.prism.skill.SkillManifest
import io.prism.skill.SkillRegistry
import io.prism.ui.theme.PrismCyan
import io.prism.ui.theme.PrismIndigo
import io.prism.ui.theme.PrismMint
import io.prism.ui.theme.PrismTextFaint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SkillsViewModel 纯函数单元测试（US-027，BR-testing-004）。
 *
 * 验证 [SkillsViewModel.Companion.combineSkills] 与 [SkillsViewModel.Companion.toUiModel]
 * 的合并/映射逻辑，不依赖 Android Context / ObjectBox / 协程，纯 JVM 可测。
 *
 * **覆盖点**：
 * - combineSkills：空列表 / 完全匹配 / 部分匹配 / config 驱动列表顺序
 * - toUiModel：4 种 source 的 icon 映射 / null manifest 降级 / manifest 透传
 */
class SkillsViewModelTest {

    // ── combineSkills 测试 ──────────────────────────────────────────

    @Test
    fun `combineSkills returns empty for empty configs and entries`() {
        val result = SkillsViewModel.combineSkills(emptyList(), emptyList())
        assertTrue("空 config + 空 entry 应返回空列表", result.isEmpty())
    }

    @Test
    fun `combineSkills returns empty for empty configs with non-empty entries`() {
        val entry = makeSkillEntry(name = "translator")
        val result = SkillsViewModel.combineSkills(emptyList(), listOf(entry))
        assertTrue("config 驱动列表，无 config 时应返回空", result.isEmpty())
    }

    @Test
    fun `combineSkills matches config with entry by name and sets manifest`() {
        val config = makeConfig(name = "translator", id = 1L)
        val entry = makeSkillEntry(name = "translator")

        val result = SkillsViewModel.combineSkills(listOf(config), listOf(entry))

        assertEquals("应返回 1 条", 1, result.size)
        assertNotNull("manifest 应非 null（匹配到 entry）", result[0].manifest)
        assertEquals("config 应匹配", config, result[0].config)
    }

    @Test
    fun `combineSkills sets manifest null when no matching entry`() {
        val config = makeConfig(name = "translator", id = 1L)
        val entry = makeSkillEntry(name = "other-skill")

        val result = SkillsViewModel.combineSkills(listOf(config), listOf(entry))

        assertEquals(1, result.size)
        assertNull("manifest 应为 null（未匹配到 entry）", result[0].manifest)
    }

    @Test
    fun `combineSkills preserves config order and handles mixed matches`() {
        val config1 = makeConfig(name = "translator", id = 1L)
        val config2 = makeConfig(name = "code-reviewer", id = 2L)
        val config3 = makeConfig(name = "summarizer", id = 3L)

        // 只有 translator 和 summarizer 有对应 entry，code-reviewer 无 manifest
        val entry1 = makeSkillEntry(name = "translator")
        val entry3 = makeSkillEntry(name = "summarizer")

        val result = SkillsViewModel.combineSkills(
            configs = listOf(config1, config2, config3),
            entries = listOf(entry1, entry3)
        )

        assertEquals("应返回 3 条（config 驱动）", 3, result.size)
        assertEquals("顺序应与 config 一致", "translator", result[0].config.name)
        assertEquals("code-reviewer", result[1].config.name)
        assertEquals("summarizer", result[2].config.name)

        assertNotNull("translator 应有 manifest", result[0].manifest)
        assertNull("code-reviewer manifest 应为 null", result[1].manifest)
        assertNotNull("summarizer 应有 manifest", result[2].manifest)
    }

    @Test
    fun `combineSkills handles duplicate entry names by taking last match (associateBy semantics)`() {
        // 边界情况：SkillRegistry.dedupByPriority 上游已按优先级去重，
        // 正常不会出现重名 entry。此处验证 associateBy 的兜底语义（last wins）。
        val config = makeConfig(name = "translator", id = 1L)
        val entry1 = makeSkillEntry(name = "translator", description = "first")
        val entry2 = makeSkillEntry(name = "translator", description = "second")

        val result = SkillsViewModel.combineSkills(listOf(config), listOf(entry1, entry2))

        assertEquals(1, result.size)
        // Kotlin associateBy 语义：重复键时 last wins
        assertEquals("associateBy 重复键应取 last", "second", result[0].manifest?.description)
    }

    // ── toUiModel 测试 ──────────────────────────────────────────────

    @Test
    fun `toUiModel maps LOCAL_BUILTIN to correct icon`() {
        val config = makeConfig(name = "translator", source = SkillSource.LOCAL_BUILTIN)
        val result = SkillsViewModel.toUiModel(config, manifest = null)
        assertEquals("LOCAL_BUILTIN 应映射为 ◈", "◈", result.icon)
    }

    @Test
    fun `toUiModel maps LOCAL_USER to correct icon`() {
        val config = makeConfig(name = "my-skill", source = SkillSource.LOCAL_USER)
        val result = SkillsViewModel.toUiModel(config, manifest = null)
        assertEquals("LOCAL_USER 应映射为 ✎", "✎", result.icon)
    }

    @Test
    fun `toUiModel maps REMOTE to correct icon`() {
        val config = makeConfig(name = "remote-skill", source = SkillSource.REMOTE)
        val result = SkillsViewModel.toUiModel(config, manifest = null)
        assertEquals("REMOTE 应映射为 ⌂", "⌂", result.icon)
    }

    @Test
    fun `toUiModel maps unknown source to fallback icon`() {
        val config = makeConfig(name = "unknown", source = "UNKNOWN_SOURCE")
        val result = SkillsViewModel.toUiModel(config, manifest = null)
        assertEquals("未知 source 应映射为 ▣", "▣", result.icon)
    }

    @Test
    fun `toUiModel preserves null manifest`() {
        val config = makeConfig(name = "translator")
        val result = SkillsViewModel.toUiModel(config, manifest = null)
        assertNull("manifest 为 null 时应保持 null", result.manifest)
    }

    @Test
    fun `toUiModel preserves non-null manifest`() {
        val config = makeConfig(name = "translator")
        val manifest = makeManifest(name = "translator", description = "中英互译")
        val result = SkillsViewModel.toUiModel(config, manifest)
        assertNotNull("manifest 非 null 时应透传", result.manifest)
        assertEquals("translator", result.manifest?.name)
        assertEquals("中英互译", result.manifest?.description)
    }

    @Test
    fun `toUiModel preserves config fields`() {
        val config = makeConfig(
            name = "translator",
            id = 42L,
            source = SkillSource.LOCAL_BUILTIN,
            isEnabled = true,
            displayName = "智能翻译"
        )
        val result = SkillsViewModel.toUiModel(config, manifest = null)
        assertEquals(42L, result.config.id)
        assertEquals("translator", result.config.name)
        assertEquals(SkillSource.LOCAL_BUILTIN, result.config.source)
        assertTrue("isEnabled 应透传", result.config.isEnabled)
        assertEquals("智能翻译", result.config.displayName)
    }

    // ── sourceToAccent 测试（BR-testing-004，guardrail M-01 补强）──

    @Test
    fun `sourceToAccent maps LOCAL_BUILTIN to PrismCyan`() {
        assertEquals(PrismCyan, sourceToAccent(SkillSource.LOCAL_BUILTIN))
    }

    @Test
    fun `sourceToAccent maps LOCAL_USER to PrismIndigo`() {
        assertEquals(PrismIndigo, sourceToAccent(SkillSource.LOCAL_USER))
    }

    @Test
    fun `sourceToAccent maps REMOTE to PrismMint`() {
        assertEquals(PrismMint, sourceToAccent(SkillSource.REMOTE))
    }

    @Test
    fun `sourceToAccent maps unknown source to PrismTextFaint fallback`() {
        assertEquals(PrismTextFaint, sourceToAccent("UNKNOWN_SOURCE"))
    }

    // ── sourceToLabel 测试（BR-testing-004，guardrail M-01 补强）──

    @Test
    fun `sourceToLabel maps LOCAL_BUILTIN to 内置`() {
        assertEquals("内置", sourceToLabel(SkillSource.LOCAL_BUILTIN))
    }

    @Test
    fun `sourceToLabel maps LOCAL_USER to 本地`() {
        assertEquals("本地", sourceToLabel(SkillSource.LOCAL_USER))
    }

    @Test
    fun `sourceToLabel maps REMOTE to 远程`() {
        assertEquals("远程", sourceToLabel(SkillSource.REMOTE))
    }

    @Test
    fun `sourceToLabel maps unknown source to 未知 fallback`() {
        assertEquals("未知", sourceToLabel("UNKNOWN_SOURCE"))
    }

    // ── formatTimestamp 测试（BR-testing-004，guardrail M-01 补强）──

    @Test
    fun `formatTimestamp returns placeholder for zero timestamp`() {
        assertEquals("ms=0 应返回占位符", "—", formatTimestamp(0L))
    }

    @Test
    fun `formatTimestamp returns placeholder for negative timestamp`() {
        assertEquals("ms<0 应返回占位符", "—", formatTimestamp(-1L))
    }

    @Test
    fun `formatTimestamp returns formatted date for valid timestamp`() {
        // 2026-01-15 10:30:00 UTC → 按 Locale.getDefault() 格式化，至少包含 "2026" 和 "01"
        val ms = 1768564200000L  // 2026-01-15 10:30:00 UTC（北京时间 18:30）
        val result = formatTimestamp(ms)
        assertTrue("有效时间戳应返回格式化字符串，而非占位符", result != "—")
        assertTrue("应包含年份 2026", result.contains("2026"))
    }

    // ── 辅助工厂 ────────────────────────────────────────────────────

    /** 构建 SkillConfig 测试实例。 */
    private fun makeConfig(
        name: String = "test-skill",
        id: Long = 0L,
        source: String = SkillSource.LOCAL_BUILTIN,
        isEnabled: Boolean = false,
        displayName: String = name
    ): SkillConfig {
        return SkillConfig(
            id = id,
            name = name,
            displayName = displayName,
            source = source,
            skillDir = "/tmp/skills/$name",
            isEnabled = isEnabled
        )
    }

    /** 构建 SkillManifest 测试实例。 */
    private fun makeManifest(
        name: String = "test-skill",
        description: String = "test description"
    ): SkillManifest {
        return SkillManifest(
            name = name,
            description = description,
            body = "instruction body"
        )
    }

    /** 构建 SkillRegistry.SkillEntry 测试实例。 */
    private fun makeSkillEntry(
        name: String = "test-skill",
        description: String = "test description"
    ): SkillRegistry.SkillEntry {
        return SkillRegistry.SkillEntry(
            config = makeConfig(name = name),
            manifest = makeManifest(name = name, description = description)
        )
    }
}
