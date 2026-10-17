package io.prism.pet.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BUG-20260913-001 **R6 根因（动作优先级）** 的回归护栏。
 *
 * 真机现象：第一条待机动作播完后，桌宠只剩表情变化 —— 点命中区、助手点播都**不再换动作**，
 * 日志稳定为 `pet action fell back: ... reason=motion 未启动`。
 *
 * 根因**不在本项目代码里**，而是 `pixi-live2d-display` 的优先级约定与本模型资产特性的组合：
 * 反编译 `MotionManager.state.reserve()` 得
 * ```
 * if (priority < 3) { if (priority <= currentPriority) return false; ... }
 * ```
 * 优先级的释放只发生在动作 `complete()`，而昔涟模型的**全部** motion 都是 `Loop = true`
 * ⇒ 第一条动作永不结束 ⇒ `currentPriority` 长期停在 `NORMAL(2)`
 * ⇒ 库默认优先级（NORMAL）的动作**被永久拒绝**。
 *
 * 修法是调用侧统一显式传 `FORCE(3)`（该值会跳过整个 `priority < 3` 分支）。
 * 这属于"**参数写错也不报错、只静默拒绝**"的形态，JVM 侧唯一抓得住的抓手是结构断言，
 * 因此这里同时钉住"本侧传了 FORCE"与"vendor 侧确实还存在该拒绝分支（即 FORCE 仍是承重件）"。
 *
 * 反证方法：
 * - 把 `MOTION_PRIORITY_FORCE` 的值从 `3` 改成 `2`，或让任一 `model.motion(...)` 少传第三个参数
 *   → [动作调用必须显式传 FORCE 优先级] / [FORCE 常量必须是库定义的 3] 立刻失败；
 * - 若上游某版本改为"动作结束即释放优先级"，vendor 不再含该拒绝分支文案
 *   → [FORCE 是承重件] 失败，提示可回到默认优先级并同步 `pet-renderer.js` 的长注释。
 */
class PetMotionPriorityTest {

    /** 沿目录树向上定位 `app/src/main/assets/pet/`（Gradle 单测的 user.dir 是模块目录）。 */
    private fun petAssetsDir(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(4) {
            val candidate = dir?.let { File(it, "src/main/assets/pet") }
            if (candidate != null && candidate.isDirectory) return candidate
            dir = dir?.parentFile
        }
        return null
    }

    private fun assetsFile(vararg parts: String): File {
        val dir = petAssetsDir()
        // 与 PetWebAssetsStructureTest 一致：定位失败必须**断言失败**而不是 assumeTrue 跳过，
        // 否则护栏会静默失效（基线本就有 32 个 skipped）。
        assertNotNull(
            "必须能定位到 app/src/main/assets/pet（当前 user.dir=${System.getProperty("user.dir")}）",
            dir
        )
        return File(dir!!, parts.joinToString("/")).also {
            assertTrue("缺失资产文件：${it.path}", it.isFile)
        }
    }

    /**
     * 逐行去掉 `//` 行注释后再取正则匹配 —— 否则 KDoc/注释里作为**反例**出现的
     * `model.motion(group, undefined, priority)` 会被误判成一处真的调用点。
     */
    private fun codeLines(path: File): List<String> =
        path.readLines().map { line -> line.substringBefore("//") }

    @Test
    fun `动作调用必须显式传 FORCE 优先级`() {
        val src = codeLines(assetsFile("pet-renderer.js")).joinToString("\n")
        val callSites = Regex("""model\.motion\(([^)]*)\)""").findAll(src).map { it.groupValues[1] }.toList()

        assertTrue(
            "pet-renderer.js 里应至少有 2 处 model.motion 调用（显式下标 + 组内随机），实际 ${callSites.size} 处",
            callSites.size >= 2
        )
        callSites.forEachIndexed { i, args ->
            assertTrue(
                "第 ${i + 1} 处 model.motion($args) 未传 MOTION_PRIORITY_FORCE：" +
                    "库默认 NORMAL(2) 会被 state.reserve() 永久拒绝（本模型动作全是 Loop=true），" +
                    "表现为'第一条动作之后再也不会换动作'",
                args.contains("MOTION_PRIORITY_FORCE")
            )
        }
    }

    @Test
    fun `FORCE 常量必须是库定义的 3`() {
        val src = codeLines(assetsFile("pet-renderer.js")).joinToString("\n")
        val decl = Regex("""MOTION_PRIORITY_FORCE\s*=\s*(\d+)""").find(src)
        assertNotNull("pet-renderer.js 必须定义 MOTION_PRIORITY_FORCE", decl)
        assertEquals(
            "MotionPriority.FORCE 在 pixi-live2d-display 中为 3；改成 2 会退回'永久拒绝'缺陷",
            "3",
            decl!!.groupValues[1]
        )
    }

    /**
     * FORCE 之所以必要，是因为 vendor 里**确实存在**该拒绝分支。上游若改掉它，
     * 本用例失败即为"可以撤回 FORCE"的提示 —— 避免留下一条已经无理由的魔法数字。
     */
    @Test
    fun `FORCE 是承重件（vendor 内确存等优先级拒绝分支）`() {
        val vendor = assetsFile("vendor/pixi-live2d-display-cubism4.min.js").readText()
        assertTrue(
            "vendor 内应存在'另一个等/更高优先级动作正在播放'的拒绝分支（FORCE 的存在理由）；" +
                "若为 false，说明上游已改优先级释放策略 ⇒ 可撤回 FORCE 并同步 pet-renderer.js 注释",
            vendor.contains("another motion is playing as an equivalent or higher priority")
        )
        assertTrue(
            "vendor 内应存在 MotionPriority 枚举且 FORCE=3（本用例断言的字面值来源）",
            vendor.contains("FORCE=3")
        )
    }
}
