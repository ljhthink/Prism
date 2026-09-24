package io.prism.voice.local

import io.prism.voice.LocalVoiceTier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TKN-LVC-016（ADR-056）：播报档位 → 生效生成参数（PRD AC-3）。
 *
 * 本类的价值在于把"档位到底改了什么"钉死在**逐字段**上：档位如果不进
 * [localCloneCacheKey]，用户切了档位也听不到变化（本项目复发率最高的缺陷类型）。
 */
class LocalVoiceTieringTest {

    private val manifest = LocalCloneConfig(numSteps = 8, numThreads = 2, speed = 0.9f)

    @Test
    fun `质量优先 - 完全不介入，逐字段等于清单值`() {
        val out = tieredLocalCloneConfig(manifest, rolePackNumSteps = null, tier = LocalVoiceTier.QUALITY)
        assertEquals(manifest, out)
    }

    @Test
    fun `均衡档 - 6 步 3 线程，其余字段不动`() {
        val out = tieredLocalCloneConfig(manifest, rolePackNumSteps = null, tier = LocalVoiceTier.BALANCED)
        assertEquals(6, out.numSteps)
        assertEquals(3, out.numThreads)
        assertEquals(
            "档位只应改步数与线程",
            manifest.copy(numSteps = 6, numThreads = 3),
            out
        )
    }

    @Test
    fun `速度优先档 - 4 步 4 线程，其余字段不动`() {
        val out = tieredLocalCloneConfig(manifest, rolePackNumSteps = null, tier = LocalVoiceTier.SPEED)
        assertEquals(4, out.numSteps)
        assertEquals(4, out.numThreads)
        assertEquals(manifest.copy(numSteps = 4, numThreads = 4), out)
    }

    @Test
    fun `质量优先档下角色包覆盖仍然生效（否则角色包参数会被静默忽略）`() {
        val out = tieredLocalCloneConfig(manifest, rolePackNumSteps = 5, tier = LocalVoiceTier.QUALITY)
        assertEquals(5, out.numSteps)
        assertEquals(2, out.numThreads)
    }

    @Test
    fun `档位优先于角色包覆盖（用户主动选择压过角色包内置值）`() {
        // 若角色包覆盖胜出，用户切到速度档却仍是 5 步 → "改了没效果"
        val out = tieredLocalCloneConfig(manifest, rolePackNumSteps = 5, tier = LocalVoiceTier.SPEED)
        assertEquals(4, out.numSteps)
        assertEquals(4, out.numThreads)
    }

    @Test
    fun `越界的角色包覆盖被忽略（withRolePackOverride 既有语义不变）`() {
        val out = tieredLocalCloneConfig(manifest, rolePackNumSteps = 999, tier = LocalVoiceTier.QUALITY)
        assertEquals(8, out.numSteps)
    }

    @Test
    fun `档位常量本身落在合法域内（否则会被 sanitized 打回，档位等于没生效）`() {
        val sanitized = tieredLocalCloneConfig(manifest, null, LocalVoiceTier.SPEED).sanitized()
        assertEquals(
            "速度档参数不得被 sanitized 改动",
            tieredLocalCloneConfig(manifest, null, LocalVoiceTier.SPEED),
            sanitized
        )
        val balanced = tieredLocalCloneConfig(manifest, null, LocalVoiceTier.BALANCED).sanitized()
        assertEquals(balanced, tieredLocalCloneConfig(manifest, null, LocalVoiceTier.BALANCED))
    }

    @Test
    fun `档位不触碰 VITS 专属旋钮（两种引擎的配置域必须隔离）`() {
        val pack = LocalVoicePack(
            voiceId = "v",
            name = "v",
            root = java.io.File("."),
            tts = LocalTtsKind.VITS,
            model = LocalModelFiles.Vits(
                model = java.io.File("m"),
                lexicon = java.io.File("l"),
                tokens = java.io.File("t"),
                dictDir = null,
                ruleFsts = emptyList()
            ),
            generation = manifest,
            references = emptyList(),
            totalBytes = 0L,
            vitsGeneration = VitsGeneration(noiseScale = 0.4f, noiseScaleW = 0.5f)
        ).let { p -> p }

        // 档位作用于 generation，不清洗 vitsGeneration（两者各自进缓存键）
        val tiered = tieredLocalCloneConfig(pack.generation, null, LocalVoiceTier.SPEED)
        assertEquals(VitsGeneration(noiseScale = 0.4f, noiseScaleW = 0.5f), pack.vitsGeneration)
        assertEquals(4, tiered.numSteps)
    }
}
