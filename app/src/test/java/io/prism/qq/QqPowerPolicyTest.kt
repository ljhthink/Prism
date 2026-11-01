package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 电源策略、路径白名单、WebUI 解析、脱敏的纯逻辑测试（US-3128）。
 *
 * 判定值全部来自 2026-11-01 真机取证：
 * - `dumpsys deviceidle whitelist` 实际输出形如 `user,com.termux,10221`；
 * - `am get-standby-bucket` 在白名单命中后恒为 `5`（EXEMPTED）；
 * - NapCat 日志里的 WebUI 行形如 `WebUi User Panel Url: http://127.0.0.1:6099/webui?token=…`；
 * - 状态文件形如 `tick=798 qq=3 onebot3000=1`。
 */
class QqPowerPolicyTest {

    private val realDump = """
        system-excidle,com.android.providers.calendar,10071
        system,com.android.shell,2000
        user,io.prism,10165
        user,com.termux,10221
    """.trimIndent()

    @Test
    fun `白名单按 token 精确匹配而不是包含`() {
        assertTrue(QqPowerPolicy.isWhitelisted(realDump, "com.termux"))
        assertTrue(QqPowerPolicy.isWhitelisted(realDump, "io.prism"))
        assertFalse(
            "com.termux.boot 不得因前缀命中",
            QqPowerPolicy.isWhitelisted(realDump, "com.termux.boot")
        )
        assertFalse(
            "com.termux 不得因 com.termux.x11 命中",
            QqPowerPolicy.isWhitelisted("user,com.termux.x11,10222", "com.termux")
        )
    }

    @Test
    fun `空输出或未就绪时保守判为未生效`() {
        assertFalse(QqPowerPolicy.isWhitelisted(null, "com.termux"))
        assertFalse(QqPowerPolicy.isWhitelisted("", "com.termux"))
        assertFalse(QqPowerPolicy.isWhitelisted("   ", "com.termux"))
    }

    @Test
    fun `不在白名单时一定需要修复`() {
        assertTrue(QqPowerPolicy.needsRepair(whitelisted = false, bucket = 5))
        assertTrue(QqPowerPolicy.needsRepair(whitelisted = false, bucket = null))
    }

    @Test
    fun `在白名单且桶为 EXEMPTED 时不需要修复`() {
        assertFalse(QqPowerPolicy.needsRepair(whitelisted = true, bucket = QqPowerPolicy.BUCKET_EXEMPTED))
    }

    @Test
    fun `在白名单但桶不对时需要补一次`() {
        assertTrue(QqPowerPolicy.needsRepair(whitelisted = true, bucket = QqPowerPolicy.BUCKET_ACTIVE))
    }

    @Test
    fun `桶读取失败不据此判失败`() {
        assertFalse(
            "读失败≠配置错，否则 Shizuku 一抖就报未生效",
            QqPowerPolicy.needsRepair(whitelisted = true, bucket = null)
        )
    }

    @Test
    fun `状态文案如实区分三种情况`() {
        assertEquals(
            "无法确认（Shizuku 未就绪或读取失败）",
            QqPowerPolicy.describe(whitelisted = false, bucket = null, readFailed = true)
        )
        assertEquals(
            "未生效：不在系统电池优化白名单",
            QqPowerPolicy.describe(whitelisted = false, bucket = null, readFailed = false)
        )
        assertEquals(
            "已生效：在电池优化白名单内",
            QqPowerPolicy.describe(whitelisted = true, bucket = 5, readFailed = false)
        )
        assertTrue(
            QqPowerPolicy.describe(whitelisted = true, bucket = 10, readFailed = false)
                .contains("部分生效")
        )
    }
}

/** WebUI 扫码地址解析、状态文件判据、展示脱敏、时长文案。 */
class QqProtocolSupervisorParseTest {

    @Test
    fun `从真实日志行解析出扫码地址`() {
        val log = "WebUi User Panel Url: http://127.0.0.1:6099/webui?token=d73a559d3139"
        assertEquals(
            "http://127.0.0.1:6099/webui?token=d73a559d3139",
            QqProtocolSupervisor.parseWebUiUrl(log)
        )
    }

