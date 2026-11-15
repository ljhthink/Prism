package io.prism.qq

import io.prism.qq.channel.QqMediaKind
import io.prism.qq.channel.QqMediaRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体失败分类 + 本地回退（2026-11-15；ADR-075 / PRD US-3134）。
 *
 * 真机事故：NapCat rkey 三通道全挂 → 图片 URL 用占位 rkey → CDN 必然 400 →
 * 旧实现静默吞错 → LLM 回「看到个头--1793kb」（把文件大小当成了画面内容）。
 */
class QqMediaFailureTest {

    /** 真机取证的降级 URL 形状（rkey 在 4 张不同图片上完全相同）。 */
    private val fallbackUrl =
        "https://gchat.qpic.cn/download?appid=1406&fileid=EhQqFlPljGbgxUuMlBQhWSfBcLReGRi1j3Ag" +
            "_gool6iR3N-JmAMyBHByb2RQgLsvWhAkr4q_ATI-coiJnGz5jAIqegJ3soIBAm5q" +
            "&rkey=CAQSKAB6JWENi5LM_xp9vumLbuThJSaYf-yzMrbZsuq7Uz2qEc3Rbib9LP4&spec=0"

    /** 正常签名 URL（multimedia 域，无 spec=0）。 */
    private val normalUrl =
        "https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=abc&rkey=REALKEY123"

    @Test
    fun `降级地址被识别`() {
        assertTrue(QqMediaFailure.looksLikeFallbackUrl(fallbackUrl))
        assertFalse(QqMediaFailure.looksLikeFallbackUrl(normalUrl))
        assertFalse("缺 rkey 不算", QqMediaFailure.looksLikeFallbackUrl(
            "https://gchat.qpic.cn/download?appid=1406&spec=0"
        ))
        assertFalse("空串", QqMediaFailure.looksLikeFallbackUrl(""))
    }

    @Test
    fun `提取 rkey 与 host`() {
        assertEquals(
            "CAQSKAB6JWENi5LM_xp9vumLbuThJSaYf-yzMrbZsuq7Uz2qEc3Rbib9LP4",
            QqMediaFailure.rkeyOf(fallbackUrl)
        )
        assertNull(QqMediaFailure.rkeyOf(normalUrl.take(40)))
        assertEquals("gchat.qpic.cn", QqMediaFailure.hostOf(fallbackUrl))
        assertEquals("", QqMediaFailure.hostOf("not a url"))
    }

    @Test
    fun `同一 rkey 被多张图片复用即判定失效`() {
        // 第 1 张：还只是"可疑"，不判失效（可能是巧合）
        assertFalse(QqMediaFailure.isRkeyExpired(fallbackUrl, 1))
        // 第 2 张起：占位符无疑
        assertTrue(QqMediaFailure.isRkeyExpired(fallbackUrl, 2))
        assertTrue(QqMediaFailure.isRkeyExpired(fallbackUrl, 99))
        // 正常 URL 不论复用多少次都不是 rkey 失效
        assertFalse(QqMediaFailure.isRkeyExpired(normalUrl, 99))
    }

    @Test
    fun `HTTP 状态分类`() {
        // 降级地址 + 400 → rkey 失效（真机实测码）
        assertEquals(
            QqMediaResult.Kind.RKEY_EXPIRED,
            QqMediaFailure.classifyHttp(400, fallbackUrl)
        )
        // 非降级地址的 400 → 网络/对端拒绝
        assertEquals(QqMediaResult.Kind.NETWORK, QqMediaFailure.classifyHttp(400, normalUrl))
        assertEquals(QqMediaResult.Kind.RKEY_EXPIRED, QqMediaFailure.classifyHttp(404, normalUrl))
        assertEquals(QqMediaResult.Kind.NETWORK, QqMediaFailure.classifyHttp(500, normalUrl))
    }

    @Test
    fun `只有签名或网络类失败才值得本地回退`() {
        assertTrue(QqMediaFailure.shouldTryLocalFallback(QqMediaResult.Kind.RKEY_EXPIRED))
        assertTrue(QqMediaFailure.shouldTryLocalFallback(QqMediaResult.Kind.NETWORK))
        assertFalse("安全策略拒绝绝不回退", QqMediaFailure.shouldTryLocalFallback(
            QqMediaResult.Kind.POLICY_REJECTED
        ))
        assertFalse(QqMediaFailure.shouldTryLocalFallback(QqMediaResult.Kind.BAD_CONTENT))
        assertFalse(QqMediaFailure.shouldTryLocalFallback(QqMediaResult.Kind.NO_VISUAL))
    }

    @Test
    fun `给模型的说明绝不能只有文件大小`() {
        // 真机教训：旧实现给的是「…约 1793KB」，模型把大小当成画面内容
        QqMediaResult.Kind.values().forEach { kind ->
            val note = QqMediaFailure.noteFor(kind)
            assertFalse("$kind 的说明不得只提 KB", note.contains("KB"))
            assertTrue("$kind 的说明不得为空", note.isNotBlank())
        }
        // rkey 失效必须明确"看不到画面"
        assertTrue(
            QqMediaFailure.noteFor(QqMediaResult.Kind.RKEY_EXPIRED).contains("看不到画面")
        )
    }

