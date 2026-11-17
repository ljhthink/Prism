package io.prism.qq

import java.time.LocalDateTime
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-077 纯函数单测：拟人节奏 / 表情闸门 / 工具策略 / 工具回路文本抽取。
 *
 * 背景（真机取证 2026-11-16 22:43–22:55）：
 * - `生成耗时` 2.4–4.4 s 就是全部耗时 → 回复前**没有延迟层**，用户感觉"秒回、有压力"；
 * - `files/qq_stickers/` 为空 + `importImage` 零调用方 → **发不出非内置表情**；
 * - QQ 被动回复无 `tools` → **用不了 Prism 功能**。
 */
class QqReplyPacingAndGateTest {

    private val fixedRandom = Random(42)

    private fun at(hour: Int) = LocalDateTime.of(2026, 11, 16, hour, 0)

    // ==================== QqReplyPacing ====================

    @Test
    fun `关闭时返回 0（对照旧行为）`() {
        val ms = QqReplyPacing.delayMsFor(
            replyText = "在呀",
            now = at(15),
            lastMessageAtMs = 0L,
            config = QqReplyPacing.Config(enabled = false)
        )
        assertEquals(0L, ms)
    }

    @Test
    fun `延迟落在配置上下限之间且随字数增长`() {
        val cfg = QqReplyPacing.Config()
        val short = QqReplyPacing.delayMsFor("嗯", at(15), 0L, cfg, fixedRandom)
        val long = QqReplyPacing.delayMsFor("啊".repeat(80), at(15), 0L, cfg, fixedRandom)
        assertTrue("短回复不得低于下限（实测 $short）", short >= cfg.minDelayMs)
        assertTrue("长回复不得超上限（实测 $long）", long <= cfg.maxDelayMs)
        assertTrue("长回复应显著更慢（短=$short 长=$long）", long > short)
    }

    @Test
    fun `深夜比白天更慢（时段系数）`() {
        val cfg = QqReplyPacing.Config(minDelayMs = 0L, maxDelayMs = 90_000L)
        val night = QqReplyPacing.thinkingMs(at(2), 0L, cfg, Random(7))
        val day = QqReplyPacing.thinkingMs(at(15), 0L, cfg, Random(7))
        assertTrue("深夜思考时间应大于白天（夜=$night 日=$day）", night > day)
    }

    @Test
    fun `刚聊过比隔夜更快（间隔系数）`() {
        val cfg = QqReplyPacing.Config()
        val now = at(15)
        val nowMs = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val justNow = QqReplyPacing.gapFactor(nowMs - 30_000L, now)
        val overnight = QqReplyPacing.gapFactor(nowMs - 20 * 60 * 60_000L, now)
        assertTrue("刚聊过应更快（$justNow）", justNow < 1.0)
        assertTrue("隔夜应更慢（$overnight）", overnight > 1.0)
    }

    @Test
    fun `min 超过上限常量时不得抛异常（会导致读配置崩溃）`() {
        // 回归：原实现一次 copy() 里用**未收敛的** minDelayMs 作 maxDelayMs 的下界，
        // 用户填 min=500_000 时 coerceIn(500000, 90000) 抛 IllegalArgumentException。
        val cfg = QqReplyPacing.Config(minDelayMs = 500_000L, maxDelayMs = 100L).sanitized()
        assertTrue("min 必须收敛到上限内", cfg.minDelayMs <= QqReplyPacing.MAX_MIN_DELAY_MS)
        assertTrue("max 必须 ≥ min", cfg.maxDelayMs >= cfg.minDelayMs)
        // 且延迟计算本身不得抛
        val ms = QqReplyPacing.delayMsFor("你好", at(15), 0L, cfg, fixedRandom)
        assertTrue("延迟必须非负", ms >= 0L)
    }

    @Test
    fun `NaN 字速被收敛（NaN 会绕过 coerceIn 使打字时间静默为 0）`() {
        val cfg = QqReplyPacing.Config(charsPerSecond = Double.NaN).sanitized()
        assertTrue("NaN 字速必须回落到默认", !cfg.charsPerSecond.isNaN())
        assertTrue(
            "NaN 字速下打字时间不得为 0（否则节奏静默失效）",
            QqReplyPacing.typingMs("一二三四五", cfg) > 0L
        )
    }

