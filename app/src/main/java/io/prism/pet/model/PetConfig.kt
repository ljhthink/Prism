package io.prism.pet.model

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 批次24（ADR-051）：桌宠用户配置（纯数据 + 自钳制，无 Android 依赖）。
 *
 * **持久化**：[io.prism.pet.PetConfigRepository]（DataStore）按字段读写；本类只负责**合法域**与
 * 读取后的钳制（PRD US-2412 AC-4：设置值越界时钳制到合法域，不崩溃）。
 *
 * **位置语义**（PRD US-2403 AC-2/AC-4）：[posX] / [posY] 是**归一化自由空间坐标**（0..1），
 * 不是像素——实际像素 = 归一化值 × (可用屏幕尺寸 − 窗口尺寸)，见 [PetWindowGeometry]。
 * 这样做的好处是**旋转/分辨率变化后位置天然可映射**，且"窗口完整落在可用区域内"是**结构性保证**
 * 而非"尽力而为"。
 *
 * @property enabled 桌宠总开关（**默认关闭**，对齐 ADR-051 D7 与 ADR-041 的教训）
 * @property appearanceId 当前选中的形象 id（null = 无形象，悬浮窗显示占位提示）
 * @property scale 形象缩放（[MIN_SCALE]..[MAX_SCALE]，超出钳制）
 * @property opacity 悬浮窗不透明度（[MIN_OPACITY]..[MAX_OPACITY]）
 * @property posX / @property posY 归一化位置（0..1）
 * @property idleEnabled 是否启用随机待机动作
 * @property reduceMotion 减少动效（关闭随机待机与呼吸，仅保留眨眼与用户触发动作，PRD US-2406 AC-4）
 * @property expressionResetSeconds 表情自动回正秒数（0 = 不自动回正）
 * @property bubbleEnabled 是否显示对话气泡
 * @property lowPowerMode 低功耗模式（渲染帧率上限 15fps）
 */
data class PetConfig(
    val enabled: Boolean = false,
    val appearanceId: String? = null,
    val scale: Float = 1.0f,
    val opacity: Float = 1.0f,
    val posX: Float = 0.86f,
    val posY: Float = 0.72f,
    val idleEnabled: Boolean = true,
    val reduceMotion: Boolean = false,
    val expressionResetSeconds: Int = PetAppearance.DEFAULT_EXPRESSION_RESET_SECONDS,
    val bubbleEnabled: Boolean = true,
    val lowPowerMode: Boolean = false
) {

    /**
     * 钳制到合法域（幂等）。
     *
     * **调用时机**：① 从 DataStore 读出后（外部可被篡改/旧版本残留）；② 设置页写入前；
     * ③ 渲染层计算尺寸前。任何进入业务逻辑的 [PetConfig] 都应是已钳制的实例。
     */
    fun clamped(): PetConfig = copy(
        appearanceId = appearanceId?.trim()?.takeIf { it.isNotEmpty() },
        scale = finite(scale, 1.0f).coerceIn(MIN_SCALE, MAX_SCALE),
        opacity = finite(opacity, 1.0f).coerceIn(MIN_OPACITY, MAX_OPACITY),
        posX = finite(posX, DEFAULT.posX).coerceIn(0f, 1f),
        posY = finite(posY, DEFAULT.posY).coerceIn(0f, 1f),
        expressionResetSeconds = expressionResetSeconds.coerceIn(
            MIN_EXPRESSION_RESET_SECONDS,
            MAX_EXPRESSION_RESET_SECONDS
        )
    )

    /**
     * 非有限浮点（NaN / ±Inf）回落到 [fallback]。
     *
     * **为什么必须单独处理**：`Float.coerceIn` 的实现基于比较运算，而 NaN 与任何值的比较都是
     * `false` ⇒ `NaN.coerceIn(0.5f, 1.5f)` **原样返回 NaN**。NaN 一旦进入窗口布局，
     * `(posX * freeSpace).roundToInt()` 会得到 `0` 或抛异常，表现为"桌宠莫名跑到左上角"，
     * 而且从日志上完全看不出原因。DataStore 里的值可能被旧版本或外部写入污染，故在此拦截。
     */
    private fun finite(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback

    /** 归一化位置是否与 [other] 有实质差异（避免高频拖动时无谓写盘）。 */
    fun positionDiffersFrom(other: PetConfig): Boolean =
        kotlin.math.abs(posX - other.posX) > POSITION_EPSILON ||
            kotlin.math.abs(posY - other.posY) > POSITION_EPSILON

    /** 是否应当运行待机动作（关闭待机 / 减少动效 / 总开关关闭 三者任一即不待机）。 */
    val idleActive: Boolean
        get() = enabled && idleEnabled && !reduceMotion

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 1.5f
        const val MIN_OPACITY = 0.3f
        const val MAX_OPACITY = 1.0f
        const val MIN_EXPRESSION_RESET_SECONDS = 0
        const val MAX_EXPRESSION_RESET_SECONDS = 60

        /** 位置差异阈值（归一化坐标）；小于该值的拖动视为抖动，不写盘。 */
        const val POSITION_EPSILON = 0.002f

        /** 出厂默认配置（桌宠关闭）。 */
        val DEFAULT = PetConfig()
    }
}

