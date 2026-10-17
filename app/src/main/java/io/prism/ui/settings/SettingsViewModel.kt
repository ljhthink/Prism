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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    private val searchEnhancementRepository: io.prism.config.SearchEnhancementConfigRepository? = null,
    /** 批次23-A（ADR-048，A4）：语音引擎手选仓库；null 时引擎选择状态降级为默认端侧/系统（向后兼容） */
    private val voiceEnginePrefs: io.prism.voice.VoiceEnginePrefs? = null,
    /** 批次23-C（ADR-048）：角色包加载器；null 时角色包管理降级为不可用（向后兼容，保持 JVM 可测） */
    private val rolePackLoader: io.prism.voice.rolepack.RolePackLoader? = null,
    /** 批次24（ADR-051）：桌宠门面；null 时桌宠管理降级为不可用（向后兼容，保持 JVM 可测） */
    private val petManager: io.prism.pet.PetManager? = null,
    /** 批次24（ADR-051）：桌宠配置仓库；null 时桌宠配置状态降级为默认值（向后兼容） */
    private val petConfigRepository: io.prism.pet.PetConfigRepository? = null,
    /**
     * LVC-1（ADR-053）：本地音色包仓库（只读定位/校验端侧音色包）；
     * null 时本地音色状态恒为 null（设置页该项按"未安装"呈现，向后兼容且保持 JVM 可测）。
     */
    private val localVoicePackStore: io.prism.voice.local.LocalVoicePackStore? = null,
    /**
     * TKN-LVC-016（ADR-057）：音色包导入器（`.voicepack` 归档 → 应用私有目录）；
     * null 时导入入口降级为不可用（向后兼容且保持 JVM 可测）。
     */
    private val localVoicePackImporter: io.prism.voice.local.LocalVoicePackImporter? = null
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

    // ==================== 批次23-A（ADR-048，A4）：语音引擎（云端双轨） ====================

    /** DashScope API Key（云端 ASR，apiKeyRef=cloud_asr_dashscope）是否已配置。 */
    val dashScopeKeyConfigured: StateFlow<Boolean> =
        apiKeyRepository.readApiKey(io.prism.voice.cloud.AliCloudAsrEngine.KEY_REF)
            .map { !it.isNullOrBlank() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** MiniMax API Key（云端 TTS，apiKeyRef=cloud_tts_minimax）是否已配置。 */
    val minimaxKeyConfigured: StateFlow<Boolean> =
        apiKeyRepository.readApiKey(io.prism.voice.cloud.MiniMaxTtsConfig.KEY_API_KEY)
            .map { !it.isNullOrBlank() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** MiniMax GroupId（apiKeyRef=cloud_tts_minimax_group）是否已配置。 */
    val minimaxGroupConfigured: StateFlow<Boolean> =
        apiKeyRepository.readApiKey(io.prism.voice.cloud.MiniMaxTtsConfig.KEY_GROUP_ID)
            .map { !it.isNullOrBlank() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** ASR 引擎手选（默认端侧；DataStore 首读前显示默认值）。 */
    val voiceAsrEngine: StateFlow<io.prism.voice.AsrEngineChoice> =
        (voiceEnginePrefs?.asrEngine() ?: flowOf(io.prism.voice.AsrEngineChoice.SYSTEM))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), io.prism.voice.AsrEngineChoice.SYSTEM)

    /** TTS 引擎手选（默认系统；DataStore 首读前显示默认值）。 */
    val voiceTtsEngine: StateFlow<io.prism.voice.TtsEngineChoice> =
        (voiceEnginePrefs?.ttsEngine() ?: flowOf(io.prism.voice.TtsEngineChoice.SYSTEM))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), io.prism.voice.TtsEngineChoice.SYSTEM)

    /** 保存 ASR 引擎手选（下次通话装配生效——VoiceEngineAssembly）。 */
    fun setVoiceAsrEngine(choice: io.prism.voice.AsrEngineChoice) {
        viewModelScope.launch {
            try {
                voiceEnginePrefs?.setAsrEngine(choice)
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "保存 ASR 引擎选择失败: $choice", e)
            }
        }
    }

    /** 保存 TTS 引擎手选（下次通话装配生效——VoiceEngineAssembly）。 */
    fun setVoiceTtsEngine(choice: io.prism.voice.TtsEngineChoice) {
        viewModelScope.launch {
            try {
                voiceEnginePrefs?.setTtsEngine(choice)
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "保存 TTS 引擎选择失败: $choice", e)
            }
        }
    }

    // ==================== LVC-1（ADR-053）：本地音色包状态 ====================

    private val _localVoiceStatus = MutableStateFlow<io.prism.voice.local.LocalVoiceStatus?>(null)

    /**
     * 当前**生效**本地音色包的安装状态（生效包 = 用户显式选择，其次角色包 `voice.local.voiceId`，
     * 见 [io.prism.voice.resolveLocalVoiceId]）。
     *
     * `null` = **尚未检测**（设置页首帧/展开前）或本 VM 未注入仓库——UI 必须把 null 与
     * "未安装"区分呈现，避免把"没查过"显示成"没装"。
     * 检测由 [refreshLocalVoiceStatus] 触发（进入/展开「语音引擎」区、切换音色包后调用）。
     */
    val localVoiceStatus: StateFlow<io.prism.voice.local.LocalVoiceStatus?> =
        _localVoiceStatus.asStateFlow()

    private val _effectiveLocalVoiceId = MutableStateFlow<String?>(null)

    /**
     * 当前**实际生效**的本地音色包 id（用户显式选择，其次角色包声明；`null` = 两者都没有）。
     *
     * 与 [localVoiceStatus] 同一次检测写入；列表据此显示"使用中"，
     * 避免用户面对"能听的是哪个包"无从判断（BUG-20260924-001 的观感面）。
     */
    val effectiveLocalVoiceId: StateFlow<String?> = _effectiveLocalVoiceId.asStateFlow()

    /**
     * 重新检测本地音色包状态（进入/展开「语音引擎」区、侧载完成后手动刷新时调用）。
     *
     * 角色包声明了 `voice.local.voiceId` 才检测；未声明 → 置为 `NotInstalled`（该项不可选）。
     * 任何异常都被收敛为状态值，不向 UI 抛。
     */
    fun refreshLocalVoiceStatus() {
        viewModelScope.launch {
            val store = localVoicePackStore
            val voiceId = try {
                // BUG-20260924-002：与两处装配点同源——用户显式选择优先于角色包声明。
                io.prism.voice.resolveLocalVoiceId(
                    userSelected = voiceEnginePrefs?.localVoiceIdOnce(),
                    rolePackDeclared = rolePackLoader?.effectiveRolePack()?.voice?.local?.voiceId
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "读取角色包本地音色声明失败", e)
                null
            }
            if (store == null || voiceId == null) {
                _effectiveLocalVoiceId.value = voiceId
                _localVoiceStatus.value =
                    io.prism.voice.local.LocalVoiceStatus.NotInstalled(voiceId.orEmpty())
                return@launch
            }
            _effectiveLocalVoiceId.value = voiceId
            _localVoiceStatus.value = try {
                store.status(voiceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "本地音色包状态检测失败", e)
                io.prism.voice.local.LocalVoiceStatus.NotInstalled(voiceId)
            }
        }
    }

    // ==================== TKN-LVC-016（ADR-056）：本地音色档位与播报模式 ====================

    /** 本地音色播报档位（默认质量优先；DataStore 首读前显示默认值）。 */
    val localVoiceTier: StateFlow<io.prism.voice.LocalVoiceTier> =
        (voiceEnginePrefs?.localVoiceTier() ?: flowOf(io.prism.voice.LocalVoiceTier.QUALITY))
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                io.prism.voice.LocalVoiceTier.QUALITY
            )

    /**
     * 保存本地音色播报档位。
     *
     * **运行时即时生效**：档位进 [io.prism.voice.local.localCloneCacheKey]，缓存签名一变，
     * 下次播报即重建引擎（无需重启 App，也无"改了没效果"的静默沿用）。
     */
    fun setLocalVoiceTier(tier: io.prism.voice.LocalVoiceTier) {
        viewModelScope.launch {
            try {
                voiceEnginePrefs?.setLocalVoiceTier(tier)
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w(TAG, "保存本地音色档位失败: $tier", e)
            }
        }
    }

    /** 本地音色播报模式（默认连续；DataStore 首读前显示默认值）。 */
    val localPlaybackMode: StateFlow<io.prism.voice.LocalClonePlaybackMode> =
        (voiceEnginePrefs?.localPlaybackMode() ?: flowOf(io.prism.voice.LocalClonePlaybackMode.CONTINUOUS))
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                io.prism.voice.LocalClonePlaybackMode.CONTINUOUS
            )

    /** 保存本地音色播报模式（同档位：进引擎构造期参数，下次播报重建引擎即生效）。 */
    fun setLocalPlaybackMode(mode: io.prism.voice.LocalClonePlaybackMode) {
        viewModelScope.launch {
            try {
                voiceEnginePrefs?.setLocalPlaybackMode(mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "保存本地音色播报模式失败: $mode", e)
            }
        }
    }

    // ==================== BUG-20260924-002（设置页 F5）：本地音色包选用 ====================

    /**
     * 用户显式选择的本地音色包 id（`null` = 未选择 ⇒ 由角色包声明决定，即改造前行为）。
     *
     * 与两处装配点（[io.prism.voice.VoiceEngineAssembly] / `TextPlaybackController`）共用
     * [io.prism.voice.resolveLocalVoiceId] 判定；选择本身进
     * [io.prism.voice.local.localCloneCacheKey] 的 `voiceId` 项 ⇒ 切换后**下次播报即重建引擎**。
     */
    val activeLocalVoiceId: StateFlow<String?> =
        (voiceEnginePrefs?.localVoiceId() ?: flowOf(null))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 选择当前本地音色包；传 `null` = 清除选择（回落角色包声明）。
     *
     * 落盘后**立即重算生效状态**：选择会改变"本地昔涟（离线）"这一项是否可选，
     * 状态不刷新会让用户看到"选完了却还是灰的"。
     * 失败（DataStore 异常）只记日志 + 提示，不抛——与档位/播报模式一致的收敛口径。
     */
    fun selectLocalVoicePack(voiceId: String?) {
        viewModelScope.launch {
            try {
                voiceEnginePrefs?.setLocalVoiceId(voiceId)
                refreshLocalVoiceStatus()
                _voicePackMessage.value =
                    if (voiceId.isNullOrBlank()) "已清除音色包选择，回落角色包声明"
                    else "已选择：$voiceId（下次播报生效）"
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w(TAG, "保存本地音色包选择失败: $voiceId", e)
                _voicePackMessage.value = "选择失败：${e.message ?: e::class.simpleName}"
            }
        }
    }

    // ==================== TKN-LVC-016（ADR-057）：音色包导入 / 列表 / 删除 ====================

    /** 已安装音色包的展示项（[ready] 为 false 时表示目录存在但校验不通过）。 */
    data class InstalledVoicePack(
        val voiceId: String,
        val name: String,
        val ttsWire: String,
        val totalBytes: Long,
        val ready: Boolean,
        val problems: List<String>
    )

    private val _localVoicePacks = MutableStateFlow<List<InstalledVoicePack>>(emptyList())

    /** 已安装音色包列表（null 语义不适用：空列表即"一个都没有"，由 [refreshLocalVoicePacks] 填充）。 */
    val localVoicePacks: StateFlow<List<InstalledVoicePack>> = _localVoicePacks.asStateFlow()

    private val _voicePackBusy = MutableStateFlow(false)

    /** 导入/删除进行中（UI 据此禁用按钮并显示进度；取消/失败都会归零）。 */
    val voicePackBusy: StateFlow<Boolean> = _voicePackBusy.asStateFlow()

    private val _voicePackMessage = MutableStateFlow<String?>(null)

    /** 一次性提示文案（成功/失败；UI 消费后调 [consumeVoicePackMessage] 清空，避免重复弹）。 */
    val voicePackMessage: StateFlow<String?> = _voicePackMessage.asStateFlow()

    /** 消费一次性提示文案。 */
    fun consumeVoicePackMessage() {
        _voicePackMessage.value = null
    }

    /** 待确认覆盖的导入（同 voiceId 已存在时由 [importVoicePack] 置位）。 */
    data class PendingVoicePackOverwrite(val voiceId: String, val file: File)

    private val _pendingVoicePackOverwrite = MutableStateFlow<PendingVoicePackOverwrite?>(null)

    /** 待确认覆盖状态（非 null 时 UI 弹二次确认；**不静默覆盖**是 ADR-057 决策 5 的硬要求）。 */
    val pendingVoicePackOverwrite: StateFlow<PendingVoicePackOverwrite?> =
        _pendingVoicePackOverwrite.asStateFlow()

    /**
     * 刷新已安装音色包列表（进入/展开设置页、导入/删除后调用）。
     *
     * 扫描口径 = [io.prism.voice.local.LocalVoicePackStore] 的候选根（外部优先）下的**子目录**，
     * 逐个走同一套校验（[io.prism.voice.local.LocalVoicePackStore.resolveAtDirectory]）。
     * 校验不通过的目录也列出来（带问题原因）——否则"导入了却看不到"会让用户无从排查。
     */
    fun refreshLocalVoicePacks() {
        viewModelScope.launch {
            val store = localVoicePackStore ?: return@launch
            _localVoicePacks.value = try {
                withContext(Dispatchers.IO) { scanVoicePackDirs(store) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "刷新音色包列表失败", e)
                emptyList()
            }
        }
    }

    private fun scanVoicePackDirs(
        store: io.prism.voice.local.LocalVoicePackStore
    ): List<InstalledVoicePack> {
        val out = LinkedHashMap<String, InstalledVoicePack>()
        for (root in store.roots) {
            val children = runCatching { root.listFiles() }.getOrNull() ?: continue
            for (dir in children.sortedBy { it.name }) {
                // 目录名必须合法（`voiceId` 白名单）——`.import-*`/`.bak-*` 等临时目录据此被排除
                if (!dir.isDirectory) continue
                if (!io.prism.voice.local.LocalVoicePackStore.isValidVoiceId(dir.name)) continue
                if (out.containsKey(dir.name)) continue // 外部根优先（与 store.resolve 的取根顺序一致）
                when (val status = store.resolveAtDirectory(dir, dir.name)) {
                    is io.prism.voice.local.LocalVoiceStatus.Ready -> out[dir.name] =
                        InstalledVoicePack(
                            voiceId = dir.name,
                            name = status.pack.name,
                            ttsWire = status.pack.tts.wire,
                            totalBytes = status.pack.totalBytes,
                            ready = true,
                            problems = emptyList()
                        )
                    is io.prism.voice.local.LocalVoiceStatus.Incomplete -> out[dir.name] =
                        InstalledVoicePack(
                            voiceId = dir.name,
                            name = dir.name,
                            ttsWire = "",
                            totalBytes = 0L,
                            ready = false,
                            problems = status.problems
                        )
                    is io.prism.voice.local.LocalVoiceStatus.NotInstalled -> Unit
                }
            }
        }
        return out.values.toList()
    }

    /**
     * 导入音色包（`source` 为已落地的本地 `.voicepack` 文件——由 UI 从 SAF 流拷入 cache）。
     *
     * 流程：读清单声明的 voiceId → 若已安装则**转为待确认**（不落盘、不覆盖）→ 否则直接导入。
     * `source` 的生命周期由本 VM 接管（成功/失败/取消后删除）。
     */
    fun importVoicePack(source: File) {
        viewModelScope.launch {
            val importer = localVoicePackImporter ?: run {
                source.delete()
                _voicePackMessage.value = "音色包导入功能不可用"
                return@launch
            }
            _voicePackBusy.value = true
            try {
                // 清扫上一次选择遗留的暂存归档（guardrail TKN-LVC-016 F-7）：正常路径下
                // 成功/失败/取消都会删掉自己那份，但**进程在导入中被杀**时 finally 不会执行，
                // 缓存目录会一直堆着几百 MB 的 .voicepack（用户看不见也无从清理）。
                withContext(Dispatchers.IO) {
                    source.parentFile?.listFiles()
                        ?.filter { it.name.startsWith(VOICEPACK_PICK_PREFIX) && it.name != source.name }
                        ?.forEach { runCatching { it.delete() } }
                }
                val declared = withContext(Dispatchers.IO) { importer.peekVoiceId(source) }
                if (declared == null) {
                    source.delete()
                    _voicePackMessage.value = "所选文件不是有效的音色包（缺少 voicepack.json 或 voiceId）"
                    return@launch
                }
                val installed = _localVoicePacks.value.any { it.voiceId == declared }
                if (installed) {
                    // **不静默覆盖**：把文件留在手上，等用户确认（或取消时删除）
                    _pendingVoicePackOverwrite.value = PendingVoicePackOverwrite(declared, source)
                    return@launch
                }
                runImport(importer, source, declared, overwrite = false)
            } finally {
                _voicePackBusy.value = false
            }
        }
    }

    /** 确认覆盖同名音色包（UI 二次确认弹窗的"覆盖"）。 */
    fun confirmVoicePackOverwrite() {
        val pending = _pendingVoicePackOverwrite.value ?: return
        _pendingVoicePackOverwrite.value = null
        viewModelScope.launch {
            val importer = localVoicePackImporter ?: run {
                pending.file.delete()
                _voicePackMessage.value = "音色包导入功能不可用"
                return@launch
            }
            _voicePackBusy.value = true
            try {
                runImport(importer, pending.file, pending.voiceId, overwrite = true)
            } finally {
                _voicePackBusy.value = false
            }
        }
    }

    /** 取消覆盖（删除暂存的归档，不留垃圾）。 */
    fun cancelVoicePackOverwrite() {
        val pending = _pendingVoicePackOverwrite.value ?: return
        _pendingVoicePackOverwrite.value = null
        viewModelScope.launch(Dispatchers.IO) { pending.file.delete() }
    }

    /**
     * UI 侧复制归档失败（读不到 URI / 拷贝异常）时上报，统一走同一条提示通道。
     *
     * 为什么不直接抛给 UI：提示文案的口径（"导入失败：…"）集中在 VM 一处，
     * 两条入口各写一句必然漂移。
     */
    fun onVoicePackPickFailed(detail: String) {
        _voicePackMessage.value = "导入失败：无法读取所选文件（${detail.take(60)}）"
    }

    private suspend fun runImport(
        importer: io.prism.voice.local.LocalVoicePackImporter,
        source: File,
        declared: String,
        overwrite: Boolean
    ) {
        try {
            when (val result = importer.import(source, overwrite)) {
                is io.prism.voice.local.LocalVoicePackImporter.Result.Success -> {
                    _voicePackMessage.value =
                        if (result.replaced) "已覆盖音色包：${result.name}"
                        else "导入成功：${result.name}"
                    refreshLocalVoicePacks()
                }
                is io.prism.voice.local.LocalVoicePackImporter.Result.Failure -> {
                    _voicePackMessage.value = "导入失败：${result.reason}"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "导入音色包异常（voice=$declared）", e)
            _voicePackMessage.value = "导入失败（${e::class.simpleName}）"
        } finally {
            withContext(Dispatchers.IO) { source.delete() }
        }
    }

    /**
     * 删除已安装音色包。
     *
     * **删的若是"用户显式选择"的那个包，必须同时清除选择**（独立审查 H-2 / BR-interface-039 ④）。
     * 为什么不是"不做特殊处理"：选择被持久化成一个 **id**，包删掉后这个 id 仍然会**遮蔽角色包声明值**
     * ⇒ 两处装配点看到的是"该 id 未安装"，于是回退**系统播报**，而不是
     * [io.prism.voice.VoiceEnginePrefs.localVoiceId] KDoc 承诺的"失效选择回落**角色包声明值**"。
     * 用户侧的现象是"删了一个包，本地音色整个没了"，且列表里没有任何包显示「使用中」而无法自救。
     *
     * 改造前不存在这条路径（那时生效包只由角色包决定，删任何包都不影响声明值）
     * ⇒ 属 BUG-20260924-002 **新引入**的回归，本处即其修复点。
     */
    fun deleteVoicePack(voiceId: String) {
        viewModelScope.launch {
            val store = localVoicePackStore ?: return@launch
            if (!io.prism.voice.local.LocalVoicePackStore.isValidVoiceId(voiceId)) return@launch
            _voicePackBusy.value = true
            try {
                val removed = withContext(Dispatchers.IO) {
                    // 只删内部根（应用私有目录）：外部根的残留可能是侧载/调试产物，
                    // 由用户或 PC 脚本自行清理，App 不越界删除自己不完全拥有的位置。
                    val internalRoot = store.roots.lastOrNull() ?: return@withContext false
                    val dir = File(internalRoot, voiceId)
                    if (!dir.isDirectory) return@withContext false
                    dir.deleteRecursively()
                }
                if (removed) {
                    // 删的正是**用户显式选择**的那个包 ⇒ 一并清选，让角色包声明值重新生效（H-2）。
                    // 不清选的话，这个失效 id 会继续遮蔽角色包声明值，装配点按"未安装"回退**系统播报**
                    // ——与 localVoiceId KDoc 承诺的"回落角色包声明值"相反。
                    val selected = voiceEnginePrefs?.localVoiceIdOnce()
                    // 判定收敛到纯函数（独立审查 H-2，可被单测钉住）：删的正是当前选择 ⇒ 清选。
                    val afterDelete = io.prism.voice.resolveSelectionAfterDelete(selected, voiceId)
                    val cleared = selected != null && afterDelete == null
                    if (cleared) voiceEnginePrefs?.setLocalVoiceId(null)
                    _voicePackMessage.value = if (cleared) {
                        "已删除音色包：$voiceId（原为当前选择，已回落角色包声明）"
                    } else {
                        "已删除音色包：$voiceId"
                    }
                    refreshLocalVoiceStatus()
                } else {
                    // 内部根没有 ⇒ 可能装在外部队列根里。**必须给出可操作的提示**：
                    // 早先只回"未找到该音色包"，用户会以为包已经没了，而列表里它还在
                    // （guardrail TKN-LVC-016 AC-6.3）。
                    val elsewhere = withContext(Dispatchers.IO) {
                        store.roots.dropLast(1).any { File(it, voiceId).isDirectory }
                    }
                    _voicePackMessage.value = if (elsewhere) {
                        "该音色包位于外部目录（侧载），请在文件管理器中删除"
                    } else {
                        "未找到该音色包"
                    }
                }
                refreshLocalVoicePacks()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "删除音色包失败: $voiceId", e)
                _voicePackMessage.value = "删除失败（${e::class.simpleName}）"
            } finally {
                _voicePackBusy.value = false
            }
        }
    }

    /** 连通性自检状态（running + 结果文案；null = 未开始）。 */
    data class VoiceSelfCheckState(val running: Boolean, val message: String)

    private val _asrSelfCheck = MutableStateFlow<VoiceSelfCheckState?>(null)

    /** 云端 ASR 连通性自检状态（设置页展示）。 */
    val asrSelfCheck: StateFlow<VoiceSelfCheckState?> = _asrSelfCheck.asStateFlow()

    private val _ttsSelfCheck = MutableStateFlow<VoiceSelfCheckState?>(null)

    /** 云端 TTS 连通性自检状态（设置页展示）。 */
    val ttsSelfCheck: StateFlow<VoiceSelfCheckState?> = _ttsSelfCheck.asStateFlow()

    /**
     * 识别引擎连通性自检（A4，BUG-20260909-002）：**按当前选中引擎分流**。
     * - 端侧（Sherpa 流式）：无需云端 Key，直接提示可用（原实现无条件检查 DashScope Key，
     *   选端侧也误报"未配置 Key"）；
     * - 云端（阿里云百炼）：DashScope WS run-task 握手，3s 预算。
     * 阻塞探测在 IO 线程执行（不卡主线程）；结果经 [asrSelfCheck] 推送 UI 文案。
     */
    fun runAsrSelfCheck() {
        if (_asrSelfCheck.value?.running == true) return
        viewModelScope.launch {
            _asrSelfCheck.value = VoiceSelfCheckState(true, "自检中…")
            val state = if (voiceAsrEngine.value == io.prism.voice.AsrEngineChoice.SYSTEM) {
                VoiceSelfCheckState(false, "当前为端侧识别引擎（Sherpa 流式），无需云端 Key")
            } else {
                val key = runCatching {
                    apiKeyRepository.readApiKeyOnce(io.prism.voice.cloud.AliCloudAsrEngine.KEY_REF)
                }.getOrNull()
                val result = if (key.isNullOrBlank()) {
                    "未配置 DashScope API Key，请先保存 Key"
                } else {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        io.prism.voice.CloudVoiceSelfCheck.checkAliCloudAsr(key)
                    }
                }
                VoiceSelfCheckState(false, result ?: "自检通过 · 云端识别连接正常")
            }
            _asrSelfCheck.value = state
        }
    }

    /**
     * 云端 TTS 连通性自检（A4）：MiniMax WS task_start → task_started，3s 预算。
     * 阻塞探测在 IO 线程执行（不卡主线程）；结果经 [ttsSelfCheck] 推送 UI 文案。
     */
    fun runTtsSelfCheck() {
        if (_ttsSelfCheck.value?.running == true) return
        viewModelScope.launch {
            _ttsSelfCheck.value = VoiceSelfCheckState(true, "自检中…")
            val apiKey = runCatching {
                apiKeyRepository.readApiKeyOnce(io.prism.voice.cloud.MiniMaxTtsConfig.KEY_API_KEY)
            }.getOrNull()
            val groupId = runCatching {
                apiKeyRepository.readApiKeyOnce(io.prism.voice.cloud.MiniMaxTtsConfig.KEY_GROUP_ID)
            }.getOrNull()
            val result = if (apiKey.isNullOrBlank() || groupId.isNullOrBlank()) {
                "未配置 MiniMax API Key 或 GroupId，请先保存"
            } else {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    io.prism.voice.CloudVoiceSelfCheck.checkMiniMaxTts(apiKey, groupId)
                }
            }
            _ttsSelfCheck.value = VoiceSelfCheckState(false, result ?: "自检通过 · 云端 TTS 连接正常")
        }
    }

    // ==================== 批次23-C（ADR-048）：角色包管理（导入/列表/切换/删除） ====================

    private val _rolePacks = MutableStateFlow<List<io.prism.voice.rolepack.RolePack>>(emptyList())

    /** 已导入角色包列表（按显示名排序；loader 单次读取，经 [refreshRolePacks] 刷新）。 */
    val rolePacks: StateFlow<List<io.prism.voice.rolepack.RolePack>> = _rolePacks.asStateFlow()

    private val _selectedRolePackId = MutableStateFlow<String?>(null)

    /** 当前选中角色包 id（null = 未选中，出厂默认助手）。 */
    val selectedRolePackId: StateFlow<String?> = _selectedRolePackId.asStateFlow()

    /**
     * 刷新角色包列表与选中态（进入设置页 / 导入 / 删除后调用）。
     * loader 文件 IO 自带 Dispatchers.IO 调度，此处仅 viewModelScope 协程安全等待。
     */
    fun refreshRolePacks() {
        viewModelScope.launch {
            try {
                _rolePacks.value = rolePackLoader?.list().orEmpty()
                _selectedRolePackId.value = rolePackLoader?.selectedId()
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "刷新角色包列表失败: ${e.message}", e)
            }
        }
    }

    /**
     * 导入角色包（批次23-C）：先经 [io.prism.voice.rolepack.RolePackParser] 解析校验，
     * 成功后 loader.import 落盘（存储文件名以包 id 规范化）。onResult(ok, 可读消息) 主线程回调
     * （解析异常消息面向角色作者，直接透出）。
     *
     * BUG-20260908-002：导入成功后**自动选中**该包（原实现只落盘不选中——用户导入后
     * 语音通话仍是出厂默认人设，误以为导入未生效）。
     */
    fun importRolePack(json: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val loader = rolePackLoader ?: run {
                    onResult(false, "角色包功能不可用")
                    return@launch
                }
                val pack = io.prism.voice.rolepack.RolePackParser.parse(json)
                loader.import(pack.id, json)
                loader.select(pack.id)
                _selectedRolePackId.value = pack.id
                refreshRolePacks()
                onResult(true, "导入成功：${pack.name}")
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "导入角色包失败: ${e.message}")
                onResult(false, e.message?.let { "导入失败：$it" } ?: "导入失败")
            }
        }
    }

    /** 选中角色包（热切换：下次装配读 effectiveRolePack 即生效）。 */
    fun selectRolePack(id: String) {
        viewModelScope.launch {
            try {
                rolePackLoader?.select(id)
                _selectedRolePackId.value = id
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "选中角色包失败: $id", e)
            }
        }
    }

    /**
     * 清除选中角色包（回落出厂默认助手；设置页「默认（Prism 助手）」行点击入口，
     * BUG-20260908-002：原实现无 UI 入口，选错后只能删除包才能回默认）。
     */
    fun clearRolePackSelection() {
        viewModelScope.launch {
            try {
                rolePackLoader?.clearSelection()
                _selectedRolePackId.value = null
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "清除角色包选中失败", e)
            }
        }
    }

    /** 删除角色包（若删除的是当前选中包，loader 自动清除选中回落出厂默认）。 */
    fun deleteRolePack(id: String) {
        viewModelScope.launch {
            try {
                rolePackLoader?.remove(id)
                refreshRolePacks()
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "删除角色包失败: $id", e)
            }
        }
    }

    // ==================== 批次24（ADR-051）：桌宠 ====================

    private val _petConfig = MutableStateFlow(io.prism.pet.model.PetConfig.DEFAULT)

    /** 桌宠配置（开关 / 形象 / 尺寸 / 不透明度 / 待机 / 减少动效 …）。 */
    val petConfig: StateFlow<io.prism.pet.model.PetConfig> = _petConfig.asStateFlow()

    private val _petAppearances = MutableStateFlow<List<io.prism.pet.model.PetAppearance>>(emptyList())

    /** 已安装桌宠形象列表。 */
    val petAppearances: StateFlow<List<io.prism.pet.model.PetAppearance>> = _petAppearances.asStateFlow()

    private val _petRuntime = MutableStateFlow(io.prism.pet.PetManager.RuntimeState.STOPPED)

    /** 悬浮窗运行态（设置页据此显示提示）。 */
    val petRuntime: StateFlow<io.prism.pet.PetManager.RuntimeState> = _petRuntime.asStateFlow()

    private val _petLastError = MutableStateFlow<String?>(null)

    /** 最近一次桌宠失败的可读原因（无则 null）。 */
    val petLastError: StateFlow<String?> = _petLastError.asStateFlow()

    private val _petMessage = MutableStateFlow<String?>(null)

    /** 最近一次导入结果提示（成功/失败均可读）。 */
    val petMessage: StateFlow<String?> = _petMessage.asStateFlow()

    init {
        if (petConfigRepository != null) {
            viewModelScope.launch {
                try {
                    petConfigRepository.config().collect { cfg -> _petConfig.value = cfg }
                } catch (e: CancellationException) {
                    throw e // BR-error-handling-007
                } catch (e: Exception) {
                    Log.w(TAG, "桌宠配置读取失败: ${e::class.simpleName}")
                }
            }
        }
        if (petManager != null) {
            val mgr = petManager
            viewModelScope.launch {
                try {
                    mgr.appearances.collect { list -> _petAppearances.value = list }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "桌宠形象列表订阅失败: ${e::class.simpleName}")
                }
            }
            viewModelScope.launch {
                try {
                    mgr.state.collect { st -> _petRuntime.value = st }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "桌宠运行态订阅失败: ${e::class.simpleName}")
                }
            }
            viewModelScope.launch {
                try {
                    mgr.lastError.collect { err -> _petLastError.value = err }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "桌宠错误流订阅失败: ${e::class.simpleName}")
                }
            }
        }
    }

    /**
     * 桌宠操作的统一启动包装（guardrail H-5 修复，BR-error-handling-007）。
     *
     * **为什么必须统一**：guardrail 在 12 处桌宠方法里查到两类违规——
     * ① 7 处 `runCatching { … }` **完全没有失败处理**（异常被静默吞掉，用户点开关毫无反应也没有任何痕迹）；
     * ② 5 处虽有 `onFailure` 但**不重抛 `CancellationException`**（协程取消被当成业务失败，
     * 会污染错误提示并让 `viewModelScope` 的取消语义失真）。
     *
     * 被包裹的 `DataStore.edit` 是**真会长期挂起**的 suspend 调用，这不是理论风险。
     * 本项目在 TKN-US019-RAG-GUARDRAIL-001 已把同款模式判为 HIGH 并立为 active 规则。
     *
     * @param what 失败时日志里用的动作名（**不含用户内容与路径**）
     * @param onError 可选的用户可见反馈（如导入失败原因）
     */
    private fun launchPet(
        what: String,
        onError: ((Throwable) -> Unit)? = null,
        block: suspend () -> Unit
    ) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：不吞 CancellationException
            } catch (e: Exception) {
                Log.w(TAG, "桌宠$what 失败: ${e::class.simpleName}")
                onError?.invoke(e)
            }
        }
    }

    /** 刷新形象列表（进入设置页时调用）。 */
    fun refreshPet() {
        launchPet("刷新形象列表") { petManager?.refreshAppearances() }
    }

    /** 打开/关闭桌宠（含服务起停；失败原因经 [petLastError] 暴露）。 */
    fun setPetEnabled(enabled: Boolean) {
        launchPet("开关", onError = { e ->
            _petMessage.value = "桌宠启动失败：${e.message ?: e::class.simpleName}"
        }) {
            petManager?.setEnabled(enabled)
        }
    }

    fun setPetAppearance(id: String?) {
        launchPet("切换形象") { petManager?.selectAppearance(id) }
    }

    fun deletePetAppearance(id: String) {
        launchPet("删除形象") { petManager?.removeAppearance(id) }
    }

    /**
     * 导入形象。
     *
     * @param isPack true = `.petpack`（ZIP），false = 单张图片
     */
    fun importPetAsset(uri: android.net.Uri, isPack: Boolean) {
        launchPet("导入形象", onError = { e ->
            _petMessage.value = "导入失败：${e.message ?: e::class.simpleName}"
        }) {
            val mgr = petManager
            if (mgr == null) {
                _petMessage.value = "桌宠功能不可用"
                return@launchPet
            }
            val result = if (isPack) mgr.importPack(uri) else mgr.importImage(uri)
            result.fold(
                onSuccess = { _petMessage.value = "已导入形象「${it.name}」" },
                onFailure = { _petMessage.value = "导入失败：${it.message ?: it::class.simpleName}" }
            )
        }
    }

    fun setPetScale(scale: Float) = launchPet("设置大小") { petManager?.setScale(scale) }

    fun setPetOpacity(opacity: Float) = launchPet("设置不透明度") { petManager?.setOpacity(opacity) }

    fun setPetIdleEnabled(enabled: Boolean) = launchPet("设置待机") { petManager?.setIdleEnabled(enabled) }

    fun setPetReduceMotion(reduce: Boolean) = launchPet("设置减少动效") { petManager?.setReduceMotion(reduce) }

    fun setPetBubbleEnabled(enabled: Boolean) = launchPet("设置气泡") { petManager?.setBubbleEnabled(enabled) }

    fun setPetLowPowerMode(enabled: Boolean) = launchPet("设置低功耗") { petManager?.setLowPowerMode(enabled) }

    fun setPetExpressionResetSeconds(seconds: Int) =
        launchPet("设置表情回正") { petManager?.setExpressionResetSeconds(seconds) }

    /** 重置桌宠到右下默认位置（PRD US-2412 AC-1「重置位置」）。 */
    fun resetPetPosition() = launchPet("重置位置") { petManager?.resetPosition() }

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

        private const val TAG = "SettingsViewModel"

        /**
         * SAF 选中的归档在 `cacheDir` 里的暂存文件名前缀（UI 侧拷贝时使用，与 [importVoicePack]
         * 的清扫**必须同源**——两处各写一份字面量必然漂移，届时清扫会静默失效）。
         */
        internal const val VOICEPACK_PICK_PREFIX = "voicepack_import_"
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
                    searchEnhancementRepository = app.searchEnhancementConfigRepository,
                    // 批次23-A（ADR-048，A4）：语音引擎手选仓库（ASR/TTS 云端双轨）
                    voiceEnginePrefs = app.voiceEnginePrefs,
                    // 批次23-C（ADR-048）：角色包加载器（导入/列表/切换/删除）
                    rolePackLoader = app.rolePackLoader,
                    // 批次24（ADR-051）：桌宠门面 + 配置仓库
                    petManager = app.petManager,
                    petConfigRepository = app.petConfigRepository,
                    // LVC-1（ADR-053）：本地音色包仓库（设置页展示安装状态 + 选项门控）
                    localVoicePackStore = app.localVoicePackStore,
                    // TKN-LVC-016（ADR-057）：音色包导入器（设置页「导入音色包」）
                    localVoicePackImporter = app.localVoicePackImporter
                )
            }
        }

        /** v1 批次15（US-1501）：智谱 API Key 引用（ApiKeyRepository 加密键）。 */
        const val ZHIPU_API_KEY_REF = "zhipu"

        /** v1 批次15（US-1502）：Tavily API Key 引用（ApiKeyRepository 加密键）。 */
        const val TAVILY_API_KEY_REF = "tavily"
    }
}