package io.prism.qq

import io.prism.qq.channel.QqMediaKind
import io.prism.qq.channel.QqMediaRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `QqMediaFetcher` 的**字节上限淘汰**回归（2026-11-15；ADR-074 / guardrail 要求的最小测试）。
 *
 * 旧缺陷：`cachedBytes` 只统计、不参与淘汰，`put()` 只看条数 →
 * 30 条 × 单图最大 8MB、且存的是 base64 String（UTF-16 双倍），最坏可达数百 MB。
 * 真机 PSS 里 Java Heap 230MB 有一部分就是它。
 */
class QqMediaFetcherCacheLimitTest {

    private fun jpegOf(size: Int): ByteArray {
        val b = ByteArray(size)
        b[0] = 0xFF.toByte(); b[1] = 0xD8.toByte()
        b[2] = 0xFF.toByte(); b[3] = 0xE0.toByte()
        return b
    }

    private fun ref(name: String) = QqMediaRef(
        QqMediaKind.IMAGE,
        url = "https://multimedia.nt.qq.com.cn/download?file=$name",
        fileName = name
    )

    @Test
    fun `超过字节上限会淘汰旧条目`() {
        // 每条 4MB，上限 10MB → 最多留 2 条
        val fetcher = QqMediaFetcher(
            maxEntries = 8,
            cacheBytesLimit = 10L * 1024 * 1024,
            download = { _, _, _ -> jpegOf(4 * 1024 * 1024) },
            clock = { 1000L }
        )
        runBlocking {
            fetcher.dataUrl(ref("a.jpg"))
            fetcher.dataUrl(ref("b.jpg"))
            fetcher.dataUrl(ref("c.jpg"))
        }
        assertTrue(
            "字节上限必须参与淘汰（旧实现只按条数，会留满 8 条×4MB=32MB）",
            fetcher.cacheSize() <= 2
        )
        assertTrue(
            "缓存字节不得超过上限",
            fetcher.cachedBytes() <= 10L * 1024 * 1024
        )
    }

    @Test
    fun `条数上限仍生效`() {
        val fetcher = QqMediaFetcher(
            maxEntries = 2,
            cacheBytesLimit = 0L, // 不限制字节，专测条数
            download = { _, _, _ -> jpegOf(1024) },
            clock = { 1000L }
        )
        runBlocking {
            fetcher.dataUrl(ref("1.jpg"))
            fetcher.dataUrl(ref("2.jpg"))
            fetcher.dataUrl(ref("3.jpg"))
        }
        assertEquals("条数上限 2", 2, fetcher.cacheSize())
    }

    @Test
    fun `生产默认值同时满足条数与字节双约束`() {
        // 不注入上限，直接用生产常量
        val fetcher = QqMediaFetcher(
            download = { _, _, _ -> jpegOf(3 * 1024 * 1024) },
            clock = { 1000L }
        )
        runBlocking {
            repeat(6) { i -> fetcher.dataUrl(ref("x$i.jpg")) }
        }
        assertTrue("条数不超过 $QqMediaFetcher_MAX_ENTRIES_DOC", fetcher.cacheSize() <= 8)
        assertTrue(
            "字节不超过 ${QqMediaFetcher.MAX_CACHE_BYTES / 1024 / 1024}MB",
            fetcher.cachedBytes() <= QqMediaFetcher.MAX_CACHE_BYTES
        )
    }

    /** 生产条数常量（便于断言文案；避免直接引用私有字段）。 */
    private val QqMediaFetcher_MAX_ENTRIES_DOC = 8
}
