package io.prism.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设置项清单守卫（ADR-079 D6）。
 *
 * 背景：UI v2 把设置从 47 行平铺拆成「6 个入口 + 详情页」。这类重构最常见的失效形态是
 * **某个设置项在拆分时被漏掉**——编译照过、单测照绿、用户点进去发现功能消失。
 *
 * 本测试用源码级断言钉住三件事：
 * 1. [SettingsSection] 恰好 6 个分区，且每个分区都在 `SettingsScreen` 的过滤里出现；
 * 2. 拆分前存在的 47 个设置行标题，全部仍出现在 `SettingsScreen.kt` 中；
 * 3. UI 源文件里不再出现 emoji 图标与全角感叹号（文案规范，ADR-079 D3）。
 *
 * 与项目既有 `QqTermuxUnstopWiringTest` 同款做法：测"装配"而不是"行为"，
 * 因为这类失效恰恰是"代码写好了但没人调用/某一行被删了"。
 */
class SettingInventoryTest {

    private val screenFile = File(
        "src/main/java/io/prism/ui/settings/SettingsScreen.kt"
    )

    private fun source(): String {
        assertTrue("找不到 SettingsScreen.kt，测试工作目录应为 app/", screenFile.exists())
        return screenFile.readText()
    }

    @Test
    fun `settings has exactly six sections`() {
        assertEquals("设置分区数量应为 6（ADR-079 D2）", 6, SettingsSection.entries.size)
        assertEquals(
            listOf("模型与密钥", "助手行为", "手机操控", "QQ 小号", "语音与形象", "网络、设备与关于"),
            SettingsSection.entries.map { it.title }
        )
    }

    @Test
    fun `every section is wired into a filter branch`() {
        val src = source()
        SettingsSection.entries.forEach { section ->
            assertTrue(
                "分区 ${section.name} 没有对应的过滤分支——它的设置项在详情页不可达",
                src.contains("section == SettingsSection.${section.name}")
            )
        }
    }

    @Test
    fun `all setting rows survived the split`() {
        val src = source()
        // **断言必须绑定到"行渲染点"，不是任意 `title = "X"`**（guardrail R1 B2-2 + R2 复核）：
        // 第一版用 `src.contains(title)`（子串）——注释/状态串就能命中；
        // 第二版改用 `title = "X"` —— 仍被**弹层同名标题**兜底：实测 12 条盲区
        // （深度思考 / API Key / AI 行为偏好 / 工具权限 / 桌宠形象 / 大小 / 不透明度 /
        //  表情回正 / 搜索引擎增强 / 首选引擎 / 性能档位 / 开源许可），删掉渲染行测试照样绿。
        // 现在收紧为：`SetRow(` 或 `PrismRow(` 紧跟的 title 字面量（允许跨行/缩进）。
        //
        // 2026-11-21 四区重建后的标题以**设计稿**为准（长名改为设计稿短名）：
        //  「后台操控（执行面）」→「后台操控」、「观看 AI 操作（Watch）」→「观看执行」、
        //  「Shizuku（后台化增强）」→「Shizuku」、「双向对话（自动回复）」→「双向对话」、
        //  「拟人节奏（思考 + 打字延迟）」→「拟人节奏」、「协议端连接」→「协议端地址」、
        //  「定时任务最近状态」→「上次执行」（kv 卡片）。
        //  **「语音引擎」入口行已删除**：设计稿是单页四组直接铺开，不再有折叠入口（ADR-079 D2）。
        val rowTitles = listOf(
            // 模型与密钥
            "深度思考", "API Key", "自定义请求头",
            // 助手行为
            "AI 行为偏好", "云端视觉旁路", "自动旁路",
            // 手机操控（顺序与设计稿一致）
            "无障碍服务", "无障碍自动恢复", "高危操作拦截", "工具权限",
            "后台操控", "隐藏执行面", "观看执行", "Shizuku", "后台保活",
            // QQ 主页面
            "传输通道", "协议端地址", "连接测试",
            "定时与主动消息", "自动回复", "表情库",
            // QQ 子页 · 定时
            "随机定时主动消息", "活跃时段", "每日消息上限", "每日定时上限", "最小间隔",
            "随机策略", "熄屏时点亮屏幕执行", "熔断状态",
            // QQ 子页 · 回复
            "双向对话", "定时消息联系人", "历史上下文条数", "注入 Prism 记忆", "拟人节奏",
            "聊天里可以调用 Prism 能力", "允许她在聊天里操作手机", "允许她写日记",
            // 语音（单页四组）
            "语音热词", "本地音色档位", "播报方式", "角色包", "桌宠",
            // 角色与桌宠子页
            "显示桌宠", "需要「显示在其他应用上层」权限", "桌宠形象", "大小", "不透明度",
            "表情回正", "待机小动作", "减少动效", "显示对话气泡", "低功耗模式", "出厂默认助手",
            // 网络、设备与关于
            "搜索引擎增强", "首选引擎", "WebView 渲染抓取", "性能档位", "版本", "开源许可"
        )
        val missingRows = rowTitles.filterNot { title ->
            Regex(
                """(SetRow|PrismRow)\(\s*(?:\n\s*)?title\s*=\s*"${Regex.escape(title)}""""
            ).containsMatchIn(src)
        }
        assertTrue(
            "以下设置行在重建中丢失（无 SetRow/PrismRow 的 title 渲染点）：$missingRows",
            missingRows.isEmpty()
        )
        assertTrue("行标题清单不应为空（防止误删断言本身）", rowTitles.size >= 50)

        // 危险项是 `PrismDangerRow(text = …)`，不是 `title =` —— 单独断言，
        // 否则会被「关闭小号助手」**确认弹层标题**伪命中（R2 实测）。
        assertTrue(
            "「关闭小号助手」危险行丢失（应有 PrismDangerRow(text = \"关闭小号助手\")）",
            src.contains("PrismDangerRow(") && src.contains("text = if (qqConfig.enabled) \"关闭小号助手\"")
        )
        assertTrue(
            "「删除这个端点」危险行丢失",
            src.contains("text = \"删除这个端点\"")
        )
    }

