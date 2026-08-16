package io.prism.ui.capabilities

import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
 * US-010：提供连接状态观测（连接中 / 已连接 / 错误）与远程预设「填 Key 后一键添加」。
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

    /**
     * 打开预设模板的编辑弹层（US-010：远程预设「填 Key 后一键添加」）。
     *
     * 本地零配置预设直接 [createFromPreset]；远程预设需用户填 API Key，故将以
     * id=0 的草稿选中，让 [io.prism.ui.capabilities.CapabilitiesScreen] 弹出配置弹层，
     * 用户填入 Key 后经「保存配置」一次性创建并落盘密钥。
     *
     * @param preset 预设模板
     */
    fun startPresetEdit(preset: McpServerConfig) {
        _selectedServer.value = McpServerConfig(
            id = 0L,
            name = preset.name,
            serverType = preset.serverType,
            transport = preset.transport,
            baseUrl = preset.baseUrl,
            apiKeyRef = preset.apiKeyRef,
            headers = preset.headers
        )
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
     *
     * **UX-001 问题 8（ADR-022）**：工具数为 0 视为「连接成功但无可用工具」——
     * 对本地未实现 server（如 Sequential Thinking 修复前）不再误导为成功。
     * 0 工具判定为失败（无工具可用 = 功能不可用），避免「连接成功 · 0 个工具」误导。
     */
    fun testConnection(config: McpServerConfig) {
        viewModelScope.launch {
            _testState.value = TestState.Testing
            _testState.value = try {
                val tools = mcpToolProvider.listTools(config)
                if (tools.isEmpty()) {
                    TestState.Fail("连接成功但无可用工具（该 Server 未实现任何工具）")
                } else {
                    TestState.Success(tools.size)
                }
            } catch (e: CancellationException) {
                // 结构化并发（CR-01）：协程取消必须重新抛出，不吞掉。
                throw e
            } catch (e: Exception) {
                // CR-05（CWE-209）：不向 UI 暴露异常内部信息（e.message 可能含 URL/路径/头部）。
                TestState.Fail("连接失败，请检查网络或 Server 配置")
            }
        }
    }

    /**
     * 观测 MCP Server 连接状态（实例包装，供 UI 订阅）。
     *
     * @param config 目标 MCP Server 配置
     * @return 连接状态流（连接中 / 已连接 / 错误，见 [CapabilitiesViewModel.observeConnectionStatus]）
     */
    fun observeConnectionStatus(config: McpServerConfig): Flow<ConnectionStatus> =
        CapabilitiesViewModel.observeConnectionStatus(config, mcpToolProvider)

    companion object {
        /** 连接探测超时（毫秒），防止网络挂起时「连接中」无限期（guardrail L-03）。 */
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** 供 [androidx.lifecycle.viewmodel.compose.viewModel] initializer 使用的工厂。 */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PrismApplication
                CapabilitiesViewModel(app.mcpServerRepository, app.mcpToolProviderDispatcher, app.apiKeyRepository)
            }
        }

        /** 预设模板（本地内置 + 远程模板），供 UI 展示「快速添加」。 */
        val presets: List<McpServerConfig> = McpServerPresets.all

        /**
         * 观测 MCP Server 连接状态（US-010：连接中 / 已连接 / 错误）。
         *
         * 语义（对齐 [McpToolProvider.listTools] 契约）：连接失败时返回空列表，
         * 故以「工具数非空 → 已连接，空 → 连接失败」判定。发射序列：
         * 1. [ConnectionStatus.Connecting]（开始探测）
         * 2. [ConnectionStatus.Connected] 或 [ConnectionStatus.Error]（listTools 结果）
         *
         * 协程取消时重新抛出（结构化并发，CR-01），不吞掉。
         *
         * @param config 目标 MCP Server 配置
         * @return 连接状态流（在 IO 线程执行探测）
         */
        fun observeConnectionStatus(config: McpServerConfig, mcpToolProvider: McpToolProvider): Flow<ConnectionStatus> =
            flow {
                emit(ConnectionStatus.Connecting)
                // 探测超时保护（guardrail L-03）：共享 httpClient 未配置 HttpTimeout，
                // 网络挂起时协同超时避免「连接中」无限期；超时降级为连接超时，不崩溃收集器。
                // Q-02 修复（guardrail TKN-UXR2-GUARDRAIL-001）：捕获所有非取消异常，
                // 与 [testConnection] 的 catch Exception 语义一致——否则 listTools 抛其他异常
                // （网络错误/Server 初始化失败）会传播取消 Flow，UI 徽章永久卡「连接中」。
                try {
                    val tools = withTimeout(CONNECT_TIMEOUT_MS) { mcpToolProvider.listTools(config) }
                    emit(
                        if (tools.isEmpty()) ConnectionStatus.Error("连接失败")
                        else ConnectionStatus.Connected(tools.size)
                    )
                } catch (e: CancellationException) {
                    throw e // 结构化并发：协程取消必须重抛
                } catch (e: TimeoutCancellationException) {
                    emit(ConnectionStatus.Error("连接超时"))
                } catch (e: Exception) {
                    // BR-error-handling-004：记录日志（不含敏感信息），降级为连接失败
                    Log.w("CapabilitiesViewModel", "observeConnectionStatus failed: ${e::class.simpleName}")
                    emit(ConnectionStatus.Error("连接失败"))
                }
            }.flowOn(Dispatchers.IO)
    }

    /** MCP Server 连接状态（US-010：连接中 / 已连接 / 错误）。 */
    sealed interface ConnectionStatus {
        /** 正在探测连接。 */
        data object Connecting : ConnectionStatus

        /** 已连接，[toolCount] 为可用的工具数量。 */
        data class Connected(val toolCount: Int) : ConnectionStatus

        /** 连接失败（网络 / 鉴权 / 配置错误）。 */
        data class Error(val message: String) : ConnectionStatus
    }
}