    @Test
    fun `多条历史地址取最后一条（重启后 token 会变）`() {
        val log = """
            WebUi User Panel Url: http://127.0.0.1:6099/webui?token=aaa111
            ...
            WebUi User Panel Url: http://127.0.0.1:6099/webui?token=bbb222
        """.trimIndent()
        assertEquals(
            "http://127.0.0.1:6099/webui?token=bbb222",
            QqProtocolSupervisor.parseWebUiUrl(log)
        )
    }

    @Test
    fun `拒绝外网地址（token 不得发往公网）`() {
        assertNull(QqProtocolSupervisor.parseWebUiUrl("http://203.0.113.9:6099/webui?token=abc"))
        assertNull(QqProtocolSupervisor.parseWebUiUrl("https://example.com/webui?token=abc"))
    }

    @Test
    fun `无匹配返回 null`() {
        assertNull(QqProtocolSupervisor.parseWebUiUrl("nothing here"))
        assertNull(QqProtocolSupervisor.parseWebUiUrl(""))
    }

    @Test
    fun `H1 token 含点 减号 下划线时不被截断`() {
        // 真机默认 token 形如 QQgiGKiNyi29k5.T（含点）——原正则只认字母数字会截断成错误 URL
        assertEquals(
            "http://127.0.0.1:6099/webui?token=QQgiGKiNyi29k5.T",
            QqProtocolSupervisor.parseWebUiUrl(
                "WebUi User Panel Url: http://127.0.0.1:6099/webui?token=QQgiGKiNyi29k5.T"
            )
        )
        assertEquals(
            "http://127.0.0.1:6099/webui?token=a_b-c.d~e",
            QqProtocolSupervisor.parseWebUiUrl("http://127.0.0.1:6099/webui?token=a_b-c.d~e")
        )
    }

    @Test
    fun `解析状态文件的 onebot3000 判据`() {
        assertEquals(1, QqProtocolSupervisor.parseOneBot3000("tick=798 qq=3 onebot3000=1"))
        assertEquals(0, QqProtocolSupervisor.parseOneBot3000("tick=798 qq=3 onebot3000=0"))
        assertEquals(0, QqProtocolSupervisor.parseOneBot3000(""))
        assertEquals(0, QqProtocolSupervisor.parseOneBot3000("乱七八糟"))
        assertTrue(QqProtocolSupervisor.isRunningStatus("onebot3000=2"))
        assertFalse(QqProtocolSupervisor.isRunningStatus("onebot3000=0"))
        assertFalse("null 视为未运行（保守）", QqProtocolSupervisor.isRunningStatus(null))
    }

    /** C1（Critical）：状态文件无时间戳/已陈旧时**必须**判为未运行，否则自动拉起永不执行。 */
    @Test
    fun `C1 陈旧状态文件不得判为在运行`() {
        val now = 1_700_000_000_000L
        val nowSec = now / 1000
        // 新鲜
        assertTrue(
            QqProtocolSupervisor.isStatusFreshAndRunning("ts=$nowSec tick=10 onebot3000=1", now)
        )
        // 刚过期（31 秒前）
        assertFalse(
            QqProtocolSupervisor.isStatusFreshAndRunning("ts=${nowSec - 31} tick=10 onebot3000=1", now)
        )
        // 严重过期（1 小时前）——进程被 SIGKILL 后文件的真实形态
        assertFalse(
            QqProtocolSupervisor.isStatusFreshAndRunning("ts=${nowSec - 3600} tick=798 onebot3000=1", now)
        )
        // 无 ts（旧版脚本）→ 视为陈旧
        assertFalse(
            QqProtocolSupervisor.isStatusFreshAndRunning("tick=798 qq=3 onebot3000=1", now)
        )
        // ts 在但端口为 0 → 未运行
        assertFalse(
            QqProtocolSupervisor.isStatusFreshAndRunning("ts=$nowSec tick=10 onebot3000=0", now)
        )
        // null
        assertFalse(QqProtocolSupervisor.isStatusFreshAndRunning(null, now))
    }

    @Test
    fun `C1 时间戳解析兼容秒与毫秒`() {
        assertEquals(1_700_000_000L, QqProtocolSupervisor.parseStatusTimestampSec("ts=1700000000 tick=1"))
        assertEquals(1_700_000_000L, QqProtocolSupervisor.parseStatusTimestampSec("ts=1700000000000 tick=1"))
        assertEquals(0L, QqProtocolSupervisor.parseStatusTimestampSec("tick=1"))
        assertEquals(0L, QqProtocolSupervisor.parseStatusTimestampSec("ts=abc"))
    }

