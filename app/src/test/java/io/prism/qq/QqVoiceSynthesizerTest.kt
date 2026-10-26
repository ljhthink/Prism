package io.prism.qq

import io.prism.voice.local.ExportableTtsEngine
import io.prism.voice.local.LocalCloneAudio
import io.prism.voice.local.LocalCloneConfig
import io.prism.voice.local.LocalVoicePack
import io.prism.voice.local.LocalReference
import io.prism.voice.local.LocalTtsKind
import io.prism.voice.local.LocalModelFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [QqVoiceSynthesizer] 选择/缓存/释放行为测试（guardrail M-11）。
 *
 * 引擎内部合成路径由 `LocalCloneTtsEngineTest` 的导出用例覆盖；此处用假引擎验证
 * 「同签名复用、签名变更释放、选择消失释放、失败透传、release 幂等」。
 */
class QqVoiceSynthesizerTest {

    private class FakeEngine(
        private val audio: LocalCloneAudio? = LocalCloneAudio(floatArrayOf(0.1f), 16_000),
        private val failure: Throwable? = null
    ) : ExportableTtsEngine {
        var synthCalls = 0
            private set
        var releases = 0
            private set

        override suspend fun synthesizeForExport(text: String): LocalCloneAudio? {
            synthCalls++
            failure?.let { throw it }
            return audio
        }

        override fun release() {
            releases++
        }
    }

    private fun pack() = LocalVoicePack(
        voiceId = "cyrene",
        name = "测试音色",
        root = File("."),
        tts = LocalTtsKind.ZIPVOICE,
        model = LocalModelFiles.ZipVoice(
            File("tokens.txt"), File("encoder.onnx"), File("decoder.onnx"),
            File("vocos.onnx"), File("lexicon.txt"), File("espeak-ng-data")
        ),
        generation = LocalCloneConfig(),
        references = listOf(
            LocalReference(id = "ref_00", audio = File("ref_00.wav"), text = "你好", emotion = "平静")
        ),
        totalBytes = 0L
    )

    private fun selection(sig: String) =
        QqVoiceSynthesizer.Selection(pack = pack(), config = LocalCloneConfig(), signature = sig)

    @Test
    fun `同签名复用引擎，不重复创建`() = runBlocking {
        var factoryCalls = 0
        val engines = mutableListOf<FakeEngine>()
        val synth = QqVoiceSynthesizer(
            selectionProvider = { selection("sig-1") },
            engineFactory = { _, _ ->
                factoryCalls++
                FakeEngine().also { engines += it }
            },
            idleReleaseMs = 60_000L
        )

        assertTrue(synth.synthesize("第一句") != null)
        assertTrue(synth.synthesize("第二句") != null)

        assertEquals("同签名只应创建一次引擎", 1, factoryCalls)
        assertEquals("两次合成应复用同一引擎", 2, engines.single().synthCalls)
        synth.release()
    }

    @Test
    fun `签名变化时释放旧引擎并重建`() = runBlocking {
        var factoryCalls = 0
        val engines = mutableListOf<FakeEngine>()
        var sig = "sig-1"
        val synth = QqVoiceSynthesizer(
            selectionProvider = { selection(sig) },
            engineFactory = { _, _ ->
                factoryCalls++
                FakeEngine().also { engines += it }
            },
            idleReleaseMs = 60_000L
        )

        synth.synthesize("第一句")
        sig = "sig-2"
        synth.synthesize("第二句")

        assertEquals(2, factoryCalls)
        assertEquals("旧引擎必须被释放", 1, engines[0].releases)
        assertEquals(0, engines[1].releases)
        synth.release()
    }

    @Test
    fun `音色包消失时立即释放已有引擎`() = runBlocking {
        var selected: QqVoiceSynthesizer.Selection? = selection("sig-1")
        val engine = FakeEngine()
        val synth = QqVoiceSynthesizer(
            selectionProvider = { selected },
            engineFactory = { _, _ -> engine },
            idleReleaseMs = 60_000L
        )

        synth.synthesize("第一句")
        selected = null
        assertNull(synth.synthesize("第二句"))

        assertEquals("音色包消失应释放引擎", 1, engine.releases)
        synth.release()
    }

