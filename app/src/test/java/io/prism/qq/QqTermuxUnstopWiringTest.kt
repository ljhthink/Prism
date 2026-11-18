package io.prism.qq

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **装配回归：force-stop 解除链路**（2026-11-18；真机 20 小时未自愈的防线）。
 *
 * ## 真机事实（RCA 摘要）
 *
 * HyperOS「锁屏后清理内存」执行 `force-stop com.termux`，而 **force-stop 会把包
 * 置为 stopped 状态**——此后 `startService(RUN_COMMAND)` 被 AMS **静默丢弃**
 * （不抛异常、不执行）。后果：
 * - 监督器每分钟写"状态文件已陈旧，判定协议端已停止"，却永远拉不起链；
 * - 本次真机连续 **20 小时** 未自愈，端口 3000 一直超时，"一键修复"只能短暂恢复。
 *
 * 解除 stopped 的**唯一可靠方式**是用 shell 身份拉起该应用的 Activity。
 *
 * ## 为什么必须做"源码级"断言
 *
 * 这条链路的失效形态是**"看起来都在、实际没接上"**：`ensureUnstopped()` 写好了
 * 但没人调用，单测照样全绿，真机照样崩。与 [QqMediaWiringTest] 同一教训。
 * 因此这里钉死三个装配点：`start()` 前置、`restartAndWait()` 前置、监督器前置。
 */
class QqTermuxUnstopWiringTest {