    @Test
    fun `只有图片与表情包有画面`() {
        assertTrue(QqMediaFailure.hasVisual(QqMediaRef(QqMediaKind.IMAGE)))
        assertTrue(QqMediaFailure.hasVisual(QqMediaRef(QqMediaKind.STICKER)))
        assertFalse(QqMediaFailure.hasVisual(QqMediaRef(QqMediaKind.FACE)))
        assertFalse(QqMediaFailure.hasVisual(QqMediaRef(QqMediaKind.VOICE)))
        assertFalse(QqMediaFailure.hasVisual(QqMediaRef(QqMediaKind.VIDEO)))
    }
}

/**
 * [QqMediaFetcher] 的失败返回与本地回退（2026-11-15；ADR-075）。
 */
class QqMediaFetcherFallbackTest {

    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0, 0x10, 0x4A, 0x46, 0x49, 0x46, 0, 1, 0
    )

    private val fallbackUrl =
        "https://gchat.qpic.cn/download?appid=1406&fileid=abc&rkey=STATICKEY&spec=0"

    private fun ref(name: String = "a.jpg", url: String = fallbackUrl) = QqMediaRef(
        QqMediaKind.STICKER, url = url, fileName = name, fileSize = 1836981
    )

    @Test
    fun `CDN 失败时不再静默 而是返回带原因的 Failure`() = runBlocking {
        val fetcher = QqMediaFetcher(
            download = { _, _, _ -> null },   // 模拟 400
            clock = { 1000L }
        )
        val r = fetcher.fetch(ref())
        assertTrue("必须是结构化失败，不能是裸 null", r is QqMediaResult.Failure)
        assertEquals(QqMediaResult.Kind.RKEY_EXPIRED, (r as QqMediaResult.Failure).kind)
    }

    @Test
    fun `CDN 失败后走本地回退并成功`() = runBlocking {
        var fallbackCalls = 0
        val fetcher = QqMediaFetcher(
            download = { _, _, _ -> null },
            localFallback = { fallbackCalls++; jpeg },
            clock = { 1000L }
        )
        val r = fetcher.fetch(ref())
        assertEquals(1, fallbackCalls)
        assertTrue(r is QqMediaResult.Success)
        val s = r as QqMediaResult.Success
        assertEquals(QqMediaResult.Source.LOCAL_FILE, s.source)
        assertTrue(s.dataUrl.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `本地回退也失败时如实返回失败`() = runBlocking {
        val fetcher = QqMediaFetcher(
            download = { _, _, _ -> null },
            localFallback = { null },
            clock = { 1000L }
        )
        val r = fetcher.fetch(ref())
        assertTrue(r is QqMediaResult.Failure)
        assertEquals(QqMediaResult.Kind.RKEY_EXPIRED, (r as QqMediaResult.Failure).kind)
    }

    @Test
    fun `安全策略拒绝时不回退`() = runBlocking {
        var fallbackCalls = 0
        val fetcher = QqMediaFetcher(
            download = { _, _, _ -> jpeg },
            localFallback = { fallbackCalls++; jpeg },
            clock = { 1000L }
        )
        val r = fetcher.fetch(ref(url = "https://evil.example.com/a.jpg"))
        assertTrue(r is QqMediaResult.Failure)
        assertEquals(QqMediaResult.Kind.POLICY_REJECTED, (r as QqMediaResult.Failure).kind)
        assertEquals("非白名单不得触发本地回退（安全边界）", 0, fallbackCalls)
    }

    @Test
    fun `无画面的类型不进回退`() = runBlocking {
        var fallbackCalls = 0
        val fetcher = QqMediaFetcher(
            download = { _, _, _ -> jpeg },
            localFallback = { fallbackCalls++; jpeg },
            clock = { 1000L }
        )
        val r = fetcher.fetch(QqMediaRef(QqMediaKind.FACE, summary = "14"))
        assertTrue(r is QqMediaResult.Failure)
        assertEquals(QqMediaResult.Kind.NO_VISUAL, (r as QqMediaResult.Failure).kind)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun `同一 rkey 复用第二次起直接走本地回退 省掉必然超时`() = runBlocking {
        var downloads = 0
        val fetcher = QqMediaFetcher(
            download = { _, _, _ -> downloads++; null },
            localFallback = { jpeg },
            clock = { 1000L }
        )
        // 第 1 张：尝试 CDN（失败）→ 回退
        fetcher.fetch(ref(name = "1.jpg"))
        assertEquals(1, downloads)
        // 第 2 张：rkey 已确认复用 → **跳过 CDN**，直接回退
        fetcher.fetch(ref(name = "2.jpg"))
        assertEquals("占位 rkey 已确认后不应再花时间下必然失败的 URL", 1, downloads)
    }

    @Test
    fun `dataUrl 兼容入口仍工作`() = runBlocking {
        val fetcher = QqMediaFetcher(
            download = { _, _, _ -> jpeg },
            clock = { 1000L }
        )
        val url = fetcher.dataUrl(ref(url = "https://multimedia.nt.qq.com.cn/x.jpg"))
        assertTrue(url != null && url!!.startsWith("data:image/jpeg;base64,"))
    }

    private val ref1 = Unit
}
