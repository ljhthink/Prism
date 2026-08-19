package io.prism.ui.settings

import androidx.datastore.preferences.core.emptyPreferences
import io.objectbox.BoxStore
import io.prism.config.VisionBypassConfigRepository
import io.prism.data.MyObjectBox
import io.prism.data.ProviderConfigRepository
import io.prism.security.ApiKeyRepository
import io.prism.security.FakePreferenceDataStore
import io.prism.security.RecordingCryptoService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ac-verifier 补充测试（TKN-V1-B2-ACCEPTANCE-001，US-301 AC3）—— SettingsViewModel 视觉旁路
 * 授权/自动开关读写补盲（既有 SettingsViewModelTest 无视觉相关用例）。
 *
 * 验证：
 * - 默认：授权 false、自动开关 true（D-6 隐私刚性要求：未授权默认不旁路）
 * - setVisionConsent(true/false) 写 → visionConsent 状态读
 * - setVisionAutoBypass(true/false) 写 → visionAutoBypass 状态读
 * - 持久化：新实例从同一 DataStore 恢复授权/开关
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelVisionSupplementTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File
    private lateinit var visionDataStore: FakePreferenceDataStore

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "settings-vision-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        visionDataStore = FakePreferenceDataStore(emptyPreferences())
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun createVm(visionRepo: VisionBypassConfigRepository? = null): SettingsViewModel =
        SettingsViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            apiKeyRepository = ApiKeyRepository(FakePreferenceDataStore(), RecordingCryptoService()),
            visionBypassConfigRepository = visionRepo
        )

    private fun createVisionRepo() = VisionBypassConfigRepository(visionDataStore)

    // ==================== 默认值 ====================

    @Test
    fun `vision consent defaults to false and auto bypass to true`() = runTest(mainDispatcher) {
        val vm = createVm(createVisionRepo())
        val job = launch { vm.visionConsent.collect { } }
        assertFalse("默认未授权（隐私刚性：不旁路）", vm.visionConsent.value)
        assertTrue("默认自动旁路开启", vm.visionAutoBypass.value)
        job.cancel()
    }

    @Test
    fun `null vision repo degrades to defaults`() = runTest(mainDispatcher) {
        val vm = createVm(null) // 向后兼容：未注入视觉仓库
        val job = launch { vm.visionConsent.collect { } }
        assertFalse(vm.visionConsent.value)
        assertTrue(vm.visionAutoBypass.value)
        job.cancel()
    }

    // ==================== 授权读写 ====================

    @Test
    fun `setVisionConsent true persists and reflects in state`() = runTest(mainDispatcher) {
        val repo = createVisionRepo()
        val vm = createVm(repo)
        val job = launch { vm.visionConsent.collect { } }
        vm.setVisionConsent(true)
        assertTrue("授权开启应反映到状态", vm.visionConsent.value)
        assertTrue("授权应持久化到仓库", repo.isConsentGiven())
        job.cancel()
    }

    @Test
    fun `setVisionConsent can be toggled off`() = runTest(mainDispatcher) {
        val repo = createVisionRepo()
        val vm = createVm(repo)
        val job = launch { vm.visionConsent.collect { } }
        vm.setVisionConsent(true)
        vm.setVisionConsent(false)
        assertFalse("授权可一键关闭", vm.visionConsent.value)
        assertFalse(repo.isConsentGiven())
        job.cancel()
    }

    // ==================== 自动开关读写 ====================

    @Test
    fun `setVisionAutoBypass false persists and reflects in state`() = runTest(mainDispatcher) {
        val repo = createVisionRepo()
        val vm = createVm(repo)
        val job = launch { vm.visionAutoBypass.collect { } }
        vm.setVisionAutoBypass(false)
        assertFalse("自动旁路关闭应反映到状态", vm.visionAutoBypass.value)
        assertFalse(repo.isAutoBypassEnabled())
        job.cancel()
    }

    @Test
    fun `setVisionAutoBypass can be re-enabled`() = runTest(mainDispatcher) {
        val repo = createVisionRepo()
        val vm = createVm(repo)
        val job = launch { vm.visionAutoBypass.collect { } }
        vm.setVisionAutoBypass(false)
        vm.setVisionAutoBypass(true)
        assertTrue("自动旁路可重新开启", vm.visionAutoBypass.value)
        assertTrue(repo.isAutoBypassEnabled())
        job.cancel()
    }

    // ==================== 跨实例持久化 ====================

    @Test
    fun `vision settings persist across viewmodel instances sharing same datastore`() = runTest(mainDispatcher) {
        val repo = createVisionRepo()
        val vm1 = createVm(repo)
        val job = launch { vm1.visionConsent.collect { } }
        vm1.setVisionConsent(true)
        vm1.setVisionAutoBypass(false)
        advanceUntilIdle()
        job.cancel()

        // 直接经仓库确定性核验持久化（DataStore 层）
        val repo2 = VisionBypassConfigRepository(visionDataStore)
        assertTrue("授权持久化到仓库", repo2.isConsentGiven())
        assertFalse("自动开关持久化到仓库", repo2.isAutoBypassEnabled())

        // 新 VM 共享同一 DataStore，状态流应反映持久化值（stateIn WhileSubscribed 需订阅激活）
        val vm2 = createVm(repo2)
        val job2 = launch { vm2.visionConsent.collect { } }
        val job3 = launch { vm2.visionAutoBypass.collect { } }
        assertTrue("授权跨实例反映", vm2.visionConsent.value)
        assertFalse("自动开关跨实例反映", vm2.visionAutoBypass.value)
        job2.cancel()
        job3.cancel()
    }
}
