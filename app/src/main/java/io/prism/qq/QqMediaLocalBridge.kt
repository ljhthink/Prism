package io.prism.qq

import android.content.Context
import io.prism.qq.channel.QqMediaRef

/**
 * **本地文件回退**：当 QQ CDN 取不到图时，从协议端容器里把图片字节"捞"出来
 * （2026-11-15 新增；ADR-075 / PRD US-3134）。
 *
 * ## 为什么需要它（真机取证）
 *
 * ```
 * [Rkey] 所有服务均已禁用, 图片使用FallBack机制        ← NapCat 反复报
 * ```
 * rkey 三通道（native / 远程服务 / 兜底）全挂 → 图片 URL 用**硬编码占位 rkey**
 * → 所有 `gchat.qpic.cn` 链接必然 `HTTP 400`（实测 3 种 UA 全 400，4 张不同图片
 * 的 rkey 完全相同）。**属 NapCat 上游能力问题，Prism 改不了。**
 *
 * 但 `get_image` 返回的 `file` 是**容器内真实存在的本地路径**——
 * NapCat 收消息时就把图落盘了。于是绕过 CDN：
 *
 * ```
 * 图片文件名 → Termux 执行 media-fetch.sh（proot find + cp）→ /sdcard/prism-media.bin
 *            → Prism 直接读该文件（共享存储，无需 root）
 * ```
 *
 * ## 边界
 *
 * - **只接受安全文件名**（`[A-Za-z0-9_-]{1,80}.{jpg,png,...}`），防注入/穿越；
 * - 只读 `/sdcard/prism-media.bin` 这**一个**文件（不放开目录）；
 * - 回执文件带 `ok` 与可读 `reason`，失败原因能到日志与 UI；
 * - 单次超时 [TIMEOUT_MS]（proot 启动约百 ms~1s，1.8MB 复制很快）。
 */
