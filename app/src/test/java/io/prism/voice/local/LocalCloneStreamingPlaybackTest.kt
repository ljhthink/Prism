package io.prism.voice.local

import io.prism.voice.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * LVC-2 流式播放契约用例（TKN-LVC-013）。
 *
 * ## 钉住的是什么
 *
 * 上游 `offline-tts-zipvoice-impl.h` 每生成一个内部句子就 `callback(cur.samples…)`，且回调载荷是
 * **增量音频**（PC 实测：各块样本数之和恰等于整段样本数）。本引擎在流式路径把这些块**立即写入一条
 * 常开音轨**，因此：
 *
 * 1. 首声只等**第一句**，不等整段（`onStart` 早于 `synthesize` 返回）；
 * 2. 合成与播放**重叠**，不再"播完一段才开始算下一段"；
 * 3. 对外回调契约不变（`onStart` 一次、`onDone`/`onError` 恰一次）——**排空先于 onDone**；
 * 4. 一旦出声，任何"输出栈失效"都按**本轮结束**收敛（`onDone`），绝不走带"已回退"的 `onError`：
 *    上层 `TextPlaybackController` 收到任何 `onError` 就会**重播整段**，用户会听到重复。
 *
 * 用 `Dispatchers.Unconfined` 同步执行（与 `LocalCloneTtsEngineTest.syncEngine` 同口径），使时序断言确定。
 */
class LocalCloneStreamingPlaybackTest {

    // ==================== 夹具 ====================

    /** 记录事件序列的监听器（`start`/`done`/`error:<msg>`）。 */
    private class RecListener(private val events: MutableList<String>) : TtsEngine.Listener {
        override fun onStart(utteranceId: String) {
            events.add("start")
        }

        override fun onDone(utteranceId: String) {
            events.add("done")
        }

        override fun onError(utteranceId: String, message: String) {
            events.add("error:$message")
        }
    }

    /**
     * 假合成器：每次 [synthesize] 推送 [chunksPerCall] 个块，每块的样本值 = 递增序号（便于断言顺序）。
     *
     * @param rate 声明的采样率（≤0 ⇒ 引擎应退回整段播放路径）
     * @param afterChunk 每推完一块回调一次（用于在"合成进行中"观测上层事件）
     */
    private class StreamSynth(
        private val rate: Int = 24_000,
        private val chunksPerCall: Int = 2,
        private val truncated: Boolean = false,
        /** 在第 1 块之后补推一个**空块**（guardrail F-2 的可达性防御用例）。 */
        private val emitEmptyChunk: Boolean = false,
        private val afterChunk: (() -> Unit)? = null
    ) : LocalCloneSynthesizer {
        val synthCalls = AtomicInteger(0)
        val chunksPushed = AtomicInteger(0)
        private val seq = AtomicInteger(0)

        override fun sampleRate(): Int = rate

        override fun synthesize(
            text: String,
            reference: LocalReference?,
            config: LocalCloneConfig,
            isCancelled: () -> Boolean,
            onChunk: (FloatArray) -> Unit
        ): LocalCloneAudio {
            synthCalls.incrementAndGet()
            for (i in 0 until chunksPerCall) {
                if (isCancelled()) break
                val value = seq.incrementAndGet().toFloat()
                onChunk(FloatArray(CHUNK_SAMPLES) { value })
                chunksPushed.incrementAndGet()
                afterChunk?.invoke()
                if (emitEmptyChunk && i == 0) onChunk(FloatArray(0))
            }
            // 整段播放路径（非流式）依赖返回值里的音频；流式路径不读它（块已经写进音轨）
            return LocalCloneAudio(floatArrayOf(0.25f, -0.25f), LEGACY_RATE, truncated = truncated)
        }

        override fun release() = Unit
    }

