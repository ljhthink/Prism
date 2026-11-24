package io.prism.phonecontrol.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ADR-065（US-3114）：执行面后端抽象 / flag 阶梯 / 降级顺序纯逻辑单测（JVM）。
 *
 * 真实建屏、截图、输入注入依赖 shell 进程与真机（见 docs/reports/2026-10-21-qq-hidden-vd-poc.md），
 * 此处固化不依赖 Android framework 的决策逻辑，防止实现漂移。
 */
class DisplayBackendTest {

    // ==================== flag 阶梯（与 PoC / HiddenDisplayFlags 单一来源） ====================

    @Test
    fun `完整 flag 组合与 PoC 真机验证值一致 0x1FEC1`() {
        assertEquals(0x1FEC1, HiddenDisplayFlags.FULL)
    }

    @Test
    fun `最小基座 - PUBLIC+TOUCH+ROTATES+SYSTEM_DECORATIONS`() {
        assertEquals(0x2C1, HiddenDisplayFlags.BASE)
        assertEquals(1, HiddenDisplayFlags.BASE and HiddenDisplayFlags.PUBLIC)
        assertEquals(0, HiddenDisplayFlags.BASE and HiddenDisplayFlags.TRUSTED)
    }

    @Test
    fun `阶梯首两级必须是非镜像 + TRUSTED（2026-11-22 逃逸回归）`() {
        val ladder = HiddenDisplayFlags.LADDER
        assertEquals(7, ladder.size)
        // ① 首选：非镜像 + 全能力 + TRUSTED
        assertEquals(HiddenDisplayFlags.FULL_OWN, ladder[0])
        assertEquals(HiddenDisplayFlags.OWN_CONTENT_ONLY, ladder[0] and HiddenDisplayFlags.OWN_CONTENT_ONLY)
        assertEquals(HiddenDisplayFlags.TRUSTED, ladder[0] and HiddenDisplayFlags.TRUSTED)
        // FULL_OWN 不应含 DEVICE_DISPLAY_GROUP（与 OWN_DISPLAY_GROUP 互斥）
        assertEquals(0, ladder[0] and HiddenDisplayFlags.DEVICE_DISPLAY_GROUP)
        // ② 次选：非镜像 + 最小 + TRUSTED
        assertEquals(HiddenDisplayFlags.TRUSTED_OWN, ladder[1])
        assertEquals(HiddenDisplayFlags.OWN_CONTENT_ONLY, ladder[1] and HiddenDisplayFlags.OWN_CONTENT_ONLY)
        assertEquals(HiddenDisplayFlags.TRUSTED, ladder[1] and HiddenDisplayFlags.TRUSTED)
    }

    @Test
    fun `阶梯顺序不变量 - 非镜像组必须先于镜像组（避开 CAPTURE、不投影主屏）`() {
        // 一级排序（不可交换）：非镜像（含 OWN_CONTENT_ONLY）全部在镜像之前。
        // 理由：镜像屏在 Android 13 要求 CAPTURE_VIDEO_OUTPUT（必然失败）；
        // 且即使授予，镜像屏会投影**用户主屏内容**并接收注入 —— 比"能跑但 untrusted"更危险。
        val ladder = HiddenDisplayFlags.LADDER
        val lastOwnContent = ladder.indexOfLast {
            (it and HiddenDisplayFlags.OWN_CONTENT_ONLY) != 0
        }
        val firstMirroring = ladder.indexOfFirst {
            (it and HiddenDisplayFlags.OWN_CONTENT_ONLY) == 0
        }
        assertTrue("阶梯里必须至少有一个非镜像变体", lastOwnContent >= 0)
        assertTrue(
            "镜像变体（index=$firstMirroring）排在非镜像变体（最后 index=$lastOwnContent）之前",
            firstMirroring == -1 || firstMirroring > lastOwnContent
        )
    }

    @Test
    fun `阶梯顺序不变量 - 同一镜像分组内 trusted 必须先于 untrusted`() {
        // 二级排序（2026-11-22 回归守卫）：旧顺序在非镜像组内把 untrusted 的 BASE_OWN
        // 排在 TRUSTED_OWN 之前 → 先成功的那一个恰好少了 TRUSTED → 目标 App 的页面跳转
        // 被系统放回主屏（拼多多首页 → 商品详情）。
        val ladder = HiddenDisplayFlags.LADDER
        listOf(
            "非镜像组" to ladder.filter { (it and HiddenDisplayFlags.OWN_CONTENT_ONLY) != 0 },
            "镜像组" to ladder.filter { (it and HiddenDisplayFlags.OWN_CONTENT_ONLY) == 0 }
        ).forEach { (groupName, group) ->
            if (group.isEmpty()) return@forEach
            val lastTrusted = group.indexOfLast { HiddenDisplayFlags.isTrusted(it) }
            val firstUntrusted = group.indexOfFirst { !HiddenDisplayFlags.isTrusted(it) }
            assertTrue(
                // 只在断言失败时展示，因此描述的是**失败态**：untrusted 出现在 trusted 之前
                "$groupName：untrusted（index=$firstUntrusted）出现在 trusted（最后 index=$lastTrusted）之前" +
                    "——后台会在应用内跳转时逃逸到主屏",
                firstUntrusted == -1 || lastTrusted == -1 || firstUntrusted > lastTrusted
            )
        }
        // 具体钉住首选项：非镜像组的第一项必须是 trusted
        assertTrue(
            "非镜像组的首选必须是 TRUSTED（否则仍会逃逸）",
            HiddenDisplayFlags.isTrusted(ladder.first { (it and HiddenDisplayFlags.OWN_CONTENT_ONLY) != 0 })
        )
    }

