package io.prism.voice.rolepack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次23-B（ADR-048）：[RolePackParser] 单测 —— Schema v1 宽松校验正负例。
 *
 * 覆盖：完整字段映射 / 缺省兜底 / 未知字段忽略 / 畸形 JSON / 畸形 schema 版本 /
 * 顶层非对象 / 保留 id / 宽松类型 / 导出 round-trip。
 */
class RolePackParserTest {

    // ==================== 正例 ====================

    @Test
    fun `完整字段 - 全部映射正确`() {
        val json = """
        {
          "schema": "prism-rolepack/1",
          "id": "my-role",
          "name": "我的角色",
          "version": "2.1.0",
          "persona": {
            "systemPrompt": "角色人设文本",
            "address": "称呼规则",
            "tone": ["语气1", "语气2"],
            "forbidden": ["禁止1"]
          },
          "style": {
            "voiceCall": { "maxSentenceLen": 24, "sentenceBreak": false, "fillers": ["嗯"] }
          },
          "voice": {
            "minimax": { "voiceId": "v1", "speed": 1.2, "emotion": "happy", "cloneVoiceId": "cv1" },
            "gptsovits": { "baseUrl": "http://host:9880", "refAudioPath": "/ref.wav", "promptText": "ref", "promptLang": "zh" },
            "system": { "pitch": 1.3, "speechRate": 0.9 }
          },
          "expression": { "avatars": ["a.png"], "stickers": ["s.png"], "enabled": true },
          "memory": { "worldbookImport": "wb.json" }
        }
        """.trimIndent()
        val pack = RolePackParser.parse(json)
        assertEquals("prism-rolepack/1", pack.schema)
        assertEquals("my-role", pack.id)
        assertEquals("我的角色", pack.name)
        assertEquals("2.1.0", pack.version)
        assertEquals("角色人设文本", pack.persona.systemPrompt)
        assertEquals("称呼规则", pack.persona.address)
        assertEquals(listOf("语气1", "语气2"), pack.persona.tone)
        assertEquals(listOf("禁止1"), pack.persona.forbidden)
        assertEquals(24, pack.style.voiceCall.maxSentenceLen)
        assertEquals(false, pack.style.voiceCall.sentenceBreak)
        assertEquals(listOf("嗯"), pack.style.voiceCall.fillers)
        assertEquals("v1", pack.voice.minimax.voiceId)
        assertEquals(1.2f, pack.voice.minimax.speed, 0.0001f)
        assertEquals("happy", pack.voice.minimax.emotion)
        assertEquals("cv1", pack.voice.minimax.cloneVoiceId)
        assertEquals("http://host:9880", pack.voice.gptsovits.baseUrl)
        assertEquals("/ref.wav", pack.voice.gptsovits.refAudioPath)
        assertEquals("ref", pack.voice.gptsovits.promptText)
        assertEquals("zh", pack.voice.gptsovits.promptLang)
        assertEquals(1.3f, pack.voice.system.pitch, 0.0001f)
        assertEquals(0.9f, pack.voice.system.speechRate, 0.0001f)
        assertEquals(listOf("a.png"), pack.expression.avatars)
        assertEquals(listOf("s.png"), pack.expression.stickers)
        assertTrue(pack.expression.enabled)
        assertEquals("wb.json", pack.memory.worldbookImport)
        assertFalse(pack.isBuiltin)
    }

    @Test
    fun `缺省兜底 - 仅 schema 与 id 时其余字段取默认值`() {
        val json = """{"schema":"prism-rolepack/1","id":"mini","name":"精简包"}"""
        val pack = RolePackParser.parse(json)
        assertEquals("prism-rolepack/1", pack.schema)
        assertEquals("1.0.0", pack.version)
        assertEquals("", pack.persona.systemPrompt)
        assertEquals("", pack.persona.address)
        assertTrue(pack.persona.tone.isEmpty())
        assertTrue(pack.persona.forbidden.isEmpty())
        assertEquals(30, pack.style.voiceCall.maxSentenceLen)
        assertTrue(pack.style.voiceCall.sentenceBreak)
        assertTrue(pack.style.voiceCall.fillers.isEmpty())
        assertNull(pack.voice.minimax.voiceId)
        assertEquals(1.0f, pack.voice.minimax.speed, 0.0001f)
        assertNull(pack.voice.gptsovits.baseUrl)
        assertEquals(1.0f, pack.voice.system.pitch, 0.0001f)
        assertEquals(1.0f, pack.voice.system.speechRate, 0.0001f)
        assertTrue(pack.expression.avatars.isEmpty())
        assertFalse(pack.expression.enabled)
        assertNull(pack.memory.worldbookImport)
        // persona 全空 → persona 区块为 null（调用方跳过注入）
        assertNull(pack.toPersonaSystemPromptSection())
    }

