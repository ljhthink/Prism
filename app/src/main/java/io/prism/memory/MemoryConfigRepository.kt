package io.prism.memory

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 记忆系统配置仓库（ADR-015 5.3 / v1 记忆深度优化 US-103~104）—— 持久化记忆系统可配置参数。
 *
 * **设计**：使用 DataStore<Preferences> 存储可配置参数，与 [io.prism.security.ApiKeyRepository]
 * 同模式（DataStore 进程级单例 + 委托属性）。独立 DataStore 文件（`prism_memory_config`），
 * 与 API Key / 文件系统根目录 DataStore 隔离，避免耦合。
 *
 * **配置项**：
 * - [WINDOW_SIZE_KEY]：L1 滑动窗口大小 N（默认 10，可配置，运行时动态生效）
 * - [DEDUP_ENABLED_KEY]：L2 批量去重开关（v1 US-103，默认 true）
 * - [MEMORY_CAPACITY_KEY]：L2 记忆容量上限（v1 US-103，默认 10000）
 * - [DECAY_LAMBDA_KEY]：软衰减时间衰减系数 λ/天（v1 US-103，默认 0.01）
 * - [DECAY_ALPHA_KEY]：软衰减使用频率增强系数 α（v1 US-103，默认 0.5）
 * - [DECAY_THRESHOLD_KEY]：软衰减注入阈值（v1 US-103，默认 20.0）
 * - [INJECTION_MAX_RESULTS_KEY]：L2 注入最大条数（v1 US-104，默认 5）
 * - [INJECTION_MAX_CHARS_KEY]：L2 注入单条字符上限（v1 US-104，默认 200）
 *
 * **校验**（fail-fast 纵深防御，BR-security-005）：
 * - 窗口大小 [MIN_WINDOW_SIZE]..[MAX_WINDOW_SIZE]
 * - 容量上限 > 0
 *
 * **线程安全**：DataStore 保证原子读写，多协程并发安全。
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
     * @return 当前 N 值；未配置时返回 [DEFAULT_WINDOW_SIZE]
     */
    suspend fun getWindowSize(): Int = windowSize().first()

    /**
     * 设置滑动窗口大小 N（持久化到 DataStore）。
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

    /** 观察 L2 批量去重开关（v1 US-103）。默认 true。 */
    fun dedupEnabled(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[DEDUP_ENABLED_KEY] ?: DEFAULT_DEDUP_ENABLED
    }

    /** 一次性读取 L2 批量去重开关。 */
    suspend fun isDedupEnabled(): Boolean = dedupEnabled().first()

    /** 设置 L2 批量去重开关。 */
    suspend fun setDedupEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[DEDUP_ENABLED_KEY] = enabled }
    }

    /** 观察 L2 记忆容量上限（v1 US-103）。默认 10000。 */
    fun memoryCapacity(): Flow<Int> = dataStore.data.map { prefs ->
        prefs[MEMORY_CAPACITY_KEY] ?: DEFAULT_MEMORY_CAPACITY
    }

    /** 一次性读取 L2 记忆容量上限。 */
    suspend fun getMemoryCapacity(): Int = memoryCapacity().first()

    /** 设置 L2 记忆容量上限（必须 > 0）。 */
    suspend fun setMemoryCapacity(capacity: Int) {
        require(capacity > 0) { "记忆容量上限必须 > 0（收到 $capacity）" }
        dataStore.edit { prefs -> prefs[MEMORY_CAPACITY_KEY] = capacity }
    }

    /** 观察软衰减时间衰减系数 λ/天（v1 US-103）。默认 0.01。 */
    fun decayLambda(): Flow<Double> = dataStore.data.map { prefs ->
        prefs[DECAY_LAMBDA_KEY] ?: DEFAULT_DECAY_LAMBDA
    }

    /** 一次性读取软衰减时间衰减系数 λ。 */
    suspend fun getDecayLambda(): Double = decayLambda().first()

    /** 设置软衰减时间衰减系数 λ（必须 ≥ 0）。 */
    suspend fun setDecayLambda(lambda: Double) {
        require(lambda >= 0.0) { "衰减系数必须 ≥ 0（收到 $lambda）" }
        dataStore.edit { prefs -> prefs[DECAY_LAMBDA_KEY] = lambda }
    }

    /** 观察软衰减使用频率增强系数 α（v1 US-103）。默认 0.5。 */
    fun decayAlpha(): Flow<Double> = dataStore.data.map { prefs ->
        prefs[DECAY_ALPHA_KEY] ?: DEFAULT_DECAY_ALPHA
    }

    /** 一次性读取软衰减使用频率增强系数 α。 */
    suspend fun getDecayAlpha(): Double = decayAlpha().first()

    /** 设置软衰减使用频率增强系数 α（必须 ≥ 0）。 */
    suspend fun setDecayAlpha(alpha: Double) {
        require(alpha >= 0.0) { "频率增强系数必须 ≥ 0（收到 $alpha）" }
        dataStore.edit { prefs -> prefs[DECAY_ALPHA_KEY] = alpha }
    }

    /** 观察软衰减注入阈值（v1 US-103）。默认 20.0。 */
    fun decayThreshold(): Flow<Double> = dataStore.data.map { prefs ->
        prefs[DECAY_THRESHOLD_KEY] ?: DEFAULT_DECAY_THRESHOLD
    }

    /** 一次性读取软衰减注入阈值。 */
    suspend fun getDecayThreshold(): Double = decayThreshold().first()

    /** 设置软衰减注入阈值（必须 ≥ 0）。 */
    suspend fun setDecayThreshold(threshold: Double) {
        require(threshold >= 0.0) { "软衰减注入阈值必须 ≥ 0（收到 $threshold）" }
        dataStore.edit { prefs -> prefs[DECAY_THRESHOLD_KEY] = threshold }
    }

    /** 观察 L2 注入最大条数（v1 US-104）。默认 5。 */
    fun injectionMaxResults(): Flow<Int> = dataStore.data.map { prefs ->
        prefs[INJECTION_MAX_RESULTS_KEY] ?: DEFAULT_INJECTION_MAX_RESULTS
    }

    /** 一次性读取 L2 注入最大条数。 */
    suspend fun getInjectionMaxResults(): Int = injectionMaxResults().first()

    /** 设置 L2 注入最大条数（必须 > 0）。 */
    suspend fun setInjectionMaxResults(max: Int) {
        require(max > 0) { "注入最大条数必须 > 0（收到 $max）" }
        dataStore.edit { prefs -> prefs[INJECTION_MAX_RESULTS_KEY] = max }
    }

    /** 观察 L2 注入单条字符上限（v1 US-104）。默认 200。 */
    fun injectionMaxChars(): Flow<Int> = dataStore.data.map { prefs ->
        prefs[INJECTION_MAX_CHARS_KEY] ?: DEFAULT_INJECTION_MAX_CHARS
    }

    /** 一次性读取 L2 注入单条字符上限。 */
    suspend fun getInjectionMaxChars(): Int = injectionMaxChars().first()

    /** 设置 L2 注入单条字符上限（必须 > 0）。 */
    suspend fun setInjectionMaxChars(max: Int) {
        require(max > 0) { "注入单条字符上限必须 > 0（收到 $max）" }
        dataStore.edit { prefs -> prefs[INJECTION_MAX_CHARS_KEY] = max }
    }

    companion object {
        /** 滑动窗口大小 N 的 DataStore key。 */
        private val WINDOW_SIZE_KEY = intPreferencesKey("sliding_window_size")

        /** L2 批量去重开关（v1 US-103）。 */
        private val DEDUP_ENABLED_KEY = booleanPreferencesKey("dedup_enabled")

        /** L2 记忆容量上限（v1 US-103）。 */
        private val MEMORY_CAPACITY_KEY = intPreferencesKey("memory_capacity")

        /** 软衰减时间衰减系数 λ/天（v1 US-103）。 */
        private val DECAY_LAMBDA_KEY = doublePreferencesKey("decay_lambda")

        /** 软衰减使用频率增强系数 α（v1 US-103）。 */
        private val DECAY_ALPHA_KEY = doublePreferencesKey("decay_alpha")

        /** 软衰减注入阈值（v1 US-103）。 */
        private val DECAY_THRESHOLD_KEY = doublePreferencesKey("decay_threshold")

        /** L2 注入最大条数（v1 US-104）。 */
        private val INJECTION_MAX_RESULTS_KEY = intPreferencesKey("injection_max_results")

        /** L2 注入单条字符上限（v1 US-104）。 */
        private val INJECTION_MAX_CHARS_KEY = intPreferencesKey("injection_max_chars")

        /** 默认滑动窗口大小（US-032 AC-2：默认 10 轮原始消息）。 */
        const val DEFAULT_WINDOW_SIZE = 10

        /** 最小允许的窗口大小（用于 UI 层校验参考）。 */
        const val MIN_WINDOW_SIZE = 1

        /** 最大允许的窗口大小（用于 UI 层校验参考，防止过大导致 token 溢出）。 */
        const val MAX_WINDOW_SIZE = 50

        /** L2 批量去重默认开关（v1 US-103）。 */
        const val DEFAULT_DEDUP_ENABLED = true

        /** L2 记忆默认容量上限（v1 US-103，参照 TencentDB-Agent-Memory memoryLimit=10000）。 */
        const val DEFAULT_MEMORY_CAPACITY = 10000

        /** 软衰减默认时间衰减系数 λ/天（v1 US-103）。 */
        const val DEFAULT_DECAY_LAMBDA = 0.01

        /** 软衰减默认使用频率增强系数 α（v1 US-103）。 */
        const val DEFAULT_DECAY_ALPHA = 0.5

        /** 软衰减默认注入阈值（v1 US-103）。priority=50 且较新时 score≈50，默认阈值 20 保留之。 */
        const val DEFAULT_DECAY_THRESHOLD = 20.0

        /** L2 注入默认最大条数（v1 US-104，参照检索预算 maxResults=5）。 */
        const val DEFAULT_INJECTION_MAX_RESULTS = 5

        /** L2 注入默认单条字符上限（v1 US-104）。 */
        const val DEFAULT_INJECTION_MAX_CHARS = 200
    }
}
