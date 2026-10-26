package io.prism.qq

import io.prism.qq.channel.QqMediaKind
import io.prism.qq.channel.QqMediaRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [QqMediaFetcher]：白名单/尺寸/缓存/降级（2026-10-26；PRD US-3124）。 */
class QqMediaFetcherTest {

    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0, 0x10, 0x4A, 0x46, 0x49, 0x46, 0, 1, 0
    )

    /** QQ CDN 真实形状（真机取证）。 */
    private val realUrl = "https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=abc&rkey=def"

    @Test
    fun `真实 QQ CDN 地址被允许`() {
        assertTrue(QqMediaFetcher.isAllowedUrl(realUrl))
        assertTrue(QqMediaFetcher.isAllowedUrl("https://gchat.qpic.cn/gchatpic_new/1/2-3.jpg"))
        assertTrue(QqMediaFetcher.isAllowedUrl("https://p.qpic.cn/x.jpg"))
    }

    @Test
    fun `非白名单域名被拒绝`() {
        assertFalse(QqMediaFetcher.isAllowedUrl("https://evil.example.com/a.jpg"))
        assertFalse(QqMediaFetcher.isAllowedUrl("https://qq.com.evil.example/a.jpg"))
        assertFalse(QqMediaFetcher.isAllowedUrl("http://multimedia.nt.qq.com.cn/a.jpg"))
        assertFalse(QqMediaFetcher.isAllowedUrl(""))
        assertFalse(QqMediaFetcher.isAllowedUrl("ftp://multimedia.nt.qq.com.cn/a.jpg"))
    }

    @Test
    fun `userInfo 混淆被拒绝`() {
        assertFalse(QqMediaFetcher.isAllowedUrl("https://user@multimedia.nt.qq.com.cn/a.jpg"))
    }

    @Test
    fun `MIME 由 magic bytes 判定`() {
        assertEquals("image/jpeg", QqMediaFetcher.detectMime(jpeg))
        assertEquals(
            "image/png",
            QqMediaFetcher.detectMime(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
            )
        )
        assertEquals(
            "image/gif",
            QqMediaFetcher.detectMime(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0, 0, 0, 0, 0, 0))
        )
        assertNull(QqMediaFetcher.detectMime(ByteArray(20))) // 全 0 不是图片
    }

    @Test
    fun `下载成功转 data URL 并命中缓存`() = runBlocking {
        var calls = 0
        val fetcher = QqMediaFetcher(download = { _, _, _ -> calls++; jpeg }, clock = { 1000L })
        val ref = QqMediaRef(QqMediaKind.IMAGE, url = realUrl, fileName = "a.jpg")
        val first = fetcher.dataUrl(ref)
        val second = fetcher.dataUrl(ref)
        assertTrue(first != null && first!!.startsWith("data:image/jpeg;base64,"))
        assertEquals(first, second)
        assertEquals(1, calls) // 第二次命中缓存，不重复下载
    }

    @Test
    fun `超限下载被拒绝`() = runBlocking {
        val big = jpeg + ByteArray(64)
        val fetcher = QqMediaFetcher(
            maxBytes = 32,
            download = { _, _, _ -> big },
            clock = { 1000L }
        )
        assertNull(fetcher.dataUrl(QqMediaRef(QqMediaKind.IMAGE, url = realUrl, fileName = "b.jpg")))
    }

    @Test
    fun `非图片内容被拒绝（防把错误页当图片）`() = runBlocking {
        val html = "<html>error</html>".toByteArray()
        val fetcher = QqMediaFetcher(download = { _, _, _ -> html }, clock = { 1000L })
        assertNull(fetcher.dataUrl(QqMediaRef(QqMediaKind.IMAGE, url = realUrl, fileName = "c")))
    }

    @Test
    fun `下载抛异常时降级为 null`() = runBlocking {
        val fetcher = QqMediaFetcher(download = { _, _, _ -> throw java.io.IOException("boom") })
        assertNull(fetcher.dataUrl(QqMediaRef(QqMediaKind.IMAGE, url = realUrl, fileName = "d")))
    }

    @Test
    fun `语音与内置表情不下载`() = runBlocking {
        var calls = 0
        val fetcher = QqMediaFetcher(download = { _, _, _ -> calls++; jpeg })
        assertNull(fetcher.dataUrl(QqMediaRef(QqMediaKind.VOICE, url = realUrl)))
        assertNull(fetcher.dataUrl(QqMediaRef(QqMediaKind.FACE, summary = "14")))
        assertEquals(0, calls)
    }

    @Test
    fun `缓存超过上限时淘汰最旧`() = runBlocking {
        val fetcher = QqMediaFetcher(
            maxEntries = 2,
            download = { _, _, _ -> jpeg },
            clock = { 1000L }
        )
        repeat(4) { i ->
            fetcher.dataUrl(QqMediaRef(QqMediaKind.IMAGE, url = realUrl, fileName = "f$i"))
        }
        assertTrue(fetcher.cacheSize() <= 2)
    }
}
