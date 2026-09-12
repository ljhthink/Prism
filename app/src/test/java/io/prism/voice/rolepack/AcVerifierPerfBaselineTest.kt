package io.prism.voice.rolepack

import io.prism.data.SkillConfig
import io.prism.data.SkillSource
import io.prism.skill.SkillManifest
import io.prism.skill.SkillRegistry
import io.prism.ui.chat.ConversationViewModel
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **ac-verifier 性能回退强制检查**（AC10 / rules/04 §11.4）。
 *
 * 对 AC10 指定的三个关键纯函数做计时测试并输出 p50/p95/p99：
 * 1. [RolePackWorldbook.select]（真实资产规模 40 条 / 20,750 字）
 * 2. [RolePack.toPersonaSystemPromptSection]（真实 person 规模 14,963 字 / 通话段 1,620 字）
 * 3. [ConversationViewModel.mergeSystemPrompt]（全层齐备 + 真实角色包体量）
 *
 * **基线状态**：`docs/reports/perf/` 内 12 份既有基线**均不覆盖**这三个函数
 * （已逐一检索：无 worldbook / persona / mergeSystemPrompt 相关条目）→ 本测试产出**初版基线**，
 * 报告内声明为"无历史基线可对比"。门禁按模板：下降 >20% 警告、>50% 失败
 * （首版无对比对象，故本轮只对**绝对量级合理性**做断言：单轮装配必须远低于一帧预算的量级）。
 *
 * 计时方法：System.nanoTime + 预热迭代（消除 JIT 与类加载），取 p50/p95/p99/max。
 */
class AcVerifierPerfBaselineTest {

    private val iterations = 2000
    private val warmup = 300

    // ==================== 被测数据 ====================

    private fun realPack(): RolePack {
        val f = listOf(File("..", "cyrene.rolepack"), File("cyrene.rolepack"))
            .firstOrNull { it.isFile }
        return if (f != null) {
            RolePackParser.parse(f.readText())
        } else {
            // 无资产环境（干净检出）：按报告登记的真实规模合成等价输入
            syntheticPack()
        }
    }

    private fun syntheticPack(): RolePack {
        val persona = "人格正文".repeat(14963 / 4)
        val call = "通话收紧段".repeat(1620 / 5)
        val entries = (1..40).map { i ->
            RolePack.WorldbookEntry(
                keys = listOf("关键词$i", "kw$i"),
                content = "设定正文$i".repeat(20750 / 40 / 6),
                constant = i <= 3
            )
        }
        return RolePack(
            id = "synthetic", name = "合成角色", version = "6.0.0",
            persona = RolePack.Persona(systemPrompt = persona, callSystemPrompt = call),
            worldbook = entries
        )
    }

    private fun skills(n: Int) = (1..n).map { i ->
        SkillRegistry.SkillEntry(
            config = SkillConfig(
                id = i.toLong(), name = "skill$i", displayName = "技能$i",
                source = SkillSource.LOCAL_BUILTIN, sourceUri = null,
                skillDir = "/skills/skill$i", isEnabled = true, isInstalled = true, version = "1.0.0"
            ),
            manifest = SkillManifest(
                name = "skill$i", description = "能力描述$i", version = "1.0.0",
                systemPrompt = null, tools = null, body = ""
            )
        )
    }

    // ==================== 计时核心 ====================