    /**
     * 假流式播放器：把每次写入记录下来。
     *
     * @param failAfterWrites 成功写入 [failAfterWrites] 块之后，后续写入一律返回 false
     *   （模拟"音轨静默失效"）；`0` = 首块即失效
     * @param emptyWritesAreFailure 复现**改造前**的会话契约：空块也返回 false。
     *   生产实现现为"空块 = 无操作（true）"，但本参数让用例能钉住**引擎侧**的空块防御是否在接线
     *   （否则用例只会走假实现的宽容路径，成为空断言）
     */
    private class StreamPlayer(
        private val failAfterWrites: Int = Int.MAX_VALUE,
        private val emptyWritesAreFailure: Boolean = false,
        private val events: MutableList<String>? = null
    ) : LocalClonePlayer {
        val openCalls = AtomicInteger(0)
        val closeCalls = AtomicInteger(0)
        val playCalls = AtomicInteger(0)
        val written = mutableListOf<FloatArray>()

        @Volatile
        var openedSampleRate = 0

        @Volatile
        var stopped = false

        override fun play(samples: FloatArray, sampleRate: Int, isCancelled: () -> Boolean) {
            playCalls.incrementAndGet()
        }

        override fun openStream(sampleRate: Int, isCancelled: () -> Boolean): LocalCloneStreamSession? {
            openCalls.incrementAndGet()
            openedSampleRate = sampleRate
            return object : LocalCloneStreamSession {
                private var frames = 0
                private var closed = false

                override val framesWritten: Int get() = frames

                override fun write(samples: FloatArray): Boolean {
                    // 空块语义可配置：true 复现生产契约（无操作），false 复现改造前的"空即作废"
                    if (samples.isEmpty()) return !emptyWritesAreFailure
                    if (closed || stopped) return false
                    if (written.size >= failAfterWrites) return false
                    written.add(samples.copyOf())
                    frames += samples.size
                    return true
                }

                override fun close() {
                    if (closed) return
                    closed = true
                    closeCalls.incrementAndGet()
                    events?.add("close")
                }
            }
        }

        override fun stop() {
            stopped = true
        }

        override fun release() = Unit
    }

    private fun packFor(refs: List<LocalReference> = listOf(ref())) = LocalVoicePack(
        voiceId = "cyrene",
        name = "昔涟",
        root = File("."),
        model = LocalModelFiles.ZipVoice(
            tokens = File("tokens.txt"),
            encoder = File("encoder.onnx"),
            decoder = File("decoder.onnx"),
            vocoder = File("vocos.onnx"),
            lexicon = File("lexicon.txt"),
            dataDir = File("espeak")
        ),
        generation = LocalCloneConfig(),
        references = refs,
        totalBytes = 0L
    )

    private fun ref() = LocalReference(
        id = "ref_00",
        audio = File("ref_00.wav"),
        text = "这是命运的邂逅吗，还是…久别重逢呢？"
    )

    private fun engine(
        synth: LocalCloneSynthesizer,
        player: LocalClonePlayer,
        config: LocalCloneConfig = LocalCloneConfig()
    ) = LocalCloneTtsEngine(
        pack = packFor(),
        config = config,
        synthesizerFactory = { synth },
        playerFactory = { player },
        ioDispatcher = Dispatchers.Unconfined,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    )

    // ==================== 用例 ====================

    @Test
    fun `流式 - 首块到达即 onStart，且 onStart 早于合成返回（首声不等整段）`() = runBlocking {
        val events = mutableListOf<String>()
        // 在"合成仍在进行中"（首块刚推完）的时刻观测上层是否已收到 onStart
        val startSeenDuringFirstChunk = AtomicBoolean(false)
        val sawFirst = AtomicBoolean(false)
        val synth = StreamSynth(chunksPerCall = 3, afterChunk = {
            if (!sawFirst.getAndSet(true)) {
                startSeenDuringFirstChunk.set(events.contains("start"))
            }
        })
        val player = StreamPlayer(events = events)
        val engine = engine(synth, player)

        engine.speak("你好，世界。这是一句更长的文本。", RecListener(events))

        assertTrue("首块产出时上层应已收到 onStart（否则等于等整段算完）", startSeenDuringFirstChunk.get())
        assertEquals(listOf("start", "close", "done"), events)
        assertEquals(1, player.openCalls.get())
    }