    @Test
    fun `group labels and kv cards survived`() {
        val src = source()
        // 分组标签（`SetSection`）与 kv 卡片标题也是信息架构的一部分：
        // 组标签丢了会让 20+ 行变成无层级平铺（正是上一轮被判定"与设计稿不符"的形态之一）。
        val sections = listOf(
            "权限与安全", "执行面", "连接", "消息", "保护", "最近状态",
            "主动消息", "执行结果", "回复", "上下文", "能力",
            "识别", "播报", "本地音色包", "角色", "桌宠",
            "搜索", "设备", "关于", "角色包", "按语境挑选"
        )
        val missingSections = sections.filterNot { src.contains("SetSection(\"$it\")") }
        assertTrue("以下分组标签丢失：$missingSections", missingSections.isEmpty())

        // 每组标签都必须**后接内容**（R2 指出的盲区：空分组也能过）。
        // 判据：标签之后到**下一个组标签**之间，至少出现过一条渲染调用（行/卡片/选项/子区块）。
        // 允许的渲染调用面较宽（语音区用的是 VoiceEngineOptionRow，桌宠区是独立 Section 组件），
        // 因此用「白名单 + 最小间距」双条件，而不是只找 `SetRow(`。
        val renderCalls = listOf(
            "SetRow(", "PrismRow(", "PrismKv(", "PrismCardBox(", "PrismOptionRow(",
            "VoiceEngineOptionRow(", "VoicePackRow(", "PetAppearanceRow(", "RolePackRow(",
            "VoiceEngineSection(", "RolePackSection(", "PetSection(", "item ", "item{"
        )
        val emptySections = sections.filter { name ->
            val idx = src.indexOf("SetSection(\"$name\")")
            if (idx < 0) return@filter true
            val next = src.indexOf("SetSection(", idx + 1)
            val end = if (next < 0) src.length else next
            val window = src.substring(idx, end)
            !renderCalls.any { window.contains(it) } || window.length < 80
        }
        assertTrue("以下分组标签后面没有内容（空分组）：$emptySections", emptySections.isEmpty())

        // QQ「最近状态」与「执行结果」用 kv 卡片承载真实结果
        assertTrue("「上次执行」kv 卡片丢失", src.contains("key = \"上次执行\""))
        assertTrue("「上次」kv 卡片丢失（定时子页）", src.contains("key = \"上次\""))
    }

