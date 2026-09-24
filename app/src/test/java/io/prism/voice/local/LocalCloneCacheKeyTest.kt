package io.prism.voice.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 引擎缓存键的**逐字段**守卫（guardrail TKN-LVC-003 F-3 / N-1 / N-2）。
 *
 * 背景：`LocalCloneTtsEngine` 在构造时定死 config 与模型；"改参数后是否重建"完全取决于这个键。
 * 历史缺陷（F-3）是键里只有 `voiceId|numSteps|refCount` → 真机改清单后同进程仍走旧值
 * （表现为"改了没效果"，污染音质 A/B）；F-3 修复当时**没有测试**，复发成本为零。
 * 本类用"逐字段循环"把它钉死：**任何一个字段漏进键都会变红**。
 */
class LocalCloneCacheKeyTest {

    private val baseConfig = LocalCloneConfig()
    private fun key(
        config: LocalCloneConfig = baseConfig,
        voiceId: String = "cyrene",
        refs: Int = 8,
        model: String = "m",
        tts: LocalTtsKind = LocalTtsKind.ZIPVOICE,
        vits: VitsGeneration = VitsGeneration(),
        mode: io.prism.voice.LocalClonePlaybackMode = io.prism.voice.LocalClonePlaybackMode.CONTINUOUS
    ) = localCloneCacheKey(voiceId, tts, refs, config, vits, mode, model)

    @Test
    fun `播报模式必须参与缓存键（TKN-LVC-016 B2：否则设置页切换"连续 整段"改了没效果）`() {
        assertNotEquals(
            "模式是在引擎**构造期**定死的输入，必须进键",
            key(mode = io.prism.voice.LocalClonePlaybackMode.CONTINUOUS),
            key(mode = io.prism.voice.LocalClonePlaybackMode.WHOLE)
        )
    }

    @Test
    fun `VITS 专属旋钮必须参与缓存键（TKN-LVC-016：只改 noiseScale 也不得复用旧引擎）`() {
        assertNotEquals(
            "noiseScale 必须参与缓存键",
            key(),
            key(vits = VitsGeneration(noiseScale = 0.4f))
        )
        assertNotEquals(
            "noiseScaleW 必须参与缓存键",
            key(),
            key(vits = VitsGeneration(noiseScaleW = 0.4f))
        )
    }

    @Test
    fun `引擎类型必须参与缓存键（ADR-055：换引擎不得复用旧引擎实例）`() {
        assertNotEquals(
            "ZipVoice 与 VITS 的键必须不同",
            key(tts = LocalTtsKind.ZIPVOICE),
            key(tts = LocalTtsKind.VITS)
        )
    }

    @Test
    fun `同一输入必须稳定`() {
        assertEquals(key(), key())
        assertEquals(key(LocalCloneConfig(numSteps = 4, speed = 0.8f)), key(LocalCloneConfig(numSteps = 4, speed = 0.8f)))
    }

    @Test
    fun `每一个生成参数都必须参与缓存键（漏一个就会"改了没效果"）`() {
        // 逐字段构造"只改这一个字段"的配置；若某字段没进键，对应断言立刻变红
        val variants = listOf(
            "numSteps" to baseConfig.copy(numSteps = baseConfig.numSteps + 1),
            "speed" to baseConfig.copy(speed = baseConfig.speed + 0.1f),
            "silenceScale" to baseConfig.copy(silenceScale = baseConfig.silenceScale + 0.1f),
            "guidanceScale" to baseConfig.copy(guidanceScale = baseConfig.guidanceScale + 0.1f),
            "featScale" to baseConfig.copy(featScale = baseConfig.featScale + 0.01f),
            "tShift" to baseConfig.copy(tShift = baseConfig.tShift + 0.1f),
            "targetRms" to baseConfig.copy(targetRms = baseConfig.targetRms + 0.01f),
            "minCharInSentence" to baseConfig.copy(minCharInSentence = baseConfig.minCharInSentence + 1),
            // maxChar 必须同时 ≥ minChar（不变量），故两个一起改
            "maxCharInSentence" to baseConfig.copy(maxCharInSentence = baseConfig.maxCharInSentence + 1),
            "numThreads" to baseConfig.copy(numThreads = baseConfig.numThreads + 1),
            "timeoutMs" to baseConfig.copy(timeoutMs = baseConfig.timeoutMs + 1_000L)
        )
        variants.forEach { (name, variant) ->
            assertNotEquals("字段 $name 必须参与缓存键", key(), key(variant))
        }
    }

    @Test
    fun `音色 id 参考集条数与模型身份都必须参与缓存键`() {
        assertNotEquals("voiceId", key(), key(voiceId = "other"))
        assertNotEquals("参考集条数", key(), key(refs = 7))
        assertNotEquals("模型身份", key(), key(model = "m2"))
    }

    @Test
    fun `分隔符碰撞必须被封住（长度前缀编码，N-1）`() {
        // 朴素拼接（`voiceId|refs|model`）下，下面两组的原始串完全相同：
        //   ("a|1", 2, ..) vs ("a", 1, "2|..")
        // 长度前缀编码使其不同 —— 这是 N-1 指出的别名碰撞
        val a = localCloneCacheKey(
            voiceId = "a", tts = LocalTtsKind.ZIPVOICE, referenceCount = 1, config = baseConfig,
            vits = VitsGeneration(), playbackMode = io.prism.voice.LocalClonePlaybackMode.CONTINUOUS,
            modelIdentity = "x|y"
        )
        val b = localCloneCacheKey(
            voiceId = "a|1", tts = LocalTtsKind.ZIPVOICE, referenceCount = 0, config = baseConfig,
            vits = VitsGeneration(), playbackMode = io.prism.voice.LocalClonePlaybackMode.CONTINUOUS,
            modelIdentity = "x|y"
        )
        assertNotEquals("含分隔符的值不得构造出同键", a, b)
        // 直接构造"值里带分隔符"的两组：只需保证不会与自身之外输入相等
        assertTrue(key(voiceId = "a|b") != key(voiceId = "a", model = "b"))
    }

    @Test
    fun `模型身份 - 原地替换模型文件必须改变身份（N-2）`() {
        val dir = Files.createTempDirectory("lvc-key").toFile()
        val encoder = File(dir, "encoder.onnx").apply { writeBytes(ByteArray(16)) }
        val decoder = File(dir, "decoder.onnx").apply { writeBytes(ByteArray(32)) }
        val before = localCloneModelIdentity(listOf(encoder, decoder))

        // 同路径原地替换（换模型变体的真实形态：文件名规范化为同名，仅内容/时间变化）
        encoder.writeBytes(ByteArray(64))
        val after = localCloneModelIdentity(listOf(encoder, decoder))
        assertNotEquals("原地替换模型后身份必须变化，否则换模型不会重建引擎", before, after)

        // 身份稳定：不动物理文件时两次读取一致
        assertEquals(after, localCloneModelIdentity(listOf(encoder, decoder)))
        // 文件名参与身份（不同变体若落到不同文件名也能区分）
        assertNotEquals(before, localCloneModelIdentity(listOf(decoder, encoder)))
    }

    @Test
    fun `缓存键不得为空且可读（便于日志排障）`() {
        val k = key()
        assertTrue("键必须有内容", k.isNotEmpty())
        assertTrue("键应包含各字段标签，便于真机排障时定位", k.contains("guidanceScale=") && k.contains("silenceScale="))
    }
}