    @Test
    fun `流式 - 每块按顺序写入常开会话，且不再走整段 play`() = runBlocking {
        val events = mutableListOf<String>()
        val synth = StreamSynth(chunksPerCall = 3)
        val player = StreamPlayer(events = events)
        val engine = engine(synth, player)

        engine.speak("第一句。第二句！第三句？", RecListener(events))

        assertEquals("流式路径不得再调用整段播放", 0, player.playCalls.get())
        assertEquals("同一请求只开一条音轨（常开）", 1, player.openCalls.get())
        assertEquals("块数应等于合成器实际推送的块数", synth.chunksPushed.get(), player.written.size)
        assertTrue("必须真的写入了音频", player.written.isNotEmpty())
        // 顺序保真：第 n 块的样本值应严格递增（换序/丢失都会被这条抓住）
        val seqs = player.written.map { it[0] }
        assertEquals(seqs.sorted(), seqs)
        assertEquals(24_000, player.openedSampleRate)
    }

    @Test
    fun `流式 - 音轨排空先于 onDone`() = runBlocking {
        val events = mutableListOf<String>()
        val synth = StreamSynth(chunksPerCall = 2)
        val player = StreamPlayer(events = events)
        val engine = engine(synth, player)

        engine.speak("你好。", RecListener(events))

        assertEquals(listOf("start", "close", "done"), events)
        assertEquals(1, player.closeCalls.get())
    }

    @Test
    fun `流式 - 合成器不声明采样率时退回整段播放路径（兼容既有实现）`() = runBlocking {
        val events = mutableListOf<String>()
        val synth = StreamSynth(rate = 0, chunksPerCall = 2)
        val player = StreamPlayer(events = events)
        val engine = engine(synth, player)

        engine.speak("你好，世界。", RecListener(events))

        assertEquals("不应打开流式会话", 0, player.openCalls.get())
        assertEquals("应退回整段播放", 1, player.playCalls.get())
        assertEquals(listOf("start", "done"), events)
    }

    @Test
    fun `流式 - 一个块都没产出时报零音频错误并带已回退标记`() = runBlocking {
        val events = mutableListOf<String>()
        val synth = StreamSynth(chunksPerCall = 0)
        val player = StreamPlayer(events = events)
        val engine = engine(synth, player)

        engine.speak("你好，世界。", RecListener(events))

        assertFalse("未产出音频不得报 onStart", events.contains("start"))
        assertEquals("回调恰好一次（close 是会话收尾，不算回调）", 1, events.count { it != "close" })
        assertTrue("应走降级链（文案须含已回退）：$events", events.any { it.startsWith("error:") && it.contains("已回退") })
        assertEquals("仍需关闭会话（幂等）", 1, player.closeCalls.get())
    }

    @Test
    fun `流式 - 首块即失效（一声未出）时报错降级，不静默吞掉`() = runBlocking {
        val events = mutableListOf<String>()
        val synth = StreamSynth(chunksPerCall = 2)
        val player = StreamPlayer(failAfterWrites = 0, events = events)
        val engine = engine(synth, player)

        engine.speak("你好，世界。", RecListener(events))

        // onStart 允许已发出（与既有整段路径同序：先 onStart、后播放），但必须收敛为 onError
        assertEquals("必须报错触发上层降级", 1, events.count { it.startsWith("error:") })
        assertTrue("文案须含已回退标记：$events", events.any { it.startsWith("error:") && it.contains("已回退") })
        assertFalse("未出声不得报 onDone（否则上层以为播完了）", events.contains("done"))
    }

    @Test
    fun `流式 - 已出声后输出栈失效按本轮结束收敛，绝不 onError（防上层重播整段）`() = runBlocking {
        val events = mutableListOf<String>()
        val synth = StreamSynth(chunksPerCall = 3)
        val player = StreamPlayer(failAfterWrites = 1, events = events)
        val engine = engine(synth, player)

        engine.speak("你好，世界。", RecListener(events))

        assertTrue("已出声：必须收敛为 onDone", events.contains("done"))
        assertFalse("已出声不得报错（上层会重播整段 ⇒ 用户听到重复）：$events", events.any { it.startsWith("error:") })
        assertEquals(1, player.written.size)
    }

    @Test
    fun `流式 - 停止后不再写入后续块且不回调`() = runBlocking {
        val events = mutableListOf<String>()
        val player = StreamPlayer(events = events)
        lateinit var engineRef: LocalCloneTtsEngine
        // 首块推完后立刻 stop：后续块不得再进入音轨
        val synth = StreamSynth(chunksPerCall = 3, afterChunk = { engineRef.stop() })
        engineRef = engine(synth, player)

        engineRef.speak("你好，世界。", RecListener(events))

        assertEquals("首块之后立刻停止 ⇒ 恰好只写入 1 块（上界弱断言已按 guardrail R2 收紧为等值）", 1, player.written.size)
        assertFalse("停止的请求不回调：$events", events.contains("done"))
        assertFalse("停止的请求不报错：$events", events.any { it.startsWith("error:") })
    }

