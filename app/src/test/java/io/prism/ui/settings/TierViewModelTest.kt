package io.prism.ui.settings

import androidx.datastore.preferences.core.emptyPreferences
import io.prism.security.FakePreferenceDataStore
import io.prism.tier.PerformanceTier
import io.prism.tier.TierConfigRepository
import io.prism.tier.TierManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * TierViewModel 单元测试（US-042，ADR-017 4.6）。
 *
 * 验证内容：
 * 1. [TierViewModel.override] 初始值为 [TierConfigRepository.OVERRIDE_AUTO]
 * 2. [TierViewModel.setOverride] 持久化覆盖值，Flow 推送新值
 * 3. [TierViewModel.clearOverride] 恢复 AUTO
 * 4. [TierViewModel.setOverride] 对非法值用 `runCatching` 兜底，不崩溃
 * 5. [TierViewModel.detectedTier] / [TierViewModel.currentTier] / [TierViewModel.totalRamBytes]
 *    正确读取 [TierManager] 快照（含覆盖优先于检测的场景）
 *
 * 测试基建：
 * - [FakePreferenceDataStore] 替代真实 DataStore（JVM 单元测试无 Android 依赖）
 * - [TierManager] 通过 [TierManager.TierDetector] / [TierManager.OverrideReader] 接口注入假实现，
 *   绕过 Android Context 与 runBlocking
 * - [UnconfinedTestDispatcher] + [Dispatchers.setMain] 让 ViewModel 的 `viewModelScope` 即时执行
 *
 * 注意：[TierViewModel.override] 为 `stateIn(viewModelScope, WhileSubscribed)`，
 * 需启动一个 collector 让 `stateIn` 进入活跃态，否则 `vm.override.value` 仅是初始占位值。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TierViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var dataStore: FakePreferenceDataStore
    private lateinit var configRepository: TierConfigRepository
    private lateinit var tierManager: TierManager

    /** 控制 [TierManager.OverrideReader] 返回值，测试不同覆盖场景。 */
    private var fakeOverride: String = TierConfigRepository.OVERRIDE_AUTO

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        dataStore = FakePreferenceDataStore(emptyPreferences())
        configRepository = TierConfigRepository(dataStore)
        tierManager = TierManager(
            tierDetector = TierManager.TierDetector {
                TierManager.DetectionResult(PerformanceTier.STANDARD, 5L * 1024L * 1024L * 1024L)
            },
            overrideReader = TierManager.OverrideReader { fakeOverride }
        ).also { it.initialize() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): TierViewModel =
        TierViewModel(configRepository, tierManager)

    // ==================== override 默认值 ====================

    @Test
    fun `override initial value is AUTO`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.override.collect { } }
        assertEquals(
            "未配置时 override 应为 AUTO",
            TierConfigRepository.OVERRIDE_AUTO,
            vm.override.value
        )
        job.cancel()
    }

    // ==================== setOverride 持久化 ====================

    @Test
    fun `setOverride persists FULL and flow emits new value`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.override.collect { } }

        vm.setOverride(PerformanceTier.FULL.name)

        assertEquals(
            "setOverride 后 override.value 应为 FULL",
            PerformanceTier.FULL.name,
            vm.override.value
        )
        // 验证确实持久化到 DataStore（跨 repository 实例读取）
        assertEquals(PerformanceTier.FULL.name, configRepository.getOverride())
        job.cancel()
    }

    @Test
    fun `setOverride persists STANDARD`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.override.collect { } }

        vm.setOverride(PerformanceTier.STANDARD.name)

        assertEquals(PerformanceTier.STANDARD.name, vm.override.value)
        job.cancel()
    }

    @Test
    fun `setOverride persists MINIMAL`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.override.collect { } }

        vm.setOverride(PerformanceTier.MINIMAL.name)

        assertEquals(PerformanceTier.MINIMAL.name, vm.override.value)
        job.cancel()
    }

    @Test
    fun `setOverride persists CHAT_ONLY`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.override.collect { } }

        vm.setOverride(PerformanceTier.CHAT_ONLY.name)

        assertEquals(PerformanceTier.CHAT_ONLY.name, vm.override.value)
        job.cancel()
    }

    @Test
    fun `setOverride overwrites previous value`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.override.collect { } }

        vm.setOverride(PerformanceTier.FULL.name)
        vm.setOverride(PerformanceTier.CHAT_ONLY.name)

        assertEquals(
            "后一次 setOverride 应覆盖前一次",
            PerformanceTier.CHAT_ONLY.name,
            vm.override.value
        )
        job.cancel()
    }

    // ==================== clearOverride ====================

    @Test
    fun `clearOverride restores AUTO after set`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.override.collect { } }

        vm.setOverride(PerformanceTier.FULL.name)
        vm.clearOverride()

        assertEquals(
            "clearOverride 后应恢复 AUTO",
            TierConfigRepository.OVERRIDE_AUTO,
            vm.override.value
        )
        job.cancel()
    }

    // ==================== 非法值兜底（runCatching） ====================

    @Test
    fun `setOverride invalid value does not crash`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.override.collect { } }

        // 非法值应被 runCatching 兜底，不抛异常
        vm.setOverride("INVALID_TIER")

        // 值应保持 AUTO（非法值未持久化）
        assertEquals(
            "非法值不应改变 override",
            TierConfigRepository.OVERRIDE_AUTO,
            vm.override.value
        )
        job.cancel()
    }

    @Test
    fun `setOverride empty string does not crash`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.override.collect { } }

        vm.setOverride("")

        assertEquals(
            "空字符串不应改变 override",
            TierConfigRepository.OVERRIDE_AUTO,
            vm.override.value
        )
        job.cancel()
    }

    @Test
    fun `setOverride lowercase tier name does not crash`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.override.collect { } }

        // 小写 "full" 不在 VALID_VALUES 中（要求大写枚举名）
        vm.setOverride("full")

        assertEquals(
            "小写档位名不应改变 override",
            TierConfigRepository.OVERRIDE_AUTO,
            vm.override.value
        )
        job.cancel()
    }

    // ==================== TierManager 快照读取 ====================

    @Test
    fun `detectedTier reads from TierManager`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        assertEquals(
            "detectedTier 应等于 TierManager.detectedTier",
            tierManager.detectedTier,
            vm.detectedTier
        )
    }

    @Test
    fun `currentTier reads from TierManager`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        assertEquals(
            "currentTier 应等于 TierManager.currentTier",
            tierManager.currentTier,
            vm.currentTier
        )
    }

    @Test
    fun `totalRamBytes reads from TierManager`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        assertEquals(
            "totalRamBytes 应等于 TierManager.totalRamBytes",
            tierManager.totalRamBytes,
            vm.totalRamBytes
        )
    }

    @Test
    fun `detectedTier is STANDARD for 5GB fake detector`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        assertEquals(
            "5GB RAM 应检测为 STANDARD 档",
            PerformanceTier.STANDARD,
            vm.detectedTier
        )
    }

    @Test
    fun `totalRamBytes is 5GB for fake detector`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        assertEquals(
            "fake detector 配置为 5GB",
            5L * 1024L * 1024L * 1024L,
            vm.totalRamBytes
        )
    }

    // ==================== 覆盖优先于检测 ====================

    @Test
    fun `currentTier reflects override when override is FULL`() = runTest(mainDispatcher) {
        // fakeOverride=AUTO（默认）→ current=detected=STANDARD
        val vm1 = createViewModel()
        assertEquals(PerformanceTier.STANDARD, vm1.currentTier)

        // 重新构造：fakeOverride=FULL → current=FULL（覆盖优先于检测）
        fakeOverride = PerformanceTier.FULL.name
        val overrideManager = TierManager(
            tierDetector = TierManager.TierDetector {
                TierManager.DetectionResult(PerformanceTier.STANDARD, 5L * 1024L * 1024L * 1024L)
            },
            overrideReader = TierManager.OverrideReader { fakeOverride }
        ).also { it.initialize() }
        val vm2 = TierViewModel(configRepository, overrideManager)

        assertEquals(
            "detectedTier 不受覆盖影响，仍为 STANDARD",
            PerformanceTier.STANDARD,
            vm2.detectedTier
        )
        assertEquals(
            "currentTier 应为覆盖值 FULL",
            PerformanceTier.FULL,
            vm2.currentTier
        )
    }

    @Test
    fun `currentTier is CHAT_ONLY when override is CHAT_ONLY`() = runTest(mainDispatcher) {
        fakeOverride = PerformanceTier.CHAT_ONLY.name
        val overrideManager = TierManager(
            tierDetector = TierManager.TierDetector {
                TierManager.DetectionResult(PerformanceTier.STANDARD, 5L * 1024L * 1024L * 1024L)
            },
            overrideReader = TierManager.OverrideReader { fakeOverride }
        ).also { it.initialize() }
        val vm = TierViewModel(configRepository, overrideManager)

        assertEquals(
            "currentTier 应为覆盖值 CHAT_ONLY（即使检测为 STANDARD）",
            PerformanceTier.CHAT_ONLY,
            vm.currentTier
        )
    }

    @Test
    fun `currentTier is MINIMAL when override is MINIMAL`() = runTest(mainDispatcher) {
        fakeOverride = PerformanceTier.MINIMAL.name
        val overrideManager = TierManager(
            tierDetector = TierManager.TierDetector {
                TierManager.DetectionResult(PerformanceTier.FULL, 8L * 1024L * 1024L * 1024L)
            },
            overrideReader = TierManager.OverrideReader { fakeOverride }
        ).also { it.initialize() }
        val vm = TierViewModel(configRepository, overrideManager)

        assertEquals(PerformanceTier.FULL, vm.detectedTier)
        assertEquals(
            "currentTier 应为覆盖值 MINIMAL（即使检测为 FULL）",
            PerformanceTier.MINIMAL,
            vm.currentTier
        )
    }

    // ==================== 覆盖持久化跨 ViewModel 实例 ====================

    @Test
    fun `override persists across viewmodel instances`() = runTest(mainDispatcher) {
        val vm1 = createViewModel()
        val job1 = launch { vm1.override.collect { } }
        vm1.setOverride(PerformanceTier.MINIMAL.name)
        job1.cancel()

        // 用同一 dataStore 构造新 ViewModel，模拟 ViewModel 重建后读取
        val vm2 = createViewModel()
        val job2 = launch { vm2.override.collect { } }

        assertEquals(
            "新 ViewModel 实例应能读到之前持久化的覆盖值",
            PerformanceTier.MINIMAL.name,
            vm2.override.value
        )
        job2.cancel()
    }
}
