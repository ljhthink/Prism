package io.prism.qq

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **装配回归**（2026-11-16；ADR-077）——沿用 [QqNotifyWiringTest] 的教训：
 * 单测测得出"函数对不对"，测不出"有没有接线"。本批四项都属于**控制流装配**，
 * 漏接就静默回到旧行为（秒回 / 发不出自定义表情 / 用不了工具 / 日记恒空）。
 *
 * 断言只认"带实参的调用形态"，并**剔除注释**后再匹配。
 */
class QqPacingToolsDiaryWiringTest {

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

    // ---------------- B1 拟人节奏 ----------------

    @Test
    fun `发送前必须真的等待拟人延迟`() {
        val code = app()
        assertTrue(
            "必须调用 QqReplyPacing.delayMsFor(...)",
            code.contains("QqReplyPacing.delayMsFor(")
        )
        assertTrue(
            "计算出的延迟必须真的 delay（否则只是算了没用）",
            Regex("""effectivePacingMs[\s\S]{0,600}?kotlinx\.coroutines\.delay\(effectivePacingMs\)""")
                .containsMatchIn(code)
        )
        assertTrue(
            "气泡间隔必须逐条计算（取代固定 700ms）",
            code.contains("QqReplyPacing.bubbleGapsMs(")
        )
    }

    @Test
    fun `节奏配置来自用户设置而不是硬编码`() {
        val code = app()
        assertTrue(code.contains("cfg.pacingEnabled"))
        assertTrue(code.contains("cfg.typingCharsPerSecond"))
        assertTrue(code.contains("cfg.minReplyDelayMs"))
        assertTrue(code.contains("cfg.maxReplyDelayMs"))
    }

    // ---------------- B2 自定义表情 ----------------

    @Test
    fun `自定义表情目录必须进入提示词`() {
        val code = app()
        assertTrue(
            "必须把 catalogForPrompt() 交给 promptHint（否则模型永远只能发内置 face）",
            Regex("""catalog\s*=\s*stickerCatalog""").containsMatchIn(code)
        )
        assertTrue(code.contains("qqStickerStore.catalogForPrompt()"))
    }

    @Test
    fun `指名表情必须真的走发送与记账`() {
        val code = app()
        assertTrue(
            "必须把 stickerRefId 传给 sendSticker（第三参）",
            Regex("""sendSticker\(\s*contact\s*,\s*effectiveStickerEmotion\s*,\s*stickerRefId\s*\)""")
                .containsMatchIn(code)
        )
        assertTrue(
            "发送成功必须 markSent 记账（否则小时上限/同图 TTL 恒失效）",
            code.contains("qqStickerStore.markSent(")
        )
        // 2026-11-16 自查修复：内置 face / mface 也必须计入小时上限
        assertTrue(
            "非指名表情必须 recordSend 记账（否则小时上限对内置表情形同虚设）",
            code.contains("qqStickerStore.recordSend(")
        )
        assertTrue(
            "必须过发送闸门",
            code.contains("QqStickerGate.decide(")
        )
        assertTrue(
            "闸门拦截后必须清掉表情（不能出现「拦了还发」）",
            Regex("""stickerAllowed[\s\S]{0,700}?stickerRefId = null""").containsMatchIn(code)
        )
    }

    @Test
    fun `自定义表情必须真的有导入入口（B-01 阻断项）`() {
        // guardrail B-01：`importImage` 此前只有单测调用 → catalogForPrompt 恒空 →
        // 整个 B2 链路真机不可达（需求 2 行为与改动前完全一致）。
        // guardrail R2-L-16：入口必须走 VM（那里有大小校验/IO/可读错误），
        // 不能在 UI 里再实现一份——否则断言测的是没人用的死路径。
        val settings = stripComments(source("io/prism/ui/settings/SettingsScreen.kt"))
        assertTrue(
            "必须能发起图片选择",
            settings.contains("qqStickerPicker.launch(")
        )
        val vm = stripComments(source("io/prism/ui/settings/SettingsViewModel.kt"))
        assertTrue("VM 必须真的调用 importImage", vm.contains("store.importImage("))
        assertTrue("VM 必须真的调用 removeUserSticker", vm.contains("store.removeUserSticker("))
        assertTrue("VM 必须真的调用 setDescription", vm.contains("store.setDescription("))
    }

