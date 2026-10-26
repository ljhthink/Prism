package io.prism.qq

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [QqExecutionGuard] 单元测试（ADR-063 修订 2026-10-25；PRD US-3115 AC-6）。
 *
 * 全部系统能力经注入点替换 → 纯 JVM；验证决策矩阵与"唤醒一次后复检"的收敛行为。
 */
class QqExecutionGuardTest {

    private var interactive = true
    private var wakeResult = true
    private var wakeCalls = 0
    private var displayState: Int? = QqExecutionGuard.STATE_ON
    private var wakePolicy = true
    private val sleepCalls = mutableListOf<Long>()
    private var keepScreenCalls = 0
    private var preflightReasons: String? = null

    @Before
    fun setUp() {
        interactive = true
        wakeResult = true
        wakeCalls = 0
        displayState = QqExecutionGuard.STATE_ON
        wakePolicy = true
        sleepCalls.clear()
        keepScreenCalls = 0
        preflightReasons = null
        QqExecutionGuard.preflightProvider = { preflightReasons }
        QqExecutionGuard.interactiveProvider = { interactive }
        QqExecutionGuard.wakeUpAction = {
            wakeCalls++
            if (wakeResult) interactive = true
            wakeResult
        }
        QqExecutionGuard.keepScreenOnAction = { keepScreenCalls++ }
        QqExecutionGuard.displayStateProvider = { displayState }
        QqExecutionGuard.wakeOnScreenOffProvider = { wakePolicy }
        QqExecutionGuard.sleeper = { sleepCalls += it }
    }

    @After
    fun tearDown() {
        // 复位为"无副作用"默认值，避免影响其它用例
        QqExecutionGuard.interactiveProvider = { true }
        QqExecutionGuard.wakeUpAction = { false }
        QqExecutionGuard.displayStateProvider = { null }
        QqExecutionGuard.wakeOnScreenOffProvider = { true }
        QqExecutionGuard.keepScreenOnAction = { }
        QqExecutionGuard.preflightProvider = { null }
        QqExecutionGuard.sleeper = { }
    }

    @Test
    fun `亮屏且显示面 ON 时直接就绪`() {
        val s = QqExecutionGuard.ensureReady(displayId = 3, requireDisplay = true, allowWake = true)
        assertEquals(QqExecutionGuard.Kind.READY, s.kind)
        assertFalse(s.blocked)
        assertEquals(0, wakeCalls)
    }

    @Test
    fun `熄屏且允许唤醒时唤醒一次并重新就绪`() {
        interactive = false
        val s = QqExecutionGuard.ensureReady(displayId = 3, requireDisplay = true, allowWake = true)
        assertEquals(QqExecutionGuard.Kind.WOKEN, s.kind)
        assertFalse(s.blocked)
        assertEquals(1, wakeCalls)
        assertEquals("唤醒后必须临时保持亮屏", 1, keepScreenCalls)
        assertTrue(sleepCalls.isNotEmpty())
    }

    @Test
    fun `亮屏时不得额外持亮屏锁（避免无谓耗电）`() {
        QqExecutionGuard.ensureReady(displayId = 3, requireDisplay = true, allowWake = true)
        assertEquals(0, keepScreenCalls)
    }

    @Test
    fun `熄屏且策略为跳过时阻塞且不唤醒`() {
        interactive = false
        wakePolicy = false
        val s = QqExecutionGuard.ensureReady(displayId = 3, requireDisplay = true, allowWake = true)
        assertTrue(s.blocked)
        assertEquals(0, wakeCalls)
        assertTrue(s.reason.contains("不允许唤醒"))
    }

    @Test
    fun `被动回复（allowWake=false）熄屏时一律阻塞`() {
        interactive = false
        val s = QqExecutionGuard.ensureReady(displayId = 3, requireDisplay = true, allowWake = false)
        assertTrue(s.blocked)
        assertEquals(0, wakeCalls)
    }

    @Test
    fun `唤醒失败时给出可读原因`() {
        interactive = false
        wakeResult = false
        val s = QqExecutionGuard.ensureReady(displayId = 3, requireDisplay = true, allowWake = true)
        assertTrue(s.blocked)
        assertTrue(s.reason.contains("唤醒失败"))
    }

