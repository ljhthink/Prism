package io.prism.phonecontrol.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行面**拆卸顺序**回归锁（ADR-065 修订 2026-10-26；PRD US-3119 AC-1）。
 *
 * 缺陷：旧实现**先**销毁显示面、**后** force-stop 目标 → 系统在两者之间把 task 迁回
 * display 0，用户看到画面闪现（真机实测 release 期间主屏出现
 * `com.android.settings/...MainSettings EXITING`）。
 *
 * **guardrail 复核结论已吸收**：只断言"清单内容"锁不住实现（把 releaseDisplay 写回旧顺序、
 * 保留清单，测试仍全绿）。因此生产代码的**执行**也走 [DisplayTeardown.run]，本用例直接断言
 * **实际执行序**（step sink）。
 */
class DisplayTeardownOrderTest {

    @Test
    fun `隐藏后端的实际执行序必须先回收目标再销毁显示面`() {
        val executed = mutableListOf<DisplayTeardown.Step>()
        val performed = DisplayTeardown.run(
            DisplayTeardown.HIDDEN_ORDER,
            DisplayTeardown.HIDDEN_ORDER.associateWith {
                { executed += it }
            }
        )
        assertEquals(DisplayTeardown.HIDDEN_ORDER, performed)
        assertEquals(
            listOf(
                DisplayTeardown.Step.STOP_LAUNCHED_PACKAGES,
                DisplayTeardown.Step.RELEASE_DISPLAY
            ),
            executed
        )
        assertTrue(DisplayTeardown.stopsBeforeRelease(executed))
    }

    @Test
    fun `legacy 后端的实际执行序同样 stop 早于 release 且监听先摘`() {
        val executed = mutableListOf<DisplayTeardown.Step>()
        DisplayTeardown.run(
            DisplayTeardown.LEGACY_ORDER,
            DisplayTeardown.LEGACY_ORDER.associateWith { step -> { executed += step } }
        )
        val stop = executed.indexOf(DisplayTeardown.Step.STOP_LAUNCHED_PACKAGES)
        val release = executed.indexOf(DisplayTeardown.Step.RELEASE_DISPLAY)
        val detach = executed.indexOf(DisplayTeardown.Step.DETACH_LISTENER)
        val stopThread = executed.indexOf(DisplayTeardown.Step.STOP_LISTENER_THREAD)
        assertTrue("stop 必须早于 release", stop < release)
        assertTrue("摘监听必须早于 release", detach < release)
        assertTrue("停线程必须早于 release", stopThread < release)
        assertEquals(DisplayTeardown.Step.CLEAR_OVERLAY_SETTING, executed.last())
    }

    @Test
    fun `旧顺序会被 run 直接拒绝（防改回先 release）`() {
        val oldOrder = listOf(
            DisplayTeardown.Step.RELEASE_DISPLAY,
            DisplayTeardown.Step.STOP_LAUNCHED_PACKAGES
        )
        assertThrows(IllegalArgumentException::class.java) {
            DisplayTeardown.run(oldOrder, emptyMap())
        }
        assertFalse(DisplayTeardown.stopsBeforeRelease(oldOrder))
    }

    @Test
    fun `缺失步骤一律判违规（防删掉 stop 直接 release）`() {
        assertFalse(
            DisplayTeardown.stopsBeforeRelease(listOf(DisplayTeardown.Step.RELEASE_DISPLAY))
        )
        assertFalse(DisplayTeardown.stopsBeforeRelease(emptyList()))
    }

    @Test
    fun `缺失 handler 的步骤不执行但仍在序列中（便于发现清单与实现漂移）`() {
        val executed = mutableListOf<DisplayTeardown.Step>()
        val performed = DisplayTeardown.run(
            DisplayTeardown.LEGACY_ORDER,
            mapOf(
                DisplayTeardown.Step.STOP_LAUNCHED_PACKAGES to { executed.add(DisplayTeardown.Step.STOP_LAUNCHED_PACKAGES) },
                DisplayTeardown.Step.RELEASE_DISPLAY to { executed.add(DisplayTeardown.Step.RELEASE_DISPLAY) }
            )
        )
        assertEquals(DisplayTeardown.LEGACY_ORDER, performed)
        assertEquals(2, executed.size)
    }
}