    @Test
    fun `普通非法值被收敛（设置页可乱填）`() {
        val cfg = QqReplyPacing.Config(
            charsPerSecond = 0.0,
            thinkingBaseMs = -100L,
            minDelayMs = 90_000L,
            maxDelayMs = 100L
        ).sanitized()
        assertTrue(cfg.charsPerSecond >= QqReplyPacing.MIN_CHARS_PER_SECOND)
        assertTrue(cfg.thinkingBaseMs >= QqReplyPacing.MIN_THINKING_MS)
        assertTrue("max 必须 ≥ min", cfg.maxDelayMs >= cfg.minDelayMs)
        assertTrue(cfg.minDelayMs <= QqReplyPacing.MAX_MIN_DELAY_MS)
    }

    @Test
    fun `气泡间隔逐条计算且最后一条为 0`() {
        val cfg = QqReplyPacing.Config()
        val bubbles = listOf("嗯", "这个嘛……人家想想", "好呀")
        val gaps = QqReplyPacing.bubbleGapsMs(bubbles, cfg, fixedRandom)
        assertEquals("间隔数必须与气泡数一致", bubbles.size, gaps.size)
        assertEquals("最后一条发完不再等", 0L, gaps.last())
        gaps.dropLast(1).forEach {
            assertTrue("间隔应在 [下限, 上限]（实测 $it）", it in QqReplyPacing.BUBBLE_GAP_FLOOR_MS..QqReplyPacing.BUBBLE_GAP_CEILING_MS)
        }
    }

    @Test
    fun `关闭节奏时气泡间隔退回旧固定值`() {
        val gaps = QqReplyPacing.bubbleGapsMs(
            listOf("a", "b"), QqReplyPacing.Config(enabled = false)
        )
        assertEquals(listOf(QqReplyPacing.LEGACY_BUBBLE_GAP_MS, 0L), gaps)
    }

    // ==================== QqStickerGate ====================

    @Test
    fun `小时上限用尽时拒绝（force 也不能突破）`() {
        val decision = QqStickerGate.decide(
            stickerId = "u1",
            explicitlyRequested = true,
            nowMs = 1_000L,
            sendsInLastHour = 6,
            sentWithinTtl = false,
            config = QqStickerGate.Config(maxPerHour = 6)
        )
        assertEquals(QqStickerGate.Decision.DenyHourlyCap, decision)
    }

    @Test
    fun `用户点名时跳过概率与同图去重`() {
        val decision = QqStickerGate.decide(
            stickerId = "u1",
            explicitlyRequested = true,
            nowMs = 1_000L,
            sendsInLastHour = 0,
            sentWithinTtl = true,
            config = QqStickerGate.Config(chancePercent = 0)
        )
        assertEquals(QqStickerGate.Decision.Force, decision)
        assertTrue(QqStickerGate.allows(decision))
    }

    @Test
    fun `同图 TTL 内不重复发（防刷图）`() {
        val decision = QqStickerGate.decide(
            stickerId = "u1",
            explicitlyRequested = false,
            nowMs = 1_000L,
            sendsInLastHour = 0,
            sentWithinTtl = true,
            config = QqStickerGate.Config(duplicateTtlHours = 24, chancePercent = 100)
        )
        assertEquals(QqStickerGate.Decision.DenyDuplicate, decision)
    }

    @Test
    fun `未点名且窗口空时按概率决定`() {
        val base = QqStickerGate.Config(chancePercent = 0)
        assertEquals(
            QqStickerGate.Decision.DenyChance,
            QqStickerGate.decide("u1", false, 1_000L, 0, false, base)
        )
        val always = QqStickerGate.Config(chancePercent = 100)
        assertEquals(
            QqStickerGate.Decision.Allow,
            QqStickerGate.decide("u1", false, 1_000L, 0, false, always)
        )
    }

    @Test
    fun `内置 face 与商城表情的闸门键不撞键`() {
        assertTrue(QqStickerGate.faceKey("14").startsWith("face:"))
        assertTrue(QqStickerGate.mfaceKey("999").startsWith("mface:"))
        assertTrue(QqStickerGate.faceKey("14") != QqStickerGate.mfaceKey("14"))
    }

    // ==================== QqStickerStore 文件名安全（guardrail F-5） ====================

    @Test
    fun `合法表情文件名放行`() {
        listOf("u01.img", "u5xyz_12.img", "cyrene_01.png", "a.JPG", "b.webp", "c.jpeg", "d.gif")
            .forEach { assertTrue("应放行 $it", QqStickerStore.isSafeStickerFileName(it)) }
    }

    @Test
    fun `路径穿越与非法扩展名一律拒绝`() {
        listOf(
            "../databases/x.img", "..\\x.img", "/etc/passwd.img", "a/b.img", "a\\b.img",
            "../../files/qq_inbox.json", "u01.img/../x", "x.apk", "x.json", "x", "  ", "u 01.img", "u01.img\n"
        ).forEach { assertTrue("必须拒绝 $it", !QqStickerStore.isSafeStickerFileName(it)) }
    }

