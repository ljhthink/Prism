package io.prism.tier

import android.app.ActivityManager
import android.content.Context

/**
 * 设备档位管理器（ADR-017 4.1）—— RAM 检测 + 用户覆盖解析 + 当前档位暴露。
 *
 * **职责**：
 * 1. 通过 [tierDetector] 检测设备 RAM 映射的档位
 * 2. 通过 [overrideReader] 读取用户覆盖值
 * 3. 解析最终生效档位（覆盖优先于 RAM 检测）
 * 4. 暴露 [currentTier] / [detectedTier] / [totalRamBytes] 供 `by lazy` 注入与 UI 读取
 *
 * **初始化时序**（ADR-017 4.4）：
 * ```kotlin
 * // PrismApplication.onCreate
 * tierManager = TierManager(
 *     tierDetector = TierManager.DefaultTierDetector(this),
 *     overrideReader = TierManager.DefaultOverrideReader(tierConfigRepository)
 * ).also { it.initialize() }
 * ```
 * [initialize] 必须在所有 `by lazy` 注入访问 [currentTier] 之前完成。
 * 由于 `by lazy` 首次访问发生在 ViewModel 构造时（远晚于 onCreate），此约束天然满足。
 *
 * **覆盖生效**（ADR-017 4.4）：用户在 UI 修改覆盖后需重启 App 生效。
 * [currentTier] 在 [initialize] 后不可变（val），UI 修改覆盖仅持久化到 DataStore，
 * 下次启动时 [initialize] 读取新值。UI 需明确提示「重启 App 生效」。
 *
 * **可测性**：通过 [tierDetector] / [overrideReader] 注入点，测试可绕过 Android Context
 * 与 runBlocking，直接构造任意档位组合。
 *
 * US-007 验收标准 1：启动时检测设备 RAM，自动选择功能档位
 * US-007 验收标准 6：用户可在设置中手动覆盖档位
 *
 * @param tierDetector RAM 检测器（生产用 [DefaultTierDetector]，测试可注入假实现）
 * @param overrideReader 覆盖读取器（生产用 [DefaultOverrideReader]，测试可注入假实现）
 */
class TierManager(
    private val tierDetector: TierDetector,
    private val overrideReader: OverrideReader
) {

    /** RAM 检测到的档位（[initialize] 后填充）。 */
    private var _detectedTier: PerformanceTier? = null
    val detectedTier: PerformanceTier
        get() = _detectedTier ?: error("TierManager 未初始化，请先调用 initialize()")

    /** 设备 RAM 总量（字节，[initialize] 后填充）。 */
    private var _totalRamBytes: Long = 0L
    val totalRamBytes: Long
        get() = _totalRamBytes

    /** 当前生效档位（覆盖优先于检测，[initialize] 后填充）。 */
    private var _currentTier: PerformanceTier? = null
    val currentTier: PerformanceTier
        get() = _currentTier ?: error("TierManager 未初始化，请先调用 initialize()")

    /** 当前覆盖值（[OVERRIDE_AUTO] 或档位枚举名，[initialize] 后填充）。 */
    private var _currentOverride: String = TierConfigRepository.OVERRIDE_AUTO
    val currentOverride: String
        get() = _currentOverride

    /**
     * 是否已初始化（[initialize] 调用后为 true）。
     *
     * 用于防御性检查，避免 `by lazy` 注入在 [initialize] 之前误访问。
     */
    val isInitialized: Boolean
        get() = _currentTier != null

    /**
     * 初始化：同步检测 RAM + 读取覆盖 + 解析最终档位。
     *
     * **必须在 [PrismApplication.onCreate] 中调用**，且在任何 `by lazy` 注入访问 [currentTier] 之前。
     *
     * 内部通过 [overrideReader] 读取覆盖（默认实现用 `runBlocking` 包 suspend 调用，
     * 测试可注入假实现直接返回内存值，避免 runBlocking）。
     */
    fun initialize() {
        val detected = tierDetector.detect()
        _detectedTier = detected.tier
        _totalRamBytes = detected.totalRamBytes

        val override = overrideReader.read()
        _currentOverride = override
        _currentTier = resolveTier(detected.tier, override)
    }

    /**
     * 解析最终档位（覆盖优先于检测）。
     *
     * @param detected RAM 检测到的档位
     * @param override 用户覆盖值（[TierConfigRepository.OVERRIDE_AUTO] 或档位枚举名）
     * @return 最终生效档位
     */
    private fun resolveTier(detected: PerformanceTier, override: String): PerformanceTier {
        if (override == TierConfigRepository.OVERRIDE_AUTO) {
            return detected
        }
        return runCatching { PerformanceTier.valueOf(override) }
            .getOrElse {
                // 防御：DataStore 被外部写入无效值时降级为检测结果（不阻断启动）
                android.util.Log.w(
                    "TierManager",
                    "无效的档位覆盖值 '$override'，降级为检测结果 $detected"
                )
                detected
            }
    }

    /** RAM 检测结果。 */
    data class DetectionResult(
        val tier: PerformanceTier,
        val totalRamBytes: Long
    )

    /** RAM 检测器接口（便于测试注入假实现）。 */
    fun interface TierDetector {
        fun detect(): DetectionResult
    }

    /** 覆盖读取器接口（便于测试注入假实现，避免 runBlocking）。 */
    fun interface OverrideReader {
        fun read(): String
    }

    /**
     * 默认 RAM 检测器 —— 通过 [ActivityManager.MemoryInfo] 获取设备 RAM 总量。
     *
     * **已知限制**（ADR-017 4.3）：`totalMem` 在某些设备上报告值小于物理 RAM，
     * 采用保守阈值（3.5GB 设备归入 CHAT_ONLY 档）。
     */
    class DefaultTierDetector(private val context: Context) : TierDetector {
        override fun detect(): DetectionResult {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val totalRamBytes = memoryInfo.totalMem
            val tier = PerformanceTier.fromRamBytes(totalRamBytes)
            return DetectionResult(tier, totalRamBytes)
        }
    }

    /**
     * 默认覆盖读取器 —— `runBlocking` 包 [TierConfigRepository.getOverride] suspend 调用。
     *
     * DataStore 首次读取通常 <50ms（单 key），onCreate 中阻塞可接受。
     *
     * **H-01 修复（CWE-754，guardrail TKN-M7-GUARDRAIL-001）**：DataStore 文件损坏或
     * 读取异常时降级为 [TierConfigRepository.OVERRIDE_AUTO]，避免 onCreate 未捕获异常
     * 导致 App 启动崩溃。CancellationException 不吞（BR-error-handling-007）。
     */
    class DefaultOverrideReader(
        private val configRepository: TierConfigRepository
    ) : OverrideReader {
        override fun read(): String = kotlinx.coroutines.runBlocking {
            try {
                configRepository.getOverride()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                android.util.Log.w("TierManager", "读取档位覆盖失败，降级为 AUTO", e)
                TierConfigRepository.OVERRIDE_AUTO
            }
        }
    }
}