    @Test
    fun `新配置项必须持久化并可开关（M-02）`() {
        val repo = stripComments(source("io/prism/qq/QqConfigRepository.kt"))
        listOf(
            "setPacingEnabled", "setTypingCharsPerSecond", "setReplyDelayRange",
            "setStickerMaxPerHour", "setStickerDuplicateTtlHours",
            "setReplyToolsEnabled", "setReplyPhoneControlEnabled",
            "setReplyToolBudgetMs", "setDiaryEnabled"
        ).forEach { setter ->
            assertTrue("缺少 setter：$setter", repo.contains("suspend fun $setter("))
        }
        // 读侧必须真的读回来（否则"写了没用"）
        assertTrue(repo.contains("prefs[PACING_ENABLED]"))
        assertTrue(repo.contains("prefs[REPLY_TOOL_BUDGET]"))
        assertTrue(repo.contains("prefs[DIARY_ENABLED]"))
        // 设置页必须能关掉节奏（逃生口）
        val settings = stripComments(source("io/prism/ui/settings/SettingsScreen.kt"))
        assertTrue("设置页必须有节奏开关", settings.contains("setPacingEnabled("))
        assertTrue("设置页必须有工具开关", settings.contains("setReplyToolsEnabled("))
        assertTrue("设置页必须有日记开关", settings.contains("setDiaryEnabled("))
    }

    @Test
    fun `手机操控必须同时受配置与性能档位约束（M-06）`() {
        val code = app()
        assertTrue(
            "phone_control 必须 && tier.isPhoneControlEnabled（原实现绕过档位）",
            Regex("""cfg\.replyPhoneControlEnabled\s*&&[\s\S]{0,200}?isPhoneControlEnabled""")
                .containsMatchIn(code)
        )
    }

    @Test
    fun `概率只能掷一次（M-03）`() {
        val code = app()
        assertTrue(
            "不得在选情绪阶段自行 Random.nextInt 再交给闸门掷第二次（25% 会变 6.25%）",
            !Regex("""stickerEmotion = when \{[\s\S]{0,900}?Random\.nextInt\(100\) < cfg\.stickerChancePercent""")
                .containsMatchIn(code)
        )
        assertTrue("闸门必须仍在", code.contains("QqStickerGate.decide("))
    }

    @Test
    fun `闸门拦截后不得把指令标记发给用户（M-04）`() {
        val code = app()
        assertTrue(
            "回落必须用已剥离标记的文本",
            code.contains("QqReplyDirective.stripMarkers(reply)")
        )
    }

    @Test
    fun `临界区总预算必须存在（M-07）`() {
        val code = app()
        assertTrue(
            "pacing 延迟必须受临界区预算压缩（否则锁占用过长 → 租约过期 → 双回复）",
            code.contains("QqReplyToolPolicy.PACING_CRITICAL_BUDGET_MS")
        )
        assertTrue(
            "租约必须 ≥ 最坏临界区（两轮排队）",
            io.prism.qq.QqInboxStore.CLAIM_TTL_MS >= 300_000L
        )
    }

    @Test
    fun `租约加长后必须有启动回收（R2-M-01）`() {
        val code = app()
        assertTrue(
            "启动补偿里必须回收陈旧 claim（否则崩溃重启后最长 5 分钟不回复）",
            code.contains("reclaimStaleClaims(")
        )
        val store = stripComments(source("io/prism/qq/QqInboxStore.kt"))
        assertTrue("store 必须实现 reclaimStaleClaims", store.contains("fun reclaimStaleClaims("))
    }

    @Test
    fun `目录必须回落到情绪标签（R2-M-02）`() {
        val store = stripComments(source("io/prism/qq/QqStickerStore.kt"))
        assertTrue(
            "catalogForPrompt 必须在描述为空时用情绪（否则先批量导入的图永久不可见）",
            Regex("""catalogForPrompt[\s\S]{0,700}?ifBlank \{ s\.emotion""").containsMatchIn(store)
        )
        val settings = stripComments(source("io/prism/ui/settings/SettingsScreen.kt"))
        assertTrue(
            "描述必须能作用于选中的那张（不只是最后一张）",
            settings.contains("qqStickerSelectedId")
        )
    }

    @Test
    fun `表情写盘必须走 VM 且在 IO 线程（R2-M-03 与 R2-L-16）`() {
        val settings = stripComments(source("io/prism/ui/settings/SettingsScreen.kt"))
        assertTrue("导入必须走 VM", settings.contains("viewModel.importQqSticker("))
        assertTrue("删除必须走 VM", settings.contains("viewModel.deleteQqSticker("))
        assertTrue("改描述必须走 VM", settings.contains("viewModel.setQqStickerDescription("))
        val vm = stripComments(source("io/prism/ui/settings/SettingsViewModel.kt"))
        assertTrue(
            "删/改描述必须在 IO 调度器上写盘",
            Regex("""deleteQqSticker[\s\S]{0,400}?Dispatchers\.IO""").containsMatchIn(vm) &&
                Regex("""setQqStickerDescription[\s\S]{0,400}?Dispatchers\.IO""").containsMatchIn(vm)
        )
        assertTrue(
            "导入必须在 IO 调度器上写盘",
            Regex("""store\.importImage[\s\S]{0,300}?Dispatchers\.IO""").containsMatchIn(vm) ||
                Regex("""Dispatchers\.IO[\s\S]{0,300}?store\.importImage""").containsMatchIn(vm)
        )
    }

