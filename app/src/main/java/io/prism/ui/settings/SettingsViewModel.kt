package io.prism.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.prism.PrismApplication
import io.prism.config.ThinkingConfigRepository
import io.prism.config.ToolApprovalConfigRepository
import io.prism.config.ToolApprovalMode
import io.prism.data.ProviderConfig
import io.prism.data.ProviderConfigRepository
import io.prism.security.ApiKeyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 设置界面 ViewModel —— 管理 Provider 配置、API Key 与深度思考偏好。
 *
 * 通过 [APPLICATION_KEY] 从 [PrismApplication] 注入 [ProviderConfigRepository] 与
 * [ApiKeyRepository]，避免在 Composable 中直接 cast（CLAUDE.code-archaeologist 建议）。
 *
 * 状态：
 * - [providers]：Provider 列表（订阅仓库 [ProviderConfigRepository.providers]）
 * - [activeProvider]：当前激活 Provider（订阅 [ProviderConfigRepository.activeProviderFlow]）
 * - [thinkingEnabled]：深度思考开关（问题 8a，ADR-020，订阅 [ThinkingConfigRepository]）
 * - [reasoningEffort]：思考强度（问题 8a，low/high/max）
 *
 * 操作：保存 / 删除 / 激活 / 从预设创建 / 读写 API Key / 设置深度思考偏好。
 */
