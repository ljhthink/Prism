package io.prism.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.prism.PrismApplication
import io.prism.data.ProviderConfig
import io.prism.data.ProviderConfigRepository
import io.prism.security.ApiKeyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置界面 ViewModel —— 管理 Provider 配置与 API Key。
 *
 * 通过 [APPLICATION_KEY] 从 [PrismApplication] 注入 [ProviderConfigRepository] 与
 * [ApiKeyRepository]，避免在 Composable 中直接 cast（CLAUDE.code-archaeologist 建议）。
 *
 * 状态：
 * - [providers]：Provider 列表（订阅仓库 [ProviderConfigRepository.providers]）
 * - [activeProvider]：当前激活 Provider（订阅 [ProviderConfigRepository.activeProviderFlow]）
 *
 * 操作：保存 / 删除 / 激活 / 从预设创建 / 读写 API Key。
 */
class SettingsViewModel(
    private val providerRepository: ProviderConfigRepository,
    private val apiKeyRepository: ApiKeyRepository
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
            apiKeyRef = "custom-${System.currentTimeMillis()}",
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

    /** 保存 API Key（加密后落盘）。 */
    fun saveApiKey(key: String, value: String) {
        viewModelScope.launch {
            apiKeyRepository.saveApiKey(key, value)
        }
    }

    companion object {
        /** 供 [viewModel] initializer 使用的工厂。 */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PrismApplication
                SettingsViewModel(app.providerConfigRepository, app.apiKeyRepository)
            }
        }
    }
}