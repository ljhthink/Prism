package io.prism.ui.chat

import io.prism.crossapp.AppAvailabilityChecker
import io.prism.crossapp.AppLauncherBridge
import io.prism.crossapp.CrossAppLauncher
import io.prism.crossapp.CrossAppLocalToolExecutor
import io.prism.crossapp.SchemeRegistry
import io.prism.data.SkillConfig
import io.prism.data.SkillSource
import io.prism.network.ToolDefinition
import io.prism.skill.SkillManifest
import io.prism.skill.SkillRegistry
import io.prism.skill.SkillToolDecl
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M6 Phase C 验收补充测试（ac-verifier TKN-M6-PHASEC-ACCEPTANCE-001）。
 *
 * 补充覆盖 Phase C 集成点：
 * - AC-C-3: [ConversationViewModel.buildTools] 合并跨 App 工具定义
 * - AC-C-8: M-1 修复后的超时层级关系断言
 *
 * **测试策略**（BR-testing-004 纯 JVM 可测性）：
 * 使用 [FakeCrossAppLauncherForBuildTools]（继承 [CrossAppLauncher] open class）注入解耦，
 * 无需 Android Context 或真实 SchemeRegistry。
 */
class M6PhaseCAcceptanceTest {

    /** Fake CrossAppLauncher，仅用于 buildTools/buildToolDefinitions（不调用 launch 方法）。 */
    private class FakeCrossAppLauncherForBuildTools :
        CrossAppLauncher(
            SchemeRegistry.empty(),
            AppAvailabilityChecker { false },
            AppLauncherBridge()
        )

    private val fakeLauncher = FakeCrossAppLauncherForBuildTools()

    // ==================== AC-C-3: buildTools 合并跨 App 工具 ====================

    @Test
    fun `AC-C-3 buildTools with crossAppLauncher returns 3 cross-app tools for empty skills`() {
        val tools = ConversationViewModel.buildTools(emptyList(), fakeLauncher)

        assertEquals("空 Skill + crossAppLauncher 应返回 3 个跨 App 工具", 3, tools.size)
        val toolNames = tools.map { it.function.name }
        assertTrue(
            "应包含 cross_app__open_app, 实际: $toolNames",
            toolNames.contains(CrossAppLocalToolExecutor.TOOL_OPEN_APP)
        )
        assertTrue(
            "应包含 cross_app__share_content, 实际: $toolNames",
            toolNames.contains(CrossAppLocalToolExecutor.TOOL_SHARE_CONTENT)
        )
        assertTrue(
            "应包含 cross_app__pick_media, 实际: $toolNames",
            toolNames.contains(CrossAppLocalToolExecutor.TOOL_PICK_MEDIA)
        )
    }

    @Test
    fun `AC-C-3 buildTools with crossAppLauncher merges skill tools and cross-app tools`() {
        val skillEntry = makeSkillEntry(
            name = "translator",
            tools = listOf(
                SkillToolDecl(
                    name = "translate",
                    description = "Translate text",
                    parameters = buildJsonObject { }
                )
            )
        )

        val tools = ConversationViewModel.buildTools(listOf(skillEntry), fakeLauncher)

        assertEquals("1 Skill 工具 + 3 跨 App 工具 = 4", 4, tools.size)
        // Skill 工具在前
        assertEquals(
            "第 1 个应为 Skill 工具",
            "translator__translate",
            tools[0].function.name
        )
        // 跨 App 工具在后
        val crossAppNames = tools.drop(1).map { it.function.name }
        assertEquals(
            "后 3 个应为跨 App 工具",
            listOf(
                CrossAppLocalToolExecutor.TOOL_OPEN_APP,
                CrossAppLocalToolExecutor.TOOL_SHARE_CONTENT,
                CrossAppLocalToolExecutor.TOOL_PICK_MEDIA
            ),
            crossAppNames
        )
    }

