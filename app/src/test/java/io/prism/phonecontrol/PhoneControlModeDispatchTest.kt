package io.prism.phonecontrol

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次20（BG-003，ADR-045）：[PhoneControlLocalToolExecutor] mode 执行面参数分派单测（JVM 纯逻辑）。
 *
 * 覆盖：
 * - mode 非法值直接拒绝（防 LLM 拼错悄然回退前台）
 * - mode=background 且用户开关（chip）关闭 → 一律拒绝（BG-005 守卫）
 * - mode=background 且开关开启 → 后台前置守卫链（JVM 下 SDK_INT=0 → API 30+ 文案）
 * - 不带 mode 的调用与旧版行为完全一致（协议向后兼容）
 * - schema：10 个工具带 mode enum，wait/take_over 不带（buildToolDefinitions 合法 JSON 由其内部解析保证）
 */
class PhoneControlModeDispatchTest {

    // ==================== mode 非法值 ====================

    @Test
    fun `非法 mode 直接拒绝`() = runBlocking {
        val executor = PhoneControlLocalToolExecutor()
        val result = executor.execute("phone_control__wait", mapOf("ms" to 1, "mode" to "bg"))
        assertTrue("实际: $result", result.startsWith("错误：mode 参数非法"))
    }

    // ==================== background + 用户开关守卫 ====================

    @Test
    fun `background 且 chip 关闭时拒绝（不进入虚拟屏守卫）`() = runBlocking {
        val executor = PhoneControlLocalToolExecutor(backgroundControlEnabledProvider = { false })
        val result = executor.execute(
            "phone_control__launch_app",
            mapOf("package" to "com.tencent.mm", "mode" to "background")
        )
        assertTrue("实际: $result", result.startsWith("⚠️ "))
        assertTrue("实际: $result", result.contains("后台操控开关未开启"))
    }

    @Test
    fun `background 且 chip 开启时进入前置守卫（JVM 下返回 Android 11 要求文案）`() = runBlocking {
        val executor = PhoneControlLocalToolExecutor(
            accessibilityEnabledProvider = { true },
            backgroundControlEnabledProvider = { true },
            appContext = null
        )
        val result = executor.execute(
            "phone_control__get_ui_state",
            mapOf("mode" to "background")
        )
        assertTrue("实际: $result", result.startsWith("错误：后台操控需要 Android 11"))
    }

    // ==================== 协议向后兼容（不带 mode = 旧版行为） ====================

    @Test
    fun `不带 mode 的 launch_app 缺参行为与旧版一致`() = runBlocking {
        val executor = PhoneControlLocalToolExecutor()
        val result = executor.execute("phone_control__launch_app", mapOf())
        assertEquals("错误：缺少 package 参数", result)
    }

    @Test
    fun `不带 mode 的非法工具名行为与旧版一致`() = runBlocking {
        val executor = PhoneControlLocalToolExecutor()
        val result = executor.execute("phone_control__no_such_tool", mapOf())
        assertEquals("错误：未知手机操控工具 no_such_tool", result)
    }

    // ==================== 真机修复（RCA 问题1）：chip 默认后台路由 ====================

    @Test
    fun `chip 开启且未传 mode 时默认路由 background（后台优先语义）`() = runBlocking {
        // JVM 下 SDK_INT=0 → 后台守卫返回 Android 11 要求文案——该文案本身证明
        // "无 mode + chip 开" 已进入后台分支（否则会走到前台缺参/未知工具文案）
        val executor = PhoneControlLocalToolExecutor(
            accessibilityEnabledProvider = { true },
            backgroundControlEnabledProvider = { true },
            appContext = null
        )
        val result = executor.execute("phone_control__get_ui_state", mapOf())
        assertTrue("实际: $result", result.startsWith("错误：后台操控需要 Android 11"))
    }

    @Test
    fun `chip 开启但显式传 foreground 时走前台路径（LLM 覆盖生效）`() = runBlocking {
        val executor = PhoneControlLocalToolExecutor(
            accessibilityEnabledProvider = { true },
            backgroundControlEnabledProvider = { true },
            appContext = null
        )
        // 显式 foreground → 不进后台守卫 → 直达 runLaunchAction 缺参检查（旧版行为）
        val result = executor.execute(
            "phone_control__launch_app",
            mapOf("mode" to "foreground")
        )
        assertEquals("错误：缺少 package 参数", result)
    }

    // ==================== schema（mode 参数注入正确性） ====================

    @Test
    fun `10 个工具 schema 含 mode enum 且取值合法`() {
        val withMode = setOf(
            "get_ui_state", "tap", "long_press", "double_tap", "swipe",
            "type", "back", "home", "launch_app", "screenshot"
        )
        val defs = PhoneControlLocalToolExecutor.buildToolDefinitions()
        var modeCount = 0
        for (def in defs) {
            val toolName = def.function.name.removePrefix(PhoneControlLocalToolExecutor.NAMESPACE)
            val props = def.function.parameters.jsonObject["properties"]?.jsonObject
            val mode = props?.get("mode")
            if (toolName in withMode) {
                assertTrue("工具 $toolName 缺少 mode 参数", mode != null)
                val enumValues = mode!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
                assertEquals(listOf("foreground", "background"), enumValues)
                modeCount++
            } else {
                assertTrue("工具 $toolName 不应带 mode", mode == null)
            }
        }
        assertEquals(10, modeCount)
    }
}
