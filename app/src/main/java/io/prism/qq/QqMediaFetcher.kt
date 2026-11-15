package io.prism.qq

import io.prism.qq.channel.QqMediaKind
import io.prism.qq.channel.QqMediaRef
import kotlinx.coroutines.CancellationException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * QQ 媒体下载器（2026-10-26 新增；PRD US-3124）。
 *
 * **为什么必须有**：真机取证显示 NapCat 事件里带完整 `url`
 * （`https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=…&rkey=…`），
 * 但 Prism 从未下载，只把内容替换成 `[图片]`。于是模型永远看不到图。
 *
 * **安全边界**（对齐 ADR-064 的"只信任可控来源"）：
 * - 仅 `https`；
 * - 域名白名单（QQ 官方 CDN），任何其它域名一律拒绝——防止事件内容把 App 变成 SSRF 跳板；
 * - 单张 ≤ [maxBytes]，超时 [timeoutMs]；
 * - 只接受图片 magic（JPEG/PNG/GIF/WEBP），其它一律当作失败。
 *
 * **缓存**：按 `file`（或 url 哈希）做 LRU，避免同一条消息在"事件 + 历史"两条路径重复下载。
 * 内存上限**双重**约束：[maxEntries] 条数 + [cacheBytesLimit] 字节（2026-11-15 内存优化新增；
 * 此前只按条数，而单条最大 8MB 且存 base64 String，最坏可达数百 MB）。
 *
 * 失败一律返回 null（调用方退化为"看不到内容"的诚实描述，不阻断回复）。
 */
