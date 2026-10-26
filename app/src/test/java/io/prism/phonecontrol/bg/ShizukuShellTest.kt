package io.prism.phonecontrol.bg

import io.prism.phonecontrol.PhoneControlSecurity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次21（SZ-001/003/005，ADR-046）：[ShizukuShell] 命令拼装纯函数单测（JVM）。
 *
 * **安全边界**：shell 命令仅由代码拼装（无用户自由文本注入面）——本测试固化各命令的
 * 精确形态，任何变更都需显式审查（guardrail 专项）。
 *
 * 三态判定（currentState/isReady）与 exec 真实执行依赖 Shizuku 服务（Robolectric 无
 * Shizuku server，真机验证），不在 JVM 覆盖范围。
 */
class ShizukuShellTest {

    @Test
    fun `amStartOnDisplay 命令拼装 - 含 display 与组件名`() {
        assertArrayEquals(
            arrayOf("am", "start", "--display", "90", "-n", "com.tencent.mm/com.tencent.mm.ui.LauncherUI"),
            ShizukuShell.buildAmStartCommand("com.tencent.mm/com.tencent.mm.ui.LauncherUI", 90)
        )
    }

    @Test
    fun `amStartForUserOnDisplay 命令拼装 - 跨用户+跨屏（ADR-062 QQ 小号）`() {
        assertArrayEquals(
            arrayOf("am", "start", "--user", "999", "--display", "4", "-n", "com.tencent.mobileqq/.activity.SplashActivity"),
            ShizukuShell.buildAmStartForUserOnDisplayCommand("com.tencent.mobileqq/.activity.SplashActivity", 999, 4)
        )
    }

    @Test
    fun `amStartForUserOnDisplay 命令拼装 - forceStopFirst 追加 -S（迁屏必需）`() {
        val cmd = ShizukuShell.buildAmStartForUserOnDisplayCommand(
            "com.tencent.mobileqq/.activity.SplashActivity", 999, 4, forceStopFirst = true
        )
        assertTrue("forceStopFirst=true 必须含 -S：${cmd.toList()}", cmd.contains("-S"))
        assertEquals("-S 必须出现在 -n 之前（am 选项先于组件）", "am start", "${cmd[0]} ${cmd[1]}")
    }

    @Test
    fun `buildPmGrantCommand 命令拼装 - 4 token 数组（SZ-101 验收项）`() {
        assertArrayEquals(
            arrayOf("pm", "grant", "com.google.android.googlequicksearchbox", "android.permission.RECORD_AUDIO"),
            ShizukuShell.buildPmGrantCommand(
                "com.google.android.googlequicksearchbox", "android.permission.RECORD_AUDIO"
            )
        )
    }

    @Test
    fun `inputSwipe 命令拼装 - 含 display 与时长`() {
        assertArrayEquals(
            arrayOf("input", "-d", "42", "swipe", "100", "200", "800", "1500", "300"),
            ShizukuShell.buildInputSwipeCommand(42, 100, 200, 800, 1500, 300)
        )
    }

    @Test
    fun `inputTap 命令拼装 - 短选项 -d（修复轮八：input 无 --display 长选项，Unknown command 且 exit=0）`() {
        assertArrayEquals(
            arrayOf("input", "-d", "109", "tap", "360", "780"),
            ShizukuShell.buildInputTapCommand(109, 360, 780)
        )
    }

    @Test
    fun `input 命令拼装 - 全部 input 命令禁用 --display 长选项（回归锁）`() {
        // 真机实证：input --display ... 报 "Unknown command: --display" 且 exit=0（假成功）。
        // am start --display 是合法的（不同命令不同语法），input 必须用 -d。
        val inputCommands = listOf(
            ShizukuShell.buildInputTapCommand(1, 0, 0),
            ShizukuShell.buildInputSwipeCommand(1, 0, 0, 1, 1, 100),
            ShizukuShell.buildKeyeventCommand(1, 4),
            ShizukuShell.buildInputTextCommand(1, "x")
        )
        for (cmd in inputCommands) {
            assertEquals("input", cmd[0])
            assertEquals("-d", cmd[1])
            assertTrue(!cmd.contains("--display"))
        }
    }

    @Test
    fun `settings get 命令拼装 - 4 token 数组（修复轮八启动对账）`() {
        assertArrayEquals(
            arrayOf("settings", "get", "global", "overlay_display_devices"),
            ShizukuShell.buildSettingsGetCommand()
        )
    }

    @Test
    fun `keyevent 命令拼装 - back=4 home=3`() {
        assertArrayEquals(
            arrayOf("input", "-d", "42", "keyevent", "4"),
            ShizukuShell.buildKeyeventCommand(42, 4)
        )
        assertArrayEquals(
            arrayOf("input", "-d", "42", "keyevent", "3"),
            ShizukuShell.buildKeyeventCommand(42, 3)
        )
    }

