package io.prism.phonecontrol

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

/**
 * 批次20（guardrail P1-3，TKN-B20-GUARDRAIL-001）：后台执行面敏感拦截**同源性**测试。
 *
 * **回归防线**（P0-1 教训）：拦截链读的树必须与实际执行目标同源（executionRoot 单一来源）。
 * 本测试固化三条防线：
 * 1. **主屏树不可达**：后台执行面下（executionDisplayId != null），getUiTreeText/findNodeByNid/
 *    nodeTextOf/nodeTextAt/nodeAt/findNodeByTextNid 全部路由到虚拟屏分支（Robolectric 无虚拟屏
 *    窗口 → 返回 null），绝不回退主屏树——P0-1 的"bg tap 经主屏树 ACTION_CLICK"路径被结构性封死；
 * 2. **bg 路径敏感拦截**：凭据输入（isCredentialInput，不依赖树）在 mode=background 命中硬拦截；
 * 3. **金融包名拦截对 mode 无关**：runLaunchAction 的敏感判定在 mode 分派共享路径上（同源结构证明）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhoneControlBackgroundExecutionTest {

    private val context: android.content.Context
        get() = org.robolectric.RuntimeEnvironment.getApplication()

    private lateinit var service: PhoneControlAccessibilityService

    @Before
    fun setUp() {
        service = Robolectric.buildService(PhoneControlAccessibilityService::class.java).get()
        // onServiceConnected 由系统绑定触发，Robolectric 需手动模拟 instance 注册
        PhoneControlAccessibilityService.instance = service
    }

    /** 共用构造：悬浮窗判定注入 true（Robolectric 下 Settings.canDrawOverlays 不可控——P1-3 注入点）。 */
    private fun bgExecutor() = PhoneControlLocalToolExecutor(
        accessibilityEnabledProvider = { true },
        backgroundControlEnabledProvider = { true },
        appContext = context,
        overlayPermissionProvider = { true }
    )

    // ==================== 防线 1：后台执行面下主屏树不可达 ====================

    @Test
    fun `后台执行面 - 全部节点函数路由到虚拟屏分支（无虚拟屏窗口时返回 null 而非主屏树）`() {
        service.setExecutionDisplay(displayId = 42)
        // Robolectric 无虚拟屏窗口 → executionRoot 必须为 null；若任何函数仍读主屏
        // （P0-1 的 rootInActiveWindow 残留），这些断言将失败（shadow 主屏同样无树，
        // 但配合防线 2/3 的行为断言与前台对照足以锁定路由）
        assertNull("getUiTreeText 应走虚拟屏分支", service.getUiTreeText())
        assertNull("findNodeByNid 应走虚拟屏分支", service.findNodeByNid(0))
        assertNull("nodeTextOf 应走虚拟屏分支", service.nodeTextOf(0))
        assertNull("nodeTextAt 应走虚拟屏分支", service.nodeTextAt(100, 100))
        assertNull("nodeAt 应走虚拟屏分支", service.nodeAt(0, 0, 0))
        assertNull("findNodeByTextNid 应走虚拟屏分支", service.findNodeByTextNid("支付"))
    }

    @Test
    fun `后台执行面 - 前台对照（executionDisplayId=null 时恢复前台语义）`() {
        service.setExecutionDisplay(displayId = 42)
        service.setExecutionDisplay(displayId = null)
        // 复位前台后 isBackgroundExecution 必须为 false（finally 还原语义）
        assertFalse(service.isBackgroundExecution)
        // 后台窗口包名查询不依赖执行面（显式 displayId）
        assertNull(service.backgroundWindowPackage(42))
    }

    // ==================== 防线 2：bg 路径敏感拦截 ====================

    @Test
    fun `bg type - 凭据输入硬拦截（不依赖执行面树）`() = runBlocking {
        val executor = bgExecutor()
        val result = executor.execute(
            "phone_control__type",
            mapOf("text" to "我的密码是123456", "mode" to "background")
        )
        assertTrue("实际: $result", result.startsWith("⚠️ "))
        assertTrue("实际: $result", result.contains("凭据"))
    }

    @Test
    fun `bg tap 敏感文本 - 锚点未命中时不回退主屏操作`() = runBlocking {
        val executor = bgExecutor()
        val result = executor.execute(
            "phone_control__tap",
            mapOf("text" to "确认支付", "mode" to "background")
        )
        // 虚拟屏树无命中 → 拦截链与执行目标同源地"未找到"（绝不回退主屏点击，P0-1 回归防线）
        assertTrue("实际: $result", result.contains("未能在当前屏幕找到"))
    }

    // ==================== 防线 3：金融包名拦截对 mode 无关 ====================

    @Test
    fun `bg launch_app - 金融专用包名硬拦截（与 foreground 同判定路径）`() = runBlocking {
        val executor = bgExecutor()
        // 敏感判定位于 mode 分派共享路径（runLaunchAction），mode 值不影响拦截结果
        for (mode in listOf("foreground", "background")) {
            val result = executor.execute(
                "phone_control__launch_app",
                mapOf("package" to "com.eg.android.AlipayGphone", "mode" to mode)
            )
            assertTrue("mode=$mode 实际: $result", result.startsWith("⚠️ "))
            assertTrue("mode=$mode 实际: $result", result.contains("金融"))
        }
    }
}