/**
 * 批次24（ADR-051 D8 / PRD US-2406）：待机行为策略 —— **纯函数**，时钟与随机源全部注入，可 JVM 单测。
 *
 * **语义**：
 * - "用户交互后重置计时"由调用方**重新调用 [nextDelayMs]** 实现——刻意不引入可变状态机，
 *   因为该状态只有"下一次到期时刻"一个字段，放进调度器只会增加可测面而不增加正确性；
 * - 间隔取 `[MIN_IDLE_MS, MAX_IDLE_MS]` 内的均匀随机（每次重新采样，避免机械节奏感）。
 */
object PetIdlePolicy {

    /** 待机动作最小间隔（毫秒）——短于此会显得"多动症"，干扰用户阅读。 */
    const val MIN_IDLE_MS = 12_000L

    /** 待机动作最大间隔（毫秒）——长于此会显得"死掉了"。 */
    const val MAX_IDLE_MS = 30_000L

    /**
     * 计算下一次待机动作的延迟。
     *
     * @param config 已钳制的配置
     * @param random 随机源（测试注入固定种子）
     * @return 延迟毫秒数；**不启用待机时返回 null**（调用方据此取消定时器）
     */
    fun nextDelayMs(config: PetConfig, random: Random = Random.Default): Long? {
        if (!config.idleActive) return null
        val span = MAX_IDLE_MS - MIN_IDLE_MS
        return MIN_IDLE_MS + random.nextLong(span + 1)
    }
}

/**
 * 批次24（ADR-051 D5 / PRD US-2403）：悬浮窗几何计算 —— **纯函数**，可 JVM 单测。
 *
 * **核心思想**：位置以"**归一化自由空间坐标**"表达（[PetConfig.posX]/[PetConfig.posY]），
 * 而不是像素偏移。像素偏移方案在旋转屏/折叠屏/多窗口切换后必须写一大堆"上次在哪、现在怎么映射"
 * 的兜底逻辑，而且**无法结构性保证窗口不被裁掉**；归一化方案下 x = posX × (屏幕宽 − 窗口宽)，
 * 只要 posX ∈ [0,1] 就**必然**完整可见。
 *
 * **窗口尺寸的双重钳制**：请求尺寸先被钳到 `[minSize, screenSize]`——`minSize` 防"缩到看不见"，
 * `screenSize` 防"形象比屏幕还大导致自由空间为负"。
 */
object PetWindowGeometry {

    /**
     * 计算窗口的最终像素矩形。
     *
     * @param areaWidth 可用区域宽（已扣除系统栏/刘海安全区）
     * @param areaHeight 可用区域高
     * @param contentWidth 期望内容宽（按 scale 与模型宽高比算出）
     * @param contentHeight 期望内容高
     * @param posX 归一化位置 0..1
     * @param posY 归一化位置 0..1
     * @param minWidth 最小窗口宽（防缩到不可见）
     * @param minHeight 最小窗口高
     * @return 像素矩形；[areaWidth]/[areaHeight] <= 0 时返回全 0 矩形（调用方应跳过布局）
     */
    fun resolve(
        areaWidth: Int,
        areaHeight: Int,
        contentWidth: Int,
        contentHeight: Int,
        posX: Float,
        posY: Float,
        minWidth: Int = 1,
        minHeight: Int = 1
    ): PetWindowFrame {
        if (areaWidth <= 0 || areaHeight <= 0) return PetWindowFrame(0, 0, 0, 0)
        val width = contentWidth.coerceIn(minWidth.coerceAtMost(areaWidth), areaWidth)
        val height = contentHeight.coerceIn(minHeight.coerceAtMost(areaHeight), areaHeight)
        val x = (posX.coerceIn(0f, 1f) * (areaWidth - width)).roundToInt()
        val y = (posY.coerceIn(0f, 1f) * (areaHeight - height)).roundToInt()
        return PetWindowFrame(x, y, width, height)
    }

    /**
     * 像素偏移 → 归一化坐标（拖动落点持久化用；[resolve] 的逆运算）。
     *
     * @param freeSpace `areaSize - windowSize`；<= 0 时无自由空间，恒返回 0
     */
    fun normalize(areaSize: Int, windowSize: Int, offset: Int): Float {
        val free = areaSize - windowSize
        if (free <= 0) return 0f
        return (offset.toFloat() / free).coerceIn(0f, 1f)
    }
}

/**
 * 悬浮窗像素矩形（纯数据）。
 *
 * @property x 左上角 X（屏幕可用区域坐标系）
 * @property y 左上角 Y
 * @property width 宽（像素）
 * @property height 高（像素）
 */
data class PetWindowFrame(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    /** 宽高均 > 0 才是可布局的窗口。 */
    val isLayoutable: Boolean
        get() = width > 0 && height > 0
}