    @Test
    fun `four sub pages are wired`() {
        val src = source()
        // ADR-079 D2 承诺的 QQ 四子页 + 语音子页（2026-11-21 落地）：
        // 每个子页必须有枚举项、标题、进入点，**以及真正的渲染分支**
        // （R2 盲区：只断言 `SettingsSubPage.X` 时，删掉 `if (subPage == X)` 渲染块不红）。
        listOf(
            "QQ_SCHEDULE" to "定时与主动消息",
            "QQ_REPLY" to "自动回复",
            "QQ_STICKERS" to "表情库",
            "VOICE_PERSONA" to "角色与桌宠"
        ).forEach { (name, title) ->
            assertTrue("子页 $name 缺少枚举定义", src.contains("$name(\"$title\")"))
            assertTrue(
                "子页 $name 没有进入点（subPage = SettingsSubPage.$name）",
                src.contains("subPage = SettingsSubPage.$name")
            )
            assertTrue(
                "子页 $name 没有渲染分支（if (subPage == SettingsSubPage.$name)）",
                src.contains("subPage == SettingsSubPage.$name")
            )
        }
        // 返回链：子页 → 分区首页 → 设置首页（BackHandler 必须走 backStep）
        assertTrue("详情页返回键没有走 backStep（子页返回会直接退出分区）", src.contains("BackHandler { backStep() }"))
        // 子页状态必须随分区复位，否则从 QQ 子页切到系统页会保留旧标题
        assertTrue("subPage 未随 section 复位（remember(section)）", src.contains("remember(section) { mutableStateOf<SettingsSubPage?>(null) }"))
    }

    @Test
    fun `settings rows force a single status line`() {
        val src = source()
        // 设计稿 `.row-status` 是 `white-space: nowrap`（app.css:261-268）：每行只有一行状态。
        // 这条断言防的是"又把实现说明塞回副标题、行高被撑到 100px+"（2026-11-21 真机根因）。
        assertTrue(
            "SetRow 未固定 statusMaxLines = 1（多行状态会把 56dp 行撑到 100px+）",
            src.contains("statusMaxLines = 1")
        )
        // **反例守卫必须禁止"除 1 以外的任何取值"**（R3：只禁 2 时改成 3 仍绿）。
        // 允许的例外只有 PrismKv 的 maxLines（诊断/失败原因那几处），它们不经过 SetRow。
        val badMaxLines = Regex("""statusMaxLines\s*=\s*(?!1\b)\d+""").findAll(src).toList()
        assertTrue(
            "SetRow 出现 statusMaxLines 非 1 的取值（应只允许 1）：${badMaxLines.map { it.value }}",
            badMaxLines.isEmpty()
        )
    }

    @Test
    fun `api key fields are gated before save`() {
        val src = source()
        // BR-security-006 的"空值 = 删除"语义 + 异步回填 = 静默删 Key 事故。
        // 三处 Key 编辑入口（语音 VoiceKeyField / API Key 弹层 / 搜索弹层）都必须有门控。
        //
        // **R3 教训**：只断言 `src.contains("enabled = loaded")` 时，删掉 ApiKeySheet 那处
        // 仍会被 VoiceKeyField 满足 → 用例名不副实。这里按**函数体切片**分别断言。
        fun bodyOf(anchor: String): String {
            val start = src.indexOf(anchor)
            if (start < 0) return ""
            // 取 anchor 之后到下一个顶层 fun 定义之间的片段（够覆盖该 Composable 的 body）
            val nextFun = Regex("""\n@Composable\nprivate fun """).find(src, start + anchor.length)
            val end = nextFun?.range?.first ?: src.length
            return src.substring(start, end)
        }

        val apiKeySheet = bodyOf("private fun ApiKeySheet(")
        assertTrue("找不到 ApiKeySheet 函数体（测试失效，必须修）", apiKeySheet.isNotBlank())
        assertTrue(
            "ApiKeySheet 的保存按钮缺少 loaded 门控（打开即保存会静默删 Key）",
            apiKeySheet.contains("enabled = loaded")
        )
        assertTrue(
            "ApiKeySheet 的保存文案缺少「读取中…」态",
            apiKeySheet.contains("读取中…")
        )
        assertTrue(
            "ApiKeySheet 未用 LaunchedEffect 回填 Key（组合期读会重复/竞态）",
            apiKeySheet.contains("LaunchedEffect(config.apiKeyRef)")
        )

        val voiceKey = bodyOf("private fun VoiceKeyField(")
        assertTrue("找不到 VoiceKeyField 函数体（测试失效，必须修）", voiceKey.isNotBlank())
        assertTrue(
            "VoiceKeyField 缺少 loaded 门控",
            voiceKey.contains("enabled = loaded")
        )
        assertTrue(
            "VoiceKeyField 未用 LaunchedEffect 回填 Key",
            voiceKey.contains("LaunchedEffect(keyRef)")
        )

        val searchSheet = bodyOf("private fun SearchEnhancementSheet(")
        assertTrue(
            "搜索弹层缺少 keysReady 门控（R5-M-02）",
            searchSheet.contains("keysReady && !saving")
        )

        // 三处都不允许在组合体里裸调 loadApiKey（必须是 LaunchedEffect）。
        // 正则放宽为「任意未加载标志名」，覆盖 `!loaded` / `!apiKeyLoaded` 等旧写法（R3 反证）。
        val bareCompositionLoad = Regex(
            """\n\s{4,}if \(!\w*[Ll]oaded\w*\) \{\s*\n(\s*//[^\n]*\n)*\s*viewModel\.loadApiKey"""
        ).containsMatchIn(src)
        assertTrue(
            "仍有字段在组合期直接调用 loadApiKey（应改为 LaunchedEffect）",
            !bareCompositionLoad
        )
    }