    @Test
    fun `展示前脱敏 QQ 号`() {
        val raw = "tick=798 qq=3 onebot3000=1\n13:17:31 可用于快速登录 of QQ：1. 3904755943 Cyrene"
        val out = QqProtocolSupervisor.redactForDisplay(raw)
        assertFalse("5 位以上数字必须打码", out.contains("3904755943"))
        assertTrue("保留前 2 位、其余打码", out.contains("39" + "*".repeat(8)))
        assertTrue("判据字段不能被破坏", out.contains("onebot3000=1"))
    }

    @Test
    fun `时长文案`() {
        assertEquals("不到 1 分钟", QqProtocolAlertNotifier.humanDuration(30_000L))
        assertEquals("5 分钟", QqProtocolAlertNotifier.humanDuration(5 * 60_000L))
        assertEquals("2 小时", QqProtocolAlertNotifier.humanDuration(120 * 60_000L))
        assertEquals("1 小时 30 分钟", QqProtocolAlertNotifier.humanDuration(90 * 60_000L))
    }
}

/** 协议端脚本路径白名单、命令拼装、注入面、凭据零硬编码。 */
class QqProtocolScriptsTest {

    private val sh = io.prism.phonecontrol.bg.ShizukuShell

    @Test
    fun `白名单含三个生产脚本与两个生成脚本`() {
        val allowed = QqProtocolScripts.allowedPaths()
        assertTrue("/data/local/tmp/run2.sh" in allowed)
        assertTrue("/data/local/tmp/run2-inner.sh" in allowed)
        assertTrue("/data/local/tmp/verify.sh" in allowed)
        assertTrue(QqProtocolScripts.STOP_SCRIPT in allowed)
        assertTrue(QqProtocolScripts.PROBE_SCRIPT in allowed)
    }

    @Test
    fun `白名单外路径一律拒绝`() {
        assertFalse(QqProtocolScripts.isDeployed("/data/data/com.termux/files/home/evil.sh"))
        assertFalse(QqProtocolScripts.isDeployed("/system/bin/sh"))
        assertFalse(QqProtocolScripts.isDeployed("/data/local/tmp/../evil.sh"))
        assertFalse(QqProtocolScripts.isDeployed(""))
    }

    @Test
    fun `停止脚本不杀 Termux 本身`() {
        val body = QqProtocolScripts.STOP_SCRIPT_BODY
        assertTrue("必须杀 NapCat", body.contains("NapCat"))
        assertTrue("必须杀 Xvfb", body.contains("Xvfb"))
        assertFalse(
            "不得 pkill termux——那会殃及用户其它会话",
            body.contains("pkill -f 'termux")
        )
    }

    @Test
    fun `可读白名单是精确集合而不是整个 sdcard`() {
        // 精确集合：协议端自己写的三个文件 + 部署标记
        assertTrue(sh.isReadablePathAllowed("/sdcard/napcat.log"))
        assertTrue(sh.isReadablePathAllowed("/sdcard/napcat-status.txt"))
        assertTrue(sh.isReadablePathAllowed("/sdcard/napcat-verify.txt"))
        assertTrue(sh.isReadablePathAllowed("/sdcard/napcat-webui.txt"))
        assertTrue(sh.isReadablePathAllowed("/data/local/tmp/prism-scripts.sha"))
        // 其它路径一律拒绝（防止将来被误用成"任意文件读取"）
        assertFalse(sh.isReadablePathAllowed("/sdcard/DCIM/x.jpg"))
        assertFalse(sh.isReadablePathAllowed("/sdcard/../data/data/io.prism/x"))
        assertFalse(sh.isReadablePathAllowed("/data/local/tmp/run2.sh"))
        assertFalse(sh.isReadablePathAllowed(""))
    }

