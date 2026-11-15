package io.prism.qq

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **装配回归**（2026-11-15；guardrail R-01/R-02 阻断项的防线）。
 *
 * ## 为什么需要"源码级"测试
 *
 * 上一轮我把 `primeDownload` 和 `prefetchMediaInBackground` 写好了，
 * 但两处**装配**的文本替换静默失败：
 * - `QqMediaLocalBridge(this)` 没传 `primeDownload` → 生产从未调用过 `get_image`；
 * - `prefetchMediaInBackground()` 定义了却零调用点 → 预取是死代码。
 *
 * 单测全绿，功能却完全没接通（真机"一片空白"）。单元测试**测不出"没接线"**。
 *
 * ## guardrail 第二轮指出的弱点（已修）
 *
 * 第一版用 `contains("prefetchMediaInBackground")` 计数 ≥2 —— 但**注释也算一次**，
 * 删掉唯一的生产调用仍然通过。现在改为：
 * 1. **剔除注释行**后再匹配；
 * 2. 断言"调用形态"（带实参的调用），而不是任意出现；
 * 3. 补上此前没守护的装配点：`ASSET_SCRIPTS` 含 `media-fetch.sh`、gradle include、
 *    `localFallback` 注入、单飞两参 `remove`、脚本预算 < 桥接预算。
 */
class QqMediaWiringTest {

    private fun source(relPath: String): String {
        val candidates = listOf(
            File("src/main/java/$relPath"),
            File("app/src/main/java/$relPath")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relPath（工作目录=${File("").absolutePath}）")
        return file.readText()
    }

    /** 剔除注释，避免"注释满足断言"。 */
    private fun stripComments(text: String): String = text.lineSequence()
        .joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") -> ""
                else -> line.substringBefore("//")
            }
        }

    private fun gradle(): String =
        listOf(File("build.gradle.kts"), File("app/build.gradle.kts"))
            .first { it.isFile }.readText()

    private fun bridge(): String = source("io/prism/qq/QqMediaLocalBridge.kt")

    /** 定位 `tools/napcat-on-phone/media-fetch.sh`（测试工作目录是 app/ 或项目根）。 */
    private fun mediaScript(): File? = listOf(
        File("../tools/napcat-on-phone/media-fetch.sh"),
        File("tools/napcat-on-phone/media-fetch.sh"),
        File("../../tools/napcat-on-phone/media-fetch.sh")
    ).firstOrNull { it.isFile }

    // ---------------- 生产装配 ----------------

    @Test
    fun `生产装配必须注入 primeDownload 且真的调用 get_image`() {
        val code = stripComments(source("io/prism/PrismApplication.kt"))
        assertTrue(
            "QqMediaLocalBridge 构造必须传 primeDownload —— 否则 get_image 永不调用，" +
                "图片永远等不到落盘",
            code.contains("primeDownload =")
        )
        assertTrue(
            "prime 必须真的调用 OneBot get_image",
            code.contains("call(\"get_image\"")
        )
        assertTrue(
            "fetcher 必须注入 localFallback，否则本地回退永不触发",
            code.contains("localFallback =")
        )
    }

    @Test
    fun `预取必须有真实调用点而不是死代码`() {
        val code = stripComments(source("io/prism/PrismApplication.kt"))
        // 只认"带实参的调用形态"；定义 `private fun prefetchMediaInBackground(message: ...)` 不匹配，
        // 注释已被剔除。
        val calls = Regex("""prefetchMediaInBackground\(\s*\w+\s*\)""").findAll(code).count()
        assertTrue(
            "必须存在 prefetchMediaInBackground(message) 形态的**调用**（实际 $calls 次）。" +
                "注释/定义不算——上一轮就是死代码却全绿",
            calls >= 1
        )
    }

    // ---------------- 部署装配 ----------------

    @Test
    fun `脚本必须同时出现在 ASSET_SCRIPTS 与 gradle include`() {
        val scripts = stripComments(source("io/prism/qq/QqTermuxBridge.kt"))
        assertTrue(
            "ASSET_SCRIPTS 必须含 media-fetch.sh（否则手机上没有该脚本，回退永不工作）",
            scripts.contains("media-fetch.sh")
        )
        assertTrue(
            "build.gradle.kts 的 assets include 必须含 media-fetch.sh（否则打不进 APK）",
            gradle().contains("media-fetch.sh")
        )
    }

    // ---------------- 并发安全 ----------------

    @Test
    fun `桥接层必须串行化以防串图`() {
        val code = stripComments(bridge())
        assertTrue(
            "fetchBytes 必须持锁串行 —— 脚本产物是全局固定路径，" +
                "并发会读到别人的图（内容错配比取不到更严重）",
            code.contains("fetchLock.withLock")
        )
    }

    @Test
    fun `单飞移除必须用两参 remove 避免永久缓存失败`() {
        val code = stripComments(source("io/prism/qq/QqMediaFetcher.kt"))
        assertTrue(
            "必须用 inFlight.remove(key, deferred)（两参）—— " +
                "单参 remove 在 computeIfAbsent 插入前完成时会永久留下已完成 Deferred",
            code.contains("inFlight.remove(key, deferred)")
        )
        assertTrue(
            "必须使用 fetcher 级 scope，不每次新建（否则 caller 取消产生孤儿协程）",
            code.contains("fetchScope")
        )
    }

    // ---------------- 预算自洽 ----------------

    @Test
    fun `脚本等待预算必须小于桥接预算`() {
        val m = Regex("""TIMEOUT_MS:\s*Long\s*=\s*([0-9_]+)L""").find(bridge())
            ?: error("找不到 TIMEOUT_MS")
        val ms = m.groupValues[1].replace("_", "").toLong()
        assertTrue(
            "TIMEOUT_MS=$ms 过长：fetch 发生在 autoReplyLocked 内（持 replyMutex），" +
                "过久会堵住后续所有回复",
            ms <= 20_000L
        )
        val script = mediaScript()
        assertTrue("找不到 tools/napcat-on-phone/media-fetch.sh", script != null)
        val text = script!!.readText()
        val sw = Regex("""WAIT_SECONDS=(\d+)""").find(text)
        assertTrue(
            "脚本必须用**绝对截止秒** WAIT_SECONDS（用轮数会因 find 耗时不可控而超预算）",
            sw != null
        )
        val secs = sw!!.groupValues[1].toLong() * 1000
        assertTrue(
            "脚本等待 ${secs}ms 必须小于桥接预算 ${ms}ms，否则桥接放弃后脚本仍在跑，" +
                "孤儿 proot 会继续写全局共享路径导致串图",
            secs < ms
        )
    }
}