class QqMediaFetcher(
    private val timeoutMs: Int = 10_000,
    private val maxBytes: Int = MAX_BYTES,
    private val maxEntries: Int = MAX_ENTRIES,
    /** 缓存**原始字节**上限（不含 base64 膨胀；默认 12MB）。0=不限制（仅测试用）。 */
    private val cacheBytesLimit: Long = MAX_CACHE_BYTES,
    /** 下载函数（可注入以便 JVM 单测；生产用 [HttpURLConnection]）。 */
    private val download: suspend (String, Int, Int) -> ByteArray? = { url, timeout, limit ->
        httpGet(url, timeout, limit)
    },
    /**
     * **本地文件回退**（2026-11-15 新增；ADR-075 / PRD US-3134）。
     *
     * 真机取证：NapCat 的 rkey 三通道全挂 → 图片 URL 是硬编码占位符 → CDN 必然 400；
     * 但 **图片字节其实已经存在协议端容器里**（`get_image` 返回 `file` 本地路径）。
     * 本回调负责"经 Termux 把容器内文件取出来"，返回原始字节；失败返回 null。
     *
     * null = 不具备该能力（JVM 单测/未装配），行为与旧版一致。
     */
    private val localFallback: (suspend (QqMediaRef) -> ByteArray?)? = null,
    /** 时钟（缓存淘汰用）。 */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * rkey 观测器（2026-11-15）：记录"同一 rkey 被多少张不同图片复用"，
     * 用于**离线**判定 rkey 失效（占位符必然大量重复）。可注入以便单测。
     */
    private val rkeyTracker: MutableMap<String, Int> = ConcurrentHashMap()
) {

    private data class Entry(val dataUrl: String, val at: Long, val bytes: Int)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val cachedBytes = AtomicLong(0)

    /**
     * 下载并转成 `data:` URL（**向后兼容入口**；失败返回 null）。
     *
     * 新代码请用 [fetch] 拿到带原因的 [QqMediaResult]。
     */
    suspend fun dataUrl(ref: QqMediaRef): String? =
        (fetch(ref) as? QqMediaResult.Success)?.dataUrl

    /**
     * **带结构化结果的获取**（2026-11-15；ADR-075）。
     *
     * 顺序：缓存 → CDN 直连 → （失败且值得）本地文件回退 → 结构化失败。
     *
     * 与旧实现的差别：**不再静默吞错**。失败时返回 [QqMediaResult.Failure]，
     * 上层据此把"为什么看不到图"如实写进给模型的说明（旧版只会得到一个 null，
     * 模型于是把 `约 1793KB` 当成画面内容 —— 真机「看到个头--1793kb」即此）。
     */
    suspend fun fetch(ref: QqMediaRef): QqMediaResult {
        // 只有图片/表情包有画面；语音/视频/内置表情不处理
        if (!QqMediaFailure.hasVisual(ref)) {
            return QqMediaResult.Failure(
                QqMediaResult.Kind.NO_VISUAL, "该类型无画面（${ref.kind}）"
            )
        }
        val url = ref.url?.trim().orEmpty()
        if (!isAllowedUrl(url)) {
            // 安全边界：非白名单/非 https **不回退**（避免把 App 变成任意文件读取器）
            return QqMediaResult.Failure(
                QqMediaResult.Kind.POLICY_REJECTED, "来源不在允许范围"
            )
        }
        val key = ref.fileName?.takeIf { it.isNotBlank() } ?: ("u:" + url.hashCode())
        cache[key]?.let { hit ->
            if (clock() - hit.at < CACHE_TTL_MS) {
                return QqMediaResult.Success(
                    dataUrl = hit.dataUrl,
                    mime = hit.dataUrl.substringAfter("data:").substringBefore(";base64,"),
                    bytes = hit.bytes,
                    source = QqMediaResult.Source.CDN
                )
            }
            evict(key)
        }

        // rkey 观测：占位 rkey 会被大量不同图片复用 → 提前判定，省掉一次必然失败的 10s 超时
        val rkey = QqMediaFailure.rkeyOf(url)
        val rkeyCount = if (rkey == null) 0 else synchronized(rkeyTracker) {
            // 2026-11-15（guardrail 预判）：rkey 是长串，若无上限会随进程运行无限增长。
            // 超过上限就整体清空——判定只需"近期是否被复用"，历史计数无价值。
            if (rkeyTracker.size >= MAX_RKEY_TRACKED) rkeyTracker.clear()
            val n = (rkeyTracker[rkey] ?: 0) + 1
            rkeyTracker[rkey] = n
            n
        }
        val preExpired = QqMediaFailure.isRkeyExpired(url, rkeyCount)

        // guardrail R-03（2026-11-15）：**记录 CDN 阶段的真实失败类别**，不要最后才猜。
        // 旧版把所有"没拿到"都归成 RKEY_EXPIRED/NETWORK，于是"字节拿到了但 magic 不合格"
        // （BAD_CONTENT）被误标成网络问题，还会白白触发一次 30s 的 proot 回退。
        var cdnFailure: QqMediaResult.Kind? = null
        if (!preExpired) {
            val bytes = try {
                download(url, timeoutMs, maxBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 2026-11-15（ADR-075）：**不再静默**——旧版这里只有 `catch { null }`，
                // 是本次"为什么看不到图"排障困难的直接原因。
                android.util.Log.w(
                    TAG,
                    "媒体下载异常 host=${QqMediaFailure.hostOf(url)} " +
                        "err=${e::class.simpleName}"
                )
                null
            }
            if (bytes != null) {
                val success = toSuccess(bytes, QqMediaResult.Source.CDN)
                if (success != null) {
                    put(key, Entry(success.dataUrl, clock(), success.bytes))
                    return success
                }
                // 拿到字节但不合格（空/超限/magic 非图片）→ BAD_CONTENT，**不值得回退**
                cdnFailure = QqMediaResult.Kind.BAD_CONTENT
            } else {
                cdnFailure = if (QqMediaFailure.looksLikeFallbackUrl(url)) {
                    QqMediaResult.Kind.RKEY_EXPIRED
                } else {
                    QqMediaResult.Kind.NETWORK
                }
            }
            android.util.Log.w(
                TAG,
                "媒体获取失败 host=${QqMediaFailure.hostOf(url)} 类别=$cdnFailure " +
                    "有字节=${bytes != null} 预判rkey失效=$preExpired"
            )
        } else {
            android.util.Log.w(
                TAG,
                "媒体跳过 CDN：rkey 已被 $rkeyCount 张图片复用（协议端降级占位符），改走本地回退"
            )
        }

        // ---- 层 B：本地文件回退（图片字节通常已在协议端容器内）----
        //
        // guardrail R-03：**必须**查 shouldTryLocalFallback —— 旧版把该函数写成了死代码，
        // 导致 BAD_CONTENT（字节不合格）也去起一次 proot（30s），纯浪费。
        val fallback = localFallback
        val worthFallback = QqMediaFailure.shouldTryLocalFallback(
            cdnFailure ?: QqMediaResult.Kind.RKEY_EXPIRED
        )
        if (fallback != null && worthFallback) {
            val local = try {
                fallback(ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "本地回退异常：${e::class.simpleName}")
                null
            }
            if (local != null) {
                val success = toSuccess(local, QqMediaResult.Source.LOCAL_FILE)
                if (success != null) {
                    android.util.Log.i(
                        TAG,
                        "媒体已通过本地文件回退获取（${success.bytes / 1024}KB）"
                    )
                    put(key, Entry(success.dataUrl, clock(), success.bytes))
                    return success
                }
            } else {
                android.util.Log.w(TAG, "本地回退未拿到字节（协议端容器内无此文件？）")
            }
        }

        // guardrail R-01（2026-11-15）：**如实归类**，不要把"回退也没拿到"一律说成 rkey 失效
        // ——那会把"脚本未部署 / proot 挂了 / 容器内确实没这个文件"误导成"协议端签名坏了"，
        // 让用户按错误方向排障。
        val kind = when {
            cdnFailure == QqMediaResult.Kind.BAD_CONTENT -> QqMediaResult.Kind.BAD_CONTENT
            preExpired || QqMediaFailure.looksLikeFallbackUrl(url) ->
                QqMediaResult.Kind.RKEY_EXPIRED
            cdnFailure != null -> cdnFailure
            else -> QqMediaResult.Kind.NETWORK
        }
        val detail = if (kind == QqMediaResult.Kind.RKEY_EXPIRED && fallback != null) {
            // 两条路都没成：既说了签名问题，也说清"本地回退同样失败"
            QqMediaFailure.diagnosticFor(kind) + "；本地文件回退亦未取到（脚本未部署或容器内无此文件）"
        } else {
            QqMediaFailure.diagnosticFor(kind)
        }
        return QqMediaResult.Failure(kind, detail)
    }

    /** 字节 → 成功结果（含尺寸/magic 校验）；不合格返回 null。 */
    private fun toSuccess(bytes: ByteArray, source: QqMediaResult.Source): QqMediaResult.Success? {
        if (bytes.isEmpty() || bytes.size > maxBytes) return null
        val mime = detectMime(bytes) ?: return null
        val dataUrl = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
        return QqMediaResult.Success(
            dataUrl = dataUrl, mime = mime, bytes = bytes.size, source = source
        )
    }

    /** 当前缓存条数（诊断/测试用）。 */
    fun cacheSize(): Int = cache.size

    /** 缓存占用字节（近似；诊断用）。 */
    fun cachedBytes(): Long = cachedBytes.get()

    private fun put(key: String, entry: Entry) {
        cache.put(key, entry)?.let { cachedBytes.addAndGet(-it.bytes.toLong()) }
        cachedBytes.addAndGet(entry.bytes.toLong())
        // 2026-11-15（内存优化；US-3132）：**按字节淘汰**，不能只看条数。
        //
        // 旧实现只 `while (cache.size > maxEntries)`，而 `cachedBytes` 只统计不参与淘汰：
        // 30 条 × 单图 8MB，且存的是 base64 String（UTF-16 双倍）→ 最坏可达数百 MB。
        // 真机 PSS 里 Java Heap 230MB 有一部分就是它。现在同时受条数与字节双重约束，
        // 且用 LRU（按 at 最旧）逐条淘汰直到两个上限都满足。
        while (cache.size > maxEntries ||
            (cacheBytesLimit > 0 && cachedBytes.get() > cacheBytesLimit)
        ) {
            val oldest = cache.entries.minByOrNull { it.value.at }?.key ?: break
            evict(oldest)
        }
    }

    private fun evict(key: String) {
        cache.remove(key)?.let { cachedBytes.addAndGet(-it.bytes.toLong()) }
    }

    companion object {
        /** 日志 TAG（2026-11-15：ADR-075 起失败必须落日志）。 */
        private const val TAG = "QqMediaFetcher"

        /** 单张图片上限（8MB；QQ 常见 100KB~2MB）。 */
        const val MAX_BYTES: Int = 8 * 1024 * 1024

        /** rkey 观测表上限（超过整体清空；只用于"近期复用"判定）。 */
        private const val MAX_RKEY_TRACKED = 64

        /** 缓存条数。 */
        const val MAX_ENTRIES: Int = 8

        /**
         * 缓存原始字节上限（2026-11-15 内存优化）。
         *
         * 12MB：约等于 6 张 2MB 图片，足够覆盖"同一条消息被事件+历史各读一次"的场景；
         * 而 base64 后实际 String 约占 2~3 倍（String 是 UTF-16），即 ~25-35MB 上界。
         * 旧实现只有 30 条上限 → 最坏 30×8MB×~2.7 ≈ 650MB 的隐患。
         */
        const val MAX_CACHE_BYTES: Long = 12L * 1024 * 1024

        /** 缓存有效期（10 分钟；QQ CDN 的 rkey 有时效，过期即重下）。 */
        const val CACHE_TTL_MS: Long = 10 * 60 * 1000L

        /** 允许的 CDN 域名（精确或后缀）。 */
        private val ALLOWED_HOSTS = listOf("multimedia.nt.qq.com.cn", "gchat.qpic.cn")
        private val ALLOWED_SUFFIXES = listOf(".qpic.cn", ".qq.com", ".qq.com.cn")

        /**
         * URL 是否允许下载。
         *
         * 规则：`https` + 主机在白名单（精确或后缀）+ 无用户信息段（防 `https://user@host` 混淆）。
         */
        fun isAllowedUrl(rawUrl: String): Boolean {
            if (rawUrl.isBlank() || rawUrl.length > MAX_URL_LENGTH) return false
            val uri = runCatching { java.net.URI(rawUrl.trim()) }.getOrNull() ?: return false
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            if (!uri.userInfo.isNullOrBlank()) return false
            val host = uri.host?.lowercase() ?: return false
            if (ALLOWED_HOSTS.any { host == it }) return true
            return ALLOWED_SUFFIXES.any { host.endsWith(it) }
        }

        /** 通过 magic bytes 判定图片 MIME（不认识返回 null）。 */
        fun detectMime(bytes: ByteArray): String? {
            if (bytes.size < 12) return null
            val b = bytes
            return when {
                b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
                b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
                    b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> "image/png"
                b[0] == 0x47.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte() -> "image/gif"
                b[0] == 0x52.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte() &&
                    b[3] == 0x46.toByte() &&
                    b[8] == 0x57.toByte() && b[9] == 0x45.toByte() &&
                    b[10] == 0x42.toByte() && b[11] == 0x50.toByte() -> "image/webp"
                else -> null
            }
        }

        private const val MAX_URL_LENGTH = 4096

        /** 生产下载实现：只读前 [limit] 字节，超限即放弃。 */
        private fun httpGet(url: String, timeoutMs: Int, limit: Int): ByteArray? {
            val conn = (URL(url).openConnection() as? HttpURLConnection) ?: return null
            return try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false // 白名单校验只对原始 URL 生效，不跟随重定向
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Prism/1.0 (QQ media fetch)")
                if (conn.responseCode !in 200..299) return null
                val declared = conn.contentLengthLong
                if (declared > limit) return null
                val out = java.io.ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > limit) return null
                        out.write(buffer, 0, read)
                    }
                }
                out.toByteArray()
            } finally {
                runCatching { conn.disconnect() }
            }
        }
    }
}
