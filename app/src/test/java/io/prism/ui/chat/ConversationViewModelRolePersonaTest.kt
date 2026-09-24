package io.prism.ui.chat

import io.prism.data.SkillConfig
import io.prism.data.SkillSource
import io.prism.skill.SkillManifest
import io.prism.skill.SkillRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-20260912-001（ADR-049）：角色 persona 装配层测试。
 *
 * **替代** TASK-20260911-001 的 `ConversationViewModelRoleRoutingTest`：
 * 该文件中的 4 个 `resolveRoleChatRouting`（M2-her 静默路由）用例随 ADR-049 D1 撤销而删除
 * ——路由函数已从产品代码移除（撤销理由见 `ConversationViewModel.Companion` 顶部 KDoc）。
 * 保留并扩展 `mergeSystemPrompt` 的身份重排断言，新增背景设定层（D4）与身份中立（D2）断言。
 */
class ConversationViewModelRolePersonaTest {

    // ADR-052 D6：身份开头去元词汇——原"不是需要扮演的设定"改为"不是在描述或者模仿谁"
    // （否定"描述/模仿"而非否定"扮演/设定"，保留引擎级反出戏锚点且不触发元词汇 priming）
    private val rolePackPersona = "【身份说明】你就是昔涟。你就是这个人，不是在描述或者模仿谁。"

    // ==================== 基础身份层重排（TASK-20260911-001 保留语义） ====================

