package io.prism.ui.settings

import io.objectbox.BoxStore
import io.prism.data.MyObjectBox
import io.prism.data.ProviderConfig
import io.prism.data.ProviderConfigRepository
import io.prism.data.ProviderPresets
import io.prism.security.ApiKeyRepository
import io.prism.security.FakePreferenceDataStore
import io.prism.security.RecordingCryptoService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * SettingsViewModel 单元测试（Provider 配置详情页接入，US-004/US-007 数据层 + UI 桥接）。
 *
 * 验证内容：
 * 1. providers 列表随仓库变化（saveProvider / createFromPreset / deleteProvider）
 * 2. activeProvider 反映激活状态（setActive）
 * 3. selectedProvider 选中/清除
 * 4. API Key 读写（saveApiKey / loadApiKey）
 *
 * 使用 [MyObjectBox] 临时目录构建 ObjectBox，[FakePreferenceDataStore] + [RecordingCryptoService]
 * 构建 ApiKeyRepository，无需 Android 环境。
 *
 * 注意：ViewModel 的 [SettingsViewModel.providers]/[SettingsViewModel.activeProvider] 为
 * `stateIn(viewModelScope, WhileSubscribed)`，其传播依赖 Main 调度器。此处将 Main 与
 * runTest 共用同一 [UnconfinedTestDispatcher]（[mainDispatcher]），保证 stateIn 即时传播。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var boxStore: BoxStore
    private lateinit var providerRepository: ProviderConfigRepository
    private lateinit var apiKeyRepository: ApiKeyRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "settings-vm-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        providerRepository = ProviderConfigRepository(boxStore)
        apiKeyRepository = ApiKeyRepository(FakePreferenceDataStore(), RecordingCryptoService())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        boxStore.close()
        tempDir.deleteRecursively()
    }

    private fun createViewModel(): SettingsViewModel =
        SettingsViewModel(providerRepository, apiKeyRepository)

    // ==================== providers 列表 ====================

    @Test
    fun `saveProvider adds provider to list`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.providers.collect { } }

        vm.saveProvider(
            ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai")
        )

        assertEquals(1, vm.providers.value.size)
        assertEquals("OpenAI", vm.providers.value[0].name)
        job.cancel()
    }

    @Test
    fun `saveProvider updates existing provider`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.providers.collect { } }

        val config = ProviderConfig(name = "Test", baseUrl = "https://old.api", apiKeyRef = "test")
        vm.saveProvider(config)
        val id = vm.providers.value.single().id

        vm.saveProvider(
            config.copy(id = id, baseUrl = "https://new.api")
        )

        assertEquals(1, vm.providers.value.size)
        assertEquals("https://new.api", vm.providers.value.single().baseUrl)
        job.cancel()
    }

    @Test
    fun `createFromPreset adds provider`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.providers.collect { } }

        assertTrue(vm.providers.value.isEmpty())
        vm.createFromPreset(ProviderPresets.openai)

        assertEquals(1, vm.providers.value.size)
        assertEquals("OpenAI", vm.providers.value[0].name)
        job.cancel()
    }

    @Test
    fun `newCustomProvider selects empty draft with unique apiKeyRef`() = runTest(mainDispatcher) {
        val vm = createViewModel()

        vm.newCustomProvider()

        val draft = vm.selectedProvider.value
        assertNotNull("应选中一个草稿配置", draft)
        assertEquals("新建草稿应为 id=0", 0L, draft?.id)
        assertTrue("apiKeyRef 应以 custom- 前缀并唯一化", draft?.apiKeyRef.orEmpty().startsWith("custom-"))
    }

    @Test
    fun `save draft then activate uses returned id`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.providers.collect { } }
        val activeJob = launch { vm.activeProvider.collect { } }

        vm.newCustomProvider()
        val draft = vm.selectedProvider.value!!

        val savedId = vm.saveProvider(
            draft.copy(name = "MyLocal", baseUrl = "http://localhost:8090", models = listOf("mixtral"))
        )
        vm.setActive(savedId)

        assertEquals("MyLocal", vm.providers.value.single().name)
        assertEquals(savedId, vm.activeProvider.value?.id)
        job.cancel()
        activeJob.cancel()
    }

    @Test
    fun `save draft without activate persists but does not activate`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.providers.collect { } }
        val activeJob = launch { vm.activeProvider.collect { } }

        vm.newCustomProvider()
        val draft = vm.selectedProvider.value!!

        val savedId = vm.saveProvider(
            draft.copy(name = "InactiveLocal", baseUrl = "http://localhost:9999", models = listOf("llama3"))
        )
        // 模拟 UI：enabled=false 时不调用 setActive

        assertEquals("InactiveLocal", vm.providers.value.single().name)
        assertEquals(savedId, vm.providers.value.single().id)
        assertNull("未激活路径下 activeProvider 应为 null", vm.activeProvider.value)
        job.cancel()
        activeJob.cancel()
    }

    @Test
    fun `deleteProvider removes provider and clears selection`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.providers.collect { } }

        vm.saveProvider(
            ProviderConfig(name = "Delete", baseUrl = "https://del.api", apiKeyRef = "del")
        )

        vm.selectProvider(vm.providers.value.single())
        vm.deleteProvider(vm.providers.value.single())

        assertTrue(vm.providers.value.isEmpty())
        assertNull("删除选中项后应清除 selectedProvider", vm.selectedProvider.value)
        job.cancel()
    }

    // ==================== activeProvider / 激活 ====================

    @Test
    fun `setActive updates activeProvider`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.providers.collect { } }
        val activeJob = launch { vm.activeProvider.collect { } }

        vm.saveProvider(
            ProviderConfig(name = "Active", baseUrl = "https://act.api", apiKeyRef = "act")
        )
        val id = vm.providers.value.single().id

        assertNull(vm.activeProvider.value)
        vm.setActive(id)

        assertNotNull(vm.activeProvider.value)
        assertEquals("Active", vm.activeProvider.value?.name)
        job.cancel()
        activeJob.cancel()
    }

    // ==================== selectedProvider ====================

    @Test
    fun `selectProvider sets and clears selection`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val config = ProviderConfig(name = "Sel", baseUrl = "https://sel.api", apiKeyRef = "sel")

        vm.selectProvider(config)
        assertEquals(config, vm.selectedProvider.value)

        vm.selectProvider(null)
        assertNull(vm.selectedProvider.value)
    }

    // ==================== API Key 读写 ====================

    @Test
    fun `saveApiKey stores encrypted key`() = runTest(mainDispatcher) {
        val vm = createViewModel()

        vm.saveApiKey("openai", "sk-secret-value")

        // ApiKeyRepository 直接读取应返回明文（加密往返一致）
        val retrieved = apiKeyRepository.readApiKey("openai").first()
        assertEquals("sk-secret-value", retrieved)
    }

    @Test
    fun `loadApiKey returns plaintext for existing key`() = runTest(mainDispatcher) {
        apiKeyRepository.saveApiKey("anthropic", "sk-ant-key")
        val vm = createViewModel()

        var loaded: String? = null
        vm.loadApiKey("anthropic") { loaded = it }

        assertEquals("sk-ant-key", loaded)
    }

    @Test
    fun `loadApiKey returns null for missing key`() = runTest(mainDispatcher) {
        val vm = createViewModel()

        var loaded: String? = "sentinel"
        vm.loadApiKey("never-saved") { loaded = it }

        assertNull("不存在的 API Key 应返回 null", loaded)
    }
}