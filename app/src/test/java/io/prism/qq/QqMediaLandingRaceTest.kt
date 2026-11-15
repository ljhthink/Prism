package io.prism.qq

import io.prism.qq.channel.QqMediaKind
import io.prism.qq.channel.QqMediaRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **落盘时序**回归（2026-11-15；ADR-075 / PRD US-3134）。
 *
 * 真机根因（第三次反馈"还是不行，一片空白"）：
 * NapCat 给出 `file` 名**不代表字节已落盘**。收到事件瞬间 `find` 会 `not_found`，
 * QQ 还在后台下载；实测 26KB 的表情 prime 后 6 秒仍找不到，数十秒后才出现。
 *
 * 这解释了"我手动测能过、用户实机收不到"——手动测用的是早已落盘的老图。
 */
class QqMediaLandingRaceTest {

    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0, 0x10, 0x4A, 0x46, 0x49, 0x46, 0, 1, 0
    )

    private fun ref(name: String = "a.jpg") = QqMediaRef(
        QqMediaKind.STICKER,
        url = "https://gchat.qpic.cn/download?appid=1406&rkey=STATIC&spec=0",
        fileName = name
    )

    @Test
    fun `prime 必须在提交脚本之前发生`() = runBlocking {
        val order = mutableListOf<String>()
        val bridge = QqMediaLocalBridge(
            context = FakeContext(),
            primeDownload = { order += "prime" },
            launch = { order += "launch"; false },
            readText = { null },
            readBytes = { null },
            sleep = { },
            clock = { 1000L }
        )
        bridge.fetchBytes(ref())
        assertEquals(
            "顺序错了会稳定 not_found（字节还没落盘就去 find）",
            listOf("prime", "launch"),
            order
        )
    }

    @Test
    fun `没有 prime 能力时仍可工作`() = runBlocking {
        var launched = 0
        val bridge = QqMediaLocalBridge(
            context = FakeContext(),
            primeDownload = null,
            launch = { launched++; false },
            readText = { null },
            readBytes = { null },
            sleep = { },
            clock = { 1000L }
        )
        assertNull(bridge.fetchBytes(ref()))
        assertEquals(1, launched)
    }

    @Test
    fun `prime 抛异常不应阻断后续流程`() = runBlocking {
        var launched = 0
        val bridge = QqMediaLocalBridge(
            context = FakeContext(),
            primeDownload = { throw IllegalStateException("boom") },
            launch = { launched++; false },
            readText = { null },
            readBytes = { null },
            sleep = { },
            clock = { 1000L }
        )
        assertNull(bridge.fetchBytes(ref()))
        assertEquals("prime 失败也要继续尝试直取", 1, launched)
    }

    @Test
    fun `文件名不安全时既不 prime 也不提交`() = runBlocking {
        var primed = 0
        var launched = 0
        val bridge = QqMediaLocalBridge(
            context = FakeContext(),
            primeDownload = { primed++ },
            launch = { launched++; true },
            readText = { "ok=1 size=1 reason=" },
            readBytes = { jpeg },
            sleep = { },
            clock = { 1000L }
        )
        assertNull(bridge.fetchBytes(ref(name = "../../etc/passwd")))
        assertEquals("非法文件名必须在最外层拦住", 0, primed)
        assertEquals(0, launched)
    }

    @Test
    fun `成功的字节会被完整返回`() = runBlocking {
        val payload = ByteArray(1024) { (it % 251).toByte() }
        val bridge = QqMediaLocalBridge(
            context = FakeContext(),
            primeDownload = { },
            launch = { true },
            readText = { "ok=1 ts=1 size=1024 reason=" },
            readBytes = { payload },
            sleep = { },
            clock = { 1000L }
        )
        val out = bridge.fetchBytes(ref())
        assertEquals(1024, out?.size)
    }

    @Test
    fun `回执缺失时按超时处理而不是误判成功`() = runBlocking {
        var t = 0L
        val bridge = QqMediaLocalBridge(
            context = FakeContext(),
            primeDownload = { },
            launch = { true },
            readText = { null },
            readBytes = { jpeg },
            sleep = { },
            clock = { t += 20_000L; t }
        )
        assertNull(bridge.fetchBytes(ref()))
    }

    @Test
    fun `回执解析支持完整格式`() {
        val r = QqMediaLocalBridge.parseReceipt("ok=1 ts=1794740935 size=1836981 reason=")
        assertTrue(r!!.ok)
        assertEquals(1836981L, r.size)
        assertEquals("", r.reason)

        val bad = QqMediaLocalBridge.parseReceipt(
            "ok=0 ts=1794740935 size=0 reason=not_found_in_container"
        )
        assertTrue(!bad!!.ok)
        assertEquals("not_found_in_container", bad.reason)

        assertNull(QqMediaLocalBridge.parseReceipt("garbage without keys"))
        assertNull(QqMediaLocalBridge.parseReceipt(null))
    }

    private class FakeContext : android.content.ContextWrapper(null)
}