    @Test
    fun `流式 - 合成返回 truncated 但探针未置位时仍按正常收敛（不可达组合的防御性收敛）`() = runBlocking {
        val events = mutableListOf<String>()
        // 生产里 truncated = 取消探针为真（见 LocalCloneAudio KDoc），故"truncated=true 且探针为假"
        // 是**不可达组合**；本用例只钉"回调仍恰好收敛一次、且不抛异常"这一防御性行为。
        val synth = StreamSynth(chunksPerCall = 2, truncated = true)
        val player = StreamPlayer(events = events)
        val engine = engine(synth, player)

        engine.speak("你好，世界。", RecListener(events))

        assertEquals("不可达组合下应正常收敛（onDone 一次）", listOf("start", "close", "done"), events)
        assertEquals(1, player.closeCalls.get())
    }

    @Test
    fun `流式 - 因取消而截短时不得报成功（无 onDone 无 onError）`() = runBlocking {
        val events = mutableListOf<String>()
        val player = StreamPlayer(events = events)
        lateinit var engineRef: LocalCloneTtsEngine
        var probeAtReturn = false
        // truncated 由**取消探针**决定（与生产一致：`truncated = isCancelled()`），探针由首块之后的
        // stop() 置真 ⇒ 还原"用户停止导致合成截短"这一真实组合。
        val synth = object : LocalCloneSynthesizer {
            override fun sampleRate(): Int = 24_000

            override fun synthesize(
                text: String,
                reference: LocalReference?,
                config: LocalCloneConfig,
                isCancelled: () -> Boolean,
                onChunk: (FloatArray) -> Unit
            ): LocalCloneAudio {
                onChunk(FloatArray(CHUNK_SAMPLES) { 0.5f })
                engineRef.stop()
                probeAtReturn = isCancelled()
                return LocalCloneAudio(floatArrayOf(0.25f), LEGACY_RATE, truncated = isCancelled())
            }

            override fun release() = Unit
        }
        engineRef = engine(synth, player)

        engineRef.speak("你好，世界。", RecListener(events))

        assertTrue("前提：返回时取消探针须为真", probeAtReturn)
        // 正向断言（guardrail R3 建议）：三条负向断言无法区分"确实播过首块后取消"与"整轮什么都没发生"
        assertTrue("首块应已写入会话 ⇒ 必须已发出 onStart：$events", events.contains("start"))
        assertEquals("且恰好只写入首块", 1, player.written.size)
        assertFalse("截短不得报 onDone：$events", events.contains("done"))
        assertFalse("截短不得报 onError：$events", events.any { it.startsWith("error:") })
    }

    @Test
    fun `流式 - silenceScale 非 1 时退回整段播放（否则静默丢弃上游整段 ScaleSilence）`() = runBlocking {
        val events = mutableListOf<String>()
        val synth = StreamSynth(chunksPerCall = 2)
        val player = StreamPlayer(events = events)
        // 0.5 在 sanitized() 的合法域内（0.01..2.0），即真实清单能写出的值
        val engine = engine(synth, player, config = LocalCloneConfig(silenceScale = 0.5f))

        engine.speak("你好，世界。", RecListener(events))

        assertEquals("silenceScale != 1 时不得走流式会话", 0, player.openCalls.get())
        assertEquals("必须退回整段播放（那里才有整段 ScaleSilence 后处理）", 1, player.playCalls.get())
        assertEquals(listOf("start", "done"), events)
    }

    @Test
    fun `流式 - 空音频块不得被当作输出栈失效（后续分段仍必须写入）`() = runBlocking {
        val events = mutableListOf<String>()
        val synth = StreamSynth(chunksPerCall = 2, emitEmptyChunk = true)
        // 让假会话复现**改造前**的"空即作废"契约：只有引擎侧真的把空块挡在 write 之外，
        // 本用例才会绿 —— 否则空块会被判成输出栈失效并丢弃其后全部分段。
        val player = StreamPlayer(emptyWritesAreFailure = true, events = events)
        val engine = engine(synth, player)

        engine.speak("你好，世界。", RecListener(events))

        assertEquals("空块之后的非空块仍必须写入音轨", 2, player.written.size)
        assertFalse("不得因空块提前收敛：$events", events.any { it.startsWith("error:") })
        assertEquals("应正常播完", listOf("start", "close", "done"), events)
    }