class QqMediaLocalBridge(
    private val context: Context,
    /** 提交脚本执行（默认走 Termux RUN_COMMAND；可注入以便单测）。 */
    private val launch: (arguments: Array<String>) -> Boolean = { args ->
        QqTermuxBridge.runMediaFetch(context.applicationContext, args)
    },

    /**
     * **中转**：把 `/sdcard` 上的产物搬到 Prism 可读的 `/data/local/tmp`
     * （2026-11-15 真机实测后定案）。
     *
     * 实测结论：Termux 只能写 `/sdcard`（`WRITABLE`），不能写 Prism 的 app 目录
     * （Android 11+ 限制）；而 Prism 无存储权限、读不了 `/sdcard`。
     * 但 Shizuku(shell) **能读** `/sdcard` 且 **能写** `/data/local/tmp`，
     * 而 Prism **能读** `/data/local/tmp` —— 三个前提均已真机验证。
     */
    private val relayToReadablePath: (source: String, target: String) -> Boolean = { src, dst ->
        io.prism.phonecontrol.bg.ShizukuShell.copySharedMediaToTmp(src, dst)
    },
    /** 清理中转文件（用完即删）。 */
    private val cleanupReadablePath: (String) -> Unit = { path ->
        runCatching { io.prism.phonecontrol.bg.ShizukuShell.removeMediaTmpFile(path) }
    },
    /** 读文本文件（默认走共享存储；可注入以便单测）。 */
    private val readText: (String) -> String? = { path ->
        runCatching { java.io.File(path).takeIf { it.isFile }?.readText() }.getOrNull()
    },
    /** 读原始字节（默认走共享存储；可注入以便单测）。 */
    private val readBytes: (String) -> ByteArray? = { path ->
        runCatching { java.io.File(path).takeIf { it.isFile }?.readBytes() }.getOrNull()
    },
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * 尝试从协议端容器导出该媒体的原始字节。
     *
     * @return 原始字节；不可用/失败返回 null（调用方退化为诚实降级）
     */
    suspend fun fetchBytes(ref: QqMediaRef): ByteArray? {
        val name = sanitizeName(ref.fileName)
        if (name == null) {
            android.util.Log.w(TAG, "本地回退跳过：文件名不安全或缺失")
            return null
        }
        // 清掉旧回执与旧产物（含上次的中转残留），避免把上一次的 ok=1 当成这次的结论
        runCatching { java.io.File(QqProtocolScripts.MEDIA_RESULT_FILE).delete() }
        runCatching { java.io.File(QqProtocolScripts.MEDIA_OUT_FILE).delete() }
        runCatching { cleanupReadablePath(QqProtocolScripts.MEDIA_RELAY_FILE) }
        runCatching { cleanupReadablePath(QqProtocolScripts.MEDIA_RELAY_RESULT_FILE) }
        val submitted = runCatching { launch(arrayOf(name)) }.getOrDefault(false)
        if (!submitted) {
            android.util.Log.w(TAG, "本地回退提交失败（Termux 未装/未授权 RUN_COMMAND？）")
            return null
        }
        val deadline = clock() + TIMEOUT_MS
        while (clock() < deadline) {
            sleep(POLL_INTERVAL_MS)
            val receipt = readReceiptRelayed() ?: continue
            if (!receipt.ok) {
                android.util.Log.w(
                    TAG, "本地回退失败：${receipt.reason.ifBlank { "未知原因" }}"
                )
                return null
            }
            // 产物在 /sdcard，Prism 读不了 → 经 Shizuku 中转一次到 /data/local/tmp
            val bytes = readBytesRelayed()
            if (bytes == null || bytes.isEmpty()) {
                android.util.Log.w(TAG, "本地回退回执 ok=1 但产物为空（或中转失败）")
                return null
            }
            // 用完即删：/sdcard 是 world-readable，/data/local/tmp 也不留残图
            runCatching {
                java.io.File(QqProtocolScripts.MEDIA_OUT_FILE).delete()
                java.io.File(QqProtocolScripts.MEDIA_RESULT_FILE).delete()
            }
            runCatching { cleanupReadablePath(QqProtocolScripts.MEDIA_RELAY_FILE) }
            runCatching { cleanupReadablePath(QqProtocolScripts.MEDIA_RELAY_RESULT_FILE) }
            return bytes
        }
        android.util.Log.w(TAG, "本地回退超时（${TIMEOUT_MS}ms 未拿到回执）")
        return null
    }

    /**
     * 读回执：先直读（将来若 Prism 获得存储权限即无需中转），
     * 否则经 Shizuku 把 `/sdcard` 的回执复制到 `/data/local/tmp` 再读。
     */
    private fun readReceiptRelayed(): Receipt? {
        parseReceipt(readText(QqProtocolScripts.MEDIA_RESULT_FILE))?.let { return it }
        val relayed = runCatching {
            relayToReadablePath(
                QqProtocolScripts.MEDIA_RESULT_FILE,
                QqProtocolScripts.MEDIA_RELAY_RESULT_FILE
            )
        }.getOrDefault(false)
        if (!relayed) return null
        return parseReceipt(readText(QqProtocolScripts.MEDIA_RELAY_RESULT_FILE))
    }

    /**
     * 读产物：先直读；读不到再经 Shizuku 中转一次（guardrail R-06）。
     *
     * 注意：**只在回执 ok=1 之后**才会走到这里（调用点在 while 内 ok 分支之后），
     * 因此不会"每 500ms 发起一次无效 exec"。
     */
    private fun readBytesRelayed(): ByteArray? {
        readBytes(QqProtocolScripts.MEDIA_OUT_FILE)
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_MEDIA_BYTES }
            ?.let { return it }
        // guardrail R-05：中转前先看文件大小，避免把一个超大文件整份读进堆
        // （superceded 之后 fetch() 的 maxBytes 校验已经来不及）。
        if (!fileIsWithinLimit(QqProtocolScripts.MEDIA_OUT_FILE)) {
            android.util.Log.w(TAG, "产物超过字节上限，跳过中转读取")
            return null
        }
        val relayed = runCatching {
            relayToReadablePath(
                QqProtocolScripts.MEDIA_OUT_FILE,
                QqProtocolScripts.MEDIA_RELAY_FILE
            )
        }.getOrDefault(false)
        if (!relayed) return null
        if (!fileIsWithinLimit(QqProtocolScripts.MEDIA_RELAY_FILE)) return null
        return readBytes(QqProtocolScripts.MEDIA_RELAY_FILE)?.takeIf { it.isNotEmpty() }
    }

    /** 文件是否在可接受大小内（不存在/不可测视为 false，交给上层如实降级）。 */
    private fun fileIsWithinLimit(path: String): Boolean = runCatching {
        val f = java.io.File(path)
        f.isFile && f.length() in 1..MAX_MEDIA_BYTES
    }.getOrDefault(false)

    companion object {
        private const val TAG = "QqMediaLocalBridge"

        /**
         * 单次导出上限（2026-11-15 真机实测校准）。
         *
         * 真机：`ok=1 size=1836981` 成功，但**8s 不够**。成功那次耗时 **2452ms**，
         * 首次（冷启 proot）会更久。15s 覆盖冷启动且不至于把回复拖太久；
         * 超时即如实降级（不编造画面）。
         *
         * guardrail R-07：本调用在 `autoReplyLocked` 内（持有 replyMutex），
         * 因此上限不能设太大——宁可"这次看不到图"，也不要堵住后续所有回复。
         */
        const val TIMEOUT_MS: Long = 15_000L

        /** 回执轮询间隔（proot 启动是秒级，500ms 足够且不空转）。 */
        const val POLL_INTERVAL_MS: Long = 500L

        /**
         * 单次可接受的媒体字节上限（guardrail R-05）。
         *
         * 与 [QqMediaFetcher.MAX_BYTES] 一致（8MB）。读文件前先比对大小，
         * 避免"先把 100MB 读进堆再被上层拒掉"。
         */
        const val MAX_MEDIA_BYTES: Long = 8L * 1024 * 1024

        /** 允许的图片扩展名。 */
        private val ALLOWED_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

        /** 文件名长度上限（QQ 的 md5 名是 32 字符 + 扩展名）。 */
        private const val MAX_NAME_LEN = 80

        /**
         * 校验并规范化图片文件名（**安全边界，不可放宽**）。
         *
         * 与 `media-fetch.sh` 的校验**双端一致**：脚本侧也独立校验，
         * 这样即使一端被改坏，另一端仍然拦得住命令注入/路径穿越。
         */
        fun sanitizeName(raw: String?): String? {
            val name = raw?.trim().orEmpty()
            if (name.isEmpty() || name.length > MAX_NAME_LEN) return null
            if (name.contains("..") || name.contains('/') || name.contains('\\')) return null
            // 2026-11-15（单测抓到的真 bug）：`[A-Za-z0-9_-]` 允许**前导 `-`**，
            // 而 `-rf.jpg` 传给 shell 会被当成选项而不是文件名。必须以字母/数字开头。
            if (!name.first().isLetterOrDigit()) return null
            if (!name.matches(Regex("""[A-Za-z0-9_-]{1,74}\.[A-Za-z0-9]{2,5}"""))) return null
            val ext = name.substringAfterLast('.').lowercase()
            if (ext !in ALLOWED_EXT) return null
            return name
        }

        /**
         * 解析脚本回执（`ok=1 ts=… size=… reason=…`）。
         *
         * 2026-11-15（单测抓到的真 bug）：上一版把 `substringAfter("ok=").trim()`
         * 直接与 `"1"` 比较 —— 而整行是 `ok=1 ts=…`，永远不相等 → **成功也被当失败**。
         * 现按 `key=value` 逐项解析。
         */
        fun parseReceipt(text: String?): Receipt? {
            if (text.isNullOrBlank()) return null
            val map = mutableMapOf<String, String>()
            text.lineSequence().forEach { line ->
                line.split(' ').forEach { token ->
                    val i = token.indexOf('=')
                    if (i > 0) map[token.substring(0, i)] = token.substring(i + 1)
                }
            }
            val ok = map["ok"]?.let { it == "1" } ?: return null
            return Receipt(
                ok = ok,
                size = map["size"]?.toLongOrNull() ?: 0L,
                reason = map["reason"].orEmpty()
            )
        }

        /** 脚本回执。 */
        data class Receipt(val ok: Boolean, val size: Long, val reason: String)
    }
}