class SettingsViewModel(
    private val providerRepository: ProviderConfigRepository,
    private val apiKeyRepository: ApiKeyRepository,
    /** 问题 8a（ADR-020）：深度思考配置仓库；null 时深度思考相关状态降级为默认值（向后兼容） */
    private val thinkingConfigRepository: ThinkingConfigRepository? = null,
    /** UXR3 问题 10（ADR-023）：工具审批模式配置仓库；null 时审批模式状态降级为默认值（向后兼容） */
    private val toolApprovalConfigRepository: ToolApprovalConfigRepository? = null,
    /** UXR8 N1（ADR-030）：用户规则配置仓库（「关于我」+「如何回答」双字段）；null 时降级为默认空（向后兼容） */
    private val userRulesRepository: io.prism.config.UserRulesRepository? = null
) : ViewModel() {

    /** Provider 已配置列表。 */
    val providers: StateFlow<List<ProviderConfig>> = providerRepository.providers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), providerRepository.providers.value)

    /** 当前激活 Provider。 */
    val activeProvider: StateFlow<ProviderConfig?> = providerRepository.activeProviderFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), providerRepository.activeProviderFlow.value)

    private val _selectedProvider = MutableStateFlow<ProviderConfig?>(null)
    /** 当前编辑的 Provider（null 表示未选中）。 */
    val selectedProvider: StateFlow<ProviderConfig?> = _selectedProvider.asStateFlow()

    private val _apiKeyLoading = MutableStateFlow(false)
    /** API Key 读取中状态。 */
    val apiKeyLoading: StateFlow<Boolean> = _apiKeyLoading.asStateFlow()

    /**
     * 深度思考开关（问题 8a，ADR-020）。
     *
     * 默认关闭（[ThinkingConfigRepository.DEFAULT_ENABLED]），避免向不兼容端点发送
     * thinking 参数。DataStore 首次读取完成前显示默认值（占位），随后自动推送持久化值。
     */
    val thinkingEnabled: StateFlow<Boolean> =
        (thinkingConfigRepository?.thinkingEnabled() ?: flowOf(ThinkingConfigRepository.DEFAULT_ENABLED))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThinkingConfigRepository.DEFAULT_ENABLED)

    /**
     * 思考强度（问题 8a，ADR-020，low / high / max）。
     *
     * 默认 high（[ThinkingConfigRepository.DEFAULT_EFFORT]）。仅深度思考开关开启时生效。
     */
    val reasoningEffort: StateFlow<String> =
        (thinkingConfigRepository?.reasoningEffort() ?: flowOf(ThinkingConfigRepository.DEFAULT_EFFORT))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThinkingConfigRepository.DEFAULT_EFFORT)

    /** 设置深度思考开关（持久化到 DataStore，运行时即时生效）。 */
    fun setThinkingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                thinkingConfigRepository?.setThinkingEnabled(enabled)
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "设置深度思考开关失败: $enabled", e)
            }
        }
    }

    /** 设置思考强度（持久化到 DataStore，运行时即时生效）。 */
    fun setReasoningEffort(effort: String) {
        viewModelScope.launch {
            try {
                thinkingConfigRepository?.setReasoningEffort(effort)
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "设置思考强度失败: $effort", e)
            }
        }
    }

    /**
     * 工具审批模式（UXR3 问题 10，ADR-023）。
     *
     * 默认 MANUAL（手动审批）。DataStore 首次读取完成前显示默认值，随后自动推送持久化值。
     */
    val toolApprovalMode: StateFlow<ToolApprovalMode> =
        (toolApprovalConfigRepository?.mode() ?: flowOf(ToolApprovalMode.DEFAULT))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ToolApprovalMode.DEFAULT)

    /** 设置工具审批模式（持久化到 DataStore，运行时即时生效）。 */
    fun setToolApprovalMode(mode: ToolApprovalMode) {
        viewModelScope.launch {
            try {
                toolApprovalConfigRepository?.setMode(mode)
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "设置工具审批模式失败: $mode", e)
            }
        }
    }

    /**
     * 用户规则（UXR8 N1，ADR-030）——「关于我」。
     *
     * 默认空。DataStore 首次读取完成前显示默认值，随后自动推送持久化值。
     */
    val userRulesAboutMe: StateFlow<String> =
        (userRulesRepository?.rules()?.map { it.aboutMe } ?: flowOf(""))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * 用户规则（UXR8 N1，ADR-030）——「如何回答」。
     *
     * 默认空。DataStore 首次读取完成前显示默认值，随后自动推送持久化值。
     */
    val userRulesHowToRespond: StateFlow<String> =
        (userRulesRepository?.rules()?.map { it.howToRespond } ?: flowOf(""))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * 保存用户规则（持久化到 DataStore，运行时即时生效，下一轮对话生效）。
     *
     * 超长由 [io.prism.config.UserRulesRepository] fail-fast 拒绝（BR-security-005），
     * 此处捕获异常并结构化日志（BR-error-handling-004），UI 层已先截断/提示。
     */
    fun saveUserRules(aboutMe: String, howToRespond: String) {
        viewModelScope.launch {
            try {
                userRulesRepository?.setRules(aboutMe, howToRespond)
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "保存用户规则失败: ${e.message}", e)
            }
        }
    }

    /** 选择要编辑的 Provider。 */
    fun selectProvider(config: ProviderConfig?) {
        _selectedProvider.value = config
    }

    /** 保存 Provider 配置（id=0 新建，id>0 更新）。@return 保存后的 id（新建时用于后续激活） */
    fun saveProvider(config: ProviderConfig): Long {
        val id = providerRepository.save(config)
        _selectedProvider.value = null
        return id
    }

    /** 从预设模板创建 Provider 配置。 */
    fun createFromPreset(preset: ProviderConfig) {
        providerRepository.createFromPreset(preset)
    }

    /**
     * 打开「新建自定义 Provider」编辑弹层。
     *
     * 生成一个 apiKeyRef 已唯一化、其余字段为空的草稿配置并选中，
     * 复用 [ProviderEditSheet] 的编辑界面完成手填创建。
     */
    fun newCustomProvider() {
        _selectedProvider.value = ProviderConfig(
            name = "",
            baseUrl = "",
            apiKeyRef = "custom-${UUID.randomUUID()}",
            models = emptyList()
        )
    }

    /** 删除 Provider 配置。 */
    fun deleteProvider(config: ProviderConfig) {
        providerRepository.remove(config.id)
        if (_selectedProvider.value?.id == config.id) _selectedProvider.value = null
    }

    /** 激活指定 Provider。 */
    fun setActive(id: Long) {
        providerRepository.setActive(id)
    }

    /** 读取指定 key 的 API Key 明文（用于编辑回显，仅在内存短暂存在）。 */
    fun loadApiKey(key: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _apiKeyLoading.value = true
            try {
                onResult(apiKeyRepository.readApiKey(key).first())
            } finally {
                _apiKeyLoading.value = false
            }
        }
    }

    /** 保存 API Key（加密后落盘）。空值跳过（BR-security-006）。 */
    fun saveApiKey(key: String, value: String) {
        viewModelScope.launch {
            apiKeyRepository.saveApiKey(key, value)
        }
    }

    /**
     * 删除指定 key 的 API Key（BR-security-006）。
     *
     * 场景：用户在 ApiKeySheet 中清空输入框后保存，应清除已存密钥而非保留旧值。
     */
    fun removeApiKey(key: String) {
        viewModelScope.launch {
            apiKeyRepository.removeApiKey(key)
        }
    }

    companion object {
        /** 供 [viewModel] initializer 使用的工厂。 */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PrismApplication
                SettingsViewModel(
                    providerRepository = app.providerConfigRepository,
                    apiKeyRepository = app.apiKeyRepository,
                    thinkingConfigRepository = app.thinkingConfigRepository,
                    // UXR3 问题 10（ADR-023）：工具审批模式配置仓库
                    toolApprovalConfigRepository = app.toolApprovalConfigRepository,
                    // UXR8 N1（ADR-030）：用户规则配置仓库（「关于我」+「如何回答」）
                    userRulesRepository = app.userRulesRepository
                )
            }
        }
    }
}