    @Test
    fun `mergeSystemPrompt - basePersona 置首替代 DEFAULT_PERSONA`() {
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "RAG-GROUNDING",
            userRules = null,
            enabledSkills = emptyList(),
            basePersona = rolePackPersona
        )
        val baseIdx = result.indexOf(rolePackPersona)
        assertTrue("角色包直陈身份应注入", baseIdx >= 0)
        assertTrue("角色包身份应为第一层（置于最前）", baseIdx == 0)
        assertFalse(
            "出厂助手身份应完全退位（不再与角色身份并存）",
            result.contains(ConversationViewModel.DEFAULT_PERSONA)
        )
    }

    @Test
    fun `mergeSystemPrompt - basePersona 重排后记忆层序不回归`() {
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "RAG-GROUNDING",
            l1Summary = "L1-SUMMARY",
            l2Memories = "L2-MEMORIES",
            l3Profiles = "L3-PROFILES",
            userRules = null,
            enabledSkills = emptyList(),
            basePersona = rolePackPersona
        )
        val baseIdx = result.indexOf(rolePackPersona)
        val ragIdx = result.indexOf("RAG-GROUNDING")
        val l1Idx = result.indexOf("L1-SUMMARY")
        val l2Idx = result.indexOf("L2-MEMORIES")
        val l3Idx = result.indexOf("L3-PROFILES")
        assertTrue(baseIdx < ragIdx)
        assertTrue(ragIdx < l1Idx)
        assertTrue(l1Idx < l2Idx)
        assertTrue(l2Idx < l3Idx)
    }

    @Test
    fun `mergeSystemPrompt - 默认 basePersona 向后兼容（出厂行为不变）`() {
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null, userRules = null, enabledSkills = emptyList()
        )
        assertTrue(
            "不传 basePersona 时保持 DEFAULT_PERSONA 基础身份（ADR-018 语义不变）",
            result.startsWith(ConversationViewModel.DEFAULT_PERSONA)
        )
    }

    // ==================== 背景设定层（ADR-049 D4） ====================

    @Test
    fun `mergeSystemPrompt - 背景设定层紧随基础身份层且先于 RAG 与记忆`() {
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "RAG-GROUNDING",
            l1Summary = "L1-SUMMARY",
            userRules = null,
            enabledSkills = emptyList(),
            basePersona = rolePackPersona,
            worldbookSection = "【背景设定】WORLDBOOK-BODY"
        )
        val baseIdx = result.indexOf(rolePackPersona)
        val worldIdx = result.indexOf("WORLDBOOK-BODY")
        val ragIdx = result.indexOf("RAG-GROUNDING")
        val l1Idx = result.indexOf("L1-SUMMARY")
        assertTrue("背景设定层应注入", worldIdx >= 0)
        assertTrue("背景设定属'我是谁'，应紧随基础身份层之后", baseIdx < worldIdx)
        assertTrue("背景设定应先于 RAG 外部资料", worldIdx < ragIdx)
        assertTrue(ragIdx < l1Idx)
    }

    @Test
    fun `mergeSystemPrompt - 背景设定为空白或缺省时不产生空区块`() {
        val absent = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null, userRules = null, enabledSkills = emptyList(),
            basePersona = rolePackPersona
        )
        val blank = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null, userRules = null, enabledSkills = emptyList(),
            basePersona = rolePackPersona, worldbookSection = "   "
        )
        assertFalse(absent.contains("【背景设定】"))
        assertFalse(blank.contains("【背景设定】"))
        assertTrue("缺省背景设定时输出应与重排前一致", absent == blank)
    }

    // ==================== 身份中立（ADR-049 D2） ====================

    @Test
    fun `mergeSystemPrompt - skill 索引不再断言助手身份`() {
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null,
            userRules = null,
            enabledSkills = listOf(makeSkillEntry("translator")),
            basePersona = rolePackPersona
        )
        assertTrue("skill 索引仍应注入（能力感知不回归）", result.contains("可用技能"))
        assertTrue("索引应含技能名", result.contains("translator"))
        assertFalse(
            "身份断言不得出现在 skill 索引层（角色会话中会与直陈身份冲突）",
            result.contains("不改变你的基础身份")
        )
    }

    // ==================== 风格尾部强化（ADR-049 D6） ====================

    @Test
    fun `mergeSystemPrompt - 风格尾部强化置于全部层之后`() {
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "RAG-GROUNDING",
            l1Summary = "L1-SUMMARY",
            userRules = null,
            enabledSkills = listOf(makeSkillEntry("translator")),
            basePersona = rolePackPersona,
            worldbookSection = "WORLDBOOK-BODY",
            styleTail = ConversationViewModel.ROLE_STYLE_TAIL
        )
        val tailIdx = result.indexOf(ConversationViewModel.ROLE_STYLE_TAIL)
        // guardrail R2 P3-E：先断言各锚点**存在**（否则 indexOf 返回 -1 会让后续比较恒真），
        // 再把"必须是最后一层"改为"尾部之后无实质内容 + 尾部只出现一次"。
        val ragIdx = result.indexOf("RAG-GROUNDING")
        val l1Idx = result.indexOf("L1-SUMMARY")
        val worldIdx = result.indexOf("WORLDBOOK-BODY")
        val skillIdx = result.indexOf("可用技能")
        assertTrue("前置层应存在：RAG", ragIdx >= 0)
        assertTrue("前置层应存在：L1", l1Idx >= 0)
        assertTrue("前置层应存在：背景设定", worldIdx >= 0)
        assertTrue("前置层应存在：skill 索引", skillIdx >= 0)
        assertTrue("尾部强化应注入", tailIdx >= 0)
        assertTrue("尾部强化应在 RAG 之后", tailIdx > ragIdx)
        assertTrue("尾部强化应在记忆层之后", tailIdx > l1Idx)
        assertTrue("尾部强化应在背景设定之后", tailIdx > worldIdx)
        assertTrue("尾部强化应在 skill 索引之后", tailIdx > skillIdx)
        assertTrue(
            "尾部强化必须是最后一层（其后无实质内容）",
            result.substringAfter(ConversationViewModel.ROLE_STYLE_TAIL).isBlank()
        )
        assertEquals(
            "尾部强化只应出现一次",
            result.length - result.replace(ConversationViewModel.ROLE_STYLE_TAIL, "").length,
            ConversationViewModel.ROLE_STYLE_TAIL.length
        )
    }

    @Test
    fun `mergeSystemPrompt - 缺省风格尾部强化时出厂行为不变`() {
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null, userRules = null, enabledSkills = emptyList()
        )
        assertFalse(result.contains("【回复方式】"))
    }

    @Test
    fun `ROLE_STYLE_TAIL - 长度约束正向优先且不重复人设正文`() {
        val tail = ConversationViewModel.ROLE_STYLE_TAIL
        assertTrue("应声明长度随情境自适应", tail.contains("长度跟着情境走"))
        assertTrue("应有反注水条款（防为凑字数扩写）", tail.contains("不要为凑长度注水"))
        assertTrue("应有反截断条款（防为求短省略必要信息）", tail.contains("省略必要信息"))
        assertTrue("应正向要求只输出真正要说的话", tail.contains("直接说你要说的话"))
        assertTrue("应约束长度预算（≤200 字，防指令密度过高压低整体遵循率）", tail.length <= 200)
    }

    private fun makeSkillEntry(name: String): SkillRegistry.SkillEntry = SkillRegistry.SkillEntry(
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
            tools = null,
            body = ""
        )
    )
}
