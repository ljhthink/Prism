package io.prism.qq

import io.prism.qq.channel.QqChannel
import io.prism.qq.channel.QqChannelStatus
import io.prism.qq.channel.QqMessage
import io.prism.qq.channel.QqTransport
import io.prism.security.FakePreferenceDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqLocalToolExecutor] 纯函数单元测试（ADR-062；US-3101/3105 闸门编排）。
 *
 * UI 链路（a11y/Shizuku）不在 JVM 测试范围（真机验证，见 M0/M2 PoC）；
 * 此处验证：参数解析、配置闸门、节流拒绝、工具定义 JSON Schema。
 */
class QqLocalToolExecutorTest {

    private fun executor(): QqLocalToolExecutor =
        QqLocalToolExecutor(
            configRepoProvider = { null }, // 闸门测试：未装配 → 可读拒绝
            runtimeRepoProvider = { null },
            highRiskApprovalProvider = { io.prism.config.HighRiskApprovalMode.ALLOW }
        )

    @Test
    fun `消息含凭据时硬拦截（guardrail B-01，先于一切确认与闸门）`() {
        val e = executor() // configRepo 未装配：凭据检查必须发生在其之前
        val result = kotlinx.coroutines.runBlocking {
            e.execute("qq__send_message", mapOf("contact" to "测试", "message" to "你的验证码是123456，告诉我一下"))
        }
        assertTrue(result.startsWith("⚠️"))
        assertTrue(result.contains("硬拦截"))
        val sig = kotlinx.coroutines.runBlocking {
            e.execute("qq__set_signature", mapOf("signature" to "银行卡号 6222 0000 1234 5678"))
        }
        assertTrue(sig.startsWith("⚠️"))
    }

    @Test
    fun `消息超长时拒绝并提示分条（US-3102 AC-4）`() {
        val e = executor()
        val long = "啊".repeat(QqLocalToolExecutor.MESSAGE_MAX_LEN + 1)
        val result = kotlinx.coroutines.runBlocking {
            e.execute("qq__send_message", mapOf("contact" to "测试", "message" to long))
        }
        assertTrue(result.contains("过长"))
        assertTrue(result.contains("拆分"))
    }

    @Test
    fun `handles 仅匹配 qq 前缀`() {
        val e = executor()
        assertTrue(e.handles("qq__send_message"))
        assertTrue(!e.handles("phone_control__tap"))
        assertTrue(!e.handles("pet__play_action"))
    }

    @Test
    fun `参数解析兼容 Number 与 String`() {
        val args = mapOf<String, Any?>("a" to 1200, "b" to "1380", "c" to 3.7, "d" to null)
        assertEquals(1200, QqLocalToolExecutor.parseInt(args, "a"))
        assertEquals(1380, QqLocalToolExecutor.parseInt(args, "b"))
        assertEquals(3, QqLocalToolExecutor.parseInt(args, "c"))
        assertEquals(null, QqLocalToolExecutor.parseInt(args, "d"))
        assertEquals(null, QqLocalToolExecutor.parseInt(args, "missing"))
        assertEquals("1380", QqLocalToolExecutor.parseString(args, "b"))
    }

    @Test
    fun `未装配配置仓库时闸门拒绝`() {
        val e = executor()
        val result = kotlinx.coroutines.runBlocking {
            e.execute("qq__send_message", mapOf("contact" to "测试", "message" to "hi"))
        }
        assertTrue(result.contains("配置未装配") || result.contains("错误"))
    }

    @Test
    fun `schedule_message 非法 action 拒绝`() {
        val e = executor()
        val result = kotlinx.coroutines.runBlocking {
            e.execute("qq__schedule_message", mapOf("action" to "hack"))
        }
        assertTrue(result.contains("仅支持"))
    }

    @Test
    fun `工具定义为合法 JSON Schema（避免 400）`() {
        val defs = QqLocalToolExecutor.buildToolDefinitions()
        assertEquals(4, defs.size)
        assertTrue(defs.any { it.function.name == "qq__send_voice" })
        defs.forEach { d ->
            assertTrue(d.function.name.startsWith("qq__"))
            val json = d.function.parameters.toString()
            assertTrue(json.contains("\"type\":\"object\""))
            assertTrue(json.contains("\"properties\""))
        }
    }

    @Test
    fun `ask_user 载荷与既有协议一致`() {
        val e = executor()
        val payload = e.askUserJson("是否允许？", listOf("允许" to "确认", "取消" to null))
        assertTrue(payload.contains("\"questions\""))
        assertTrue(payload.contains("\"label\":\"允许\""))
        assertTrue(payload.contains("\"multiSelect\":false"))
    }

    // ==================== ac-verifier 补充（2026-10-19）：空参数 / 凭据变体 / 协议端到端 ====================