    @Test
    fun `穿越样本在加载时被丢弃而不是被外发（readSnapshot 收口）`() {
        // 模拟「stickers.json 被外部写入恶意 fileName」：加载后该条目必须不存在，
        // 否则 pick() 命中后 OneBotQqChannel 会把任意文件 base64 外发给联系人。
        val root = kotlin.io.path.createTempDirectory("sticker-f5").toFile()
        try {
            val dir = java.io.File(root, "qq_stickers").apply { mkdirs() }
            java.io.File(dir, "stickers.json").writeText(
                """{"userStickers":[
                    {"id":"evil","emotion":"开心","fileName":"../../qq_inbox.json","sha1":"x"},
                    {"id":"ok","emotion":"开心","fileName":"u01.img","sha1":"y"}
                ],"learned":[],"recentSends":[]}"""
            )
            java.io.File(dir, "u01.img").writeBytes(ByteArray(64) { 1 })
            val store = QqStickerStore(root)
            val ids = store.userStickers().map { it.id }
            assertTrue("恶意条目必须被丢弃（实际：$ids）", "evil" !in ids)
            assertTrue("合法条目必须保留", "ok" in ids)
        } finally {
            root.deleteRecursively()
        }
    }

    // ==================== QqReplyToolPolicy ====================

    @Test
    fun `白名单放行且黑名单永远拒绝`() {
        listOf(
            "web_search__search", "knowledge_base__search",
            "todo_write", "document__create_docx", "diary__write"
        ).forEach { assertTrue("应放行 $it", QqReplyToolPolicy.isAllowed(it)) }

        listOf(
            "qq__send_message", "ask_user__ask", "pet__play_action",
            "cross_app__open_app", "mcp_x__y",
            // 2026-11-17 真机取证：hotlist 两次串行 HTTP，>30s，聊天里等不起 → 从白名单移除
            "hotlist__get"
        ).forEach { assertTrue("必须拒绝 $it", !QqReplyToolPolicy.isAllowed(it)) }
    }

    @Test
    fun `手机操控默认关闭、显式开启才放行`() {
        val tool = "phone_control__tap"
        assertTrue("默认必须拒绝（高风险）", !QqReplyToolPolicy.isAllowed(tool))
        assertTrue("显式开启后放行", QqReplyToolPolicy.isAllowed(tool, phoneControlEnabled = true))
    }

    @Test
    fun `过滤后不含被禁工具`() {
        val tools = listOf(
            def("web_search__search"), def("qq__send_message"), def("diary__write")
        )
        val filtered = QqReplyToolPolicy.filter(tools)
        assertEquals(listOf("web_search__search", "diary__write"), QqReplyToolPolicy.namesOf(filtered))
    }

    // ==================== QqReplyToolLoop ====================

    @Test
    fun `抽取最终文本时跳过工具结果与占位消息`() {
        val messages = listOf(
            user("帮我查下热榜"),
            assistant("", toolCalls = listOf(toolCall("call_1", "hotlist__get"))),
            tool("hotlist 结果：1. xxx"),
            assistant("查到了，第一是 xxx")
        )
        assertEquals("查到了，第一是 xxx", QqReplyToolLoop.extractFinalText(messages))
    }

    @Test
    fun `没有可用 assistant 文本时返回 null（调用方回落）`() {
        val messages = listOf(user("在吗"), tool("结果"))
        assertEquals(null, QqReplyToolLoop.extractFinalText(messages))
    }

    @Test
    fun `轮数用尽时抽工具结果摘要做强制作答`() {
        // 真机实测：模型连续搜索把轮数用尽，最后一条是带 toolCalls 的占位消息 → 无正文。
        val messages = listOf(
            user("查一下科技新闻"),
            assistant("", toolCalls = listOf(toolCall("call_1", "web_search__search"))),
            tool("搜索结果：1. 某新闻 2. 另一条"),
            assistant("", toolCalls = listOf(toolCall("call_2", "web_search__search"))),
            tool("搜索结果：3. 第三条")
        )
        assertEquals("轮数用尽 → 无最终文本", null, QqReplyToolLoop.extractFinalText(messages))
        val digest = QqReplyToolLoop.extractToolDigest(messages)
        assertTrue("必须抽到工具结果（否则强制作答无意义）", digest.isNotEmpty())
        assertTrue("摘要应含最新一条结果", digest.last().contains("第三条"))
        val ctx = QqReplyToolLoop.digestContext(digest)!!
        assertTrue("必须明确要求不再查", ctx.contains("不用再查"))
        assertTrue("必须禁止编造", ctx.contains("不要编造"))
    }

