package io.prism.voice.local

import io.prism.voice.LocalClonePlaybackMode
import io.prism.voice.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * TKN-LVC-016（ADR-056）：播报管线双模单测（PRD AC-1 / AC-2）。
 *
 * 本类用**真实时间与真实线程**（IO 调度器 + 阻塞假实现）钉住两条**时序性**契约，
 * 因为它们是本批唯一无法用纯函数表达的部分：
 *
 * - 连续模式：段 N 播放期间段 N+1 **已经在合成**（"一句一句推理再一句一句播"的根因）；
 * - 整段模式：**只调用一次** `play`，且调用发生在**全部合成完成之后**。
 *
 * 时序断言全部用同一单调时钟（`System.nanoTime`）的区间比较，不用"睡够 N 毫秒"，
 * 因此不受机器负载影响（阈值只用于区分"重叠"与"串行"，二者相差一个数量级的播放时长）。
 */
class LocalClonePlaybackModesTest {

    private fun now(): Long = System.nanoTime()

    private fun ms(n: Long): Long = n * 1_000_000L

    /** 假合成器：记录每次合成的起止时刻，并按 [costMs] 阻塞。 */
    private class TimingSynth(
        private val clock: () -> Long,
        private val costMs: Long,
        private val samplesPerSegment: Int = 2,
        private val sampleRate: Int = 24_000
    ) : LocalCloneSynthesizer {
        val starts = CopyOnWriteArrayList<Long>()
        val ends = CopyOnWriteArrayList<Long>()
        val calls = AtomicInteger(0)

        override fun synthesize(
            text: String,
            reference: LocalReference?,
            config: LocalCloneConfig,
            isCancelled: () -> Boolean,
            onChunk: (FloatArray) -> Unit
        ): LocalCloneAudio {
            calls.incrementAndGet()
            starts += clock()
            if (costMs > 0) {
                val deadline = System.currentTimeMillis() + costMs
                while (System.currentTimeMillis() < deadline && !isCancelled()) Thread.sleep(1)
            }
            ends += clock()
            return LocalCloneAudio(FloatArray(samplesPerSegment) { 0.1f }, sampleRate)
        }

        override fun sampleRate(): Int = sampleRate

        override fun release() = Unit
    }

    /** 假播放器：记录每次播放的起止时刻与样本总量，并按 [playMs] 阻塞。 */
    private class TimingPlayer(
        private val clock: () -> Long,
        private val playMs: Long,
        private val streaming: Boolean = false
    ) : LocalClonePlayer {
        val starts = CopyOnWriteArrayList<Long>()
        val ends = CopyOnWriteArrayList<Long>()
        val calls = AtomicInteger(0)
        val totalSamples = AtomicInteger(0)
        val openStreamCalls = AtomicInteger(0)

        override fun play(samples: FloatArray, sampleRate: Int, isCancelled: () -> Boolean) {
            if (isCancelled()) return
            calls.incrementAndGet()
            totalSamples.addAndGet(samples.size)
            starts += clock()
            val deadline = System.currentTimeMillis() + playMs
            while (System.currentTimeMillis() < deadline && !isCancelled()) Thread.sleep(1)
            ends += clock()
        }

        override fun openStream(sampleRate: Int, isCancelled: () -> Boolean): LocalCloneStreamSession? {
            openStreamCalls.incrementAndGet()
            if (!streaming) return null
            return object : LocalCloneStreamSession {
                override val framesWritten: Int get() = 0
                override fun write(samples: FloatArray): Boolean = !isCancelled()
                override fun close() = Unit
            }
        }

        override fun stop() = Unit

        override fun release() = Unit
    }

    private class LatchListener : TtsEngine.Listener {
        val done = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        override fun onStart(utteranceId: String) {
            events += "start"
        }

        override fun onDone(utteranceId: String) {
            events += "done"
            done.countDown()
        }

        override fun onError(utteranceId: String, message: String) {
            events += "error:$message"
            done.countDown()
        }
    }