    @Test
    fun `缺省兜底 - id 缺省时按内容哈希生成稳定 id`() {
        val json = """{"schema":"prism-rolepack/1"}"""
        val pack1 = RolePackParser.parse(json)
        val pack2 = RolePackParser.parse(json)
        assertTrue(pack1.id.startsWith("imported-"))
        assertEquals(pack1.id, pack2.id)  // 同内容 → 同 id（重复导入幂等）
        assertEquals(pack1.id, pack1.name)  // name 缺省 → 回落 id
    }

    @Test
    fun `未知字段忽略 - 顶层与嵌套层未知字段不影响解析`() {
        val json = """
        {
          "schema": "prism-rolepack/1",
          "id": "future", "name": "向前兼容", "version": "1.0.0",
          "futureTopLevel": { "x": 1 },
          "persona": { "systemPrompt": "s", "futureField": true },
          "style": { "voiceCall": { "maxSentenceLen": 20, "futureStyle": 1 } },
          "voice": { "system": { "pitch": 1.1, "futureVoice": "x" } }
        }
        """.trimIndent()
        val pack = RolePackParser.parse(json)
        assertEquals("s", pack.persona.systemPrompt)
        assertEquals(20, pack.style.voiceCall.maxSentenceLen)
        assertEquals(1.1f, pack.voice.system.pitch, 0.0001f)
    }

    @Test
    fun `宽松类型 - 数字写成字符串可解析`() {
        val json = """
        {"schema":"prism-rolepack/1","id":"lenient","name":"宽松",
         "style":{"voiceCall":{"maxSentenceLen":"24"}},
         "voice":{"system":{"pitch":"1.5","speechRate":"0.8"}}}
        """.trimIndent()
        val pack = RolePackParser.parse(json)
        assertEquals(24, pack.style.voiceCall.maxSentenceLen)
        assertEquals(1.5f, pack.voice.system.pitch, 0.0001f)
        assertEquals(0.8f, pack.voice.system.speechRate, 0.0001f)
    }

    @Test
    fun `宽松类型 - 字段类型错误回退默认值不抛`() {
        val json = """
        {"schema":"prism-rolepack/1","id":"badtype","name":"错类型",
         "style":{"voiceCall":{"maxSentenceLen":"not-a-number"}},
         "voice":{"system":{"pitch":[1.0]}}}
        """.trimIndent()
        val pack = RolePackParser.parse(json)
        assertEquals(30, pack.style.voiceCall.maxSentenceLen)  // 回退默认
        assertEquals(1.0f, pack.voice.system.pitch, 0.0001f)   // 非标量回退默认
    }

    @Test
    fun `显式 null - 等价于字段缺失走兜底`() {
        val json = """
        {"schema":"prism-rolepack/1","id":"nulls","name":"空值",
         "persona":null,"style":null,"voice":null,
         "memory":{"worldbookImport":null}}
        """.trimIndent()
        val pack = RolePackParser.parse(json)
        assertEquals("", pack.persona.systemPrompt)
        assertEquals(30, pack.style.voiceCall.maxSentenceLen)
        assertEquals(1.0f, pack.voice.system.pitch, 0.0001f)
        assertNull(pack.memory.worldbookImport)
    }

    // ==================== 负例 ====================

    @Test
    fun `畸形 JSON - 语法错误抛可读异常`() {
        val e = assertThrows(RolePackParseException::class.java) {
            RolePackParser.parse("{not valid json,,")
        }
        assertTrue(e.message!!.contains("JSON"))
    }

