package io.prism.phonecontrol.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `secure` 设置写入的**白名单与参数校验**单测（guardrail **G-07** 要求抽出纯函数后测试）。
 *
 * 越权写系统设置是本轮唯一新增的强副作用能力，白名单必须不可绕过。
 */
class SecureWritePolicyTest {

    @Test
    fun `白名单只允许两个无障碍相关 key`() {
        assertTrue(ShizukuShell.isSecureKeyAllowed("enabled_accessibility_services"))
        assertTrue(ShizukuShell.isSecureKeyAllowed("accessibility_enabled"))
    }

    @Test
    fun `其它 key 一律拒绝（含大小写与相似名绕过）`() {
        listOf(
            "enabled_accessibility_serservices", // 拼写差异
            "ENABLED_ACCESSIBILITY_SERVICES",   // 大小写
            "enabled_accessibility_services ",  // 尾空格
            "enabled_notification_listeners",
            "default_input_method",
            "adb_enabled",
            ""
        ).forEach {
            assertFalse("必须拒绝：'$it'", ShizukuShell.isSecureKeyAllowed(it))
        }
    }

    @Test
    fun `值含控制字符被拒绝（防注入与换行注入）`() {
        assertFalse(ShizukuShell.isSecureValueAllowed("a\nb"))
        assertFalse(ShizukuShell.isSecureValueAllowed("a\rb"))
        assertFalse(ShizukuShell.isSecureValueAllowed("a\u0000b"))
        assertTrue(ShizukuShell.isSecureValueAllowed("com.x/y:com.z/w"))
        assertTrue(ShizukuShell.isSecureValueAllowed("1"))
    }

    @Test
    fun `命令使用绝对路径（PATH 缺失时裸 settings 会 127）`() {
        val cmd = ShizukuShell.buildPutSecureCommand("accessibility_enabled", "1")
        assertEquals("/system/bin/settings", cmd[0])
        assertEquals("put", cmd[1])
        assertEquals("secure", cmd[2])
        assertEquals("accessibility_enabled", cmd[3])
        assertEquals("1", cmd[4])
    }

    @Test
    fun `force-stop 跨用户命令带 --user`() {
        val cmd = ShizukuShell.buildForceStopForUserCommand("com.tencent.mobileqq", 999)
        assertEquals(listOf("am", "force-stop", "--user", "999", "com.tencent.mobileqq"), cmd.toList())
    }

    @Test
    fun `kill 命令只接受整数 PID`() {
        assertEquals(listOf("kill", "1234"), ShizukuShell.buildKillCommand(1234).toList())
    }

    @Test
    fun `失败日志对 settings 写入做脱敏（不打印服务列表）`() {
        val merged = "com.user.reader/com.user.reader.Svc:io.prism/io.prism.phonecontrol.PhoneControlAccessibilityService"
        val desc = ShizukuShell.describeCommandForLog(
            ShizukuShell.buildPutSecureCommand("enabled_accessibility_services", merged)
        )
        assertFalse("不得泄漏用户的其它无障碍服务名：$desc", desc.contains("com.user.reader"))
        assertTrue(desc.contains("enabled_accessibility_services"))
        assertTrue(desc.contains("chars"))
    }

    @Test
    fun `非 settings 命令仍按原样描述（便于排障）`() {
        val desc = ShizukuShell.describeCommandForLog(
            arrayOf("am", "force-stop", "--user", "999", "com.tencent.mobileqq")
        )
        assertTrue(desc.contains("force-stop"))
        assertTrue(desc.contains("com.tencent.mobileqq"))
    }
}
