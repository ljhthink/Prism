package io.prism.ui.capabilities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.prism.PrismApplication
import io.prism.data.McpServerConfig
import io.prism.data.McpServerPresets
import io.prism.data.McpServerRepository
import io.prism.network.McpToolProvider
import io.prism.security.ApiKeyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 能力中枢 ViewModel —— 管理 MCP Server 配置与连接测试。
 *
 * 通过 [Factory] 从 [PrismApplication] 注入 [McpServerRepository] 与 [McpToolProvider]，
 * 将仓库的 [McpServerRepository.servers] 流暴露给 UI，并提供增删改、启用切换、
 * 从预设创建、连接测试能力。
 *
 * 状态：
 * - [servers]：全部 MCP Server 配置列表（订阅仓库）
 * - [selectedServer]：当前编辑的 MCP Server（null 表示未选中）
 * - [testState]：连接测试状态（idle / testing / success / fail）
 *
 * US-008：将 CapabilitiesScreen 的静态 MCP Server 数据替换为动态、可配置的数据。
 */
class CapabilitiesViewModel(
    private val serverRepository: McpServerRepository,
    private val mcpToolProvider: McpToolProvider,
    private val apiKeyRepository: ApiKeyRepository
) : ViewModel() {

    /** 全部 MCP Server 配置列表。 */
    val servers: StateFlow<List<McpServerConfig>> = serverRepository.servers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), serverRepository.servers.value)

    private val _selectedServer = MutableStateFlow<McpServerConfig?>(null)
    /** 当前编辑的 MCP Server（null 表示未选中）。 */
    val selectedServer: StateFlow<McpServerConfig?> = _selectedServer.asStateFlow()

    /** 连接测试状态。 */
    sealed interface TestState {
        data object Idle : TestState
        data object Testing : TestState
        data class Success(val toolCount: Int) : TestState
        data class Fail(val message: String) : TestState
    }

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    /** 连接测试状态（供 UI 展示 loading / 结果）。 */
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    /** 选择要编辑的 MCP Server。 */
    fun selectServer(config: McpServerConfig?) {
        _selectedServer.value = config
    }

    /** 保存 MCP Server 配置（id=0 新建，id>0 更新）。 */
    fun saveServer(config: McpServerConfig) {
        serverRepository.save(config)
        _selectedServer.value = null
    }

    /** 从预设模板创建 MCP Server 配置。 */
    fun createFromPreset(preset: McpServerConfig) {
        serverRepository.createFromPreset(preset)
    }

    /** 新建自定义 MCP Server 草稿并选中（name/baseUrl 留空，apiKeyRef 唯一化）。 */
    fun newCustomServer() {
        _selectedServer.value = McpServerConfig(name = "", baseUrl = "", apiKeyRef = "mcp-${UUID.randomUUID()}")
    }

    /** 切换 MCP Server 启用状态。 */
    fun setEnabled(id: Long, enabled: Boolean) {
        serverRepository.setEnabled(id, enabled)
    }

    /** 保存 API Key（加密后落盘，明文不落盘）。 */
    fun saveApiKey(key: String, value: String) {
        viewModelScope.launch {
            apiKeyRepository.saveApiKey(key, value)
        }
    }

    /** 删除 MCP Server 配置。 */
    fun deleteServer(config: McpServerConfig) {
        serverRepository.remove(config.id)
        if (_selectedServer.value?.id == config.id) _selectedServer.value = null
    }

    /**
     * 测试 MCP Server 连接（调用 listTools 验证）。
     *
     * 成功时记录工具数量，失败时记录错误描述；测试期间 [testState] 为 Testing。
     */
    fun testConnection(config: McpServerConfig) {
        viewModelScope.launch {
            _testState.value = TestState.Testing
            _testState.value = try {
                val tools = mcpToolProvider.listTools(config)
                TestState.Success(tools.size)
            } catch (e: CancellationException) {
                // 结构化并发（CR-01）：协程取消必须重新抛出，不吞掉。
                throw e
            } catch (e: Exception) {
                // CR-05（CWE-209）：不向 UI 暴露异常内部信息（e.message 可能含 URL/路径/头部）。
                // 此处为第二道防线：McpClientManager 已降级返回，理论上不会抛业务异常，
                // 但为纵深防御，失败分支仅展示通用文案，不拼接 e.message。
                TestState.Fail("连接失败，请检查网络或 Server 配置")
            }
        }
    }

    companion object {
        /** 供 [androidx.lifecycle.viewmodel.compose.viewModel] initializer 使用的工厂。 */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PrismApplication
                CapabilitiesViewModel(app.mcpServerRepository, app.mcpToolProviderDispatcher, app.apiKeyRepository)
            }
        }

        /** 预设模板（本地内置 + 远程模板），供 UI 展示「快速添加」。 */
        val presets: List<McpServerConfig> = McpServerPresets.all
    }
}