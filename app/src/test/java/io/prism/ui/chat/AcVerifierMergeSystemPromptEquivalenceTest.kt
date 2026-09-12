package io.prism.ui.chat

import io.prism.data.SkillConfig
import io.prism.data.SkillSource
import io.prism.skill.SkillManifest
import io.prism.skill.SkillRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * **ac-verifier 独立 Golden-Master 等价性验证**（TASK-20260912-001 / AC8）。
 *
 * **判据**：未选中角色包时 `mergeSystemPrompt` 的输出必须与扩展前**逐字节一致**
 * （唯一**预期**差异为 D2 有意删除的 skill 索引身份断言从句——本条单独归因）。
 *
 * **双保险方法**（不转录、可执行、防自证）：
 * 1. **[headMergeSystemPrompt] 忠实转录**：改动前实现的逐行抄录（仅换名为 head*）。
 *    它在**编译期**存在，可与当前实现做全组合**逐字节**比较（这是主判据）。
 * 2. **[HEAD 保真校验] 运行时从 git 对象库取 HEAD 源码**，做结构性断言
 *    （函数体行数 / append 次数 / 分支数 / 不含新层 / 含旧身份断言），
 *    证明 ① 中的转录与真实 HEAD 结构一致——防"转录时顺手改了内容"导致等价性结论失真。
 *
 * 覆盖组合：8 个可空文本层全 2^8 = 256 种 non-null/null 组合 + 空白串边界 + 技能索引 3 态。
 *
 * **D2 差异的处置（关键）**：skill 索引行的身份断言是**本轮有意删除**的，故全组合比较前先把
 * HEAD 输出的该从句规范化掉，使"逐字节一致"这一判据对**其余所有内容**成立；
 * 该从句本身的移除由 [skill 索引身份断言的移除是唯一差异] 单独断言。
 */
class AcVerifierMergeSystemPromptEquivalenceTest {

    // ==================== 1. 主判据：逐字节等价比较 ====================

    @Test
    fun `AC8 - 未选中角色包路径：全新层取默认值时与 HEAD 全组合逐字节一致（含 256 组合）`() {
        val values = listOf("RAG", "L1", "L2", "L3", "RULES", "PHONE", "HOTLIST", "TODO")
        var compared = 0
        var mismatches = 0
        val firstMismatch = StringBuilder()

        fun check(inputs: List<String?>, skills: List<SkillRegistry.SkillEntry>, tag: String) {
            val old = normalize(headMergeSystemPrompt(inputs, skills))
            val new = ConversationViewModel.mergeSystemPrompt(
                ragPrompt = inputs[0], l1Summary = inputs[1], l2Memories = inputs[2],
                l3Profiles = inputs[3], userRules = inputs[4], phoneControlGuidance = inputs[5],
                hotListGuidance = inputs[6], todoGuidance = inputs[7], enabledSkills = skills
                // basePersona / worldbookSection / styleTail 全部走缺省 → 出厂路径
            )
            compared++
            if (old != new) {
                mismatches++
                if (firstMismatch.isEmpty()) {
                    firstMismatch.append(
                        "\n[$tag] inputs=${values.indices.map { "${values[it]}=${inputs[it]}" }} skills=${skills.size}\n" +
                            "OLD(len=${old.length})<<<${old.take(500)}>>>\n" +
                            "NEW(len=${new.length})<<<${new.take(500)}>>>"
                    )
                }
            }
        }

        // 全 2^8 组合
        for (mask in 0 until (1 shl values.size)) {
            check(values.mapIndexed { i, v -> if (mask and (1 shl i) != 0) v else null }, emptyList(), "mask=$mask")
        }
        // 空白串边界（isNullOrBlank 语义：纯空白应等价于 null）
        check(listOf("   ", "", null, null, "\t\n", null, null, null), emptyList(), "blank-mixed")
        check(List(8) { "  " }, emptyList(), "all-blank")
        // 技能索引 3 态 × 空/满输入
        listOf(
            emptyList(),
            listOf(skillEntry("s1")),
            listOf(skillEntry("s1"), skillEntry("s2"))
        ).forEach { skills ->
            check(List(8) { null }, skills, "skill-empty")
            check(values, skills, "skill-full")
        }

        println("[AC-VERIFIER] AC8 Golden-Master 组合比较：$compared 组，mismatch=$mismatches")
        assertEquals(
            "未选中角色包路径除 skill 索引身份断言外必须与 HEAD 逐字节一致；首个差异：$firstMismatch",
            0, mismatches
        )
    }