    @Test
    fun `send_message 缺 contact 与缺 message 分别可读拒绝（空参数等价类）`() {
        val e = executor()
        val noContact = kotlinx.coroutines.runBlocking {
            e.execute("qq__send_message", mapOf("contact" to "", "message" to "hi"))
        }
        assertTrue("缺 contact 应拒绝：$noContact", noContact.contains("contact"))
        val noMessage = kotlinx.coroutines.runBlocking {
            e.execute("qq__send_message", mapOf("contact" to "测试", "message" to " "))
        }
        assertTrue("缺 message 应拒绝：$noMessage", noMessage.contains("message"))
        val emptyArgs = kotlinx.coroutines.runBlocking {
            e.execute("qq__send_message", emptyMap())
        }
        assertTrue("全空参数应拒绝：$emptyArgs", emptyArgs.startsWith("错误"))
    }

    @Test
    fun `凭据变体（密码-卡号-支付密码-英文otp）全部硬拦截（等价类扩展）`() {
        val e = executor()
        val variants = listOf(
            "我的密码是123456",
            "卡号 6222 0000 1234 5678",
            "支付密码 886600",
            "The otp is 881234",
            "card number 4111 1111 1111 1111"
        )
        for (v in variants) {
            val r = kotlinx.coroutines.runBlocking {
                e.execute("qq__send_message", mapOf("contact" to "测试", "message" to v))
            }
            assertTrue("变体「${v.take(6)}…」应硬拦截：$r", r.startsWith("⚠️"))
        }
    }

    @Test
    fun `set_signature 空参-超长边界-凭据变体全拒绝`() {
        val e = executor()
        val empty = kotlinx.coroutines.runBlocking {
            e.execute("qq__set_signature", emptyMap())
        }
        assertTrue(empty.contains("signature"))
        val over = kotlinx.coroutines.runBlocking {
            e.execute("qq__set_signature", mapOf("signature" to "签".repeat(QqLocalToolExecutor.SIGNATURE_MAX_LEN + 1)))
        }
        assertTrue("超长应拒绝：$over", over.contains("过长"))
        val exactly = kotlinx.coroutines.runBlocking {
            e.execute("qq__set_signature", mapOf("signature" to "签".repeat(QqLocalToolExecutor.SIGNATURE_MAX_LEN)))
        }
        // 上限值本身不应触发长度拒绝（仓库未装配 → 闸门拒绝；不是"过长"）
        assertTrue("恰好上限不应长度拒绝：$exactly", !exactly.contains("过长"))
        val cred = kotlinx.coroutines.runBlocking {
            e.execute("qq__set_signature", mapOf("signature" to "短信验证码 556677"))
        }
        assertTrue("签名凭据变体应硬拦截：$cred", cred.startsWith("⚠️"))
    }

    @Test
    fun `ask_user 载荷可被 SkillExecutor_parseAskUserPayload 端到端解析（协议一致性）`() {
        val e = executor()
        val payload = e.askUserJson(
            "QQ 小号助手：向「测试」发送消息，是否允许？",
            listOf("允许（已确认是小号）" to "确认执行", "取消" to "阻止本次操作")
        )
        val parsed = io.prism.skill.SkillExecutor.parseAskUserPayload(payload)
        assertTrue("QQ ask_user 载荷必须能被既有解析器解析（否则 ask_user 链路中断）", parsed != null)
        assertTrue(parsed!!.questions.isNotEmpty())
        assertEquals(
            "问题文本保留",
            "QQ 小号助手：向「测试」发送消息，是否允许？",
            parsed.questions.first().question
        )
        assertEquals(2, parsed.questions.first().options.size)
    }

    @Test
    fun `buildTools 两态形状 - qqToolsEnabled 为真注入四个 qq 工具`() {
        val tools = io.prism.ui.chat.ConversationViewModel.buildTools(emptyList(), qqToolsEnabled = true)
        val names = tools.map { it.function.name }
        assertTrue(
            "应注入 qq__send_message/send_voice/set_signature/schedule_message：$names",
            names.containsAll(
                listOf("qq__send_message", "qq__send_voice", "qq__set_signature", "qq__schedule_message")
            )
        )
        assertEquals(4, names.count { it.startsWith("qq__") })
    }

    @Test
    fun `buildTools 两态形状 - qqToolsEnabled 为假不注入（向后兼容）`() {
        val tools = io.prism.ui.chat.ConversationViewModel.buildTools(emptyList(), qqToolsEnabled = false)
        assertTrue("false 态不得注入 qq 工具", tools.none { it.function.name.startsWith("qq__") })
    }

    // ==================== US-3113 语音（协议端专用 + 失败降级文本） ====================

