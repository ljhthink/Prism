package io.prism.phonecontrol.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AccessibilityMerge] 单测（ADR-046 修订 2026-10-26；PRD US-3116 AC-2/AC-3）。
 *
 * 核心不变量：**写回只能追加，绝不顶掉用户其它无障碍服务**。这条一旦破，
 * 用户的读屏/自动化会被 Prism "顺手关掉"——比功能失效严重得多。
 */
class AccessibilityMergeTest {

    private val component = "io.prism/io.prism.phonecontrol.PhoneControlAccessibilityService"

    @Test
    fun `空值或 null 字面量追加只得到本服务`() {
        assertEquals(component, AccessibilityMerge.append(null, component))
        assertEquals(component, AccessibilityMerge.append("", component))
        assertEquals(component, AccessibilityMerge.append("null", component))
        assertEquals(component, AccessibilityMerge.append("NULL", component))
        assertEquals(component, AccessibilityMerge.append("  ", component))
    }

    @Test
    fun `用户既有服务必须原样保留且顺序不变`() {
        val userA = "com.example.reader/com.example.reader.ReaderService"
        val userB = "com.other.auto/com.other.auto.TapService"
        val merged = AccessibilityMerge.append("$userA:$userB", component)
        assertEquals("$userA:$userB:$component", merged)
        // 逐项解析确认既有项都在
        val parsed = AccessibilityMerge.parse(merged)
        assertEquals(3, parsed.size)
        assertEquals(userA, parsed[0])
        assertEquals(userB, parsed[1])
        assertEquals(component, parsed[2])
    }

    @Test
    fun `已存在时不重复追加（幂等）`() {
        val once = AccessibilityMerge.append("com.x/y", component)
        val twice = AccessibilityMerge.append(once, component)
        assertEquals(once, twice)
        assertEquals(2, AccessibilityMerge.parse(twice).size)
    }

    @Test
    fun `大小写差异视为已存在（系统写回可能改大小写）`() {
        val existing = component.uppercase()
        val merged = AccessibilityMerge.append(existing, component)
        assertEquals("不应新增一条", 1, AccessibilityMerge.parse(merged).size)
    }

    @Test
    fun `含多余分隔符与空白的脏值被规范化`() {
        val merged = AccessibilityMerge.append("  com.a/b :: com.c/d  ", component)
        assertEquals(listOf("com.a/b", "com.c/d", component), AccessibilityMerge.parse(merged))
    }

    @Test
    fun `isApplied 只有在期望条目全在时才为真`() {
        val written = "com.a/b:$component"
        assertTrue(AccessibilityMerge.isApplied(written, "com.a/b:$component"))
        // 系统额外插入别的条目不算失败
        assertTrue(AccessibilityMerge.isApplied(written, "com.a/b:$component:com.extra/z"))
        // 少一条（本服务被再次清除）→ 未生效
        assertFalse(AccessibilityMerge.isApplied(written, "com.a/b"))
        // 读回为空 → 未生效
        assertFalse(AccessibilityMerge.isApplied(written, null))
        assertFalse(AccessibilityMerge.isApplied(written, "null"))
    }

    @Test
    fun `lostExisting 检出被顶掉的用户服务（回滚判据）`() {
        val before = "com.a/b:$component"
        val after = component // 用户服务被顶掉了
        assertEquals(listOf("com.a/b"), AccessibilityMerge.lostExisting(before, after))
        // 没丢 → 空
        assertTrue(AccessibilityMerge.lostExisting(before, "com.a/b:$component").isEmpty())
        // 写前为空 → 无可丢失
        assertTrue(AccessibilityMerge.lostExisting(null, component).isEmpty())
    }

    @Test
    fun `空组件名不破坏既有值`() {
        assertEquals("com.a/b", AccessibilityMerge.append("com.a/b", "   "))
        assertEquals("", AccessibilityMerge.append(null, ""))
    }
}