    private fun ref() = LocalReference(
        id = "ref_00",
        audio = File("ref_00.wav"),
        text = "你好，世界",
        emotion = "平静"
    )

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
        references = listOf(ref()),
        totalBytes = 0L
    )

    private fun engine(
        synth: LocalCloneSynthesizer,
        player: LocalClonePlayer,
        config: LocalCloneConfig = LocalCloneConfig(),
        mode: LocalClonePlaybackMode = LocalClonePlaybackMode.CONTINUOUS
    ) = LocalCloneTtsEngine(
        pack = pack(),
        config = config,
        synthesizerFactory = { synth },
        playerFactory = { player },
        ioDispatcher = Dispatchers.IO,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        nowMs = { System.currentTimeMillis() },
        playbackMode = mode
    )

    /** 3 句、每句 20+ 字 ⇒ 必然切成 ≥2 段（`DEFAULT_SEGMENT_CHARS = 40`）。 */
    private val multiSegmentText =
        "第一句用来测试分段行为的文本内容一共二十个字。" +
            "第二句用来测试分段行为的文本内容一共二十个字。" +
            "第三句用来测试分段行为的文本内容一共二十个字。"

    private fun speakAndWait(engine: LocalCloneTtsEngine, text: String, timeoutSec: Long = 30): LatchListener {
        val listener = LatchListener()
        engine.speak(text, listener)
        assertTrue("合成/播放未在 ${timeoutSec}s 内收尾", listener.done.await(timeoutSec, TimeUnit.SECONDS))
        return listener
    }

    // ==================== 连续模式（AC-1） ====================

    @Test
    fun `连续 - 后一段的合成必须与前一段的播放重叠（本批核心契约）`() = runBlocking {
        val synth = TimingSynth(::now, costMs = 20)
        val player = TimingPlayer(::now, playMs = 400)
        val engine = engine(synth, player, mode = LocalClonePlaybackMode.CONTINUOUS)

        val listener = speakAndWait(engine, multiSegmentText)

        assertEquals("本轮应当成功收尾", listOf("start", "done"), listener.events.toList())
        assertTrue("至少切成两段才有意义，实际 ${synth.calls.get()}", synth.calls.get() >= 2)
        // 核心断言：第 2 次合成**开始于**第 1 次播放**结束之前**。
        // 串行实现（改造前）恒有 starts[1] > ends[0]，此处必然变红。
        assertTrue(
            "第 2 段合成（${synth.starts[1]}）必须早于第 1 段播放结束（${player.ends[0]}）——" +
                "否则仍是一条一条串行",
            synth.starts[1] < player.ends[0]
        )
        // 互补断言（排除"全部合成完再播"这种同样满足上一条的形态）：
        // 首段播放必须在**到最后一段合成开始之前**就已开始，即合成与播放是**交替**推进的。
        assertTrue(
            "首段播放（${player.starts[0]}）必须早于最后一段合成的开始（${synth.starts.last()}）——" +
                "否则本模式退化为'整段模式'",
            player.starts[0] < synth.starts.last()
        )
    }

    @Test
    fun `连续 - 段间静音不得包含合成等待（第 2 次播放紧接第 1 次播放）`() = runBlocking {
        // 合成远快于播放：段间间隔应当只剩调用间隙，绝不该是"一次合成的耗时"
        val synth = TimingSynth(::now, costMs = 5)
        val player = TimingPlayer(::now, playMs = 200)
        val engine = engine(synth, player, mode = LocalClonePlaybackMode.CONTINUOUS)

        speakAndWait(engine, multiSegmentText)

        assertTrue(player.starts.size >= 2)
        val gap = player.starts[1] - player.ends[0]
        assertTrue(
            "段间间隔 $((gap) / 1_000_000)ms 必须远小于一次合成耗时——" +
                "播放侧不得等待下一段合成",
            gap < ms(100)
        )
    }

    @Test
    fun `连续 - 每段各播放一次且样本数不被合并`() = runBlocking {
        val synth = TimingSynth(::now, costMs = 5, samplesPerSegment = 3)
        val player = TimingPlayer(::now, playMs = 5)
        val engine = engine(synth, player, mode = LocalClonePlaybackMode.CONTINUOUS)

        speakAndWait(engine, multiSegmentText)

        assertEquals("连续模式逐段播放", synth.calls.get(), player.calls.get())
        assertEquals("总样本数 = 段数 × 每段样本数", synth.calls.get() * 3, player.totalSamples.get())
    }

    // ==================== 整段模式（AC-2） ====================

    @Test
    fun `整段 - 只调用一次 play 且传入拼接后的完整 PCM`() = runBlocking {
        val synth = TimingSynth(::now, costMs = 5, samplesPerSegment = 3)
        val player = TimingPlayer(::now, playMs = 5)
        val engine = engine(synth, player, mode = LocalClonePlaybackMode.WHOLE)

        val listener = speakAndWait(engine, multiSegmentText)

        assertEquals(listOf("start", "done"), listener.events.toList())
        assertTrue("至少两段才谈得上'拼接'，实际 ${synth.calls.get()}", synth.calls.get() >= 2)
        assertEquals("整段模式必须只播一次", 1, player.calls.get())
        assertEquals(
            "播放的样本数必须是所有段之和",
            synth.calls.get() * 3,
            player.totalSamples.get()
        )
    }

    @Test
    fun `整段 - 播放必须发生在全部合成完成之后`() = runBlocking {
        val synth = TimingSynth(::now, costMs = 20)
        val player = TimingPlayer(::now, playMs = 5)
        val engine = engine(synth, player, mode = LocalClonePlaybackMode.WHOLE)

        speakAndWait(engine, multiSegmentText)

        assertEquals(1, player.calls.get())
        assertTrue(
            "播放起始（${player.starts[0]}）必须晚于最后一次合成结束（${synth.ends.last()}）——" +
                "否则就不是'全部合成完再播'",
            player.starts[0] >= synth.ends.last()
        )
    }

    @Test
    fun `整段 - 超长文本回落连续模式（防无界累积，AC-2 上限）`() = runBlocking {
        val synth = TimingSynth(::now, costMs = 1)
        val player = TimingPlayer(::now, playMs = 1)
        val engine = engine(synth, player, mode = LocalClonePlaybackMode.WHOLE)
        // 超过 WHOLE_MODE_MAX_CHARS 的文本
        val long = "很长的一段用来测试上限回落行为的文本内容。".repeat(12)

        speakAndWait(engine, long)

        assertTrue(
            "超长文本必须回落连续模式（逐段播放），实际 play=${player.calls.get()}, synth=${synth.calls.get()}",
            player.calls.get() > 1 && player.calls.get() == synth.calls.get()
        )
    }

    @Test
    fun `整段 - 即使 silenceScale 允许流式也不得走流式路径（模式语义优先）`() = runBlocking {
        val synth = TimingSynth(::now, costMs = 1)
        val player = TimingPlayer(::now, playMs = 1, streaming = true)
        val engine = engine(
            synth,
            player,
            config = LocalCloneConfig(silenceScale = LocalCloneTtsEngine.SILENCE_SCALE_STREAMABLE),
            mode = LocalClonePlaybackMode.WHOLE
        )

        speakAndWait(engine, multiSegmentText)

        assertEquals("整段模式不得开启流式会话", 0, player.openStreamCalls.get())
        assertEquals("整段模式只播一次", 1, player.calls.get())
    }

    // ==================== 单飞契约（guardrail TKN-LVC-016 B1） ====================

    /**
     * 第 1 次合成立刻返回**零音频**（把消费者逼进"早退"分支），之后每次合成**无视取消探针**
     * 地阻塞。这样才能制造真正的危险窗口：消费者已经 return、锁已释放，而旧 producer 还在 native 里。
     * （若生产者只是被 cancel 掉、消费者仍在 channel 上挂起，则消费者会一直等到 producer 收尾，
     * 反而"意外地"维持了串行——这正是本用例第一版变异存活的原因。）
     */
    private class StubbornSynth(private val blockAfterFirstMs: Long) : LocalCloneSynthesizer {
        val calls = AtomicInteger(0)
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        override fun synthesize(
            text: String,
            reference: LocalReference?,
            config: LocalCloneConfig,
            isCancelled: () -> Boolean,
            onChunk: (FloatArray) -> Unit
        ): LocalCloneAudio {
            val n = calls.incrementAndGet()
            val now = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, now) }
            try {
                if (n == 1) return LocalCloneAudio(FloatArray(0), 0)
                val deadline = System.currentTimeMillis() + blockAfterFirstMs
                while (System.currentTimeMillis() < deadline) Thread.sleep(2)
                return LocalCloneAudio(floatArrayOf(0.1f, 0.1f), 24_000)
            } finally {
                concurrent.decrementAndGet()
            }
        }

        override fun sampleRate(): Int = 24_000

        override fun release() = Unit
    }

    @Test
    fun `单飞 - 新请求不得与仍在 native 中的旧合成并发进入 synthesize`() = runBlocking {
        // 改造前：`synthesize` 在**持锁**的消费协程内阻塞 ⇒ 新请求必然排队。
        // 引入预取生产者后它跑在锁外，若只 `cancel()` 不 join 就 unlock，新请求会立刻
        // 进入 native，与旧调用并发（UB/崩溃）。本用例锁住这条不变量。
        val synth = StubbornSynth(blockAfterFirstMs = 600)
        val player = TimingPlayer(::now, playMs = 0)
        val engine = engine(synth, player, mode = LocalClonePlaybackMode.CONTINUOUS)

        val first = LatchListener()
        engine.speak(multiSegmentText, first)
        assertTrue("第 1 次合成应已进入", awaitTrue(3000) { synth.calls.get() >= 1 })
        // 等到"消费者已早退、旧 producer 仍在 native"的窗口真正出现
        assertTrue("第 2 次合成应已开始", awaitTrue(3000) { synth.concurrent.get() >= 1 && synth.calls.get() >= 2 })

        val second = LatchListener()
        engine.speak(multiSegmentText, second)
        assertTrue("第 2 轮应收尾", second.done.await(20, TimeUnit.SECONDS))

        assertEquals(
            "任何时刻都只能有一次 synthesize 在跑（旧请求必须被 join 干净再让锁）",
            1,
            synth.maxConcurrent.get()
        )
    }

    private fun awaitTrue(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
    }

    // ==================== 默认值（AC-3.4 / 兼容） ====================

    @Test
    fun `默认构造为连续模式（不传 playbackMode 时行为 = 连续）`() = runBlocking {
        val synth = TimingSynth(::now, costMs = 5)
        val player = TimingPlayer(::now, playMs = 5)
        val engine = LocalCloneTtsEngine(
            pack = pack(),
            config = LocalCloneConfig(),
            synthesizerFactory = { synth },
            playerFactory = { player },
            ioDispatcher = Dispatchers.IO,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )

        speakAndWait(engine, multiSegmentText)

        assertEquals("默认必须逐段播放（连续）", synth.calls.get(), player.calls.get())
    }

    @Test
    fun `两种模式下回调次数与顺序完全一致（接口契约零回归）`() = runBlocking {
        listOf(LocalClonePlaybackMode.CONTINUOUS, LocalClonePlaybackMode.WHOLE).forEach { mode ->
            val listener = speakAndWait(
                engine(TimingSynth(::now, 1), TimingPlayer(::now, 1), mode = mode),
                multiSegmentText
            )
            assertEquals("mode=$mode 的回调序列", listOf("start", "done"), listener.events.toList())
        }
    }
}