    /**
     * 读源码（兼容两种工作目录：Gradle 单测的 CWD 可能是仓库根，也可能是 `app/`）。
     *
     * 与 [QqMediaWiringTest] 同一策略——按候选路径探测，找不到就失败而不是静默空串。
     */
    private fun source(relPath: String): String {
        val candidates = listOf(
            File(relPath),
            File("app/$relPath")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到源码：$relPath（CWD=${File(".").absolutePath}）")
        return file.readText(Charsets.UTF_8)
    }

    /** 剔除注释行（以 `//` 、`*` 或块注释起始符开头的行），避免"注释也算装配"的假通过。 */
    private fun code(relPath: String): String =
        source(relPath)
            .lineSequence()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    // 相对路径以**仓库根**书写，source() 同时尝试 `app/` 前缀（Gradle 单测的 CWD 是 `app/`）。
    private val bridge = "src/main/java/io/prism/qq/QqTermuxBridge.kt"
    private val supervisor = "src/main/java/io/prism/qq/QqProtocolSupervisor.kt"

    @Test
    fun `桥接必须提供解除 stopped 的两个版本`() {
        val code = code(bridge)
        assertTrue(
            "必须有阻塞版 ensureUnstopped()",
            Regex("""fun ensureUnstopped\(\)""").containsMatchIn(code)
        )
        assertTrue(
            "必须有挂起版 ensureUnstoppedAwait()（协程里不能用 Thread.sleep）",
            Regex("""suspend fun ensureUnstoppedAwait\(\)""").containsMatchIn(code)
        )
        assertTrue(
            "必须提供 Termux 存活判据",
            Regex("""fun isTermuxProcessAlive\(\)""").containsMatchIn(code)
        )
    }

    @Test
    fun `解除 stopped 必须靠拉起 Activity 而不是 startService`() {
        val code = code(bridge)
        assertTrue(
            "必须拉起 Termux 主 Activity（startService 在 force-stop 后会被丢弃）",
            code.contains("TermuxActivity")
        )
        assertTrue(
            "必须以 shell 身份 am start",
            code.contains("ShizukuShell.amStartForUser(")
        )
    }

    @Test
    fun `Termux 状态必须是三态而非布尔`() {
        val code = code(bridge)
        assertTrue(
            "必须有 ALIVE/DEAD/UNKNOWN 三态（ps 查询失败不能被当成'已死'）",
            code.contains("enum class TermuxState")
        )
        listOf("ALIVE", "DEAD", "UNKNOWN").forEach {
            assertTrue("TermuxState 缺少 $it", code.contains(it))
        }
        assertTrue(
            "必须有 termuxProcessState() 供区分",
            Regex("""fun termuxProcessState\(\): TermuxState""").containsMatchIn(code)
        )
    }

    @Test
    fun `start 只在明确 DEAD 且解除失败时才拒绝`() {
        val code = code(bridge)
        val startBody = code
            .substringAfter("fun start(context: Context): Boolean {")
            .substringBefore("\n    }\n")
        assertTrue(
            "拒绝条件必须显式要求 TermuxState.DEAD（UNKNOWN 不得拒绝）",
            startBody.contains("TermuxState.DEAD")
        )
        assertTrue(
            "start() 必须先 ensureUnstopped()",
            startBody.contains("ensureUnstopped()")
        )
        assertTrue(
            "Termux 确认拉不起时必须 return false（否则误报'已提交'）",
            startBody.contains("return false")
        )
    }

    @Test
    fun `restartAndWait 必须前置解除 stopped`() {
        val code = code(bridge)
        val body = code
            .substringAfter("suspend fun restartAndWait(")
            .substringBefore("return outcome")
        assertTrue(
            "restartAndWait 必须在提交 STOP 前解除 stopped",
            body.contains("ensureUnstoppedAwait()")
        )
        val unstopAt = body.indexOf("ensureUnstoppedAwait()")
        val stopAt = body.indexOf("QqProtocolScripts.STOP_SCRIPT")
        assertTrue(
            "解除 stopped 必须排在 STOP 之前（先恢复接收能力再提交）",
            unstopAt in 0 until stopAt
        )
    }

    @Test
    fun `监督器自愈路径必须前置解除 stopped 并留痕`() {
        val code = code(supervisor)
        val body = code
            .substringAfter("private suspend fun maybeAutoRestart(")
            .substringBefore("private ")
        assertTrue(
            "自愈必须先判 Termux 是否活着",
            body.contains("isTermuxProcessAlive()")
        )
        assertTrue(
            "自愈必须解除 stopped",
            body.contains("ensureUnstoppedAwait()")
        )
        val unstopAt = body.indexOf("ensureUnstoppedAwait()")
        val deployAt = body.indexOf("deployScriptsDetailed()")
        assertTrue(
            "解除 stopped 必须排在部署脚本之前（否则脚本部署成功了链仍起不来）",
            unstopAt in 0 until deployAt
        )
    }

    @Test
    fun `restart 与 repairAll 也必须解除 stopped`() {
        // guardrail M2：UI「重启」按钮走 restart()；force-stop 后原实现仍返回 true。
        val bridgeCode = code(bridge)
        val restartBody = bridgeCode
            .substringAfter("fun restart(context: Context): Boolean {")
            .substringBefore("\n    }\n")
        assertTrue(
            "restart() 必须先 ensureUnstopped()",
            restartBody.contains("ensureUnstopped()")
        )
        assertTrue(
            "restart() 在 Termux 无法恢复时必须 return false",
            restartBody.contains("return false")
        )

        // guardrail M1：一键修复若不前置，probe 必假 → readiness 误判
        // EXTERNAL_APPS_BLOCKED → 让用户去开 allow-external-apps，永远修不好。
        val supervisorCode = code(supervisor)
        val repairBody = supervisorCode
            .substringAfter("suspend fun repairAll(): RepairReport {")
            .substringBefore("if (started)")
        assertTrue(
            "repairAll() 必须先判 Termux 是否活着",
            repairBody.contains("isTermuxProcessAlive()")
        )
        assertTrue(
            "repairAll() 必须解除 stopped",
            repairBody.contains("ensureUnstoppedAwait()")
        )
        val unstopAt = repairBody.indexOf("ensureUnstoppedAwait()")
        val probeAt = repairBody.indexOf("probeVerified(")
        assertTrue(
            "解除 stopped 必须排在闭环探针之前（否则探针必假，误诊为 allow-external-apps）",
            unstopAt in 0 until probeAt
        )
    }

    // ==================== 陈旧状态文件不变量（真机 E2E 现场发现） ====================

    @Test
    fun `Termux 刚被 force-stop 时状态文件新鲜也不可信`() {
        // 真机实测：force-stop 后 14 秒触发修复，状态文件 ts 仍在 30s 新鲜窗口内，
        // 于是 alreadyRunning=true → 正确地解除了 stopped 却跳过启动 → 仍然没起。
        // 不变量：Termux 进程不存在 ⇒ 它内部的 run2 链必死。
        val code = code(supervisor)

        val repairBody = code
            .substringAfter("suspend fun repairAll(): RepairReport {")
            .substringBefore("if (started)")
        assertTrue(
            "repairAll 必须用 termuxAlive 收窄 alreadyRunning（不能无条件读状态文件）",
            repairBody.contains("if (termuxAlive)")
        )
        assertTrue(
            "Termux 曾死时必须把 alreadyRunning 判为 false",
            Regex("""else \{[\s\S]{0,220}?false""").containsMatchIn(repairBody)
        )

        val autoBody = code
            .substringAfter("private suspend fun maybeAutoRestart(")
            .substringBefore("private ")
        assertTrue(
            "监督器必须判三态而不是直接用 isProtocolPortListening()",
            autoBody.contains("termuxProcessState()")
        )
        assertTrue(
            "DEAD 时必须把 alreadyRunning 判为 false",
            autoBody.contains("TermuxState.DEAD")
        )
        assertTrue(
            "UNKNOWN 必须保守沿用状态文件（不因查询故障狂重启）",
            autoBody.contains("TermuxState.ALIVE")
        )
    }

    // ==================== 进程名精确匹配（guardrail M3） ====================
    @Test
    fun `Termux 主进程与其子进程算存活`() {
        listOf(
            "10123 com.termux",
            "  10123 com.termux",
            "10124 com.termux:terminal",
            "10125 com.termux:background"
        ).forEach {
            assertTrue("应判为 Termux 进程：[$it]", QqTermuxBridge.isTermuxProcessLine(it))
        }
    }

    @Test
    fun `Termux 插件包与 Prism 自身不得误判为存活`() {
        // 关键回归：插件包用 contains("com.termux") 会命中 → 跳过 unstop →
        // 本次 20 小时故障原样复现。
        listOf(
            "12000 com.termux.api",
            "12001 com.termux.boot",
            "12002 com.termux.widget",
            "12003 com.termux.styling",
            "12004 io.prism",
            "12005 com.tencent.mobileqq"
        ).forEach {
            assertTrue("不得判为 Termux 进程：[$it]", !QqTermuxBridge.isTermuxProcessLine(it))
        }
    }
}
