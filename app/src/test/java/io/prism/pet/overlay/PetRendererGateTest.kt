package io.prism.pet.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BUG-20260913-001 回归护栏。
 *
 * 该 Bug 的两个根因**都不是"逻辑写错"**，而是"顺序/环境假设错"：
 * ① Kotlin 在页面脚本就绪前下发命令；② vendor 包依赖浏览器里不存在的 `process`。
 * 这类缺陷 JVM 单测**原本一个都抓不到**（真机上表现为黑箱白屏），
 * 所以这里用两种可失败的方式把它钉住：
 * - [PetRendererGateTest]：把"就绪前必须压住"这条时序契约抽成纯逻辑来测；
 * - [PetWebAssetsStructureTest]：对渲染页做**结构断言**（垫片必须在 vendor 脚本之前）。
 *
 * 两类的反证方法都写在各自 KDoc 里 —— 改坏任一处，对应用例必红。
 */
class PetRendererGateTest {

    /**
     * 反证：把 [PetRendererGate.canSendNow] 改成恒 `true`（即回到 Bug 版本的无条件下发），
     * 本用例立刻失败。
     */
    @Test
    fun `就绪前必须压住下发`() {
        val gate = PetRendererGate()
        assertFalse("未 boot 时不得下发", gate.canSendNow())

        gate.markSyncPending()
        assertTrue("被压住的请求必须被记住", gate.hasPendingSync)
        assertFalse("记了待发也不代表现在能发", gate.canSendNow())
    }

    /**
     * 反证：把 [PetRendererGate.onRendererBooted] 改成恒返回 `false`
     * （即"不补发"），本用例失败 —— 而"不补发"正是修复前那种静默失败的形态。
     */
    @Test
    fun `boot 必须触发一次补发`() {
        val gate = PetRendererGate()
        gate.markSyncPending()

        val shouldFlush = gate.onRendererBooted()

        assertTrue("boot 时若有待发请求，必须要求调用方补发", shouldFlush)
        assertTrue("boot 后放行", gate.canSendNow())
        assertFalse("补发标记应被消费掉", gate.hasPendingSync)
    }

    /** 无待发请求时不应无谓补发（否则每次开窗都多一次无意义 eval）。 */
    @Test
    fun `无待发请求时 boot 不触发补发`() {
        val gate = PetRendererGate()
        assertFalse(gate.onRendererBooted())
        assertTrue(gate.canSendNow())
    }

    /**
     * 多条下发合并为一个待发标记：描述符与选项都是全量快照，补发一次即等价于补发全部。
     * 若实现改成队列，本用例仍会通过 —— 故这条是"语义说明"而非"反证点"，如实标注。
     */
    @Test
    fun `多次待发合并为一次补发`() {
        val gate = PetRendererGate()
        repeat(5) { gate.markSyncPending() }
        assertTrue(gate.onRendererBooted())
        assertFalse("只应补发一次", gate.hasPendingSync)
    }

    @Test
    fun `重复 boot 不重复补发`() {
        val gate = PetRendererGate()
        gate.markSyncPending()
        assertTrue(gate.onRendererBooted())
        assertFalse("第二个 boot 不应再要求补发", gate.onRendererBooted())
    }

    @Test
    fun `复位后回到未就绪`() {
        val gate = PetRendererGate()
        gate.onRendererBooted()
        gate.reset()
        assertFalse(gate.canSendNow())
        assertFalse(gate.hasPendingSync)
    }
}

/**
 * 渲染页的**结构断言**（BUG-20260913-001 根因 A 的护栏）。
 *
 * 反证：把 `index.html` 里的 `window.process` 垫片挪到 vendor 脚本**之后**（或删掉），
 * 本用例立刻失败 —— 而那正是"`PIXI.live2d` 永不挂载、白屏"的成因。
 */
class PetWebAssetsStructureTest {

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

    private fun indexHtml(): String {
        val dir = petAssetsDir()
        // guardrail D-6：这里**不能用 `Assume.assumeTrue`**——定位失败会变成"跳过"，
        // 而基线本就有 32 个 skipped，护栏会静默失效（"测试在跑但没测到"比"没有测试"更危险）。
        // 定位失败即断言失败。
        assertNotNull("必须能定位到 app/src/main/assets/pet（当前 user.dir=${System.getProperty("user.dir")}）", dir)
        return File(dir!!, "index.html").readText()
    }