    @Test
    fun `唤醒后屏幕仍不可交互时阻塞`() {
        interactive = false
        QqExecutionGuard.wakeUpAction = { wakeCalls++; true } // 返回成功但不改变状态
        val s = QqExecutionGuard.ensureReady(displayId = 3, requireDisplay = true, allowWake = true)
        assertTrue(s.blocked)
        assertTrue(s.reason.contains("仍不可交互"))
    }

    @Test
    fun `显示面已关闭时阻塞（不计唤醒）`() {
        displayState = QqExecutionGuard.STATE_OFF
        val s = QqExecutionGuard.ensureReady(displayId = 3, requireDisplay = true, allowWake = true)
        assertTrue(s.blocked)
        assertTrue(s.reason.contains("显示面已关闭"))
        assertEquals(0, wakeCalls)
    }

    // ==================== guardrail G-02 / G-07 回归锁 ====================

    @Test
    fun `执行面尚未创建时不应判阻塞（惰性创建，不是不可用）`() {
        displayState = null
        val s = QqExecutionGuard.ensureReady(
            displayId = android.view.Display.INVALID_DISPLAY,
            requireDisplay = true,
            allowWake = true
        )
        assertFalse("执行面由发送链路惰性创建，不得据此阻塞：${s.reason}", s.blocked)
    }

    @Test
    fun `前置条件缺失时直接阻塞且绝不唤醒屏幕`() {
        interactive = false // 熄屏 + 允许唤醒 —— 若无前置检查就会点亮屏幕
        preflightReasons = "无障碍服务未开启（QQ 小号助手依赖它操作 QQ 界面）"
        val s = QqExecutionGuard.ensureReady(displayId = 3, requireDisplay = true, allowWake = true)
        assertTrue(s.blocked)
        assertTrue(s.reason.contains("无障碍"))
        assertEquals("前置不满足时不得点亮屏幕（扰民副作用）", 0, wakeCalls)
        assertEquals("前置不满足时不得持亮屏锁", 0, keepScreenCalls)
        assertTrue("不得进入唤醒后的等待", sleepCalls.isEmpty())
    }

    @Test
    fun `前置条件齐备时正常放行并唤醒`() {
        interactive = false
        preflightReasons = null
        val s = QqExecutionGuard.ensureReady(displayId = 3, requireDisplay = true, allowWake = true)
        assertEquals(QqExecutionGuard.Kind.WOKEN, s.kind)
        assertEquals(1, wakeCalls)
    }

    @Test
    fun `协议端路径不做前置检查（不需要执行面）`() {
        preflightReasons = "无障碍服务未开启"
        val s = QqExecutionGuard.ensureReady(
            displayId = android.view.Display.INVALID_DISPLAY,
            requireDisplay = false,
            allowWake = true
        )
        assertFalse(s.blocked)
    }

    @Test
    fun `显示面不存在时按待创建处理而不阻塞（G-02）`() {
        displayState = null
        val s = QqExecutionGuard.ensureReady(displayId = 3, requireDisplay = true, allowWake = true)
        // 语义修订：displayId 存在但系统查不到 → 视为"待重建"，由发送链路 ensureDisplay 兜底；
        // 只有"已存在但 state=OFF"才阻塞（见上一个用例）。
        assertFalse(s.blocked)
    }

    @Test
    fun `协议端路径不要求显示面（requireDisplay=false）`() {
        displayState = null
        val s = QqExecutionGuard.ensureReady(
            displayId = android.view.Display.INVALID_DISPLAY,
            requireDisplay = false,
            allowWake = true
        )
        assertEquals(QqExecutionGuard.Kind.READY, s.kind)
    }

    @Test
    fun `决策矩阵纯函数可独立验证`() {
        assertEquals(
            QqExecutionGuard.Kind.BLOCKED,
            QqExecutionGuard.decide(
                interactive = false, wakePolicy = false, displayState = null, requireDisplay = true
            ).kind
        )
        assertEquals(
            QqExecutionGuard.Kind.READY,
            QqExecutionGuard.decide(
                interactive = true, wakePolicy = false, displayState = QqExecutionGuard.STATE_ON,
                requireDisplay = true
            ).kind
        )
        assertEquals(
            QqExecutionGuard.Kind.READY,
            QqExecutionGuard.decide(
                interactive = true, wakePolicy = true, displayState = QqExecutionGuard.STATE_DOZE,
                requireDisplay = true
            ).kind
        )
    }
}