    /**
     * 流式分支的**段间停顿接线**（验收 F2）。
     *
     * 为什么单独立这一条：`gapMsFor` / `silenceSamples` 的算法已有专项用例，但**流式分支里
     * "到底插没插"这层接线**在改造时**没有任何断言**（既有流式用例全部 gap=0）。
     * 而 BUG-20260924-003 把 `$SilenceScale` 由 0.5 改回 1.0，`silenceScale == 1` 正是流式门控
     * ⇒ **流式分支由"生产不可达"变为可达**，这条盲区从理论问题变成实际问题（ac-verifier 判定）。
     *
     * 期望值口径：本用例的被测量是**接线**（插入与否、位置、尾部不插），
     * 故段数与"哪些边界该给停顿"由同一套纯函数推导（算法本身不在此用例的射程内），
     * 并**先断言推导出的边界数 ≥ 1**——否则本用例会退化成"0 个静音也算通过"的空断言。
     */
    @Test
    fun `流式 - 段间停顿生效于流式分支且末段之后不插（sentenceGapMs）`() = runBlocking {
        val events = mutableListOf<String>()
        val synth = StreamSynth(chunksPerCall = 3)
        val player = StreamPlayer(events = events)
        val gapMs = 400
        // 必须**长于 DEFAULT_SEGMENT_CHARS(40)**，否则 segmentForSynthesis 直接返回单段 ⇒ 无段边界
        val text = "第一段测试内容需要足够长才能被切成两段以上。" +
            "第二段同样要足够长，用来验证段间停顿确实被插进去了！" +
            "第三段继续补充一些文字凑长度，确保总长超过切分阈值？"
        val config = LocalCloneConfig(sentenceGapMs = gapMs)

        // 用与引擎**同一套**输入推导期望（常量是 internal，测试可见）
        val segments = segmentForSynthesis(text, DEFAULT_SEGMENT_CHARS, FIRST_SEGMENT_CHARS)
        val expectedGaps = segments.dropLast(1).count {
            LocalCloneSegmentGap.gapMsFor(it, gapMs) > 0
        }
        assertTrue("前置：本用例的文本必须真的产生 ≥1 个「该插停顿」的段边界，否则是空断言", expectedGaps >= 1)

        val engine = engine(synth, player, config)
        engine.speak(text, RecListener(events))

        assertEquals("必须走流式路径（否则测的不是本用例的目标分支）", 1, player.openCalls.get())
        assertEquals("流式路径不得再调用整段播放", 0, player.playCalls.get())

        val silences = player.written.filter { it.isNotEmpty() && it.all { s -> s == 0f } }
        assertEquals("段间静音块数应等于「该插停顿」的段边界数", expectedGaps, silences.size)
        // 400ms @24kHz = 9600 样本
        silences.forEach { assertEquals("静音长度 = ${gapMs}ms @24kHz", 9_600, it.size) }
        assertFalse(
            "末段之后不得插静音（否则尾音被拖长、onDone 被无谓延迟）",
            player.written.last().all { s -> s == 0f }
        )
        // 位置保真：每个静音块的前后都必须是非静音块（不得出现连续静音 / 贴在结尾）
        silences.forEach { sil ->
            val i = player.written.indexOfFirst { it === sil }
            assertTrue("静音块前必须是音频块（idx=$i）", i > 0 && player.written[i - 1].any { s -> s != 0f })
            assertTrue(
                "静音块后必须是音频块（idx=$i）",
                i < player.written.lastIndex && player.written[i + 1].any { s -> s != 0f }
            )
        }
        assertEquals("应正常播完", listOf("start", "close", "done"), events)
    }

    private companion object {
        /** 每块样本数（仅需非零，用于 framesWritten 与顺序断言）。 */
        const val CHUNK_SAMPLES = 8

        /** 整段播放路径（非流式）下假合成器回报的采样率。 */
        const val LEGACY_RATE = 24_000
    }
}
