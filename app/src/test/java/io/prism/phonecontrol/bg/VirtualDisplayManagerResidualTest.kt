package io.prism.phonecontrol.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import android.view.Display

/**
 * 2026-11-22 回归（B2）单测：后台操控「虚拟屏无窗口」→ 回落主屏。
 *
 * 根因：残留对账把**本进程自己创建的隐藏屏**当成「上次进程死亡的残留」，
 * 先 `force-stop` 掉屏上正在跑的目标、再放弃释放屏 → 屏在、窗口 0 → LLM 判定后台不可用。
 *
 * 这里锁的是归属判定纯函数（生产路径 `reconcileResidualHiddenDisplay` 直接调用它）。
 */
class VirtualDisplayManagerResidualTest {

    private val hidden = HIDDEN_DISPLAY_NAME

    @Test
    fun `本进程持有的 display 不算残留（回归主因）`() {
        assertFalse(
            "本进程正在使用的隐藏屏被判成残留 → 会杀掉自己刚启动的目标",
            VirtualDisplayManager.isResidualHiddenDisplay(
                candidateId = 6,
                candidateName = hidden,
                ownedDisplayId = 6,
                ownedCreated = true
            )
        )
    }

    @Test
    fun `其他 displayId 的同名隐藏屏才算残留`() {
        assertTrue(
            "上次进程死亡的残留屏必须能被识别（修复轮八能力不回退）",
            VirtualDisplayManager.isResidualHiddenDisplay(
                candidateId = 5,
                candidateName = hidden,
                ownedDisplayId = 6,
                ownedCreated = true
            )
        )
    }

    @Test
    fun `本进程未持有执行面时全部同名隐藏屏都算残留`() {
        assertTrue(
            VirtualDisplayManager.isResidualHiddenDisplay(
                candidateId = 6,
                candidateName = hidden,
                ownedDisplayId = 6,
                ownedCreated = false
            )
        )
    }

    @Test
    fun `主屏与非本应用命名的屏永远不算残留`() {
        assertFalse(
            "主屏不可被判为残留",
            VirtualDisplayManager.isResidualHiddenDisplay(
                candidateId = Display.DEFAULT_DISPLAY,
                candidateName = hidden,
                ownedDisplayId = Display.INVALID_DISPLAY,
                ownedCreated = false
            )
        )
        assertFalse(
            "用户自建的模拟副屏/投屏不可被清理",
            VirtualDisplayManager.isResidualHiddenDisplay(
                candidateId = 7,
                candidateName = "叠加视图 #1",
                ownedDisplayId = Display.INVALID_DISPLAY,
                ownedCreated = false
            )
        )
    }

    // ==================== 对账守卫真值表（3 参数 × 8 组合穷举）====================
    //
    // guardrail R1-S1 / R2-2：守卫本体原先零覆盖（删掉生产守卫，4 条用例仍全绿）。
    // 现在决策收敛为纯函数 `shouldSkipResidualReconcile(ownedCreated, hiddenMode, displayAlive)`，
    // 等价于重构前内联条件 `isCreated && (!isHiddenMode || isCurrentDisplayAlive(context))`。
    // 8 组合全部覆盖，其中 (T, false, *) 两行是 R2-1 实测发现的语义回退位置。

    @Test
    fun `守卫真值表 - 持有隐藏屏且存活时跳过（本次回归主因）`() {
        assertTrue(VirtualDisplayManager.shouldSkipResidualReconcile(true, true, true))
    }

    @Test
    fun `守卫真值表 - 持有 overlay 执行面时跳过（R2-1 语义回退点）`() {
        assertTrue(
            "overlay 会话若被放行对账，会把本会话目标 force-stop 掉（症状与 B2 同构）",
            VirtualDisplayManager.shouldSkipResidualReconcile(true, false, true)
        )
        // overlay 模式下 displayAlive 不参与判定（调用方恒传 true，这里验证短路语义）
        assertTrue(VirtualDisplayManager.shouldSkipResidualReconcile(true, false, false))
    }

    @Test
    fun `守卫真值表 - 持有但隐藏屏已失效时不跳过（交给归属判定）`() {
        assertFalse(VirtualDisplayManager.shouldSkipResidualReconcile(true, true, false))
    }

    @Test
    fun `守卫真值表 - 未持有时一律不跳过（冷启动必须清理残留）`() {
        assertFalse(VirtualDisplayManager.shouldSkipResidualReconcile(false, true, true))
        assertFalse(VirtualDisplayManager.shouldSkipResidualReconcile(false, true, false))
        assertFalse(VirtualDisplayManager.shouldSkipResidualReconcile(false, false, true))
        assertFalse(VirtualDisplayManager.shouldSkipResidualReconcile(false, false, false))
    }

    @Test
    fun `生产对账入口必须调用该决策函数（防接线被整块删除）`() {
        // R2-2 实测：整块删除生产守卫时，只测纯函数的用例仍绿。
        // R3 实测：用裸 `String.contains` 时，把真实调用**注释掉**、字面文本留在注释里
        //   会让 9/9 全绿却守卫失效 —— 因此断言前必须剥掉注释与空行。
        val src = File("src/main/java/io/prism/phonecontrol/bg/VirtualDisplayManager.kt")
        assertTrue("找不到 VirtualDisplayManager.kt，测试工作目录应为 app/", src.exists())
        val codeOnly = src.readLines()
            .map { it.substringBefore("//") }          // 行尾注释
            .filterNot { it.trimStart().startsWith("*") } // KDoc 行
            .joinToString("\n")
        assertTrue(
            "reconcileResidualHiddenDisplay 没有**生效的** shouldSkipResidualReconcile 调用（守卫被删除/被注释）",
            codeOnly.contains("if (shouldSkipResidualReconcile(isCreated, isHiddenMode, displayAlive))")
        )
        assertTrue(
            "守卫前必须按 hidden 模式短路计算 displayAlive（overlay 恒 true），且不能只写在注释里",
            codeOnly.contains("val displayAlive = if (isHiddenMode) isCurrentDisplayAlive(context) else true")
        )
    }
}
