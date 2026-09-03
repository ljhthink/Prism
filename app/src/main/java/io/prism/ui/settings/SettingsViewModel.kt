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
    private val userRulesRepository: io.prism.config.UserRulesRepository? = null,
    /** v1 US-301（方案 B 识图）：视觉旁路配置仓库（授权 + 自动开关 + 熔断）；null 时降级默认（向后兼容） */
    private val visionBypassConfigRepository: io.prism.config.VisionBypassConfigRepository? = null,
    /** v1 US-201（LLM 操控手机）：无障碍服务连接状态提供者；null 时降级为恒 false（向后兼容，保持 JVM 可测） */
    private val phoneControlStatusProvider: (() -> Boolean)? = null,
    /** v1 真机二次修复（Issue 4b）：手机操控高危动作（发送/删除/拨号）确认策略仓库 */
    private val highRiskApprovalRepository: io.prism.config.HighRiskApprovalRepository? = null,
    /** v1 批次15（US-1507）：搜索增强配置仓库（SearXNG 端点 + WebView 抓取开关）；null 时降级默认（向后兼容） */
    private val searchEnhancementRepository: io.prism.config.SearchEnhancementConfigRepository? = null
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

    /**
     * v1 US-301：视觉旁路授权（用户明示：图片可外发到视觉 Provider）。默认 false。
     * 隐私刚性要求（D-6）：未授权不触发云端旁路（仅 OCR 兜底可用）。
     */
    val visionConsent: StateFlow<Boolean> =
        (visionBypassConfigRepository?.consent() ?: flowOf(false))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** v1 US-301：视觉旁路自动开关（默认开启，需授权后生效）。 */
    val visionAutoBypass: StateFlow<Boolean> =
        (visionBypassConfigRepository?.autoBypass()
            ?: flowOf(io.prism.config.VisionBypassConfigRepository.DEFAULT_AUTO_BYPASS))
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                io.prism.config.VisionBypassConfigRepository.DEFAULT_AUTO_BYPASS
            )

    /** 设置视觉旁路授权（用户明示同意图片外发到视觉 Provider）。 */
    fun setVisionConsent(given: Boolean) {
        viewModelScope.launch {
            try {
                visionBypassConfigRepository?.setConsent(given)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "设置视觉旁路授权失败: $given", e)
            }
        }
    }

    /** 设置视觉旁路自动开关。 */
    fun setVisionAutoBypass(enabled: Boolean) {
        viewModelScope.launch {
            try {
                visionBypassConfigRepository?.setAutoBypassEnabled(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "设置视觉旁路开关失败: $enabled", e)
            }
        }
    }

    // ==================== v1 US-201：手机操控（无障碍服务） ====================

    private val _phoneControlConnected = MutableStateFlow(phoneControlStatusProvider?.invoke() == true)

    /** 手机操控无障碍服务是否已连接（设置页引导状态；由 [refreshPhoneControlStatus] 刷新）。 */
    val phoneControlConnected: StateFlow<Boolean> = _phoneControlConnected.asStateFlow()

    /**
     * 刷新无障碍服务连接状态。
     *
     * **设计**：不内置无限轮询循环（避免 runTest/StandardTestDispatcher 下调度器永不空闲导致
     * 单元测试挂起）。由设置页 Composable 用 LaunchedEffect 定时调用（UI 侧轮询，VM 侧无循环）。
     * 状态变化时打日志（v1 US-201 可观测性）。
     */
    fun refreshPhoneControlStatus() {
        val connected = phoneControlStatusProvider?.invoke() == true
        if (_phoneControlConnected.value != connected) {
            Log.i("SettingsViewModel", "手机操控无障碍服务连接状态变更: $connected")
            _phoneControlConnected.value = connected
        }
    }

    /** 手机操控高危动作（发送/删除/拨号）确认策略（v1 真机二次修复 Issue 4b）。 */
    val highRiskApprovalMode: StateFlow<io.prism.config.HighRiskApprovalMode> =
        (highRiskApprovalRepository?.mode()
            ?: flowOf(io.prism.config.HighRiskApprovalMode.ASK))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), io.prism.config.HighRiskApprovalMode.ASK)

    /** 设置手机操控高危动作确认策略（v1 真机二次修复 Issue 4b）。 */
    fun setHighRiskApprovalMode(mode: io.prism.config.HighRiskApprovalMode) {
        viewModelScope.launch {
            try {
                highRiskApprovalRepository?.setMode(mode)
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "设置高危动作策略失败：${e::class.simpleName}")
            }
        }
    }

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

    // ==================== v1 批次15：搜索增强（US-1501/1502/1507） ====================

    /** 智谱 API Key（apiKeyRef=zhipu）是否已配置（用于设置页副标题「已配置/未配置」）。 */
    val zhipuKeyConfigured: StateFlow<Boolean> =
        apiKeyRepository.readApiKey(ZHIPU_API_KEY_REF)
            .map { !it.isNullOrBlank() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Tavily API Key（apiKeyRef=tavily）是否已配置。 */
    val tavilyKeyConfigured: StateFlow<Boolean> =
        apiKeyRepository.readApiKey(TAVILY_API_KEY_REF)
            .map { !it.isNullOrBlank() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * SearXNG 自建端点配置（US-1507）。null = 未配置（引擎链完全跳过，零行为变化）。
     * DataStore 首次读取完成前显示 null（占位），随后自动推送持久化值。
     */
    val searxngSettings: StateFlow<io.prism.config.SearchEnhancementConfigRepository.SearxngSettings?> =
        (searchEnhancementRepository?.searxngSettings() ?: flowOf(null))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * WebView 渲染抓取开关（v1 批次15 US-1506 存储侧：默认关闭；由抓取侧 Agent 消费，
     * 本 VM 只负责设置页读写）。DataStore 首次读取完成前显示默认值（占位）。
     */
    val webviewFetchEnabled: StateFlow<Boolean> =
        (searchEnhancementRepository?.webviewFetchEnabled()
            ?: flowOf(io.prism.config.SearchEnhancementConfigRepository.DEFAULT_WEBVIEW_FETCH_ENABLED))
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                io.prism.config.SearchEnhancementConfigRepository.DEFAULT_WEBVIEW_FETCH_ENABLED
            )

    /**
     * 保存 SearXNG 配置（持久化到 DataStore，运行时即时生效）。
     * endpoint 为空白时视为清除配置（引擎链回到零配置行为）。
     */
    fun saveSearxngSettings(endpoint: String, username: String, password: String) {
        viewModelScope.launch {
            try {
                searchEnhancementRepository?.setSearxngSettings(endpoint, username, password)
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "保存 SearXNG 配置失败: ${e.message}", e)
            }
        }
    }

    /**
     * 首选搜索引擎（v1 批次15.1 US-1509）：结构化引擎链中首个尝试的引擎。
     * 空串 = 跟随默认顺序（Bocha → 智谱 → SearXNG → Tavily）。
     */
    val preferredEngine: StateFlow<String> =
        (searchEnhancementRepository?.preferredEngine() ?: flowOf(""))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** 保存首选引擎（空串 = 恢复默认顺序）。 */
    fun setPreferredEngine(engine: String) {
        viewModelScope.launch {
            try {
                searchEnhancementRepository?.setPreferredEngine(engine)
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "保存首选搜索引擎失败: ${e.message}", e)
            }
        }
    }

    /** 设置 WebView 渲染抓取开关（持久化到 DataStore）。 */
    fun setWebviewFetchEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                searchEnhancementRepository?.setWebviewFetchEnabled(enabled)
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "设置 WebView 抓取开关失败: $enabled", e)
            }
        }
    }

    /** 选择要编辑的 Provider。 */
    fun selectProvider(config: ProviderConfig?) {
        _selectedProvider.value = config
    }

    /** 保存 Provider 配置（id=0 新建，id>0 更新）。@return 保存后的 id（新建时用于后续激活） */
    fun saveProvider(config: ProviderConfig): Long {
        // v1 批次13（B，D16b）：按模型名自动启用视觉能力（开箱即用）——用户配了 glm-4.6v-flash
        // 等视觉模型时无需知道要去手动开关 supportsVision，截图即以图片注入会话（发挥多模态）。
        // **仅当用户未显式设置过**（supportsVisionSet=false）才自动提示并落标记；用户已显式触碰
        //（含显式关闭，隐私语义）则尊重其选择，绝不覆盖（防截图内容静默外发）。
        // 误判（模型名带视觉字样但端点不支持图片）由 400 降级链（SkillExecutor 剥离图片 +
        // 截图转 OCR/UI 树）自愈。
        val firstModel = config.models.firstOrNull()
        if (!config.supportsVisionSet && firstModel != null && ProviderConfig.detectVisionSupport(firstModel)) {
            config.supportsVision = true
            config.supportsVisionSet = true
        }
        val id = providerRepository.save(config)
        // v1 真机二次修复（Issue 3）：把某 Provider 标记为「视觉旁路 Provider」本身即用户"把图片
        // 外发到该端点"的明确意图 → 同步写入云端旁路授权 consent，避免用户"激活了视觉模型却
        // 仍只见 OCR"（原误解：isBypassAvailable 因 consent 默认 false 恒不过，Cloud 从不执行）。
        if (config.isVisionFallback) {
            viewModelScope.launch {
                visionBypassConfigRepository?.setConsent(true)
                visionBypassConfigRepository?.setAutoBypassEnabled(true)
                // v1 真机二次修复（Issue 3b）：重新激活视觉 Provider 即清零云端旁路熔断计数。
                // 旁路经「连续失败 [MAX_FAILURES] 次自动停用」熔断后，配置修好前 cloud 永不触发、
                // 只剩 OCR。激活/保存动作代表用户"期望云端旁路可用"的信号，应重置熔断让 cloud 重试。
                visionBypassConfigRepository?.resetFailures()
            }
        }
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
                    userRulesRepository = app.userRulesRepository,
                    // v1 US-301（方案 B 识图）：视觉旁路配置仓库（授权 + 自动开关 + 熔断）
                    visionBypassConfigRepository = app.visionBypassConfigRepository,
                    // v1 US-201（LLM 操控手机）：无障碍服务连接状态提供者（v1 真机二次修复 Issue 4：
                    // 用系统真实启用状态判定，避免微信等重内存 App 打开时进程实例空窗误报"未启用"）
                    phoneControlStatusProvider = {
                        io.prism.phonecontrol.PhoneControlAccessibilityService.isEnabledInSystem(app)
                    },
                    // v1 真机二次修复（Issue 4b）：高危动作确认策略仓库
                    highRiskApprovalRepository = app.highRiskApprovalRepository,
                    // v1 批次15（US-1507）：搜索增强配置仓库（SearXNG 端点 + WebView 抓取开关）
                    searchEnhancementRepository = app.searchEnhancementConfigRepository
                )
            }
        }

        /** v1 批次15（US-1501）：智谱 API Key 引用（ApiKeyRepository 加密键）。 */
        const val ZHIPU_API_KEY_REF = "zhipu"

        /** v1 批次15（US-1502）：Tavily API Key 引用（ApiKeyRepository 加密键）。 */
        const val TAVILY_API_KEY_REF = "tavily"
    }
}