    @Test
    fun `isTrusted 纯函数真值表`() {
        assertTrue(HiddenDisplayFlags.isTrusted(HiddenDisplayFlags.TRUSTED))
        assertTrue(HiddenDisplayFlags.isTrusted(HiddenDisplayFlags.FULL))
        assertTrue(HiddenDisplayFlags.isTrusted(HiddenDisplayFlags.FULL_OWN))
        assertTrue(HiddenDisplayFlags.isTrusted(HiddenDisplayFlags.TRUSTED_OWN))
        assertFalse(HiddenDisplayFlags.isTrusted(HiddenDisplayFlags.BASE))
        assertFalse(HiddenDisplayFlags.isTrusted(HiddenDisplayFlags.BASE_OWN))
        // 真机实测的 untrusted 组合（0x2c9）必须被判为 false
        assertFalse(HiddenDisplayFlags.isTrusted(0x2c9))
    }

    // ==================== 后端能力与降级顺序 ====================

    @Test
    fun `隐藏后端 - 主屏不可见且未创建时无尺寸无错误`() {
        val backend = RemoteShellBackend()
        assertEquals(DisplayBackend.Kind.HIDDEN, backend.kind)
        assertFalse("隐藏后端不得声明主屏可见", backend.visibleOnMainScreen)
        assertNull(backend.frameSize())
        assertNull(backend.lastError())
    }

    @Test
    fun `兼容后端 - 明示主屏可能出现小窗（UI 诚实说明依据）`() {
        assertEquals(DisplayBackend.Kind.LEGACY, OverlayLegacyBackend.kind)
        assertTrue("legacy 后端必须声明主屏可能可见", OverlayLegacyBackend.visibleOnMainScreen)
    }

    @Test
    fun `backendPlan - 开关开且 Shizuku 就绪时隐藏优先、legacy 兜底`() {
        assertEquals(
            listOf(DisplayBackend.Kind.HIDDEN, DisplayBackend.Kind.LEGACY),
            VirtualDisplayManager.backendPlan(hiddenEnabled = true, shizukuReady = true)
        )
    }

    @Test
    fun `backendPlan - 开关关或 Shizuku 未就绪时直接 legacy（不白等失败重绑定）`() {
        assertEquals(
            listOf(DisplayBackend.Kind.LEGACY),
            VirtualDisplayManager.backendPlan(hiddenEnabled = false, shizukuReady = true)
        )
        assertEquals(
            listOf(DisplayBackend.Kind.LEGACY),
            VirtualDisplayManager.backendPlan(hiddenEnabled = true, shizukuReady = false)
        )
        assertEquals(
            listOf(DisplayBackend.Kind.LEGACY),
            VirtualDisplayManager.backendPlan(hiddenEnabled = false, shizukuReady = false)
        )
    }

    // ==================== 文案分流（guardrail 2026-11-22 B2）====================

    @Test
    fun `untrusted 执行面的诊断文案不得声称 trusted`() {
        // 真机回归（2026-11-22）：阶梯③ BASE_OWN(untrusted) 命中时，旧诊断硬编码
        // 「隐藏虚拟屏（trusted，主屏无小窗）」，等于对 LLM 谎报 —— 它会在错误前提上重试。
        val src = File("src/main/java/io/prism/phonecontrol/PhoneControlLocalToolExecutor.kt")
        assertTrue("找不到 executor 源码，测试工作目录应为 app/", src.exists())
        val codeOnly = src.readLines()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertTrue(
            "隐藏后端诊断必须按 hiddenDisplayTrusted 分流（否则 untrusted 时仍谎报 trusted）",
            codeOnly.contains("VirtualDisplayManager.hiddenDisplayTrusted")
        )
        assertTrue(
            "untrusted 分支必须明确告知「页面跳转会被放回主屏」",
            codeOnly.contains("未获系统信任（untrusted）")
        )
    }

    @Test
    fun `设置页 note 必须按 FALLBACK 与 DEGRADED 分流`() {
        // 「隐藏屏已建成但缺 TRUSTED」不是回落；旧实现一律渲染「已回落兼容模式 · $note」，
        // 自相矛盾（guardrail B2）。
        val src = File("src/main/java/io/prism/ui/settings/SettingsScreen.kt")
        assertTrue("找不到设置页源码，测试工作目录应为 app/", src.exists())
        val text = src.readText()
        assertTrue(
            "设置页未按 BackendNoteKind.DEGRADED 分流文案",
            text.contains("BackendNoteKind.DEGRADED")
        )
        assertTrue(
            "设置页仍未读取 lastBackendNoteKind（kind 永远是默认值）",
            text.contains("lastBackendNoteKind")
        )
    }
}
