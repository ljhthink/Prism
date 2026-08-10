package io.prism.memory

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 记忆系统配置仓库（ADR-015 5.3）—— 持久化 L1 滑动窗口大小 N。
 *
 * **设计**：使用 DataStore<Preferences> 存储可配置参数，与 [io.prism.security.ApiKeyRepository]
 * 同模式（DataStore 进程级单例 + 委托属性）。独立 DataStore 文件（`prism_memory_config`），
 * 与 API Key / 文件系统根目录 DataStore 隔离，避免耦合。
 *
 * **配置项**：
 * - [WINDOW_SIZE_KEY]：L1 滑动窗口大小 N（默认 10，可配置，运行时动态生效）
 *
 * **校验**：[setWindowSize] 拒绝 ≤0 或 > [MAX_WINDOW_SIZE] 的值（fail-fast 纵深防御，
 * BR-security-005），N 必须在 [MIN_WINDOW_SIZE]..[MAX_WINDOW_SIZE] 范围内。
 *
 * **线程安全**：DataStore 保证原子读写，多协程并发安全。
 *
 * US-032 AC-4：可配置 N（通过 DataStore 持久化，默认 10），运行时动态生效
 *
 * @param dataStore Memory Config 专用 DataStore（`prism_memory_config`）
 */
class MemoryConfigRepository(
    private val dataStore: DataStore<Preferences>
) {

    /**
     * 观察滑动窗口大小 N（热流，配置变更时自动推送）。
     *
     * @return N 的 Flow，首次读取若未配置返回 [DEFAULT_WINDOW_SIZE]
     */
    fun windowSize(): Flow<Int> = dataStore.data.map { prefs ->
        prefs[WINDOW_SIZE_KEY] ?: DEFAULT_WINDOW_SIZE
    }

    /**
     * 一次性读取滑动窗口大小 N（suspend 单值）。
     *
     * 供 [SlidingWindowMemoryManager.processMessages] 在协程内一次取用当前 N 值。
     *
     * @return 当前 N 值；未配置时返回 [DEFAULT_WINDOW_SIZE]
     */
    suspend fun getWindowSize(): Int = windowSize().first()

    /**
     * 设置滑动窗口大小 N（持久化到 DataStore）。
     *
     * **纵深防御**（BR-security-005，guardrail-enforcer M-1 修复）：
     * 同时校验下界（≥ [MIN_WINDOW_SIZE]）和上界（≤ [MAX_WINDOW_SIZE]），
     * 防止 DataStore 被外部写入超大值导致下游 token 溢出。
     *
     * @param size 新的窗口大小，必须在 [MIN_WINDOW_SIZE]..[MAX_WINDOW_SIZE] 范围内
     * @throws IllegalArgumentException 当 size < [MIN_WINDOW_SIZE] 或 size > [MAX_WINDOW_SIZE] 时抛出（fail-fast）
     */
    suspend fun setWindowSize(size: Int) {
        require(size >= MIN_WINDOW_SIZE) {
            "滑动窗口大小 N 必须 ≥ $MIN_WINDOW_SIZE（收到 $size）"
        }
        require(size <= MAX_WINDOW_SIZE) {
            "滑动窗口大小 N 必须 ≤ $MAX_WINDOW_SIZE，过大将导致 token 溢出（收到 $size）"
        }
        dataStore.edit { prefs ->
            prefs[WINDOW_SIZE_KEY] = size
        }
    }

    companion object {
        /** 滑动窗口大小 N 的 DataStore key。 */
        private val WINDOW_SIZE_KEY = intPreferencesKey("sliding_window_size")

        /** 默认滑动窗口大小（US-032 AC-2：默认 10 轮原始消息）。 */
        const val DEFAULT_WINDOW_SIZE = 10

        /** 最小允许的窗口大小（用于 UI 层校验参考）。 */
        const val MIN_WINDOW_SIZE = 1

        /** 最大允许的窗口大小（用于 UI 层校验参考，防止过大导致 token 溢出）。 */
        const val MAX_WINDOW_SIZE = 50
    }
}