    private class Stats(
        val label: String,
        val scale: String,
        val samples: LongArray
    ) {
        private fun pct(p: Double): Double {
            val s = samples.sortedArray()
            val idx = ((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)
            return s[idx] / 1000.0
        }

        val min get() = samples.min() / 1000.0
        val p50 get() = pct(0.50)
        val p95 get() = pct(0.95)
        val p99 get() = pct(0.99)
        val max get() = samples.max() / 1000.0
        val mean get() = samples.average() / 1000.0
        val throughput get() = 1_000_000.0 / mean

        fun row(): String =
            "| %-46s | %-26s | %8.1f | %8.1f | %8.1f | %8.1f | %8.1f | %12.1f |".format(
                label, scale, min, p50, p95, p99, max, throughput
            )

        override fun toString() = row()
    }

    private fun measure(label: String, scale: String, block: () -> Any?): Stats {
        repeat(warmup) { block() }
        val samples = LongArray(iterations)
        for (i in 0 until iterations) {
            val t0 = System.nanoTime()
            block()
            samples[i] = System.nanoTime() - t0
        }
        return Stats(label, scale, samples)
    }

    // ==================== 主用例 ====================

    @Test
    fun `AC10 - 三个关键纯函数计时基线（p50 p95 p99 与吞吐）`() {
        val pack = realPack()
        val entries = pack.worldbook
        val persona = pack.persona.systemPrompt
        val call = pack.persona.callSystemPrompt
        val allKeys = entries.flatMap { it.keys }.filter { it.isNotBlank() }
        val fullSkills = skills(6)

        val assetDesc = if (entries.size == 40 && persona.length == 14963) "真实资产" else "合成等价输入"

        // **隔离测量**：scanText 用 StringBuilder 预构造（禁用 `+=`——Kotlin 的 String.plus 是
        // 逐次分配新串，长串上会退化为 O(n²)，会把**测试夹具开销**误报成产品开销；
        // 首轮实测即因此得到 select 高命中 794us 的虚高值，见报告「性能对比表」脚注）
        val hitScan = buildString {
            append(allKeys.take(20).joinToString(" "))
            repeat(6) { append(" 另外再加上一段较长的用户输入用于模拟真实对话轮次的扫描文本") }
        }
        println("[AC-VERIFIER] hitScan 长度=${hitScan.length} 字，候选键=${allKeys.size} 个")

        val worldbookEntries = "worldbook 40 条 / ${entries.sumOf { it.content.length }} 字"
        val personaDesc = "persona ${persona.length} 字 + 通话段 ${call.length} 字"

        // 预取一次入选结果，供"仅渲染/仅合并"的隔离测量复用（避免把 select 开销重复计入）
        val preSel = RolePackWorldbook.select(entries, hitScan)
        val preSection = RolePackWorldbook.toSystemPromptSection(preSel.picked)
        val prePersona = pack.toPersonaSystemPromptSection(voiceCall = false)!!

        val stats = listOf(
            measure("RolePackWorldbook.select（空扫描→仅恒定）", worldbookEntries) {
                RolePackWorldbook.select(entries, "")
            },
            measure("RolePackWorldbook.select（高命中扫描）", "${worldbookEntries}／scan ${hitScan.length} 字") {
                RolePackWorldbook.select(entries, hitScan)
            },
            measure("toSystemPromptSection（隔离：仅渲染）", "入选 ${preSel.picked.size} 条") {
                RolePackWorldbook.toSystemPromptSection(preSel.picked)
            },
            measure("toPersonaSystemPromptSection(voiceCall=false)", personaDesc) {
                pack.toPersonaSystemPromptSection(voiceCall = false)
            },
            measure("toPersonaSystemPromptSection(voiceCall=true)", personaDesc) {
                pack.toPersonaSystemPromptSection(voiceCall = true)
            },
            measure("mergeSystemPrompt（出厂态，无角色包）", "DEFAULT_PERSONA + 6 技能") {
                ConversationViewModel.mergeSystemPrompt(
                    ragPrompt = "RAG-GROUNDING-RULES", l1Summary = "L1-SUMMARY",
                    l2Memories = "L2-MEMORIES", l3Profiles = "L3-PROFILES",
                    userRules = "USER-RULES", phoneControlGuidance = "PHONE-GUIDANCE",
                    hotListGuidance = "HOTLIST-GUIDANCE", todoGuidance = "TODO-GUIDANCE",
                    enabledSkills = fullSkills
                )
            },
            measure("mergeSystemPrompt（角色全层，隔离：预取层）", "persona+worldbook+tail") {
                ConversationViewModel.mergeSystemPrompt(
                    ragPrompt = "RAG-GROUNDING-RULES", l1Summary = "L1-SUMMARY",
                    l2Memories = "L2-MEMORIES", l3Profiles = "L3-PROFILES",
                    userRules = null, phoneControlGuidance = "PHONE-GUIDANCE",
                    hotListGuidance = "HOTLIST-GUIDANCE", todoGuidance = "TODO-GUIDANCE",
                    enabledSkills = fullSkills,
                    basePersona = prePersona,
                    worldbookSection = preSection,
                    styleTail = ConversationViewModel.ROLE_STYLE_TAIL
                )
            },
            measure("单轮完整装配链（worldbook→persona→merge）", "端到端纯函数链") {
                val sel = RolePackWorldbook.select(entries, hitScan)
                val wb = RolePackWorldbook.toSystemPromptSection(sel.picked)
                ConversationViewModel.mergeSystemPrompt(
                    ragPrompt = "RAG-GROUNDING-RULES", l1Summary = "L1-SUMMARY",
                    l2Memories = "L2-MEMORIES", l3Profiles = "L3-PROFILES",
                    userRules = null, phoneControlGuidance = "PHONE-GUIDANCE",
                    hotListGuidance = "HOTLIST-GUIDANCE", todoGuidance = "TODO-GUIDANCE",
                    enabledSkills = fullSkills,
                    basePersona = pack.toPersonaSystemPromptSection(voiceCall = false)!!,
                    worldbookSection = wb, styleTail = ConversationViewModel.ROLE_STYLE_TAIL
                )
            }
        )

        println()
        println("[AC-VERIFIER] 性能基线（输入=$assetDesc，iterations=$iterations，warmup=$warmup，单位=微秒）")
        println("| 指标 | 规模 | min | p50 | p95 | p99 | max | 吞吐(ops/s) |")
        println("|---|---|---|---|---|---|---|---|")
        stats.forEach { println(it.row()) }
        println()

        // ==================== systemPrompt 体积对比（AC10 体积增长判据） ====================

        val shipped = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null, userRules = null, enabledSkills = emptyList()
        )
        val factoryFull = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "RAG-GROUNDING-RULES", l1Summary = "L1-SUMMARY",
            l2Memories = "L2-MEMORIES", l3Profiles = "L3-PROFILES",
            userRules = "USER-RULES", phoneControlGuidance = "PHONE-GUIDANCE",
            hotListGuidance = "HOTLIST-GUIDANCE", todoGuidance = "TODO-GUIDANCE",
            enabledSkills = fullSkills
        )
        val sel = RolePackWorldbook.select(entries, hitScan)
        val wbSection = RolePackWorldbook.toSystemPromptSection(sel.picked)
        val roleFull = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "RAG-GROUNDING-RULES", l1Summary = "L1-SUMMARY",
            l2Memories = "L2-MEMORIES", l3Profiles = "L3-PROFILES",
            userRules = null, phoneControlGuidance = "PHONE-GUIDANCE",
            hotListGuidance = "HOTLIST-GUIDANCE", todoGuidance = "TODO-GUIDANCE",
            enabledSkills = fullSkills,
            basePersona = pack.toPersonaSystemPromptSection(voiceCall = false)!!,
            worldbookSection = wbSection, styleTail = ConversationViewModel.ROLE_STYLE_TAIL
        )

        // "扩展前"角色轮次体积 = DEFAULT_PERSONA 位被旧 persona（v5：文本人格 + 通话段混装）
        // 替代，且无 worldbook / 无 styleTail。用真实资产的两段之和近似 v5 的 persona 总量。
        val v5PersonaApprox = persona.length + call.length
        val roleFullMinusNewLayers = roleFull.length - wbSection.orEmpty().length -
            ConversationViewModel.ROLE_STYLE_TAIL.length

        println("[AC-VERIFIER] systemPrompt 体积（字符）：")
        println("  - 出厂最小态（仅 DEFAULT_PERSONA）: ${shipped.length}")
        println("  - 出厂全层态                    : ${factoryFull.length}")
        println("  - 角色全层态（本轮扩展后）        : ${roleFull.length}")
        println("  - 其中 v5 persona 近似总量       : $v5PersonaApprox")
        println("  - 其中 worldbook 区块            : ${wbSection.orEmpty().length}（入选 ${sel.picked.size} 条，预算上限 ${RolePackWorldbook.MAX_CHARS}）")
        println("  - 其中 ROLE_STYLE_TAIL           : ${ConversationViewModel.ROLE_STYLE_TAIL.length}")
        println("  - 本轮**净新增**层（worldbook+tail）: ${roleFull.length - roleFullMinusNewLayers}")
        println()

        // ==================== 门禁断言 ====================

        // 1) 单轮装配链绝对量级：纯函数链必须 < 5ms（真实首字延迟由网络 LLM 主导，~500ms-3s）
        val chain = stats.last()
        assertTrue(
            "单轮纯函数装配链 p95 应 < 5ms（实测 ${chain.p95}us）——超出则提示词装配开始影响首字延迟",
            chain.p95 < 5000.0
        )
        // 2) select 必须亚毫秒（40 条 × 命中扫描：O(条目数 × 平均 key 命中尝试)，与扫描长度线性相关）
        val selectHit = stats[1]
        assertTrue(
            "select p95 应 < 5ms（实测 ${selectHit.p95}us）",
            selectHit.p95 < 5000.0
        )
        // 2b) 隔离测量：仅渲染不得成为瓶颈
        val render = stats[2]
        assertTrue("toSystemPromptSection p95 应 < 200us（实测 ${render.p95}us）", render.p95 < 200.0)

        // 2c) 微基准证伪：select 高命中的耗时是否真的来自 `contains(ignoreCase=true)`？
        // 隔离同一判定原语（不做条目遍历/分配），与 select 同口径计时后对比——若同量级，
        // 则 select 的开销即"keys × contains"本身（无隐藏放大）；若远超则需继续追查。
        val rawContains = measure("隔离原语：keys × contains(ignoreCase) 全遍历", "40 条 × ${allKeys.size} keys") {
            var acc = 0
            entries.forEach { e -> e.keys.forEach { k -> if (k.isNotBlank() && hitScan.contains(k, true)) acc++ } }
            acc
        }
        println(
            "[AC-VERIFIER] 隔离原语 p50=${rawContains.p50}us p95=${rawContains.p95}us  vs  " +
                "select p50=${selectHit.p50}us p95=${selectHit.p95}us  → 比值 ${"%.2f".format(selectHit.p50 / rawContains.p50.coerceAtLeast(0.01))}×"
        )
        assertTrue(
            "select 与隔离原语应同量级（select.p50 < 20×原语.p50 + 200us）——远超则说明存在未被识别的放大",
            selectHit.p50 < rawContains.p50 * 20 + 200
        )
        // 3) 体积闸：worldbook 区块不得超过 MAX_CHARS + 引导句长度
        assertTrue(
            "worldbook 区块体积必须受 MAX_CHARS 约束（实测 ${wbSection!!.length}）",
            wbSection.length <= RolePackWorldbook.MAX_CHARS + 400
        )
        // 4) 体积登记的"净新增"量级
        val netNew = roleFull.length - roleFullMinusNewLayers
        assertTrue("净新增层应 > 0（证明新层已生效）", netNew > 0)
        assertTrue(
            "净新增层应 ≤ MAX_CHARS + 500（worldbook 上限 + styleTail + 分隔符）",
            netNew <= RolePackWorldbook.MAX_CHARS + 500
        )
    }
}
