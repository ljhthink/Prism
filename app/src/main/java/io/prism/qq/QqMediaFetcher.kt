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
    /** 时钟（缓存淘汰用）。 */
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private data class Entry(val dataUrl: String, val at: Long, val bytes: Int)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val cachedBytes = AtomicLong(0)

    /**
     * 下载并转成 `data:` URL（可直接作为 `image_url` 内容给视觉模型，也可给 OCR 用）。
     *
     * @return `data:image/jpeg;base64,...`；不可用/被拒/失败返回 null
     */
    suspend fun dataUrl(ref: QqMediaRef): String? {
        // 只有图片/表情包有画面；语音/视频/内置表情不处理
        if (ref.kind != QqMediaKind.IMAGE && ref.kind != QqMediaKind.STICKER) return null
        val url = ref.url?.trim().orEmpty()
        if (!isAllowedUrl(url)) return null
        val key = ref.fileName?.takeIf { it.isNotBlank() } ?: ("u:" + url.hashCode())
        cache[key]?.let { hit ->
            if (clock() - hit.at < CACHE_TTL_MS) return hit.dataUrl
            evict(key)
        }
        val bytes = try {
            download(url, timeoutMs, maxBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        if (bytes.isEmpty() || bytes.size > maxBytes) return null
        val mime = detectMime(bytes) ?: return null
        val dataUrl = "data:$mime;base64," +
            Base64.getEncoder().encodeToString(bytes)
        put(key, Entry(dataUrl, clock(), bytes.size))
        return dataUrl
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
        /** 单张图片上限（8MB；QQ 常见 100KB~2MB）。 */
        const val MAX_BYTES: Int = 8 * 1024 * 1024

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