    @Test
    fun `空文本与合成失败不创建或调用引擎`() = runBlocking {
        var factoryCalls = 0
        val synth = QqVoiceSynthesizer(
            selectionProvider = { selection("sig-1") },
            engineFactory = { _, _ ->
                factoryCalls++
                null
            },
            idleReleaseMs = 60_000L
        )

        assertNull(synth.synthesize("   "))
        assertEquals(0, factoryCalls)
        assertNull("引擎工厂返回 null 时应返回 null", synth.synthesize("在吗"))
        synth.release()
    }

    @Test
    fun `release 幂等且释放引擎`() = runBlocking {
        val engine = FakeEngine()
        var factoryCalls = 0
        val synth = QqVoiceSynthesizer(
            selectionProvider = { selection("sig-1") },
            engineFactory = { _, _ ->
                factoryCalls++
                engine
            },
            idleReleaseMs = 60_000L
        )

        synth.synthesize("第一句")
        synth.release()
        synth.release()

        assertEquals("release 只应真正释放一次（第二次无引擎）", 1, engine.releases)
    }

    @Test
    fun `空闲超时自动释放引擎`() = runBlocking {
        val engine = FakeEngine()
        val synth = QqVoiceSynthesizer(
            selectionProvider = { selection("sig-1") },
            engineFactory = { _, _ -> engine },
            idleReleaseMs = 30L
        )

        synth.synthesize("第一句")
        var released = false
        for (i in 0 until 50) {
            if (engine.releases > 0) {
                released = true
                break
            }
            kotlinx.coroutines.delay(10)
        }
        assertTrue("空闲到期应释放引擎", released)
        synth.release()
    }

    @Test
    fun `合成抛异常后引擎仍会按空闲计时释放（M-10）`() = runBlocking {
        val engine = FakeEngine(failure = IllegalStateException("native boom"))
        val synth = QqVoiceSynthesizer(
            selectionProvider = { selection("sig-1") },
            engineFactory = { _, _ -> engine },
            idleReleaseMs = 30L
        )

        assertNull("异常应转为 null", synth.synthesize("会抛错的文本"))
        var released = false
        for (i in 0 until 50) {
            if (engine.releases > 0) {
                released = true
                break
            }
            kotlinx.coroutines.delay(10)
        }
        assertTrue("异常路径也必须重排空闲释放", released)
        synth.release()
    }

    @Test
    fun `新合成会取消旧的空闲计时（D-08）`() = runBlocking {
        val engine = FakeEngine()
        val synth = QqVoiceSynthesizer(
            selectionProvider = { selection("sig-1") },
            engineFactory = { _, _ -> engine },
            idleReleaseMs = 250L
        )

        synth.synthesize("第一句")
        kotlinx.coroutines.delay(150)
        synth.synthesize("第二句")
        // 距第一句已 150ms、距第二句 0ms；再等 150ms（第一句 300ms / 第二句 150ms）
        kotlinx.coroutines.delay(150)
        assertEquals("旧计时必须被取消，不得提前释放", 0, engine.releases)

        // 第二句之后再等 150ms（累计 300ms > 250ms）才应释放
        kotlinx.coroutines.delay(150)
        assertTrue("新的空闲计时到期应释放", engine.releases > 0)
        synth.release()
    }

    @Test
    fun `releaseAsync 非阻塞释放引擎`() = runBlocking {
        val engine = FakeEngine()
        val synth = QqVoiceSynthesizer(
            selectionProvider = { selection("sig-1") },
            engineFactory = { _, _ -> engine },
            idleReleaseMs = 60_000L
        )
        synth.synthesize("第一句")

        synth.releaseAsync()

        var released = false
        for (i in 0 until 50) {
            if (engine.releases > 0) {
                released = true
                break
            }
            kotlinx.coroutines.delay(10)
        }
        assertTrue("releaseAsync 应异步释放", released)
    }
}