    @Test
    fun `工具回路超时与抽不出文本都必须留痕（不得静默回落）`() {
        val code = app()
        assertTrue(
            "超预算必须留 warn（3s 内看不出工具被调用过）",
            code.contains("工具回路超预算")
        )
        assertTrue(
            "抽不出最终文本必须留 warn（否则真机看不出「工具调了但没拿到回复」）",
            code.contains("工具回路未产出最终文本")
        )
        // 2026-11-17 真机取证：轮数用尽时不能丢弃已查到的结果 —— 必须强制作答
        assertTrue(
            "必须把工具结果摘要交给强制作答（否则用户看到「说去查却没查」）",
            code.contains("QqReplyToolLoop.extractToolDigest(") &&
                code.contains("QqReplyToolLoop.digestContext(")
        )
        assertTrue(
            "强制作答必须是**禁用工具**的单次补全（composer.composeReply 不带 tools 参数）",
            Regex("""digestContext[\s\S]{0,1200}?composer\.composeReply\(""").containsMatchIn(code)
        )
        assertTrue(
            "executeLoop 必须被 withTimeoutOrNull 包住（否则预算只是摆设）",
            Regex("""withTimeoutOrNull\(\s*cfg\.replyToolBudgetMs\s*\)[\s\S]{0,600}?executeLoop\(""")
                .containsMatchIn(code)
        )
        assertTrue(
            "超时后必须回落（返回 null）",
            Regex("""工具回路超预算[\s\S]{0,600}?return null""").containsMatchIn(code)
        )
    }

    // ---------------- B3 工具回路 ----------------

    @Test
    fun `QQ 回复必须先试工具回路并可回落`() {
        val code = app()
        assertTrue("必须有工具回路入口", code.contains("composeReplyWithTools("))
        assertTrue(
            "回落后必须仍走单次补全（不丢回复）",
            Regex("""composeReplyWithTools\([\s\S]{0,1600}?composer\.composeReply\(""")
                .containsMatchIn(code)
        )
        assertTrue("工具必须按策略过滤", code.contains("QqReplyToolPolicy.filter("))
        assertTrue(
            "回路必须复用 executeLoop（不新写循环）",
            Regex("""skillExecutor\.executeLoop\(""").containsMatchIn(code)
        )
        assertTrue(
            "必须抽最终文本（否则会把工具占位消息当回复发出去）",
            code.contains("QqReplyToolLoop.extractFinalText(")
        )
    }

    @Test
    fun `工具回路受开关与图片例外约束`() {
        val code = app()
        assertTrue("总开关必须生效", code.contains("if (!cfg.replyToolsEnabled) return null"))
        assertTrue(
            "带图一轮必须走单次补全（多模态+工具在部分端点会 400）",
            code.contains("if (imageDataUrl != null) return null")
        )
        assertTrue("手机操控必须受显式开关", code.contains("cfg.replyPhoneControlEnabled"))
    }

    // ---------------- B4 角色日记 ----------------

    @Test
    fun `日记必须能被注入上下文`() {
        val code = app()
        assertTrue("必须构建 diarySection", code.contains("roleDiaryStore.promptSection("))
        assertTrue(
            "必须真的传进 composeReply（否则算了没用）",
            Regex("""diarySection\s*=\s*diarySection""").containsMatchIn(code)
        )
        assertTrue("必须受日记开关约束", code.contains("if (cfg.diaryEnabled)"))
    }

    @Test
    fun `日记工具必须注册进组合执行器与主聊天工具表`() {
        val appCode = app()
        assertTrue(
            "日记执行器必须进 compositeLocalToolExecutor（否则工具永远不可执行）",
            Regex("""CompositeLocalToolExecutor\([\s\S]{0,900}?diaryLocalToolExecutor""")
                .containsMatchIn(appCode)
        )
        val vm = stripComments(source("io/prism/ui/chat/ConversationViewModel.kt"))
        assertTrue(
            "buildTools 必须能注入 diary__write",
            vm.contains("DiaryLocalToolExecutor.buildToolDefinition()")
        )
        assertTrue(
            "Factory 必须注入 diaryToolExecutor",
            vm.contains("diaryToolExecutor = app.diaryLocalToolExecutor")
        )
    }

    @Test
    fun `提示词声明"可以真的去做"且日记判据在场`() {
        val composer = stripComments(source("io/prism/qq/QqProactiveComposer.kt"))
        assertTrue(
            "工具回路 system 必须包含能力劝导（真机：只说'去查'却没查）",
            composer.contains("【可以真的去做】")
        )
        assertTrue(
            "日记开时必须拼入触发判据",
            composer.contains("DiaryLocalToolExecutor.TRIGGER_HINT")
        )
        assertTrue(
            "日记段必须标注'不必跟他提起'（否则会把内心话当聊天素材复述）",
            composer.contains("不必跟他提起")
        )
    }
}
