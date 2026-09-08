package io.prism.embedding

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 批次22 crash-fix（TKN-B22-CRASH-001 方案C，2026-09-06）：[EmbedderFactory.createMultilingualSafe]
 * 降级契约单测。
 *
 * 背景：真机 FATAL 循环崩溃（sherpa AAR pickFirst so 缺 OrtGetApiBase → OrtEnvironment
 * <clinit> 毒化 → 主线程注入 embedder 无兜底）。**契约：native 故障最多降级 RAG 功能，
 * 永不杀死进程**——Safe 工厂必须吞掉 Throwable（含 Error）并返回 [NullEmbedder]。
 */
class EmbedderFactorySafeTest {

    /** 永远抛 IOException 的输入流（模拟 assets 模型读取失败）。 */
    private class BrokenInputStream : InputStream() {
        override fun read(): Int = throw IOException("boom")
    }

    @Test
    fun `createMultilingualSafe - 模型流读取失败降级 NullEmbedder 不抛出`() {
        val embedder = EmbedderFactory.createMultilingualSafe(
            modelInput = BrokenInputStream(),
            tokenizerJsonInput = ByteArrayInputStream("{}".toByteArray())
        )
        assertTrue("失败必须降级为 NullEmbedder", embedder is NullEmbedder)
    }

    @Test
    fun `createMultilingualSafe - tokenizer 解析失败降级 NullEmbedder 不抛出`() {
        // 空 tokenizer.json → UnigramTokenizer 构造失败
        val embedder = EmbedderFactory.createMultilingualSafe(
            modelInput = ByteArrayInputStream(ByteArray(16)),
            tokenizerJsonInput = ByteArrayInputStream(ByteArray(0))
        )
        assertTrue("失败必须降级为 NullEmbedder", embedder is NullEmbedder)
    }

    @Test
    fun `createMultilingualSafe - Error 分支也降级（本次崩溃同款 NoClassDefFoundError 路径）`() {
        // 模拟 native 类毒化场景：构造期抛 Error（runCatching 必须吞掉 Throwable 而非仅 Exception）
        val errorStream = object : InputStream() {
            override fun read(): Int = throw NoClassDefFoundError("ai.onnxruntime.OrtEnvironment")
        }
        val embedder = EmbedderFactory.createMultilingualSafe(
            modelInput = errorStream,
            tokenizerJsonInput = ByteArrayInputStream("{}".toByteArray())
        )
        assertTrue("Error 必须同样降级为 NullEmbedder", embedder is NullEmbedder)
    }
}
