package io.prism.qq

import io.prism.qq.channel.QqMediaKind
import io.prism.qq.channel.QqMediaRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 本地文件回退的**安全边界**与回执解析（2026-11-15；ADR-075 / PRD US-3134）。
 *
 * 这一层是唯一"由事件内容驱动去读手机文件"的路径，校验必须严；
 * 且成功/失败都要能从回执里读出可读原因（否则又回到"静默失败"）。
 */
class QqMediaLocalBridgeTest {

    // ---------- 文件名白名单（安全边界）----------

    @Test
    fun `合法 QQ 文件名被接受`() {
        assertEquals(
            "523119BEA8C6C0749FD09A0359C0E1F2.jpg",
            QqMediaLocalBridge.sanitizeName("523119BEA8C6C0749FD09A0359C0E1F2.jpg")
        )
        assertEquals("a.png", QqMediaLocalBridge.sanitizeName("a.png"))
        assertEquals("IMG-1_2.webp", QqMediaLocalBridge.sanitizeName("IMG-1_2.webp"))
    }

    @Test
    fun `路径穿越与注入被拒绝`() {
        val bad = listOf(
            "../../etc/passwd",
            "..%2f..%2fetc",
            "a/../b.jpg",
            "/etc/shadow.jpg",
            "a.jpg;rm -rf /",
            "a.jpg\$(whoami)",
            "a.jpg`id`",
            "a.jpg|nc evil 80",
            "a b.jpg",
            "-rf.jpg",
            "",
            "   ",
            null
        )
        bad.forEach { name ->
            assertNull("必须拒绝：$name", QqMediaLocalBridge.sanitizeName(name))
        }
    }

    @Test
    fun `非图片扩展名被拒绝`() {
        listOf("a.sh", "a.txt", "a.apk", "a.so", "a.onnx", "a").forEach {
            assertNull("必须拒绝非图片：$it", QqMediaLocalBridge.sanitizeName(it))
        }
    }

    @Test
    fun `过长文件名被拒绝`() {
        val long = "a".repeat(80) + ".jpg"
        assertNull("超过 80 字符必须拒绝", QqMediaLocalBridge.sanitizeName(long))
    }

    // ---------- 回执解析与产物读取 ----------

    @Test
    fun `回执 ok=1 时返回字节`() = runBlocking {
        val bridge = QqMediaLocalBridge(
            context = FakeContext(),
            launch = { true },
            readText = { "ok=1 ts=1794738000 size=1836981 reason=" },
            readBytes = { byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3) },
            sleep = { },
            clock = { 1000L }
        )
        val bytes = bridge.fetchBytes(QqMediaRef(QqMediaKind.STICKER, fileName = "a.jpg"))
        assertEquals(5, bytes?.size)
    }

    @Test
    fun `回执 ok=0 时返回 null`() = runBlocking {
        val bridge = QqMediaLocalBridge(
            context = FakeContext(),
            launch = { true },
            readText = { "ok=0 ts=1794738000 size=0 reason=not_found_in_container" },
            readBytes = { byteArrayOf(1) },
            sleep = { },
            clock = { 1000L }
        )
        assertNull(bridge.fetchBytes(QqMediaRef(QqMediaKind.STICKER, fileName = "a.jpg")))
    }

    @Test
    fun `回执 ok=1 但产物为空时返回 null`() = runBlocking {
        val bridge = QqMediaLocalBridge(
            context = FakeContext(),
            launch = { true },
            readText = { "ok=1 ts=1 size=0 reason=" },
            readBytes = { null },
            sleep = { },
            clock = { 1000L }
        )
        assertNull(bridge.fetchBytes(QqMediaRef(QqMediaKind.STICKER, fileName = "a.jpg")))
    }

    @Test
    fun `提交失败时不读回执直接返回 null`() = runBlocking {
        var readCount = 0
        val bridge = QqMediaLocalBridge(
            context = FakeContext(),
            launch = { false },
            readText = { readCount++; "ok=1" },
            readBytes = { byteArrayOf(1) },
            sleep = { },
            clock = { 1000L }
        )
        assertNull(bridge.fetchBytes(QqMediaRef(QqMediaKind.STICKER, fileName = "a.jpg")))
        assertEquals("提交失败不应轮询", 0, readCount)
    }

    @Test
    fun `文件名不安全时根本不提交脚本`() = runBlocking {
        var launched = 0
        val bridge = QqMediaLocalBridge(
            context = FakeContext(),
            launch = { launched++; true },
            readText = { "ok=1" },
            readBytes = { byteArrayOf(1) },
            sleep = { },
            clock = { 1000L }
        )
        assertNull(bridge.fetchBytes(QqMediaRef(QqMediaKind.STICKER, fileName = "../../x.jpg")))
        assertEquals("非法文件名必须在本地拦住，不进入 shell", 0, launched)
    }

    /** 仅提供 [android.content.Context] 的最小占位（本类在 JVM 测试中不用它的方法）。 */
    private class FakeContext : android.content.ContextWrapper(null)
}
