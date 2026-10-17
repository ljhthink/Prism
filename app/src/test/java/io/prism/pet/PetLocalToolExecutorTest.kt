package io.prism.pet

import io.prism.pet.model.PetActionKind
import io.prism.pet.model.PetActionRef
import io.prism.pet.model.PetAppearance
import io.prism.pet.model.PetAppearanceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次24（ADR-051 D8 / PRD US-2409）：`pet__play_action` 工具行为单测。
 *
 * 覆盖三种必须"返回可读结果、绝不抛异常"的降级路径（未开启 / 无形象 / 别名非法），
 * 以及工具定义本身的契约（命名空间、枚举、严格参数）。
 */
class PetLocalToolExecutorTest {

    private val appearance = PetAppearance(
        id = "cyrene",
        name = "昔涟",
        kind = PetAppearanceKind.LIVE2D,
        model = "Cyrene.model3.json",
        actions = mapOf(
            "happy" to PetActionRef(PetActionKind.EXPRESSION, "开心眼", displayName = "开心"),
            "wave" to PetActionRef(PetActionKind.MOTION, "动作#6", 0, "动作回正")
        )
    )

    private fun executor(
        appearance: PetAppearance?,
        enabled: Boolean = true,
        dispatched: MutableList<String> = mutableListOf()
    ) = PetLocalToolExecutor(
        appearanceProvider = { appearance },
        enabledProvider = { enabled },
        dispatch = { dispatched.add(it) }
    )

    @Test
    fun `handles - 仅认自己的命名空间`() {
        val ex = executor(appearance)
        assertTrue(ex.handles(PetLocalToolExecutor.TOOL_PLAY_ACTION))
        assertFalse(ex.handles("hotlist__get"))
        assertFalse(ex.handles("pet__play"))
        assertEquals("pet__play_action", PetLocalToolExecutor.TOOL_PLAY_ACTION)
    }

    @Test
    fun `执行 - 正常播放并派发归一化别名`() = runBlocking<Unit> {
        val dispatched = mutableListOf<String>()
        val result = executor(appearance, dispatched = dispatched)
            .execute(PetLocalToolExecutor.TOOL_PLAY_ACTION, mapOf("action" to "  HAPPY "))

        assertTrue(result.contains("昔涟"))
        assertTrue(result.contains("开心"))
        assertEquals(listOf("happy"), dispatched)
    }

    @Test
    fun `执行 - 桌宠未开启返回可读说明且不派发`() = runBlocking<Unit> {
        val dispatched = mutableListOf<String>()
        val result = executor(appearance, enabled = false, dispatched = dispatched)
            .execute(PetLocalToolExecutor.TOOL_PLAY_ACTION, mapOf("action" to "happy"))

        assertTrue(result.contains("未开启"))
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `执行 - 无形象返回可读说明`() = runBlocking<Unit> {
        val result = executor(null)
            .execute(PetLocalToolExecutor.TOOL_PLAY_ACTION, mapOf("action" to "happy"))
        assertTrue(result.contains("尚未选择"))
    }

    /** PRD US-2409 AC-4：别名非法时必须列出可用别名，让模型有机会自我纠正。 */
    @Test
    fun `执行 - 别名非法列出可用别名`() = runBlocking<Unit> {
        val result = executor(appearance)
            .execute(PetLocalToolExecutor.TOOL_PLAY_ACTION, mapOf("action" to "angry"))
        assertTrue(result.contains("angry"))
        assertTrue(result.contains("happy"))
        assertTrue(result.contains("wave"))
    }

    @Test
    fun `执行 - 形象无任何动作时给出不同措辞`() = runBlocking<Unit> {
        val result = executor(appearance.copy(actions = emptyMap()))
            .execute(PetLocalToolExecutor.TOOL_PLAY_ACTION, mapOf("action" to "happy"))
        assertTrue(result.contains("没有配置任何动作"))
    }

    @Test
    fun `执行 - 缺参数与未知工具名`() = runBlocking<Unit> {
        val ex = executor(appearance)
        assertTrue(ex.execute(PetLocalToolExecutor.TOOL_PLAY_ACTION, emptyMap()).contains("缺少 action"))
        assertTrue(ex.execute("other__tool", emptyMap()).contains("未知本地工具"))
    }

    @Test
    fun `执行 - 参数类型非法当作缺参数`() = runBlocking<Unit> {
        val result = executor(appearance)
            .execute(PetLocalToolExecutor.TOOL_PLAY_ACTION, mapOf("action" to 42))
        assertTrue(result.contains("缺少 action"))
    }

    @Test
    fun `工具定义 - 命名空间 枚举与严格参数`() {
        val def = PetLocalToolExecutor.buildToolDefinition()
        assertEquals("pet__play_action", def.function.name)

        val params = def.function.parameters.toString()
        assertTrue("必须声明 additionalProperties=false", params.contains("\"additionalProperties\":false"))
        assertTrue(params.contains("\"required\":[\"action\"]"))
        // enum 必须是内置别名全集的子集式枚举，保证模型只能在渲染层认识的集合里选
        PetActionKind.entries.forEach { /* 保持对枚举类型的引用，避免未使用告警 */ }
        assertTrue(params.contains("\"happy\""))
        assertTrue(params.contains("\"reset\""))
    }
}