    @Test
    fun `脚本路径白名单拒绝 shell 元字符与上跳`() {
        assertTrue(sh.isTermuxScriptPathAllowed("/data/local/tmp/run2.sh"))
        assertFalse(sh.isTermuxScriptPathAllowed("/data/local/tmp/a b.sh"))
        assertFalse(sh.isTermuxScriptPathAllowed("/data/local/tmp/a;rm.sh"))
        assertFalse(sh.isTermuxScriptPathAllowed("/data/local/tmp/a\$b.sh"))
        assertFalse(sh.isTermuxScriptPathAllowed("/data/local/tmp/../etc/passwd"))
        assertFalse(sh.isTermuxScriptPathAllowed("/data/data/com.termux/files/home/x.sh"))
        assertFalse(sh.isTermuxScriptPathAllowed("/data/local/tmp/a\nb.sh"))
    }

    @Test
    fun `包名白名单拒绝非法输入`() {
        assertTrue(sh.isPackageNameAllowed("com.termux"))
        assertTrue(sh.isPackageNameAllowed("io.prism"))
        assertFalse(sh.isPackageNameAllowed("com.termux; rm -rf /"))
        assertFalse(sh.isPackageNameAllowed("com.termux\n"))
        assertFalse(sh.isPackageNameAllowed("123"))
        assertFalse(sh.isPackageNameAllowed(""))
        assertFalse(sh.isPackageNameAllowed("a".repeat(201)))
    }

    @Test
    fun `写文件命令把内容与路径都作为 argv 传参`() {
        val cmd = sh.buildWriteShellFileCommand(
            "/data/local/tmp/x.sh",
            "echo hi; rm -rf /sdcard"
        )
        // 第 3 个参数必须是**固定脚本**，不能把内容拼进去
        assertEquals(
            "printf '%s' \"\$1\" > \"\$2\" && /system/bin/chmod 755 \"\$2\"",
            cmd[2]
        )
        // 内容与路径分别落在 argv[4] / argv[5]
        assertEquals("echo hi; rm -rf /sdcard", cmd[4])
        assertEquals("/data/local/tmp/x.sh", cmd[5])
    }

    @Test
    fun `Termux RUN_COMMAND 命令用绝对路径且后台执行`() {
        val cmd = sh.buildTermuxRunCommand("/data/local/tmp/run2.sh").toList()
        assertEquals("必须用绝对 am", "/system/bin/am", cmd[0])
        assertTrue("必须指定 RunCommandService", cmd.contains("com.termux/com.termux.app.RunCommandService"))
        assertTrue("必须后台执行（否则 Termux 前台会话会被系统拒绝）", cmd.contains("true"))
        assertTrue(
            "bash 必须用 Termux 内绝对路径（用户服务 PATH 不含它）",
            cmd.contains("/data/data/com.termux/files/usr/bin/bash")
        )
        assertTrue("脚本路径必须原样作为参数", cmd.contains("/data/local/tmp/run2.sh"))
    }

    @Test
    fun `设备命令用绝对路径（用户服务 PATH 不含 system bin）`() {
        assertEquals(
            "/system/bin/dumpsys",
            sh.buildDeviceIdleWhitelistCommand("com.termux", add = true)[0]
        )
        assertEquals("/system/bin/am", sh.buildGetStandbyBucketCommand("com.termux")[0])
        assertEquals("/system/bin/am", sh.buildSetStandbyBucketCommand("com.termux", 10)[0])
    }

    /**
     * H4 的真正保险（2026-11-01 第二轮审查 H-1 修复）。
     *
     * **原测试是假绿**：它只匹配 `token\s*=`，而首轮被删掉的旧写法是
     * `T="${1:-QQgiGKiNyi29k5.T}"` —— 把 token 原样加回去该用例**依然全绿**。
     *
     * 现在改为「禁止凭据形态」：
     * - 任何 `T=`/`TOKEN=`/`token=`/`Authorization:` 后跟长串；
     * - 任何 `Bearer <长串>`；
     * - 已知的历史 token 字面量；
     * - 形如 `[A-Za-z0-9]{6,}\.[A-Za-z0-9]{4,}` 的高熵串（真机 token 形态）。
     *
     * 并同时扫描**构建产物**（assets 同步结果），避免"源干净、包里有"。
     */
    @Test
    fun `脚本源与构建产物都不得含凭据形态（H4-H1）`() {
        val dirs = listOfNotNull(locateNapcatTools(), locateNapcatAssets())
        assertTrue("必须能定位到至少一处脚本（否则本用例无意义）", dirs.isNotEmpty())
        // N-5：若产物目录不存在（干净检出未 build），本用例只能扫源码——
        // 打印一行提示，避免"以为扫了产物其实没扫"的假象。
        if (locateNapcatAssets() == null) {
            println("[QqProtocolScriptsTest] 未找到 build/generated/napcatAssets（未构建过），本次仅扫描源码")
        }
        val names = listOf("verify.sh", "run2.sh", "run2-inner.sh")
        var scanned = 0
        dirs.forEach { dir ->
            names.forEach { name ->
                val f = File(dir, name)
                if (!f.isFile) return@forEach
                scanned++
                val text = f.readText()
                forbiddenPatterns.forEach { (label, regex) ->
                    assertFalse(
                        "$name 命中禁止形态「$label」（部署目录对其它 App 可读，不得带凭据）",
                        regex.containsMatchIn(text)
                    )
                }
            }
        }
        assertTrue("实际扫描到的脚本数应 ≥3", scanned >= 3)
    }