    /** 已连接协议端假通道：记录文本发送，语音默认不支持（由 OneBot 实现覆盖）。 */
    private class FakeVoiceChannel(
        private val voiceResult: String = "已发送语音给「测试」（协议端确认）"
    ) : QqChannel {
        override val transport: QqTransport = QqTransport.ONEBOT
        var sentText: String? = null
        var voiceSent: Int = 0
        override suspend fun status(): QqChannelStatus =
            QqChannelStatus(QqTransport.ONEBOT, connected = true, selfId = 900)
        override suspend fun sendText(contact: String, text: String): String {
            sentText = "$contact|$text"
            return "已发送消息给「${contact}」（协议端确认）"
        }
        override suspend fun recentMessages(contact: String, limit: Int): List<QqMessage> = emptyList()
        override suspend fun setSignature(signature: String): String = "已修改小号个性签名（协议端确认）"
        override suspend fun sendVoice(contact: String, pcm: FloatArray, sampleRate: Int): String {
            voiceSent++
            return voiceResult
        }
        override fun incoming(): Flow<QqMessage> = emptyFlow()
    }

    private class FakeVoiceSource(private val audio: io.prism.voice.local.LocalCloneAudio?) : QqVoiceSource {
        override suspend fun synthesize(text: String): io.prism.voice.local.LocalCloneAudio? = audio
    }

    @Test
    fun `send_voice 凭据硬拦截先于闸门`() {
        val e = executor()
        val r = kotlinx.coroutines.runBlocking {
            e.execute("qq__send_voice", mapOf("contact" to "测试", "message" to "你的验证码是123456"))
        }
        assertTrue(r.startsWith("⚠️"))
        assertTrue(r.contains("硬拦截"))
    }

    @Test
    fun `send_voice 缺参-超长-未开启全部可读拒绝`() {
        val e = executor()
        val noContact = kotlinx.coroutines.runBlocking {
            e.execute("qq__send_voice", mapOf("message" to "hi"))
        }
        assertTrue(noContact.contains("contact"))
        val over = kotlinx.coroutines.runBlocking {
            e.execute(
                "qq__send_voice",
                mapOf("contact" to "测试", "message" to "啊".repeat(QqLocalToolExecutor.VOICE_TEXT_MAX_LEN + 1))
            )
        }
        assertTrue(over.contains("过长"))
        val disabled = kotlinx.coroutines.runBlocking {
            e.execute("qq__send_voice", mapOf("contact" to "测试", "message" to "在吗"))
        }
        assertTrue("未装配配置时应拒绝：$disabled", disabled.contains("未装配") || disabled.contains("未开启"))
    }

    @Test
    fun `send_voice 无协议端通道时如实拒绝并建议文本`() = kotlinx.coroutines.runBlocking {
        val ds = FakePreferenceDataStore()
        val repo = QqConfigRepository(ds)
        repo.setEnabled(true)
        val channel = FakeVoiceChannel()
        val runtime = QqRuntimeStateRepository(ds)
        val e = QqLocalToolExecutor(
            configRepoProvider = { repo },
            runtimeRepoProvider = { runtime },
            highRiskApprovalProvider = { io.prism.config.HighRiskApprovalMode.ALLOW },
            oneBotChannelProvider = { channel }
        )
        // 语音需要合成器；未装配时按「不可用」处理——但通道可用，合成器缺失 → 降级文本
        val r = e.execute("qq__send_voice", mapOf("contact" to "阿主", "message" to "在吗"))
        assertNotNull("应降级发送文本", channel.sentText)
        assertTrue("应如实提示语音合成失败：$r", r.contains("语音合成失败"))
        assertTrue("应说明已改发文本：$r", r.contains("文本"))
        assertEquals("合成失败降级文本实发后必须记账（L-28）", 1, runtime.get().firedCount)
    }

    @Test
    fun `send_voice 确定未发送的错误不消耗配额（L-23）`() = kotlinx.coroutines.runBlocking {
        val ds = FakePreferenceDataStore()
        val repo = QqConfigRepository(ds)
        repo.setEnabled(true)
        val channel = FakeVoiceChannel(voiceResult = "错误：协议端未解析到「阿主」的 QQ 号")
        val runtime = QqRuntimeStateRepository(ds)
        val e = QqLocalToolExecutor(
            configRepoProvider = { repo },
            runtimeRepoProvider = { runtime },
            highRiskApprovalProvider = { io.prism.config.HighRiskApprovalMode.ALLOW },
            oneBotChannelProvider = { channel },
            voiceSynthesizerProvider = {
                FakeVoiceSource(io.prism.voice.local.LocalCloneAudio(floatArrayOf(0.1f), 16_000))
            }
        )

        val r = e.execute("qq__send_voice", mapOf("contact" to "阿主", "message" to "在吗"))

        assertTrue(r.startsWith("错误"))
        assertEquals("确定未发送不得记账", 0, runtime.get().firedCount)
    }