    @Test
    fun `畸形 schema 版本 - 不支持的版本抛可读异常`() {
        val e = assertThrows(RolePackParseException::class.java) {
            RolePackParser.parse("""{"schema":"prism-rolepack/2","id":"x","name":"x"}""")
        }
        assertTrue(e.message!!.contains("prism-rolepack/1"))
        assertTrue(e.message!!.contains("prism-rolepack/2"))
    }

    @Test
    fun `畸形 schema 版本 - schema 缺失抛可读异常`() {
        val e = assertThrows(RolePackParseException::class.java) {
            RolePackParser.parse("""{"id":"x","name":"x"}""")
        }
        assertTrue(e.message!!.contains("schema"))
    }

    @Test
    fun `畸形 schema - schema 类型错误抛可读异常`() {
        assertThrows(RolePackParseException::class.java) {
            RolePackParser.parse("""{"schema":{"v":1},"id":"x","name":"x"}""")
        }
    }

    @Test
    fun `顶层非对象 - 抛可读异常`() {
        val e = assertThrows(RolePackParseException::class.java) {
            RolePackParser.parse("""["not","an","object"]""")
        }
        assertTrue(e.message!!.contains("顶层"))
    }

    @Test
    fun `保留 id - default 对解析器合法（B0 自身即标准文档，导入通道另行拒绝）`() {
        // B0 的 id 就是 "default"——导出 round-trip 必须可解析（导入侧的保留值拒绝在 Loader.import）
        val pack = RolePackParser.parse("""{"schema":"prism-rolepack/1","id":"default","name":"内置"}""")
        assertEquals("default", pack.id)
        assertTrue(pack.isBuiltin)
    }

    // ==================== persona 区块 ====================

    @Test
    fun `persona 区块 - 各非空小节输出`() {
        val pack = RolePackParser.parse(
            """
            {"schema":"prism-rolepack/1","id":"p","name":"测试角色",
             "persona":{"systemPrompt":"人设正文","address":"称呼",
                        "tone":["语气A","语气B"],"forbidden":["禁A"]}}
            """.trimIndent()
        )
        val section = pack.toPersonaSystemPromptSection()
        assertNotNull(section)
        // TASK-20260911-001：头部改为直陈式"【身份说明】你就是X"（去"人设/生效角色"元词汇）
        assertTrue(section!!.startsWith("【身份说明】你就是测试角色"))
        assertTrue(section.contains("人设正文"))
        assertTrue(section.contains("对用户的称呼：称呼"))
        assertTrue(section.contains("语气规则：语气A；语气B"))
        assertTrue(section.contains("禁止事项：禁A"))
        assertTrue("尾部应含语音转写纠错功能条目", section.contains("转写注意事项"))
    }

    // ==================== 导出 ====================

    @Test
    fun `toPrettyJson - round-trip 幂等`() {
        val source = """
        {"schema":"prism-rolepack/1","id":"rt","name":"回环","version":"3.2.1",
         "persona":{"systemPrompt":"sp","address":"ad","tone":["t1"],"forbidden":["f1"]},
         "style":{"voiceCall":{"maxSentenceLen":18,"sentenceBreak":false,"fillers":["嗯"]}},
         "voice":{"minimax":{"voiceId":"v","speed":1.5,"emotion":"e","cloneVoiceId":"c"},
                  "gptsovits":{"baseUrl":"u","refAudioPath":"r","promptText":"p","promptLang":"zh"},
                  "system":{"pitch":1.2,"speechRate":1.4}},
         "expression":{"avatars":["a"],"stickers":["s"],"enabled":true},
         "memory":{"worldbookImport":"w"}}
        """.trimIndent()
        val parsed = RolePackParser.parse(source)
        val exported = RolePackParser.toPrettyJson(parsed)
        val reparsed = RolePackParser.parse(exported)
        assertEquals(parsed, reparsed)
        assertTrue(exported.contains("\n"))  // prettyPrint 格式化
    }

    @Test
    fun `toPrettyJson - B0 导出后可再解析且等于 B0`() {
        val reparsed = RolePackParser.parse(RolePackParser.toPrettyJson(RolePack.DEFAULT_ROLE_PACK))
        assertEquals(RolePack.DEFAULT_ROLE_PACK, reparsed)
    }
}
