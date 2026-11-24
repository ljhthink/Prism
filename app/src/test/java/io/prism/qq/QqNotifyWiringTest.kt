package io.prism.qq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **装配回归**（2026-11-15；ADR-076）。
 *
 * 沿用 [QqMediaWiringTest] 的教训：单元测试测得出"函数对不对"，测不出"有没有接线"。
 * 本轮四项修复都属于**控制流装配**，一旦漏接就静默回到旧行为：
 *
 * 1. 节流挂起必须走 `QqInboxPolicy.holdMs(...)`，否则仍是硬编码 60s（回复永远落后一条）；
 * 2. 收件箱必须**批量**领取并经 `autoReplyBurst` 生成，否则又回到逐条点答；
 * 3. 生成结果必须经 `QqReplyBubbles.split` 再逐条发送，否则多气泡是空话；
 * 4. 入站必须把 `mediaNote` 落进会话记录、历史渲染必须优先用它，否则 `[表情包]` 依旧无语义。
 *
 * 断言只认"带实参的调用形态"，并**剔除注释**后再匹配。
 */
class QqNotifyWiringTest {

    private fun source(relPath: String): String {
        val candidates = listOf(
            File("src/main/java/$relPath"),
            File("app/src/main/java/$relPath")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relPath（工作目录=${File("").absolutePath}）")
        return file.readText()
    }

    private fun stripComments(text: String): String = text.lineSequence()
        .joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") -> ""
                else -> line.substringBefore("//")
            }
        }

    private fun app(): String = stripComments(source("io/prism/PrismApplication.kt"))

    @Test
    fun `节流挂起必须按原因分流（不得硬编码 60 秒）`() {
        val code = app()
        assertTrue(
            "收件箱 hold 必须用 QqInboxPolicy.holdMs(result.message) —— " +
                "硬编码 60_000L 会让只差 3s 的节流消息排到 60s 后（真机回复落后一条的根因）",
            code.contains("QqInboxPolicy.holdMs(")
        )
    }

    @Test
    fun `收件箱必须批量领取并走 burst 生成`() {
        val code = app()
        assertTrue(
            "claimDue 必须用 BURST_LIMIT（>1），否则连发消息永远逐条生成",
            code.contains("QqInboxStore.BURST_LIMIT")
        )
        assertTrue(
            "必须存在 autoReplyBurst( 的调用形态 —— 否则批合并只是死代码",
            Regex("""autoReplyBurst\(\s*\w+\s*\)""").containsMatchIn(code)
        )
    }

    @Test
    fun `回复必须经气泡拆分后逐条发送`() {
        val code = app()
        assertTrue(
            "发送前必须调用 QqReplyBubbles.split(...)，否则多气泡永不生效",
            code.contains("QqReplyBubbles.split(")
        )
    }

    @Test
    fun `入站媒体说明必须落进会话记录`() {
        val code = app()
        assertTrue(
            "入站 append 必须带 mediaNote（否则历史里只剩 [表情包] 占位符）",
            code.contains("replyTarget.copy(mediaNote =")
        )
        assertTrue(
            "历史渲染必须优先用 mediaNote",
            stripComments(source("io/prism/qq/QqContextBuilder.kt"))
                .contains("message.mediaNote")
        )
    }

    @Test
    fun `连发消息必须作为 pending 传入提示词`() {
        val code = app()
        assertTrue(
            "composeReply 必须传 pending = burst，否则模型看不到还没回的那几条",
            code.contains("pending = burst")
        )
    }

    @Test
    fun `本轮必须注入时间与回复形状`() {
        val code = app()
        assertTrue("必须注入当前时间（时间感）", code.contains("now = java.time.LocalDateTime.now()"))
        assertTrue("必须抽样回复形状", code.contains("QqReplyShape.pick()"))
    }

    // ==================== guardrail 复审（M-01 ~ M-04）的装配防线 ====================

    @Test
    fun `M-01 去重必须按条且在生成之前`() {
        val code = app()
        assertTrue(
            "必须对整批逐条过滤 isDuplicate（只查末条会让末条被去重时整批被判 DONE → 丢消息）",
            Regex("""replyBatch\s*=\s*\(sortedBatch[\s\S]{0,200}isDuplicate""").containsMatchIn(code)
        )
        assertTrue(
            "过滤后必须能整体短路（全部已回过 → skipped）",
            code.contains("if (replyBatch.isEmpty())")
        )
    }

    @Test
    fun `M-02 部分送达不得整批重试且不得清熔断计数`() {
        val code = app()
        assertTrue("必须记录真正发出的气泡", code.contains("sentBubbles"))
        assertTrue(
            "sentBubbles 必须是会话记录的入账源（不是全部气泡）",
            Regex("""sentBubbles\.forEach\s*\{""").containsMatchIn(code)
        )
        assertTrue("必须有部分送达判据", code.contains("partiallySent"))
        // guardrail R2-M-01：归一成"已发送"若走 recordReplied 会清 FAILURE_STREAK —— 必须走
        // recordUnconfirmedReplied（计窗口、不动 streak），与既有 G-r3-01 设计一致。
        val partialBranch = Regex("""partiallySent\s*->\s*\{[\s\S]{0,1400}?\n\s*\}""")
            .find(code)?.value ?: error("找不到 partiallySent 的结算分支")
        assertTrue(
            "部分送达分支必须走 recordUnconfirmedReplied（不得用 recordReplied 清熔断计数）",
            partialBranch.contains("recordUnconfirmedReplied(") &&
                !partialBranch.contains("recordReplied(")
        )
        // guardrail R3-M-01：部分送达时 result 已被归一成"已发送"，failed 分支不可达 →
        // 若不在此处补判，账号级失败（风控"前几条过、后面被拒"）在熔断里**完全不计数**。
        assertTrue(
            "部分送达分支必须用未归一的 sendResult 补判账号级失败（否则熔断永不触发）",
            partialBranch.contains("countsTowardCircuit(") &&
                partialBranch.contains("recordFailure(")
        )
        assertTrue(
            "补判必须基于未被归一化的 sendResult（归一后的 result 恒为成功）",
            partialBranch.contains("classify(sendResult)")
        )
    }

    @Test
    fun `M-03 气泡数必须受被动窗口额度约束且按真实外发记账`() {
        val code = app()
        assertTrue(
            "发送前必须用 QqThrottle.availableSendSlots 截断气泡数（窗口按回复计数，气泡会放大外发次数）",
            code.contains("QqThrottle.availableSendSlots(")
        )
        assertTrue("截断必须真正作用到 bubbles", code.contains("maxBubblesNow"))
        assertTrue(
            "发送后必须按真实外发条数补记账（否则窗口上限实际被放大到 3 倍）",
            code.contains("recordExtraPassiveSends(sentAt, extraSends)")
        )
        assertTrue(
            "extraSends 必须由 sentBubbles.size 派生（不能用 bubbles.size / 常量）",
            Regex("""extraSends\s*=\s*\(sentBubbles\.size\s*-\s*1\)""").containsMatchIn(code)
        )
    }

    @Test
    fun `M-04 成功时必须对整批写幂等键`() {
        val code = app()
        assertTrue(
            "必须对整批 messageId 写幂等键（只写末条 → 重投前几条会被二次回复）",
            Regex("""replyBatch\.forEach\s*\{[\s\S]{0,160}setLastRepliedMessageId""")
                .containsMatchIn(code)
        )
    }

    @Test
    fun `指令解析必须先于气泡拆分且气泡仅用于协议端`() {
        val code = app()
        val parseAt = code.indexOf("QqReplyDirective.parse(")
        val splitAt = code.indexOf("QqReplyBubbles.split(")
        assertTrue("两个调用点都必须存在", parseAt >= 0 && splitAt >= 0)
        assertTrue(
            "指令必须先解析/剥离再拆气泡，否则字面量 [[sticker:…]] 会被拆到不同气泡里泄漏",
            parseAt < splitAt
        )
        assertTrue(
            "无障碍通道不得多气泡（一次只能注入一条）",
            Regex("""channel\.transport\s*!=\s*io\.prism\.qq\.channel\.QqTransport\.ONEBOT""")
                .containsMatchIn(code)
        )
    }

    @Test
    fun `批内条目必须全部终结（claim 不悬空）`() {
        val code = app()
        // 定位**主结算块**：从 burst 生成处到 `handled +=`。
        // 2026-11-22（guardrail M-04②）：原实现用 `claimed.forEach` 定位起点，
        // 于是新增的"折叠分支"（在 burst 之前、同样终结条目）会被当成主结算块，
        // 折叠分支一旦漏 done 就永远不会被发现。改为按 `autoReplyBurst` 之后定位。
        val burstAt = code.indexOf("val result = autoReplyBurst(")
        val handledAt = code.indexOf("handled += claimed.size", burstAt)
        assertTrue("必须存在 burst 生成与结算", burstAt >= 0 && handledAt > burstAt)
        val settlement = code.substring(burstAt, handledAt)
        listOf("qqInboxStore.done(", "qqInboxStore.hold(", "qqInboxStore.retryLater(").forEach { call ->
            assertTrue(
                "claim 过的条目必须三条终结路径都在（缺 $call 会让该状态悬空到租约回收）",
                settlement.contains(call)
            )
        }
        assertTrue("HOLD 必须走 holdMs 分流", code.contains("QqInboxPolicy.holdMs("))
    }

    // ==================== ADR-080（2026-11-22）装配防线 ====================

    @Test
    fun `B-01 采集与处理必须解耦（合并窗口才可能生效）`() {
        val code = app()
        // 事件采集体内**不得**直接处理：否则合并窗口的 delay 会阻塞 enqueue，
        // `claimDue` 永远只拿到 1 条（真机"一句话+表情被回两次"的另一半根因）。
        val collectAt = code.indexOf("oneBotChannel.incoming().collect")
        assertTrue("必须存在事件采集", collectAt >= 0)
        val collectBody = code.substring(collectAt, code.indexOf("return@launch", collectAt))
        assertFalse(
            "采集体内不得直接调用 processInboxOnce（会与合并窗口的 delay 串行）",
            collectBody.contains("processInboxOnce(")
        )
        assertTrue("采集体必须投递唤醒信号", collectBody.contains("inboxWake.trySend("))
        assertTrue("必须存在独立的处理协程", code.contains("for (unit in inboxWake)"))
        assertTrue(
            "唤醒必须用 CONFLATED（多条事件只需一次唤醒，不堆积不阻塞）",
            code.contains("Channel.CONFLATED")
        )
        assertTrue(
            "合并窗口必须在 claim 之前等待",
            Regex("""QqInboxCoalescer\.waitMs\([\s\S]{0,1200}?claimDue""").containsMatchIn(code)
        )
    }

    @Test
    fun `M-02 折叠分支落盘失败不得终结条目`() {
        val code = app()
        val foldAt = code.indexOf("val foldMedia = io.prism.qq.QqMediaFollow.shouldFold")
        assertTrue("折叠分支必须存在", foldAt >= 0)
        val foldBody = code.substring(foldAt, code.indexOf("val result = autoReplyBurst(", foldAt))
        assertTrue("折叠分支必须检测入历史结果", foldBody.contains("appendFailed"))
        assertTrue("落盘失败必须退避重试", foldBody.contains("qqInboxStore.retryLater("))
        // 失败分支必须在 done() 之前 return，避免"先删后写"的顺序反转
        val failedReturnAt = foldBody.indexOf("if (appendFailed)")
        val doneAt = foldBody.indexOf("qqInboxStore.done(")
        assertTrue("appendFailed 分支必须先于 done 终结路径", failedReturnAt in 0 until doneAt)
    }

    @Test
    fun `M-03 折叠只针对表情，图片与视频必须正常回应`() {
        val follow = stripComments(source("io/prism/qq/QqMediaFollow.kt"))
        assertTrue("必须定义可折叠类型集合", follow.contains("FOLDABLE_KINDS"))
        assertTrue("表情包必须可折叠", follow.contains("\"STICKER\""))
        assertTrue("内置表情必须可折叠", follow.contains("\"FACE\""))
        // guardrail R2-L5：原来的 `contains("\"IMAGE\" to")` 是恒真空断言（setOf 里永不出现
        // map 的 `to`）。改为直接钉死集合字面量——任何把 IMAGE 加进去的改动都会变红。
        assertTrue(
            "可折叠集合必须恰好是 STICKER + FACE",
            Regex("""FOLDABLE_KINDS\s*=\s*setOf\(([^)]*)\)""")
                .find(follow)?.groupValues?.get(1)
                ?.let { it.contains("\"STICKER\"") && it.contains("\"FACE\"") && !it.contains("\"IMAGE\"") }
                ?: false
        )
        assertTrue(
            "含非可折叠类型必须直接返回 false",
            Regex("""mediaKinds\.any\s*\{\s*it\s*!in\s*FOLDABLE_KINDS\s*}""").containsMatchIn(follow)
        )
    }

    @Test
    fun `M-04③ time__now 必须在 QQ 白名单内且不在拒绝名单`() {
        val policy = stripComments(source("io/prism/qq/QqReplyToolPolicy.kt"))
        assertTrue("ALLOWED_EXACT 必须包含 time__now", policy.contains("\"time__now\""))
        assertTrue(
            "isAllowed 必须对精确名放行（否则工具下发了也永远不走执行器）",
            policy.contains("if (toolName in ALLOWED_EXACT) return true")
        )
        // 时间工具的注入点必须是 QQ 工具回路（不得误加到主聊天工具清单）
        val app = app()
        assertTrue(
            "QQ 工具回路必须注入 TimeNowLocalToolExecutor 定义",
            app.contains("TimeNowLocalToolExecutor.buildToolDefinition()")
        )
    }
}
