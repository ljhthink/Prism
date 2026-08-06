package io.prism.ui.capabilities

import io.objectbox.BoxStore
import io.prism.data.McpServerConfig
import io.prism.data.McpServerPresets
import io.prism.data.McpServerRepository
import io.prism.data.MyObjectBox
import io.prism.network.McpToolProvider
import io.prism.security.ApiKeyRepository
import io.prism.security.FakePreferenceDataStore
import io.prism.security.RecordingCryptoService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
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
 * CapabilitiesViewModel 单元测试（US-008 UI 层桥接，GAP-002 覆盖）。
 *
 * 验证内容：
 * 1. servers 列表随仓库变化（saveServer / createFromPreset / deleteServer / setEnabled）
 * 2. selectedServer 选中/清除/新建草稿
 * 3. testConnection 连接测试状态（成功 / 失败降级不含 e.message）
 * 4. saveApiKey 加密存储
 *
 * 使用 [MyObjectBox] 临时目录构建 ObjectBox，[FakePreferenceDataStore] + [RecordingCryptoService]
 * 构建 ApiKeyRepository，[FakeMcpToolProvider] 注入连接层，无需 Android 环境。
 *
 * 注意：ViewModel 的 [CapabilitiesViewModel.servers] 为 `stateIn(viewModelScope, WhileSubscribed)`，
 * 其传播依赖 Main 调度器。此处将 Main 与 runTest 共用同一 [UnconfinedTestDispatcher]（[mainDispatcher]），
 * 保证 stateIn 即时传播。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilitiesViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var boxStore: BoxStore
    private lateinit var serverRepository: McpServerRepository
    private lateinit var apiKeyRepository: ApiKeyRepository
    private lateinit var toolProvider: FakeMcpToolProvider
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "cap-vm-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        serverRepository = McpServerRepository(boxStore)
        apiKeyRepository = ApiKeyRepository(FakePreferenceDataStore(), RecordingCryptoService())
        toolProvider = FakeMcpToolProvider()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        boxStore.close()
        tempDir.deleteRecursively()
    }

    private fun createViewModel(): CapabilitiesViewModel =
        CapabilitiesViewModel(serverRepository, toolProvider, apiKeyRepository)

    // ==================== servers 列表 ====================

    @Test
    fun `saveServer adds server to list`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.servers.collect { } }

        vm.saveServer(
            McpServerConfig(name = "Context7", baseUrl = "https://mcp.context7.com/mcp", apiKeyRef = "context7")
        )

        assertEquals(1, vm.servers.value.size)
        assertEquals("Context7", vm.servers.value[0].name)
        job.cancel()
    }

    @Test
    fun `createFromPreset adds server`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.servers.collect { } }

        assertTrue(vm.servers.value.isEmpty())
        vm.createFromPreset(McpServerPresets.remotePresets.first())

        assertEquals(1, vm.servers.value.size)
        assertEquals(McpServerPresets.remotePresets.first().name, vm.servers.value[0].name)
        job.cancel()
    }

    @Test
    fun `setEnabled toggles server enabled flag`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.servers.collect { } }

        vm.saveServer(McpServerConfig(name = "GitHub", baseUrl = "https://api.githubcopilot.com/mcp", apiKeyRef = "github"))
        val id = vm.servers.value.single().id

        assertTrue("默认停用", !vm.servers.value.single().isEnabled)
        vm.setEnabled(id, true)
        assertTrue("启用后应为 true", vm.servers.value.single().isEnabled)
        job.cancel()
    }

    @Test
    fun `deleteServer removes server and clears selection`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.servers.collect { } }

        vm.saveServer(McpServerConfig(name = "Del", baseUrl = "https://del.mcp"))
        vm.selectServer(vm.servers.value.single())
        vm.deleteServer(vm.servers.value.single())

        assertTrue(vm.servers.value.isEmpty())
        assertNull("删除选中项后应清除 selectedServer", vm.selectedServer.value)
        job.cancel()
    }

    // ==================== selectedServer / 新建草稿 ====================

    @Test
    fun `selectServer sets and clears selection`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val config = McpServerConfig(name = "Sel", baseUrl = "https://sel.mcp")

        vm.selectServer(config)
        assertEquals(config, vm.selectedServer.value)

        vm.selectServer(null)
        assertNull(vm.selectedServer.value)
    }

    @Test
    fun `newCustomServer selects empty draft with unique apiKeyRef`() = runTest(mainDispatcher) {
        val vm = createViewModel()

        vm.newCustomServer()

        val draft = vm.selectedServer.value
        assertNotNull("应选中一个草稿配置", draft)
        assertEquals("新建草稿应为 id=0", 0L, draft?.id)
        assertTrue("apiKeyRef 应以 mcp- 前缀并唯一化", draft?.apiKeyRef.orEmpty().startsWith("mcp-"))
    }

    // ==================== testConnection 连接测试 ====================

    @Test
    fun `testConnection success records tool count`() = runTest(mainDispatcher) {
        toolProvider.tools = listOf("read_file", "write_file")
        val vm = createViewModel()
        val config = McpServerConfig(name = "FS", baseUrl = "https://fs.mcp")

        vm.testConnection(config)

        val state = vm.testState.value
        assertTrue("成功态应为 Success", state is CapabilitiesViewModel.TestState.Success)
        assertEquals(2, (state as CapabilitiesViewModel.TestState.Success).toolCount)
    }

    @Test
    fun `testConnection failure degrades to generic message without e message`() = runTest(mainDispatcher) {
        // McpClientManager 已降级返回空列表，listTools 不抛业务异常；
        // 此处用 fake 抛异常验证 ViewModel 第二道防线：降级为通用文案且不泄露 e.message（CR-05）
        toolProvider.failure = RuntimeException("connect to https://internal:8080 failed: secret-path")
        val vm = createViewModel()
        val config = McpServerConfig(name = "Bad", baseUrl = "https://bad.mcp")

        vm.testConnection(config)

        val state = vm.testState.value
        assertTrue("失败态应为 Fail", state is CapabilitiesViewModel.TestState.Fail)
        val message = (state as CapabilitiesViewModel.TestState.Fail).message
        assertTrue("失败文案应为通用信息", message.contains("连接失败"))
        assertTrue("不得泄露异常内部信息（e.message）", !message.contains("secret-path"))
        assertTrue("不得泄露异常内部信息（URL/路径）", !message.contains("internal:8080"))
    }

    // ==================== saveApiKey ====================

    @Test
    fun `saveApiKey stores encrypted key`() = runTest(mainDispatcher) {
        val vm = createViewModel()

        vm.saveApiKey("context7", "sk-secret-value")

        val retrieved = apiKeyRepository.readApiKey("context7").first()
        assertEquals("sk-secret-value", retrieved)
    }

    // ==================== US-010 远程预设加载 ====================

    @Test
    fun `remote presets contains 9 templates`() = runTest(mainDispatcher) {
        val names = McpServerPresets.remotePresets.map { it.name }.toSet()
        assertEquals(9, names.size)
        assertEquals(
            setOf("GitHub", "Notion", "Slack", "Sentry", "Stripe", "Asana", "Brave", "Exa", "Context7"),
            names
        )
    }

    @Test
    fun `startPresetEdit selects preset draft with id zero`() = runTest(mainDispatcher) {
        val vm = createViewModel()
        val preset = McpServerPresets.all.first { it.name == "Context7" }

        vm.startPresetEdit(preset)

        val draft = requireNotNull(vm.selectedServer.value)
        assertEquals("新建草稿应为 id=0", 0L, draft.id)
        assertEquals(preset.name, draft.name)
        assertEquals(preset.serverType, draft.serverType)
        assertEquals(preset.baseUrl, draft.baseUrl)
        assertEquals(preset.apiKeyRef, draft.apiKeyRef)
        assertEquals(preset.headers, draft.headers)
    }

    @Test
    fun `observeConnectionStatus emits connected with tool count`() = runTest(mainDispatcher) {
        toolProvider.tools = listOf("read_file", "write_file")
        val config = McpServerConfig(name = "FS", baseUrl = "https://fs.mcp")

        val statuses = CapabilitiesViewModel.observeConnectionStatus(config, toolProvider).toList()

        assertEquals(
            listOf(
                CapabilitiesViewModel.ConnectionStatus.Connecting,
                CapabilitiesViewModel.ConnectionStatus.Connected(2)
            ),
            statuses
        )
    }

    @Test
    fun `observeConnectionStatus emits error when listTools returns empty`() = runTest(mainDispatcher) {
        toolProvider.tools = emptyList()
        val config = McpServerConfig(name = "FS", baseUrl = "https://fs.mcp")

        val statuses = CapabilitiesViewModel.observeConnectionStatus(config, toolProvider).toList()

        assertEquals(2, statuses.size)
        assertEquals(CapabilitiesViewModel.ConnectionStatus.Connecting, statuses[0])
        assertTrue("空工具应判定为连接失败", statuses[1] is CapabilitiesViewModel.ConnectionStatus.Error)
    }

    /** 可注入的假 [McpToolProvider]，用于测试连接成功 / 失败路径。 */
    private class FakeMcpToolProvider : McpToolProvider {
        var tools: List<String> = emptyList()
        var failure: Exception? = null

        override suspend fun listTools(config: McpServerConfig): List<String> {
            failure?.let { throw it }
            return tools
        }

        override suspend fun callTool(config: McpServerConfig, name: String, arguments: Map<String, Any?>): String =
            "mock-result"
    }
}