    @Test
    fun `choice sheets always offer a cancel path`() {
        val src = source()
        // 遮罩是不可见的裸 clickable（PrismSheetHost 无 contentDescription）：
        // 只靠点遮罩 = TalkBack 用户关不上（guardrail R2-6-5 / R3 复核）。
        // 因此每个 PrismChoiceSheet 调用点都必须传 onDismiss（组件据此渲染「取消」）。
        val callSites = Regex("""PrismChoiceSheet\(""").findAll(src).count()
        val dismisses = Regex("""onDismiss\s*=\s*\{""").findAll(src).count()
        assertTrue("PrismChoiceSheet 调用点数量异常（$callSites）", callSites >= 4)
        assertTrue(
            "PrismChoiceSheet 调用点 $callSites 个，但 onDismiss 只有 $dismisses 处（缺「取消」按钮）",
            dismisses >= callSites
        )
    }

    @Test
    fun `removed rows stay removed`() {
        val src = source()
        listOf("风险告知", "图片消息").forEach { title ->
            assertTrue(
                "「$title」应已按设计稿删除（ADR-079 D3），但仍作为设置行存在",
                !src.contains("title = \"$title\"")
            )
        }
    }

    @Test
    fun `qq settings title uses the short form after rename`() {
        // 首页入口叫「QQ 小号」，详情页分区标题也叫「QQ 小号」；
        // 旧的「QQ 小号助手」仅允许作为详情页内的分组标题保留。
        assertTrue(SettingsSection.QQ.title == "QQ 小号")
    }

    @Test
    fun `settings rows no longer render emoji icons`() {
        val src = source()
        // UI v2 的 SetRow 已不渲染图标；断言渲染路径里没有把 icon 画出来的语句。
        assertTrue(
            "SetRow 仍在渲染 icon 字面量（ADR-079 D3 要求去图标）",
            !src.contains("Text(text = icon")
        )
        // R2-M1（guardrail 复核）：icon/iconColor 形参已整体删除，
        // 因此断言改为「不再出现这两个参数」——而不是断言抑制注解存在。
        assertTrue(
            "SetRow 仍保留 icon/iconColor 形参（ADR-079 D3 要求去图标）",
            !src.contains("private fun SetRow(\n    icon")
        )
        assertTrue(
            "设置页仍在传 icon 参数（ADR-079 D3 要求去图标）",
            !Regex("(?m)^\\s*icon\\s*=\\s*\"").containsMatchIn(src)
        )
    }

    @Test
    fun `no full-width exclamation in new ui copy`() {
        val files = listOf(
            "src/main/java/io/prism/ui/settings/SettingsScreen.kt",
            "src/main/java/io/prism/ui/chat/ConversationScreen.kt",
            "src/main/java/io/prism/voice/VoiceCallOverlay.kt",
            "src/main/java/io/prism/ui/conversationlist/ConversationListScreen.kt"
        )
        files.forEach { rel ->
            val f = File(rel)
            if (!f.exists()) return@forEach
            val bad = f.readLines().withIndex().filter { (_, line) ->
                line.contains("！") && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*")
            }
            assertTrue(
                "$rel 出现全角感叹号（ADR-079 D3 禁感叹号）：${bad.take(3).map { it.index + 1 }}",
                bad.isEmpty()
            )
        }
    }
}
