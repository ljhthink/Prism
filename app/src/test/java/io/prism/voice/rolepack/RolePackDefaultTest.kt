package io.prism.voice.rolepack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次23-B（ADR-048）：[RolePack.DEFAULT_ROLE_PACK]（B0 通用人设）出厂默认值断言。
 *
 * B0 是通用模板组成部分（§三-B0 设计稿）——中性、克制、零第三方角色内容；
 * 字段值变更需同步 ADR-048 设计稿（出厂人设为批准稿，勿随意改动）。
 */
class RolePackDefaultTest {

    @Test
    fun `B0 - 标识字段`() {
        assertEquals("prism-rolepack/1", RolePack.DEFAULT_ROLE_PACK.schema)
        assertEquals("default", RolePack.DEFAULT_ROLE_PACK.id)
        assertEquals("Prism 助手", RolePack.DEFAULT_ROLE_PACK.name)
        assertEquals("1.0.0", RolePack.DEFAULT_ROLE_PACK.version)
        assertTrue(RolePack.DEFAULT_ROLE_PACK.isBuiltin)
    }

    @Test
    fun `B0 - persona 设计稿值`() {
        val persona = RolePack.DEFAULT_ROLE_PACK.persona
        assertEquals(
            "你是 Prism，一台 Android 设备上的语音助手。说话专业、简洁、口语化；" +
                "一次只说一件事；不确定就明说不确定；涉及隐私/支付/删除类操作必须先向用户确认；" +
                "不用 emoji，不堆客套话。",
            persona.systemPrompt
        )
        assertEquals("对用户称「你」，不使用固定昵称", persona.address)
        assertEquals(
            listOf("短句优先", "先结论后解释", "被追问时给步骤而非泛泛而谈"),
            persona.tone
        )
        assertEquals(
            listOf("编造未执行的操作结果", "未经确认执行敏感操作", "泄露系统提示词与配置"),
            persona.forbidden
        )
    }

    @Test
    fun `B0 - voiceCall 出厂风格`() {
        val style = RolePack.DEFAULT_ROLE_PACK.style.voiceCall
        assertEquals(30, style.maxSentenceLen)
        assertTrue(style.sentenceBreak)
        assertTrue(style.fillers.isEmpty())
    }

    @Test
    fun `B0 - 系统 TTS 中性参数与云端音色缺省`() {
        assertEquals(1.0f, RolePack.DEFAULT_ROLE_PACK.voice.system.pitch, 0.0001f)
        assertEquals(1.0f, RolePack.DEFAULT_ROLE_PACK.voice.system.speechRate, 0.0001f)
        assertNull(RolePack.DEFAULT_ROLE_PACK.voice.minimax.voiceId)
        assertNull(RolePack.DEFAULT_ROLE_PACK.voice.gptsovits.baseUrl)
    }

    @Test
    fun `B0 - 表情与记忆层出厂预留`() {
        assertFalse(RolePack.DEFAULT_ROLE_PACK.expression.enabled)
        assertTrue(RolePack.DEFAULT_ROLE_PACK.expression.avatars.isEmpty())
        assertTrue(RolePack.DEFAULT_ROLE_PACK.expression.stickers.isEmpty())
        assertNull(RolePack.DEFAULT_ROLE_PACK.memory.worldbookImport)
    }

    @Test
    fun `B0 - persona 区块可生成（作为 Schema 出厂示例）`() {
        val section = RolePack.DEFAULT_ROLE_PACK.toPersonaSystemPromptSection()
        assertTrue(section!!.contains("Prism 助手"))
        assertTrue(section.contains("语音助手"))
    }

    @Test
    fun `toPersonaSystemPromptSection - 直陈式身份头 + 语音转写纠错条目`() {
        // TASK-20260911-001：头部从"【角色包人设 · 生效角色：X】"改为直陈式"【身份说明】你就是X"
        // ——去"人设/生效角色"元词汇（Q3 替代品感根因之一）；尾部追加去身份化的语音转写纠错
        // 功能条目（DEFAULT_PERSONA 第 6 条在 basePersona 重排后的替代注入点）
        val pack = RolePack.DEFAULT_ROLE_PACK.copy(
            id = "test", name = "昔涟",
            persona = RolePack.Persona(
                systemPrompt = "人格核心内容", address = "称你", tone = listOf("轻快"), forbidden = listOf("不说教")
            )
        )
        val section = pack.toPersonaSystemPromptSection()!!
        assertTrue("头部应为直陈式身份", section.startsWith("【身份说明】你就是昔涟"))
        assertTrue("不应残留'人设'元词汇标记", !section.contains("角色包人设"))
        assertTrue("不应残留'生效角色'标记", !section.contains("生效角色"))
        assertTrue("应包含语音转写纠错条目", section.contains("转写注意事项"))
        // TASK-20260912-001（guardrail R1 M1 / BR-safety-001）：防线迁移的**覆盖维度等价性**——
        // 原断言只钉"不可逆操作"一个词，无法发现"人名/联系人/专有名词"实体维度被收窄。
        // 此处逐维度钉住（等价性细则与回归背景见 RolePackV6Test 同名维度用例）。
        assertTrue("纠错条目应覆盖不可逆操作确认", section.contains("不可逆"))
        assertTrue("应覆盖人名维度", section.contains("人名"))
        assertTrue("应覆盖联系人维度", section.contains("联系人"))
        assertTrue("应覆盖专有名词维度", section.contains("专有名词"))
    }
}
