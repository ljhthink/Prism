package io.prism.voice.local

import io.prism.voice.LocalVoiceTier

/**
 * 本地音色播报档位 → 生效生成参数（TKN-LVC-016 / ADR-056）。
 *
 * 抽成**纯函数**的理由与 [localCloneCacheKey] 相同：档位是否"真的生效"完全取决于它算出的
 * [LocalCloneConfig]，因此必须能被 JVM 单测逐字段钉住（"改了没效果"是本项目复发性最高的缺陷类型）。
 *
 * ## 优先级（显式排列，不得隐式依赖求值顺序）
 *
 * `基础清单参数` < `角色包 voice.local.numSteps 覆盖` < `用户档位`。
 *
 * 档位之所以**压过**角色包覆盖：档位是用户在设置页的**主动选择**，若被角色包内置值压住，
 * 用户切了档位却听不到变化——正是 we 要根除的"改了没效果"。反之角色包覆盖只在 `QUALITY`
 * 档下作为默认真实生效（`QUALITY` 档 = 完全不介入）。
 *
 * ## 为什么只有这两个旋钮
 *
 * 见 [LocalVoiceTier] KDoc：步数与线程是唯一被 PC 实测证实的稳定提速轴；
 * "缩短参考音频"轴虽另有 1.46× 但会带来用户已否决的听感退化，**不得**藏进提速档。
 */
internal object LocalVoiceTierParams {
    /** 均衡档：步数 8→6、线程 2→3。 */
    const val BALANCED_STEPS = 6
    const val BALANCED_THREADS = 3

    /** 速度优先档：步数 4（distill 官方下限）、线程 4（`LocalCloneConfig.MAX_THREADS` 上界）。 */
    const val SPEED_STEPS = 4
    const val SPEED_THREADS = 4
}

/**
 * 按档位计算生效生成参数。
 *
 * @param base 清单里的基础参数（已 [LocalCloneConfig.sanitized]）
 * @param rolePackNumSteps 角色包 `voice.local.numSteps`（null/越界时不覆盖）
 * @param tier 用户档位
 * @return 生效参数（结果仍落在 [LocalCloneConfig] 的合法域内——档位常量本身即合法值）
 */
internal fun tieredLocalCloneConfig(
    base: LocalCloneConfig,
    rolePackNumSteps: Int?,
    tier: LocalVoiceTier
): LocalCloneConfig {
    val withRolePack = base.withRolePackOverride(rolePackNumSteps)
    return when (tier) {
        // 质量优先 = **完全不介入**（返回角色包覆盖后的原值）⇒ 听感基线与改造前逐字段一致
        LocalVoiceTier.QUALITY -> withRolePack
        LocalVoiceTier.BALANCED -> withRolePack.copy(
            numSteps = LocalVoiceTierParams.BALANCED_STEPS,
            numThreads = LocalVoiceTierParams.BALANCED_THREADS
        )
        LocalVoiceTier.SPEED -> withRolePack.copy(
            numSteps = LocalVoiceTierParams.SPEED_STEPS,
            numThreads = LocalVoiceTierParams.SPEED_THREADS
        )
    }
}
