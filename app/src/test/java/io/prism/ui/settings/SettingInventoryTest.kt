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
        // 拆分前后仍应存在的设置行标题（逐个核对自 v1.1 源码）。\n        // UI v2 按设计稿**有意删除**两行：\n        //  - 「风险告知」：与总开关弹层重复，改为开启时弹一次（ADR-079 D3）；\n        //  - 「图片消息」：静态说明行，不承载任何操作（ADR-079 D3）。\n        // 这两行由下面的 `removed rows stay removed` 用例反向钉住。
        val titles = listOf(
            "Provider 配置", "API Key", "深度思考", "AI 行为偏好",
            "云端视觉旁路", "自动旁路",
            "无障碍自动恢复", "无障碍服务", "高危操作拦截", "后台操控（执行面）",
            "隐藏执行面", "观看 AI 操作（Watch）", "Shizuku（后台化增强）", "工具权限",
            "QQ 小号助手", "传输通道", "协议端连接", "连接测试",
            "定时消息联系人", "随机定时主动消息", "双向对话（自动回复）", "活跃时段",
            "每日消息上限", "熄屏时点亮屏幕执行", "定时任务最近状态", "历史上下文条数",
            "注入 Prism 记忆", "拟人节奏（思考 + 打字延迟）", "聊天里可以调用 Prism 能力",
            "允许她在聊天里操作手机", "允许她写日记", "随机策略", "已熔断",
            "搜索引擎增强", "WebView 渲染抓取", "性能档位", "关于 Prism", "角色包",
            "语音热词", "语音引擎", "角色包", "桌宠",
            "显示桌宠", "需要「显示在其他应用上层」权限",
            "搜索增强", "设备档位"
        )
        val missing = titles.filterNot { src.contains(it) }
        assertTrue(
            "以下设置项在拆分中丢失：$missing",
            missing.isEmpty()
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





