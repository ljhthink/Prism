package io.prism.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.prism.PrismApplication
import io.prism.tier.PerformanceTier
import io.prism.tier.TierConfigRepository
import io.prism.tier.TierManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设备档位设置 ViewModel（ADR-017 4.6，US-042）—— 桥接 [TierManager] 运行时状态
 * 与 [TierConfigRepository] 持久化覆盖，供 [SettingsScreen] 档位 UI 读写。
 *
 * **职责边界**：
 * - 只读暴露 [TierManager] 的运行时快照（[detectedTier] / [currentTier] / [totalRamBytes]），
 *   这些值在 App 启动后不可变（[TierManager.initialize] 后缓存到内存），UI 直接读 getter 即可，
 *   无需 StateFlow 包装。
 * - 通过 [TierConfigRepository.override] Flow 暴露持久化的用户覆盖值（[override]），
 *   UI 修改覆盖后 Flow 推送新值，但 **运行档位需重启 App 才反映**（ADR-017 4.4）。
 *
 * **覆盖生效语义**（ADR-017 4.4）：
 * - [setOverride] / [clearOverride] 仅持久化到 DataStore，**不立即改变 [currentTier]**。
 * - [TierManager] 在 `PrismApplication.onCreate` 中 `runBlocking` 一次性读取覆盖值缓存到内存，
 *   后续 `by lazy` 注入读内存字段。用户修改覆盖后，下次启动才生效。
 * - UI 必须明确提示「重启 App 生效」（[SettingsScreen] TierSheet 底部固定提示）。
 *
 * **错误处理**（BR-error-handling-007）：[setOverride] / [clearOverride] 用显式 try-catch
 * 包裹 suspend 调用，CancellationException 重抛（不吞），其他异常记录警告日志但不抛
 * （UI 已写入 DataStore 失败属于非致命错误，不应崩溃；用户可重试）。M-01 修复
 * （guardrail TKN-M7-GUARDRAIL-001）：原用 `runCatching` 会吞 CancellationException
 * 违反 BR-error-handling-007，已改为显式 try-catch。
 *
 * **线程安全**：[TierManager] 字段在 [TierManager.initialize] 后不可变（val），
 * 多线程读取安全；[TierConfigRepository] DataStore 保证原子读写。
 *
 * US-007 验收标准 6：用户可在设置中手动覆盖档位
 *
 * @param tierConfigRepository 档位配置仓库（持久化用户覆盖）
 * @param tierManager 档位管理器（提供运行时档位快照）
 */
class TierViewModel(
    private val tierConfigRepository: TierConfigRepository,
    private val tierManager: TierManager
) : ViewModel() {

    /**
     * RAM 检测到的档位（来自 [TierManager.detectedTier]，运行中不变）。
     *
     * 用于 UI 显示「自动检测」结果，与用户覆盖对比。
     */
    val detectedTier: PerformanceTier
        get() = tierManager.detectedTier

    /**
     * 当前生效档位（覆盖优先于检测，来自 [TierManager.currentTier]，运行中不变）。
     *
     * 这是 App 本次启动实际使用的档位，决定 RAG / embedder / L2 等功能开关。
     * 用户修改覆盖后，[currentTier] **不立即改变**，需重启 App。
     */
    val currentTier: PerformanceTier
        get() = tierManager.currentTier

    /**
     * 设备 RAM 总量（字节，来自 [TierManager.totalRamBytes]）。
     *
     * UI 格式化为 GB 显示（如「8.0GB」）。
     */
    val totalRamBytes: Long
        get() = tierManager.totalRamBytes

    /**
     * 用户覆盖值（持久化，UI 可改）。
     *
     * - [TierConfigRepository.OVERRIDE_AUTO]：无覆盖，使用 RAM 检测结果
     * - `FULL` / `STANDARD` / `MINIMAL` / `CHAT_ONLY`：强制使用指定档位
     *
     * StateFlow 初始值为 [TierConfigRepository.OVERRIDE_AUTO]（DataStore 首次读取完成前
     * 的占位值，与 [TierConfigRepository.override] Flow 的默认值一致）。
     */
    val override: StateFlow<String> = tierConfigRepository.override()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TierConfigRepository.OVERRIDE_AUTO
        )

    /**
     * 设置用户覆盖档位（持久化到 DataStore，需重启 App 生效）。
     *
     * **校验**：[TierConfigRepository.setOverride] 内部 `require(value in VALID_VALUES)`，
     * 非法值抛 [IllegalArgumentException]。本方法用 try-catch 兜底，失败时记录警告日志。
     *
     * **M-01 修复（guardrail TKN-M7-GUARDRAIL-001，BR-error-handling-007）**：
     * 不使用 `runCatching`（会吞 CancellationException），改用显式 try-catch
     * 重抛 CancellationException + catch Exception 记录日志。
     *
     * @param value 覆盖值，必须为 [TierConfigRepository.VALID_VALUES] 之一
     */
    fun setOverride(value: String) {
        viewModelScope.launch {
            try {
                tierConfigRepository.setOverride(value)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w("TierViewModel", "设置档位覆盖失败: $value", e)
            }
        }
    }

    /**
     * 清除用户覆盖（恢复为 [TierConfigRepository.OVERRIDE_AUTO]，需重启 App 生效）。
     *
     * 等价于 [setOverride]([TierConfigRepository.OVERRIDE_AUTO])，但语义更清晰，
     * 供 UI「恢复自动」选项调用。
     *
     * **M-01 修复**：同 [setOverride]，显式重抛 CancellationException。
     */
    fun clearOverride() {
        viewModelScope.launch {
            try {
                tierConfigRepository.clearOverride()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w("TierViewModel", "清除档位覆盖失败", e)
            }
        }
    }

    companion object {
        /**
         * 供 `viewModel()` initializer 使用的工厂。
         *
         * 从 [PrismApplication] 注入 [tierConfigRepository] + [tierManager]，
         * 避免在 Composable 中直接 cast（与 [SettingsViewModel] 同模式）。
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PrismApplication
                TierViewModel(app.tierConfigRepository, app.tierManager)
            }
        }
    }
}