    @Test
    fun `无工具结果时不产生强制作答上下文`() {
        assertTrue(QqReplyToolLoop.extractToolDigest(listOf(user("在吗"))).isEmpty())
        assertEquals(null, QqReplyToolLoop.digestContext(emptyList()))
    }

    @Test
    fun `工具结果摘要受条数与长度上限约束`() {
        val long = "长".repeat(QqReplyToolLoop.MAX_DIGEST_CHARS + 500)
        val messages = (1..5).map { tool(long) }
        val digest = QqReplyToolLoop.extractToolDigest(messages)
        assertEquals(QqReplyToolLoop.MAX_DIGEST_RESULTS, digest.size)
        assertTrue(
            "单条必须截断（否则 prompt 会被工具结果撑爆）",
            digest.all { it.length <= QqReplyToolLoop.MAX_DIGEST_CHARS }
        )
    }

    @Test
    fun `纯媒体消息不触发工具回路`() {
        assertTrue(
            !QqReplyToolLoop.shouldUseTools(listOf(def("web_search__search")), enabled = true, hasVisibleText = false)
        )
        assertTrue(
            QqReplyToolLoop.shouldUseTools(listOf(def("web_search__search")), enabled = true, hasVisibleText = true)
        )
        assertTrue(
            "无工具时不得走回路",
            !QqReplyToolLoop.shouldUseTools(emptyList(), enabled = true, hasVisibleText = true)
        )
    }

    @Test
    fun `预算判定`() {
        assertTrue(QqReplyToolLoop.isBudgetExhausted(0L, 1_000L, 1_500L))
        assertTrue(!QqReplyToolLoop.isBudgetExhausted(0L, 1_000L, 500L))
        assertTrue("预算 0 视为不限制", !QqReplyToolLoop.isBudgetExhausted(0L, 0L, 999_999L))
    }

    // ==================== 指令解析：指名表情 ====================

    @Test
    fun `点名语法解析出 stickerRef`() {
        val parsed = QqReplyDirective.parse("好呀 [[sticker:id:u5x]]")
        assertEquals("好呀", parsed.text)
        assertEquals("u5x", parsed.stickerRef)
        assertEquals(null, parsed.stickerEmotion)
    }

    @Test
    fun `编号别名与关键词都识别为指名`() {
        assertEquals("x1", QqReplyDirective.parse("嗯 [[sticker:编号:x1]]").stickerRef)
        assertEquals("粉色小人", QqReplyDirective.parse("嗯 [[sticker:id:粉色小人]]").stickerRef)
    }

    @Test
    fun `普通情绪标签仍按情绪解析（向后兼容）`() {
        val parsed = QqReplyDirective.parse("在呀 [[sticker:开心]]")
        assertEquals("开心", parsed.stickerEmotion)
        assertEquals(null, parsed.stickerRef)
    }

    @Test
    fun `自定义表情目录非空时提示词给出指名语法`() {
        val hint = QqReplyDirective.promptHint(
            enabledSticker = true, enabledVoice = false,
            catalog = listOf("u1：粉色小人举着抱抱牌（情绪=抱抱）")
        )
        assertTrue(hint.contains("[[sticker:id:"))
        assertTrue(hint.contains("粉色小人"))
        // 目录为空时不得出现指名语法（否则模型会凭空编 id）
        val empty = QqReplyDirective.promptHint(enabledSticker = true, enabledVoice = false)
        assertTrue(!empty.contains("[[sticker:id:"))
    }

    // ==================== 辅助构造 ====================

    private fun def(name: String) = io.prism.network.ToolDefinition(
        function = io.prism.network.ToolDefinition.FunctionDef(
            name = name,
            description = "d",
            parameters = kotlinx.serialization.json.JsonObject(emptyMap())
        )
    )

    private fun user(text: String) = io.prism.ui.model.ChatMessage(
        id = 1L, role = io.prism.ui.model.Role.USER, content = text, timestamp = 1L
    )

    private fun assistant(text: String, toolCalls: List<io.prism.ui.model.ToolCallRef> = emptyList()) =
        io.prism.ui.model.ChatMessage(
            id = 2L, role = io.prism.ui.model.Role.ASSISTANT, content = text,
            timestamp = 2L, toolCalls = toolCalls
        )

    private fun tool(text: String) = io.prism.ui.model.ChatMessage(
        id = 3L, role = io.prism.ui.model.Role.TOOL, content = text, timestamp = 3L,
        toolCallId = "call_1", toolName = "hotlist__get"
    )

    private fun toolCall(id: String, name: String) =
        io.prism.ui.model.ToolCallRef(id = id, functionName = name, arguments = "{}")
}