    @Test
    fun `process 垫片必须排在所有 vendor 脚本之前`() {
        val html = indexHtml()
        val shim = html.indexOf("window.process")
        assertTrue("index.html 必须包含 process 垫片（否则 pixi-live2d-display 抛 ReferenceError）", shim >= 0)

        val firstVendor = html.indexOf("src=\"vendor/")
        assertTrue("index.html 必须有 vendor 脚本引用", firstVendor >= 0)
        assertTrue(
            "垫片必须出现在第一个 vendor 脚本之前（当前 shim@$shim, vendor@$firstVendor）",
            shim < firstVendor
        )
    }

    /**
     * guardrail D-5：**垫片必须是承重件**，而不是"看起来在、其实没用"。
     *
     * 断言 vendor bundle 内**确实存在**裸露的 `process.env.NODE_ENV`：
     * - 若将来 vendor 升级把该引用内联掉，本用例会失败 —— 提示"垫片已成死代码，可删并同步注释"；
     * - 若某次改动把它重新引入而垫片被误删，本用例 + 上面那条会一起红。
     *
     * 缺了这条，"垫片是否还有效"就完全无人守护。
     */
    @Test
    fun `垫片是承重件（vendor 内确存裸露 process 引用）`() {
        val dir = petAssetsDir()
        assertNotNull("必须能定位到 assets/pet", dir)
        val bundles = File(dir!!, "vendor").listFiles { f -> f.name.endsWith(".js") }.orEmpty()
        assertTrue("vendor 下必须有 JS 产物", bundles.isNotEmpty())
        val withRawProcess = bundles.filter { it.readText().contains("process.env.NODE_ENV") }
        assertTrue(
            "至少一个 vendor 产物应含裸露 process.env.NODE_ENV（垫片的存在理由）。" +
                "若为 0，说明上游已内联该引用 ⇒ 垫片可移除，请同步更新 index.html 注释与本用例",
            withRawProcess.isNotEmpty()
        )
    }

    @Test
    fun `vendor 脚本载入顺序与依赖一致`() {
        val html = indexHtml()
        // 必须匹配 `src="…"` 属性而不是裸文件名：注释里也会提到这些名字，
        // 早期版本用裸字符串匹配，于是把注释位置当成了脚本位置（本用例自己先失败了一次）。
        fun at(name: String) = html.indexOf("src=\"$name\"")
        val pixi = at("vendor/pixi.min.js")
        val core = at("vendor/live2dcubismcore.min.js")
        val l2d = at("vendor/pixi-live2d-display-cubism4.min.js")
        val renderer = at("pet-renderer.js")
        assertTrue("四个脚本都必须被引用（pixi=$pixi core=$core l2d=$l2d renderer=$renderer）", listOf(pixi, core, l2d, renderer).all { it > 0 })
        assertTrue("PIXI 必须先于 live2d 运行时", pixi < l2d)
        assertTrue("Cubism Core 必须先于 live2d 运行时", core < l2d)
        assertTrue("pet-renderer.js 必须最后（依赖上面三者）", l2d < renderer)
    }

    @Test
    fun `vendor 运行时是自包含的（不需外部 wasm）`() {
        val dir = petAssetsDir()
        assertNotNull("必须能定位到 assets/pet", dir)
        val core = File(dir!!, "vendor/live2dcubismcore.min.js")
        assertTrue("Cubism Core 必须随包提供", core.isFile)
        val text = core.readText()
        // asm.js 构建的特征：Emscripten 在局部遮蔽 WebAssembly 并让 wasmBinary 短路。
        // 若某天换成真正的 wasm 构建，本断言会失败——那时必须同时补 .wasm 资产。
        assertTrue(
            "Cubism Core 预期为自包含 asm.js（含 asmLibraryArg 且无外部 wasm 加载分支）",
            text.contains("asmLibraryArg")
        )
        val vendorFiles = File(dir, "vendor").listFiles()?.map { it.name }.orEmpty()
        assertTrue(
            "vendor/ 下不应出现 .wasm（若出现说明换了构建，需配套调整）",
            vendorFiles.none { it.endsWith(".wasm") }
        )
    }
}