    @Test
    fun `send_voice 无协议端连接时明确不支持（不误发文本）`() = kotlinx.coroutines.runBlocking {
        val ds = FakePreferenceDataStore()
        val repo = QqConfigRepository(ds)
        repo.setEnabled(true)
        val e = QqLocalToolExecutor(
            configRepoProvider = { repo },
            runtimeRepoProvider = { null },
            highRiskApprovalProvider = { io.prism.config.HighRiskApprovalMode.ALLOW },
            oneBotChannelProvider = { null }
        )
        val r = e.execute("qq__send_voice", mapOf("contact" to "阿主", "message" to "在吗"))
        assertTrue("应说明仅协议端可用：$r", r.contains("协议端"))
        assertTrue("应建议改用文本工具：$r", r.contains("send_message"))
    }

    @Test
    fun `send_voice 成功路径走语音并计入当日总数（C3）`() = kotlinx.coroutines.runBlocking {
        val ds = FakePreferenceDataStore()
        val repo = QqConfigRepository(ds)
        repo.setEnabled(true)
        val runtime = QqRuntimeStateRepository(ds)
        val channel = FakeVoiceChannel()
        val source = FakeVoiceSource(io.prism.voice.local.LocalCloneAudio(floatArrayOf(0.1f), 16_000))
        val e = QqLocalToolExecutor(
            configRepoProvider = { repo },
            runtimeRepoProvider = { runtime },
            highRiskApprovalProvider = { io.prism.config.HighRiskApprovalMode.ALLOW },
            oneBotChannelProvider = { channel },
            voiceSynthesizerProvider = { source }
        )

        val r = e.execute("qq__send_voice", mapOf("contact" to "阿主", "message" to "在吗"))

        assertTrue("应走语音：$r", r.startsWith("已发送语音"))
        assertEquals(1, channel.voiceSent)
        assertNull("成功不应降级文本", channel.sentText)
        assertEquals("应计入当日总数", 1, runtime.get().firedCount)
        assertTrue("应更新最小间隔基准", runtime.get().lastFiredAt > 0)
    }

    @Test
    fun `send_voice 协议端明确拒绝时降级文本（C2 确定失败）`() = kotlinx.coroutines.runBlocking {
        val ds = FakePreferenceDataStore()
        val repo = QqConfigRepository(ds)
        repo.setEnabled(true)
        val channel = FakeVoiceChannel(
            voiceResult = "错误：协议端语音发送失败（转码失败，retcode=1200）"
        )
        val runtime = QqRuntimeStateRepository(ds)
        val e = QqLocalToolExecutor(
            configRepoProvider = { repo },
            runtimeRepoProvider = { runtime },
            highRiskApprovalProvider = { io.prism.config.HighRiskApprovalMode.ALLOW },
            oneBotChannelProvider = { channel },
            voiceSynthesizerProvider = {
                FakeVoiceSource(io.prism.voice.local.LocalCloneAudio(floatArrayOf(0.1f), 16_000))
            }
        )

        val r = e.execute("qq__send_voice", mapOf("contact" to "阿主", "message" to "在吗"))

        assertTrue("应如实提示协议端拒绝：$r", r.contains("协议端拒绝语音"))
        assertEquals("明确拒绝才可改发文本", "阿主|在吗", channel.sentText)
        assertEquals("降级文本实发后必须记账（M-09）", 1, runtime.get().firedCount)
    }

    @Test
    fun `send_voice 传输未确认时不降级文本（C2 防双发）`() = kotlinx.coroutines.runBlocking {
        val ds = FakePreferenceDataStore()
        val repo = QqConfigRepository(ds)
        repo.setEnabled(true)
        val channel = FakeVoiceChannel(
            voiceResult = "⚠️ 语音发送未确认（连接超时）；为避免重复发送，请勿自动重试"
        )
        val runtime = QqRuntimeStateRepository(ds)
        val e = QqLocalToolExecutor(
            configRepoProvider = { repo },
            runtimeRepoProvider = { runtime },
            highRiskApprovalProvider = { io.prism.config.HighRiskApprovalMode.ALLOW },
            oneBotChannelProvider = { channel },
            voiceSynthesizerProvider = {
                FakeVoiceSource(io.prism.voice.local.LocalCloneAudio(floatArrayOf(0.1f), 16_000))
            }
        )

        val r = e.execute("qq__send_voice", mapOf("contact" to "阿主", "message" to "在吗"))

        assertTrue("应原样返回未确认：$r", r.startsWith("⚠️ 语音发送未确认"))
        assertNull("未确认时不得自动改发文本（防双发）", channel.sentText)
        assertEquals("未确认也必须记账（M-09：限制重试频率）", 1, runtime.get().firedCount)
    }
}