    /** 禁止出现在脚本里的凭据形态（label → 正则）。 */
    private val forbiddenPatterns: List<Pair<String, Regex>> = listOf(
        "赋值给 T/TOKEN 的长串" to
            Regex("""(?:^|\s)(?:T|TOKEN|NAPCAT_TOKEN|ACCESS_TOKEN)\s*=\s*["']?[A-Za-z0-9._~+/-]{8,}"""),
        "token= 长串" to Regex("""token\s*=\s*["']?[A-Za-z0-9._~+/-]{8,}""", RegexOption.IGNORE_CASE),
        "Authorization 头" to Regex("""Authorization\s*:\s*\S{8,}""", RegexOption.IGNORE_CASE),
        "Bearer 后长串字面量" to Regex("""Bearer\s+[A-Za-z0-9._~+/-]{12,}"""),
        "已知历史 token" to Regex("""QQgiGKiNyi29k5\.T"""),
        // 真机 token 形态：至少 6 位字母数字 + 「点」+ 4 位，且**同时含大写与数字**
        //（否则 `meta_event.lifecycle.connect` 这类固定协议词会误报，见下方用例）
        "高熵点号串（真机 token 形态）" to
            Regex("""(?=[A-Za-z0-9]*[A-Z])(?=[A-Za-z0-9]*[0-9])[A-Za-z0-9]{6,}\.[A-Za-z0-9]{4,}""")
    )

    /** 反向验证：禁止形态的正则**真的能抓到**首轮被删掉的旧写法（防"假绿"，H-1）。 */
    @Test
    fun `H1 禁止形态能抓到旧写法（反向验证）`() {
        val oldForms = listOf(
            """T="${'$'}{1:-QQgiGKiNyi29k5.T}"""",
            "T=QQgiGKiNyi29k5.T",
            "NAPCAT_TOKEN=abcdef123456",
            "Authorization: Bearer abcdef1234567890",
            "curl -H 'Authorization: Bearer QQgiGKiNyi29k5.T'"
        )
        oldForms.forEach { sample ->
            assertTrue(
                "禁止形态必须命中：$sample",
                forbiddenPatterns.any { (_, r) -> r.containsMatchIn(sample) }
            )
        }
        // 固定协议词不得误报
        listOf(
            "1. WS 握手返回 101 且首帧是 meta_event.lifecycle.connect",
            "/opt/QQ/resources/app/config/onebot11_<QQ号>.json",
            "echo \"VERIFY_DONE\""
        ).forEach { safe ->
            forbiddenPatterns.forEach { (label, r) ->
                assertFalse("固定协议词不得被误判为凭据（$label）：$safe", r.containsMatchIn(safe))
            }
        }
    }

    /** 从 Gradle 测试工作目录（模块目录）向上找 `tools/napcat-on-phone`。 */
    private fun locateNapcatTools(): File? = locateDir("tools/napcat-on-phone")

    /** 构建产物里的 assets 同步结果（`app/build/generated/napcatAssets/napcat`）。 */
    private fun locateNapcatAssets(): File? = locateDir("build/generated/napcatAssets/napcat")

    private fun locateDir(relative: String): File? {
        var cur: File? = File(".").absoluteFile
        repeat(5) {
            val candidate = cur?.resolve(relative)
            if (candidate != null && candidate.isDirectory) return candidate
            cur = cur?.parentFile
        }
        return null
    }
}
