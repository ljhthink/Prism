package io.prism.phonecontrol.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MainDisplayWindows] 单测（guardrail **G-02**；PRD US-3119 AC-2）。
 *
 * 关键：小号在 XSpace(999)，用户自己也用同一个包（user 0）。判据必须能区分，
 * 否则"清不掉泄漏窗口、却杀掉用户本人的 QQ"。
 */
class MainDisplayWindowsTest {

    private val dump = """
        WINDOW MANAGER WINDOWS (dumpsys window windows)
          Display #0 (activities from top to bottom):
          Window #36 Window{c17b3e9 u0 com.android.settings/com.android.settings.MainSettings}:
            mDisplayId=0 taskId=3122
            mOwnerUid=1000
          Window #37 Window{d04fffd u999 com.tencent.mobileqq/com.tencent.mobileqq.activity.SplashActivity}:
            mDisplayId=0 taskId=3136
          Window #38 Window{4cdcf54 u0 com.tencent.mobileqq/com.tencent.mobileqq.activity.SplashActivity}:
            mDisplayId=38 taskId=3137
          Window #39 Window{7c47729 u0 io.prism/io.prism.MainActivity}:
            mDisplayId=0 taskId=3077
    """.trimIndent()

    @Test
    fun `解析出窗口的用户与显示`() {
        val wins = MainDisplayWindows.parse(dump)
        assertTrue(wins.size >= 4)
        assertTrue(
            wins.any {
                it.userId == 999 &&
                    it.packageName == "com.tencent.mobileqq" &&
                    it.displayId == 0
            }
        )
        assertTrue(
            wins.any {
                it.userId == 0 &&
                    it.packageName == "com.tencent.mobileqq" &&
                    it.displayId == 38
            }
        )
    }

    @Test
    fun `主屏 + 指定用户精确匹配（G-02 核心）`() {
        // 只取 display 0 上 user 999 的窗口
        val for999 = MainDisplayWindows.packagesOnMainDisplayForUser(dump, 999)
        assertEquals(setOf("com.tencent.mobileqq"), for999)

        // user 0 在主屏上的窗口：settings 与 io.prism；QQ 的 user0 实例在 display 38，不算
        val for0 = MainDisplayWindows.packagesOnMainDisplayForUser(dump, 0)
        assertEquals(setOf("com.android.settings", "io.prism"), for0)
        assertFalse("用户 0 的 QQ 在副屏，绝不能算作主屏泄漏", "com.tencent.mobileqq" in for0)
    }

    @Test
    fun `空输入与乱码返回空集合（fail-safe：不动作）`() {
        assertTrue(MainDisplayWindows.parse(null).isEmpty())
        assertTrue(MainDisplayWindows.parse("").isEmpty())
        assertTrue(MainDisplayWindows.parse("garbage\nlines\nonly").isEmpty())
        assertTrue(MainDisplayWindows.packagesOnMainDisplayForUser(null, 999).isEmpty())
    }

    @Test
    fun `缺 mDisplayId 的窗口不参与判定`() {
        val noDisplay = """
            Window #1 Window{aaa u999 com.x/com.x.A}:
              mOwnerUid=10306
        """.trimIndent()
        assertTrue(MainDisplayWindows.parse(noDisplay).isEmpty())
    }

    @Test
    fun `同一窗口块只取第一个 mDisplayId（避免被后续行覆盖）`() {
        val dup = """
            Window #1 Window{aaa u999 com.x/com.x.A}:
              mDisplayId=0
              mDisplayId=7
        """.trimIndent()
        val wins = MainDisplayWindows.parse(dup)
        assertEquals(1, wins.size)
        assertEquals(0, wins[0].displayId)
    }

    @Test
    fun `分区头不会被上一窗口块误吞（R2-05）`() {
        // 窗口块缺 mDisplayId，紧接着是分区头 —— 不能把分区头的 displayId 当成该窗口的
        val dump = """
            Window #1 Window{aaa u999 com.leaky/com.leaky.A}:
              mOwnerUid=10306
              Display: mDisplayId=0 (organized)
            Window #2 Window{bbb u0 com.ok/com.ok.B}:
              mDisplayId=0
        """.trimIndent()
        val wins = MainDisplayWindows.parse(dump)
        assertEquals(1, wins.size)
        assertEquals("com.ok", wins[0].packageName)
        // 缺 displayId 的 com.leaky 不得被算到 display 0（否则会误杀）
        assertFalse("com.leaky" in MainDisplayWindows.packagesOnMainDisplayForUser(dump, 999))
    }

    @Test
    fun `多包多用户逐包归属（R2-02 核心：混合会话不得串用用户）`() {
        // 场景：先用 user 999 发了 QQ，再用 user 0 开了 com.other（主屏）
        val mixed = """
            Window #1 Window{aaa u999 com.tencent.mobileqq/com.tencent.mobileqq.activity.SplashActivity}:
              mDisplayId=0
            Window #2 Window{bbb u0 com.other/com.other.Main}:
              mDisplayId=0
        """.trimIndent()
        // 按 user 999 判定 → 只有 QQ；按 user 0 判定 → 只有 com.other。
        // 生产代码对**每个包用它自己的用户**判定，因此：
        //   QQ(999) 会因在 display 0 被清；com.other(0) 也会因在 display 0 被清。
        // 关键反例：QQ 的 user 0 实例**不存在**于 dump 中 → 绝不会被 "user 0" 误杀。
        assertEquals(
            setOf("com.tencent.mobileqq"),
            MainDisplayWindows.packagesOnMainDisplayForUser(mixed, 999)
        )
        assertEquals(
            setOf("com.other"),
            MainDisplayWindows.packagesOnMainDisplayForUser(mixed, 0)
        )
    }
}