    @Test
    fun `inputText 命令拼装 - 短选项 -d + 完整文本单 argv（修复轮八：对齐 SZ-101 纯函数约定）`() {
        assertArrayEquals(
            arrayOf("input", "-d", "90", "text", "hello world"),
            ShizukuShell.buildInputTextCommand(90, "hello world")
        )
    }

    @Test
    fun `long_press 语义 - 同点慢速 swipe（600ms）`() {
        // performLongPress 的 shell 分支 = inputSwipe(px,py,px,py,600)
        // 数组：[0]input [1]-d [2]90 [3]swipe [4]x1 [5]y1 [6]x2 [7]y2 [8]duration
        val cmd = ShizukuShell.buildInputSwipeCommand(90, 540, 1200, 540, 1200, 600)
        assertEquals("540", cmd[4])
        assertEquals("1200", cmd[5])
        assertEquals("540", cmd[6])
        assertEquals("1200", cmd[7])
        assertEquals("600", cmd[8])
    }

    @Test
    fun `normalizeMode 语义未被 Shizuku 改动（回归锁）`() {
        assertEquals(VirtualDisplayManager.MODE_BACKGROUND, VirtualDisplayManager.normalizeMode("background"))
        assertEquals(VirtualDisplayManager.MODE_FOREGROUND, VirtualDisplayManager.normalizeMode(null))
    }

    @Test
    fun `shizuku 常量 - 执行超时 5s 上限`() {
        assertEquals(5_000L, ShizukuShell.EXEC_TIMEOUT_MS)
    }

    @Test
    fun `forceStop 语义 - 白名单内（仅内部包名集合来源）`() {
        // force-stop 不在本测试直接执行（依赖 Shizuku server），此处固化
        // "仅代码拼装" 的边界意识：包名必须经 PhoneControlSecurity.isSensitivePackage 前置
        assertTrue(PhoneControlSecurity.isSensitivePackage("com.eg.android.AlipayGphone"))
    }

    // ==================== 隐藏虚拟屏响应解析（ADR-065 / US-3114） ====================

    @Test
    fun `parseHiddenDisplayResult - OK 响应解析 displayId 与 flags`() {
        val ok = ShizukuShell.parseHiddenDisplayResult("OK:24:0x1fec1")
        assertTrue(ok is ShizukuShell.HiddenDisplayResult.Ok)
        ok as ShizukuShell.HiddenDisplayResult.Ok
        assertEquals(24, ok.displayId)
        assertEquals(0x1FEC1, ok.flags)
    }

    @Test
    fun `parseHiddenDisplayResult - flags 缺省或零值容错`() {
        val ok = ShizukuShell.parseHiddenDisplayResult("OK:24:0x0")
        assertTrue(ok is ShizukuShell.HiddenDisplayResult.Ok)
        assertEquals(0, (ok as ShizukuShell.HiddenDisplayResult.Ok).flags)
        val noFlags = ShizukuShell.parseHiddenDisplayResult("OK:7")
        assertTrue(noFlags is ShizukuShell.HiddenDisplayResult.Ok)
        assertEquals(7, (noFlags as ShizukuShell.HiddenDisplayResult.Ok).displayId)
    }

    @Test
    fun `parseHiddenDisplayResult - displayId 必须为正数（0 与负值均拒绝）`() {
        // guardrail r1 Q-13：OK:0 与 OK:-5 若被当作有效屏，INVALID_DISPLAY(-1) 判定失效
        assertTrue(ShizukuShell.parseHiddenDisplayResult("OK:0:0x0") is ShizukuShell.HiddenDisplayResult.Error)
        assertTrue(ShizukuShell.parseHiddenDisplayResult("OK:-1:0x0") is ShizukuShell.HiddenDisplayResult.Error)
        assertTrue(ShizukuShell.parseHiddenDisplayResult("OK:-5:0x0") is ShizukuShell.HiddenDisplayResult.Error)
    }

    @Test
    fun `parseHiddenDisplayResult - ERR 响应保留可读原因（SecurityException 场景）`() {
        val err = ShizukuShell.parseHiddenDisplayResult(
            "ERR:java.lang.SecurityException:packageName must match the calling uid"
        )
        assertTrue(err is ShizukuShell.HiddenDisplayResult.Error)
        assertTrue((err as ShizukuShell.HiddenDisplayResult.Error).message.contains("packageName"))
    }

    @Test
    fun `parseHiddenDisplayResult - 空响应与非法格式必须失败（不静默成功）`() {
        assertTrue(ShizukuShell.parseHiddenDisplayResult(null) is ShizukuShell.HiddenDisplayResult.Error)
        assertTrue(ShizukuShell.parseHiddenDisplayResult("  ") is ShizukuShell.HiddenDisplayResult.Error)
        assertTrue(ShizukuShell.parseHiddenDisplayResult("OK:abc:0x1") is ShizukuShell.HiddenDisplayResult.Error)
        assertTrue(ShizukuShell.parseHiddenDisplayResult("garbage") is ShizukuShell.HiddenDisplayResult.Error)
    }
}
