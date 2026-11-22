package io.prism.phonecontrol.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自身包名守卫单测（2026-11-22：后台操控「闪退回桌面」）。
 *
 * 三处回收路径（`stopLaunchedPackages` / `stopPersistedLaunched` / `evictFromMainDisplay`）
 * 与启动路径（`launchApp` / `launchAppForUser` / `shellLaunchAndVerify`）共用
 * [VirtualDisplayManager.shouldForceStopTarget]；清单过滤逻辑则由
 * [VirtualDisplayManager.reclaimableTargets] 承担——两者都被本文件覆盖，
 * 删掉任何一处的接线都会让这里的用例失败（guardrail L-9：避免"删掉调用点测试仍绿"）。
 */
class VirtualDisplayManagerSelfGuardTest {

    private val self = "io.prism"

    @Test
    fun `自身包名不可回收`() {
        assertFalse(VirtualDisplayManager.shouldForceStopTarget(self, self))
    }

    @Test
    fun `第三方包名可回收`() {
        assertTrue(VirtualDisplayManager.shouldForceStopTarget("com.xunmeng.pinduoduo", self))
        assertTrue(VirtualDisplayManager.shouldForceStopTarget("com.android.settings", self))
    }

    @Test
    fun `空或空白包名不可回收`() {
        assertFalse(VirtualDisplayManager.shouldForceStopTarget("", self))
        assertFalse(VirtualDisplayManager.shouldForceStopTarget("   ", self))
    }

    @Test
    fun `大小写与前后缀不同的包名不被误判为自身`() {
        // 精确匹配：避免把 io.prism.debug / io.prism.xxx 当成自身（它们是独立安装的应用）
        assertTrue(VirtualDisplayManager.shouldForceStopTarget("io.prism.debug", self))
        assertTrue(VirtualDisplayManager.shouldForceStopTarget("IO.PRISM", self))
    }

    // ==================== 持久化清单过滤（接线级不变量） ====================

    @Test
    fun `清单里的自身条目被跳过且不进入待回收集合`() {
        val (targets, skipped) = VirtualDisplayManager.reclaimableTargets(
            listOf("io.prism|0", "com.xunmeng.pinduoduo|0"),
            self
        )
        assertEquals(listOf("com.xunmeng.pinduoduo" to 0), targets)
        assertEquals(listOf("io.prism|0"), skipped)
    }

    @Test
    fun `清单里的自身条目不论用户 id 都跳过`() {
        val (targets, skipped) = VirtualDisplayManager.reclaimableTargets(
            listOf("io.prism|999", "io.prism|0"),
            self
        )
        assertTrue(targets.isEmpty())
        assertEquals(2, skipped.size)
    }

    @Test
    fun `清单解析容错 缺用户按零 脏条目跳过`() {
        val (targets, skipped) = VirtualDisplayManager.reclaimableTargets(
            listOf("com.tencent.mobileqq", "com.tencent.mobileqq|999", "|", "   "),
            self
        )
        assertEquals(listOf("com.tencent.mobileqq" to 0, "com.tencent.mobileqq" to 999), targets)
        assertEquals(listOf("|", "   "), skipped)
    }

    @Test
    fun `空清单不产生任何回收目标`() {
        val (targets, skipped) = VirtualDisplayManager.reclaimableTargets(emptyList(), self)
        assertTrue(targets.isEmpty())
        assertTrue(skipped.isEmpty())
    }
}