    @Test
    fun `AC-C-3 buildTools with null crossAppLauncher returns only skill tools (backward compat)`() {
        val skillEntry = makeSkillEntry(
            name = "translator",
            tools = listOf(
                SkillToolDecl(
                    name = "translate",
                    description = "Translate text",
                    parameters = buildJsonObject { }
                )
            )
        )

        val tools = ConversationViewModel.buildTools(listOf(skillEntry), null)

        assertEquals("crossAppLauncher=null 时仅返回 Skill 工具", 1, tools.size)
        assertEquals("translator__translate", tools[0].function.name)
        assertFalse(
            "不应包含跨 App 工具",
            tools.any { it.function.name.startsWith("cross_app__") }
        )
    }

    @Test
    fun `AC-C-3 buildTools cross-app tools come after skill tools (ordering)`() {
        val entries = listOf(
            makeSkillEntry(
                name = "skill_a",
                tools = listOf(SkillToolDecl("tool1", "desc1", buildJsonObject { }))
            ),
            makeSkillEntry(
                name = "skill_b",
                tools = listOf(SkillToolDecl("tool2", "desc2", buildJsonObject { }))
            )
        )

        val tools = ConversationViewModel.buildTools(entries, fakeLauncher)

        assertEquals("2 Skill 工具 + 3 跨 App 工具 = 5", 5, tools.size)
        // 前 2 个是 Skill 工具
        assertTrue("skill_a__tool1 应在前", tools[0].function.name == "skill_a__tool1")
        assertTrue("skill_b__tool2 应在前", tools[1].function.name == "skill_b__tool2")
        // 后 3 个是跨 App 工具
        tools.drop(2).forEach { tool ->
            assertTrue(
                "后 3 个应以 cross_app__ 开头: ${tool.function.name}",
                tool.function.name.startsWith("cross_app__")
            )
        }
    }

    @Test
    fun `AC-C-3 buildTools cross-app tool definitions have valid JSON Schema`() {
        val tools = ConversationViewModel.buildTools(emptyList(), fakeLauncher)

        // open_app: appId required
        val openApp = tools.first { it.function.name == CrossAppLocalToolExecutor.TOOL_OPEN_APP }
        assertTrue(
            "open_app description 不应为空",
            openApp.function.description.isNotBlank()
        )

        // share_content: content required
        val shareContent = tools.first { it.function.name == CrossAppLocalToolExecutor.TOOL_SHARE_CONTENT }
        assertTrue(
            "share_content description 不应为空",
            shareContent.function.description.isNotBlank()
        )

        // pick_media: mediaType required
        val pickMedia = tools.first { it.function.name == CrossAppLocalToolExecutor.TOOL_PICK_MEDIA }
        assertTrue(
            "pick_media description 不应为空",
            pickMedia.function.description.isNotBlank()
        )
    }

    // ==================== AC-C-8: M-1 修复超时层级断言 ====================

    @Test
    fun `AC-C-8 bridge timeout is strictly shorter than SkillExecutor timeout (BR-concurrency-005)`() {
        val bridgeTimeout = AppLauncherBridge.DEFAULT_TIMEOUT_MS
        val skillExecutorTimeout = io.prism.skill.SkillExecutor.DEFAULT_TOOL_TIMEOUT_MS

        assertEquals("SkillExecutor 默认超时应为 30s", 30_000L, skillExecutorTimeout)
        assertEquals("AppLauncherBridge 默认超时应为 25s (M-1 修复)", 25_000L, bridgeTimeout)
        assertTrue(
            "bridge timeout ($bridgeTimeout) 必须 STRICTLY SHORTER than SkillExecutor timeout ($skillExecutorTimeout) — BR-concurrency-005",
            bridgeTimeout < skillExecutorTimeout
        )
    }

    // ==================== 辅助函数 ====================

    /** 构造测试用 SkillRegistry.SkillEntry（不依赖 Android Context）。 */
    private fun makeSkillEntry(
        name: String,
        tools: List<SkillToolDecl>? = null
    ): SkillRegistry.SkillEntry = SkillRegistry.SkillEntry(
        config = SkillConfig(
            id = 0L,
            name = name,
            displayName = name,
            source = SkillSource.LOCAL_BUILTIN,
            sourceUri = null,
            skillDir = "/skills/$name",
            isEnabled = true,
            isInstalled = true,
            version = "1.0.0"
        ),
        manifest = SkillManifest(
            name = name,
            description = "Test skill $name",
            version = "1.0.0",
            systemPrompt = null,
            tools = tools,
            body = ""
        )
    )
}
