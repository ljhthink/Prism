package io.prism.ui.settings

import io.objectbox.BoxStore
import io.prism.config.ToolApprovalConfigRepository
import io.prism.config.ToolApprovalMode
import io.prism.data.MyObjectBox
import io.prism.data.ProviderConfigRepository
import io.prism.security.ApiKeyRepository
import io.prism.security.FakePreferenceDataStore
import io.prism.security.RecordingCryptoService
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
import java.io.File

/**
 * SettingsViewModel 工具审批模式补充测试（ac-verifier，TKN-UXR3-ACCEPTANCE-001）。
 *
 * 覆盖 guardrail N-6（LOW）指出的缺口：SettingsViewModel.toolApprovalMode 无直接单测。
 * 验证：
 * - 仓库为 null 时降级为默认 MANUAL（向后兼容）
 * - 未持久化时默认 MANUAL
 * - setToolApprovalMode 持久化到 DataStore，mode() 反映新值
 * - 三种模式均可设置并读回
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelToolApprovalTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "settings-approval-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        boxStore.close()
        tempDir.deleteRecursively()
    }

    private fun createViewModel(approvalRepo: ToolApprovalConfigRepository?): SettingsViewModel {
        val providerRepo = ProviderConfigRepository(boxStore)
        val apiKeyRepo = ApiKeyRepository(FakePreferenceDataStore(), RecordingCryptoService())
        return SettingsViewModel(
            providerRepository = providerRepo,
            apiKeyRepository = apiKeyRepo,
            thinkingConfigRepository = null,
            toolApprovalConfigRepository = approvalRepo
        )
    }

    @Test
    fun `toolApprovalMode defaults to MANUAL when repository is null`() = runTest(mainDispatcher) {
        val vm = createViewModel(approvalRepo = null)
        val job = launch { vm.toolApprovalMode.collect { } }
        assertEquals("仓库为 null 应降级为默认 MANUAL", ToolApprovalMode.MANUAL, vm.toolApprovalMode.value)
        job.cancel()
    }

    @Test
    fun `toolApprovalMode defaults to MANUAL when nothing persisted`() = runTest(mainDispatcher) {
        val repo = ToolApprovalConfigRepository(FakePreferenceDataStore())
        val vm = createViewModel(approvalRepo = repo)
        val job = launch { vm.toolApprovalMode.collect { } }
        assertEquals("未持久化时应为默认 MANUAL", ToolApprovalMode.MANUAL, vm.toolApprovalMode.value)
        job.cancel()
    }

    @Test
    fun `setToolApprovalMode persists and flow reflects new value`() = runTest(mainDispatcher) {
        val dataStore = FakePreferenceDataStore()
        val repo = ToolApprovalConfigRepository(dataStore)
        val vm = createViewModel(approvalRepo = repo)
        val job = launch { vm.toolApprovalMode.collect { } }

        vm.setToolApprovalMode(ToolApprovalMode.DISABLED)
        // WhileSubscribed 共享协程 + Unconfined 调度器，立即传播
        assertEquals("设置后 flow 应反映 DISABLED", ToolApprovalMode.DISABLED, vm.toolApprovalMode.value)
        // 底层 DataStore 已持久化
        assertEquals("DataStore 应已持久化 DISABLED", ToolApprovalMode.DISABLED, repo.getMode())

        vm.setToolApprovalMode(ToolApprovalMode.AUTO)
        assertEquals("设置后 flow 应反映 AUTO", ToolApprovalMode.AUTO, vm.toolApprovalMode.value)
        assertEquals("DataStore 应已持久化 AUTO", ToolApprovalMode.AUTO, repo.getMode())
        job.cancel()
    }

    @Test
    fun `setToolApprovalMode round trips all three modes`() = runTest(mainDispatcher) {
        val dataStore = FakePreferenceDataStore()
        val repo = ToolApprovalConfigRepository(dataStore)
        val vm = createViewModel(approvalRepo = repo)
        val job = launch { vm.toolApprovalMode.collect { } }

        for (mode in ToolApprovalMode.entries) {
            vm.setToolApprovalMode(mode)
            assertEquals("模式 $mode 应生效", mode, vm.toolApprovalMode.value)
            assertEquals("模式 $mode 应持久化", mode, repo.getMode())
        }
        job.cancel()
    }
}
