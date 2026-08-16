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

    // ==================== removeApiKey（BR-security-006 / DEF-001 B-2 修复） ====================

    /**
     * 验证 removeApiKey 委托 apiKeyRepository.removeApiKey 删除已存密钥。
     *
     * 场景：用户在 ApiKeySheet 中清空输入框后点击保存，应清除已存密钥。
     * BR-security-006 子语义 (3)：UI 层清空 → removeApiKey 删除。
     */
    @Test
    fun `removeApiKey deletes existing key via repository`() = runTest(mainDispatcher) {
        // 先保存一个密钥
        apiKeyRepository.saveApiKey("openai", "sk-will-be-removed")
        assertEquals("sk-will-be-removed", apiKeyRepository.readApiKey("openai").first())

        val vm = createViewModel()
        // 模拟 ApiKeySheet 清空后保存：调用 removeApiKey
        vm.removeApiKey("openai")

        assertNull("removeApiKey 后密钥应被删除", apiKeyRepository.readApiKey("openai").first())
    }

    /**
     * 验证 removeApiKey 对不存在的 key 幂等（不抛异常）。
     *
     * 这是 DataStore 的 remove 操作的固有保证，但需验证 ViewModel 层委托不破坏此保证。
     */
    @Test
    fun `removeApiKey for nonexistent key is idempotent`() = runTest(mainDispatcher) {
        val vm = createViewModel()

        // 调用 removeApiKey 删除从未存在的 key，不应抛异常
        vm.removeApiKey("never-existed")

        assertNull(apiKeyRepository.readApiKey("never-existed").first())
    }

    /**
     * 验证 saveApiKey 空值跳过 + removeApiKey 显式删除的语义差异（BR-security-006 核心）。
     *
     * - saveApiKey(key, "") → 空值跳过，已存密钥保留（ProviderEditSheet 场景）
     * - removeApiKey(key) → 显式删除已存密钥（ApiKeySheet 场景）
     */
    @Test
    fun `saveApiKey empty skips while removeApiKey deletes`() = runTest(mainDispatcher) {
        apiKeyRepository.saveApiKey("openai", "sk-original")
        val vm = createViewModel()

        // saveApiKey 空值：不覆盖已有密钥
        vm.saveApiKey("openai", "")
        assertEquals("saveApiKey 空值不应覆盖已有密钥", "sk-original", apiKeyRepository.readApiKey("openai").first())

        // removeApiKey：显式删除
        vm.removeApiKey("openai")
        assertNull("removeApiKey 后已存密钥应被删除", apiKeyRepository.readApiKey("openai").first())
    }

    // ==================== 问题 8a：深度思考配置（ADR-020） ====================

    @Test
    fun `thinkingEnabled defaults to false without repository`() = runTest(mainDispatcher) {
        // 未注入 ThinkingConfigRepository 时降级为默认关闭（向后兼容）
        val vm = createViewModel()
        assertTrue("默认关闭深度思考", !vm.thinkingEnabled.value)
        assertEquals("默认思考强度 high", "high", vm.reasoningEffort.value)
    }

    @Test
    fun `setThinkingEnabled persists and updates flow`() = runTest(mainDispatcher) {
        val thinkingRepo = io.prism.config.ThinkingConfigRepository(
            FakePreferenceDataStore(androidx.datastore.preferences.core.emptyPreferences())
        )
        val vm = SettingsViewModel(providerRepository, apiKeyRepository, thinkingRepo)
        val job = launch { vm.thinkingEnabled.collect { } }

        assertTrue("初始默认关闭", !vm.thinkingEnabled.value)
        vm.setThinkingEnabled(true)
        assertTrue("开启后 thinkingEnabled 应为 true", vm.thinkingEnabled.value)
        assertEquals("持久化值应可读", true, thinkingRepo.getThinkingEnabled())
        job.cancel()
    }

    @Test
    fun `setReasoningEffort persists valid value`() = runTest(mainDispatcher) {
        val thinkingRepo = io.prism.config.ThinkingConfigRepository(
            FakePreferenceDataStore(androidx.datastore.preferences.core.emptyPreferences())
        )
        val vm = SettingsViewModel(providerRepository, apiKeyRepository, thinkingRepo)
        val job = launch { vm.reasoningEffort.collect { } }

        vm.setReasoningEffort("max")
        assertEquals("max", vm.reasoningEffort.value)
        assertEquals("持久化值应可读", "max", thinkingRepo.getReasoningEffort())
        job.cancel()
    }
}