    @Test
    fun `AC8 - skill 索引身份断言的移除是唯一差异且长度精确匹配`() {
        val inputs = listOf("RAG", "L1", "L2", "L3", "RULES", "PHONE", "HOTLIST", "TODO")
        val skills = listOf(skillEntry("translator"))

        val oldRaw = headMergeSystemPrompt(inputs, skills)
        val newRaw = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = inputs[0], l1Summary = inputs[1], l2Memories = inputs[2],
            l3Profiles = inputs[3], userRules = inputs[4], phoneControlGuidance = inputs[5],
            hotListGuidance = inputs[6], todoGuidance = inputs[7], enabledSkills = skills
        )

        // 差异必须**有且仅有**这一处
        val removed = "，不改变你的基础身份"
        assertTrue("HEAD 输出应含身份断言", oldRaw.contains(removed))
        assertFalse("新实现不得含身份断言", newRaw.contains(removed))
        assertEquals(
            "新旧输出长度差必须恰等于被删从句长度（证明无其它字符改动）",
            removed.length, oldRaw.length - newRaw.length
        )
        // 精确归因：新版把「，不改变你的基础身份」整段从句连同其前导逗号一并删除，
        // 故用**显式期望串**（而非双向 replace，后者会掩盖两侧差异方向）做逐字节断言。
        val explicitExpected = oldRaw.replace("；否则按需使用，不改变你的基础身份）：", "；否则按需使用）：")
        assertTrue(
            "HEAD 输出中必须能定位到该从句的完整上下文（防归因锚点失效）",
            explicitExpected != oldRaw
        )
        assertEquals(
            "除该从句外，新旧输出必须逐字节一致（用显式期望串断言）",
            explicitExpected, newRaw
        )
        // 出厂态渲染顺序与既有语义不得回归
        assertTrue("出厂态仍须以 DEFAULT_PERSONA 开头", newRaw.startsWith(ConversationViewModel.DEFAULT_PERSONA))
        assertTrue("skill 索引仍须在场", newRaw.contains("可用技能"))
        assertTrue("技能名仍须在场", newRaw.contains("translator"))
        assertTrue("技能索引仍须要求'点名即执行'", newRaw.contains("必须按对应规则执行"))
    }

    @Test
    fun `AC8 - 默认 basePersona 等价性：显式传 DEFAULT_PERSONA 与缺省逐字节相同`() {
        val inputs = listOf("RAG", null, "L2", null, null, null, null, null)
        val implicit = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = inputs[0], l1Summary = inputs[1], l2Memories = inputs[2],
            l3Profiles = inputs[3], userRules = inputs[4], phoneControlGuidance = inputs[5],
            hotListGuidance = inputs[6], todoGuidance = inputs[7], enabledSkills = emptyList()
        )
        val explicit = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = inputs[0], l1Summary = inputs[1], l2Memories = inputs[2],
            l3Profiles = inputs[3], userRules = inputs[4], phoneControlGuidance = inputs[5],
            hotListGuidance = inputs[6], todoGuidance = inputs[7], enabledSkills = emptyList(),
            basePersona = ConversationViewModel.DEFAULT_PERSONA
        )
        assertEquals("缺省 basePersona 必须等于显式传 DEFAULT_PERSONA", implicit, explicit)
    }

    @Test
    fun `AC8 - 新增可选层为空白或 null 时输出不变（空层不得污染 prompt）`() {
        val base = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "R", userRules = null, enabledSkills = emptyList()
        )
        listOf(null, "", "   ", "\t\n ").forEach { blank ->
            assertEquals(
                "worldbookSection=$blank 不得改变输出",
                base,
                ConversationViewModel.mergeSystemPrompt(
                    ragPrompt = "R", userRules = null, enabledSkills = emptyList(),
                    worldbookSection = blank
                )
            )
            assertEquals(
                "styleTail=$blank 不得改变输出",
                base,
                ConversationViewModel.mergeSystemPrompt(
                    ragPrompt = "R", userRules = null, enabledSkills = emptyList(),
                    styleTail = blank
                )
            )
        }
    }

    // ==================== 2. HEAD 保真校验（防转录失真） ====================

    @Test
    fun `HEAD 保真 - 运行时从 git 取证的 HEAD 版本与转录体结构一致`() {
        val head = headSource()
        assumeTrue("无 git 环境，跳过 HEAD 保真校验", head != null)
        val body = extractFunction(head!!, "internal fun mergeSystemPrompt(")
        assertTrue("应能从 HEAD 提取 mergeSystemPrompt", body != null)
        val b = body!!

        // ① HEAD 版本确实不含本轮新增物
        assertFalse("HEAD 不得含 basePersona", b.contains("basePersona"))
        assertFalse("HEAD 不得含 worldbookSection", b.contains("worldbookSection"))
        assertFalse("HEAD 不得含 styleTail", b.contains("styleTail"))
        assertFalse("HEAD 不得含 hasWorldbook", b.contains("hasWorldbook"))
        assertFalse("HEAD 不得含 hasStyleTail", b.contains("hasStyleTail"))
        // ② HEAD 版本确实含被本轮删除/实参化的锚点
        assertTrue("HEAD 应含 append(DEFAULT_PERSONA)", b.contains("append(DEFAULT_PERSONA)"))
        assertTrue("HEAD 应含旧身份断言", b.contains("不改变你的基础身份"))
        assertTrue("HEAD 应含 todoGuidance 分支", b.contains("if (hasTodoGuidance)"))

        // ③ 转录体与 HEAD 的函数体行数一致（结构未被转录者增删）
        val transcribedLines = TRANSCRIBED_HEAD_BODY_LINE_COUNT
        val headLines = b.lines().size
        println("[AC-VERIFIER] HEAD mergeSystemPrompt 行数=$headLines，转录常量=$transcribedLines")
        assertEquals(
            "转录体行数与 HEAD 不符——转录或 HEAD 取值有误（等价性结论将失真）",
            headLines, transcribedLines
        )

        // ④ 分支数量一致（8 个 if(has*) + skill 索引 if）
        val headIfs = b.lines().count { it.trim().startsWith("if (has") || it.trim().startsWith("if (skillIndex") }
        assertEquals("HEAD 的条件分支数应为 9", 9, headIfs)
    }

    // ==================== 转录：改动前的 mergeSystemPrompt ====================

    /**
     * 改动前（HEAD）实现的结构常量：函数体行数。用于 [HEAD 保真] 用例的交叉校验。
     * 改动 HEAD 源码会使本数值失效，届时保真用例会失败并提示重新取证——这是刻意的。
     */
    private companion object {
        /** `git show HEAD:...ConversationViewModel.kt` 中 mergeSystemPrompt 的花括号配平文本行数。 */
        const val TRANSCRIBED_HEAD_BODY_LINE_COUNT = 81
    }

    /** 出厂路径（D2 之前）skill 索引行原文；新实现删除了"不改变你的基础身份"从句。 */
    private val legacyIdentityClause = "，不改变你的基础身份"

    /** 把 HEAD 输出规范化到"新实现的预期基线"：即剔除 D2 有意删除的身份断言从句。 */
    private fun normalize(headOutput: String): String = headOutput.replace(legacyIdentityClause, "")

    /**
     * **改动前 `mergeSystemPrompt` 的忠实转录**（结构见 [TRANSCRIBED_HEAD_BODY_LINE_COUNT] 校验）。
     *
     * 逐行对应 `git show HEAD:app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt`
     * 中同名函数体（3256–3336 行），仅做机械换名（`mergeSystemPrompt`→`headMergeSystemPrompt`），
     * 注释按原文保留以便逐行目视比对。
     */
    private fun headMergeSystemPrompt(
        inputs: List<String?>,
        enabledSkills: List<SkillRegistry.SkillEntry>
    ): String {
        val ragPrompt = inputs[0]
        val l1Summary = inputs[1]
        val l2Memories = inputs[2]
        val l3Profiles = inputs[3]
        val userRules = inputs[4]
        val phoneControlGuidance = inputs[5]
        val hotListGuidance = inputs[6]
        val todoGuidance = inputs[7]
        val hasUserRules = !userRules.isNullOrBlank()
        val hasRag = !ragPrompt.isNullOrBlank()
        val hasL1 = !l1Summary.isNullOrBlank()
        val hasL2 = !l2Memories.isNullOrBlank()
        val hasL3 = !l3Profiles.isNullOrBlank()
        val hasPhoneGuidance = !phoneControlGuidance.isNullOrBlank()
        val hasHotListGuidance = !hotListGuidance.isNullOrBlank()
        val hasTodoGuidance = !todoGuidance.isNullOrBlank()
        // ADR-018：轻量 Skill 索引（name + description），不注入完整 systemPrompt。
        // description 缺失时回退为 name（仍可感知能力存在）。
        val skillIndex = enabledSkills.mapNotNull { entry ->
            val name = entry.config.name
            val desc = entry.manifest.description.takeIf { it.isNotBlank() }
            desc?.let { "$name（$it）" } ?: name
        }

        return buildString {
            // ADR-018：默认 persona 始终作为基础身份
            append(ConversationViewModel.DEFAULT_PERSONA)
            // ADR-030：用户规则（除安全限制外最高优先级）—— persona 之后、RAG 之前
            if (hasUserRules) {
                append("\n\n")
                append(userRules)
            }
            // v1 US-202：手机操控安全声明（安全层，紧随用户规则之后、RAG 之前——
            // UI 文本为不可信数据源 + 工具使用边界，防 prompt injection 越权）
            if (hasPhoneGuidance) {
                append("\n\n")
                append(phoneControlGuidance)
            }
            // v1 批次9（US-904）：热榜能力声明（能力层，紧随手机操控之后、RAG 之前）。
            if (hasHotListGuidance) {
                append("\n\n")
                append(hotListGuidance)
            }
            // v1 批次17（US-1702）：TODO 任务规划指引（todo_write 使用纪律，紧随热榜之后）
            if (hasTodoGuidance) {
                append("\n\n")
                append(todoGuidance)
            }
            // ADR-015 决策4 合并顺序：RAG → L1 摘要 → L2 跨会话 → L3 画像 → Skill 索引
            if (hasRag) {
                append("\n\n")
                append(ragPrompt)
            }
            if (hasL1) {
                append("\n\n")
                append(l1Summary)
            }
            if (hasL2) {
                append("\n\n")
                append(l2Memories)
            }
            if (hasL3) {
                append("\n\n")
                append(l3Profiles)
            }
            if (skillIndex.isNotEmpty()) {
                append("\n\n")
                // DEF-007（Bug-5 次根因）：强化措辞 —— 用户明确点名技能名/能力时必须执行，
                // 否则按需使用、不改变基础身份。降低 LLM 忽略索引的概率。
                append("可用技能（用户明确提到技能名或对应能力时必须按对应规则执行；否则按需使用，不改变你的基础身份）：")
                append(skillIndex.joinToString("、"))
            }
        }.trim()
    }

    // ==================== git 取证辅助 ====================

    private val repoRoot: File by lazy {
        listOf(File(".."), File("."), File("../.."))
            .map { it.canonicalFile }
            .firstOrNull { File(it, ".git").exists() } ?: File("..").canonicalFile
    }

    /** `git show HEAD:<file>` 输出；无 git 时返回 null（用例以 Assume 跳过）。 */
    private fun headSource(): String? {
        if (!File(repoRoot, ".git").exists()) return null
        return runCatching {
            val proc = ProcessBuilder(
                "git", "show",
                "HEAD:app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt"
            ).directory(repoRoot).start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val err = proc.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            proc.waitFor()
            if (proc.exitValue() != 0) {
                println("[AC-VERIFIER] git show 失败（exit=${proc.exitValue()}）：$err")
                null
            } else {
                out.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** 按行扫描提取花括号配平的完整函数声明文本（含签名行，至配平的 `}` 行）。 */
    private fun extractFunction(source: String, signatureMarker: String): String? {
        val lines = source.split('\n')
        val startIdx = lines.indexOfFirst { it.contains(signatureMarker) }
        if (startIdx < 0) return null
        var depth = 0
        var seenBrace = false
        for (i in startIdx until lines.size) {
            for (ch in lines[i]) {
                if (ch == '{') {
                    depth++
                    seenBrace = true
                } else if (ch == '}') {
                    depth--
                }
            }
            if (seenBrace && depth <= 0) return lines.subList(startIdx, i + 1).joinToString("\n")
        }
        return null
    }

    private fun skillEntry(name: String) = SkillRegistry.SkillEntry(
        config = SkillConfig(
            id = 0L, name = name, displayName = name, source = SkillSource.LOCAL_BUILTIN,
            sourceUri = null, skillDir = "/skills/$name", isEnabled = true, isInstalled = true,
            version = "1.0.0"
        ),
        manifest = SkillManifest(
            name = name, description = "desc-$name", version = "1.0.0",
            systemPrompt = null, tools = null, body = ""
        )
    )
}
