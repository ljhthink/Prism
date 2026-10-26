package io.prism.phonecontrol.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShizukuInstancePlan] 单测（ADR-046 修订 2026-10-26；PRD US-3117 AC-3）。
 *
 * 这条清理逻辑会 `kill` 进程，误杀代价极高（可能杀掉当前服务或用户其它应用的进程），
 * 因此把"匹配规则、排除规则、解析容错"全部穷举。
 */
class ShizukuInstancePlanTest {

    private val appId = "io.prism"
    private val self = "io.prism:shizuku"

    @Test
    fun `进程名拼接`() {
        assertEquals(self, ShizukuInstancePlan.processName(appId))
        assertEquals("com.x.y:shizuku", ShizukuInstancePlan.processName("com.x.y"))
    }

    @Test
    fun `只挑本应用同名进程`() {
        val ps = """
            PID NAME
              889 io.prism:shizuku
            20643 io.prism:shizuku
              195 shizuku_server
            12345 com.other.app:shizuku
        """.trimIndent()
        val pids = ShizukuInstancePlan.orphanPids(ps, appId, keepPid = -1)
        assertEquals(listOf(889, 20643), pids)
    }

    @Test
    fun `排除当前绑定的 PID（不能把自己杀了）`() {
        val ps = """
            PID NAME
              889 io.prism:shizuku
            20643 io.prism:shizuku
        """.trimIndent()
        assertEquals(listOf(889), ShizukuInstancePlan.orphanPids(ps, appId, keepPid = 20643))
    }

    @Test
    fun `前缀相似但不同的进程名一律不匹配`() {
        val ps = """
            PID NAME
              100 io.prism:shizuku_extra
              101 io.prism:shizuku2
              102 io.prism:shizuku:remote
              103 my.io.prism:shizuku
        """.trimIndent()
        assertTrue(
            "必须完全相等才匹配：${ShizukuInstancePlan.orphanPids(ps, appId, -1)}",
            ShizukuInstancePlan.orphanPids(ps, appId, -1).isEmpty()
        )
    }

    @Test
    fun `表头、空行、异常行被安全忽略`() {
        val ps = """
            PID NAME
            (garbage)
            14
            15 not-a-pid-shape name
            16 io.prism:shizuku
            
        """.trimIndent()
        assertEquals(listOf(16), ShizukuInstancePlan.orphanPids(ps, appId, -1))
    }

    @Test
    fun `空输出或 null 返回空列表`() {
        assertTrue(ShizukuInstancePlan.orphanPids(null, appId, -1).isEmpty())
        assertTrue(ShizukuInstancePlan.orphanPids("", appId, -1).isEmpty())
        assertTrue(ShizukuInstancePlan.orphanPids("PID NAME", appId, -1).isEmpty())
    }

    @Test
    fun `非正 PID 被排除（0 或负数不是有效目标）`() {
        val ps = "0 io.prism:shizuku\n-5 io.prism:shizuku\n7 io.prism:shizuku"
        assertEquals(listOf(7), ShizukuInstancePlan.orphanPids(ps, appId, -1))
    }

    @Test
    fun `describe 空列表给占位符`() {
        assertEquals("-", ShizukuInstancePlan.describe(emptyList()))
        assertEquals("1,2", ShizukuInstancePlan.describe(listOf(1, 2)))
    }
}
