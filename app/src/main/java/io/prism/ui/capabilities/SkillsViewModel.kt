package io.prism.ui.capabilities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.prism.PrismApplication
import io.prism.data.SkillConfig
import io.prism.data.SkillExecutionRecord
import io.prism.data.SkillExecutionRepository
import io.prism.data.SkillRepository
import io.prism.skill.DownloadResult
import io.prism.skill.SkillDownloader
import io.prism.skill.SkillManifest
import io.prism.skill.SkillRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Skills 管理 ViewModel（US-027 / US-028 / US-029，ADR-013 5.5 / 5.6 / 5.7）。
 *
 * **职责**：
 * - 暴露 [skills] StateFlow 供 UI 订阅（实时响应启用/禁用切换）
 * - 管理 [selectedSkill]（详情弹层状态）
 * - 提供 [setSkillEnabled] 落库启用状态（R2-1 修复：optimistic update 同步刷新 [selectedSkill]）
 * - US-028：[installFromUrl] 触发远程 Skill 下载 + 沙箱校验 + scanAndSync 刷新
 * - US-029：[loadExecutionRecords] 加载选中 Skill 的最近 10 次执行记录，[executionRecords] 供 UI 展示
 *
 * **数据源策略**（考古报告 D-1 决策，方案 B）：
 * - 主数据源：[SkillRepository.skills]（实时响应 setEnabled，持久化层）
 * - manifest 丰富：[SkillRegistry.skills]（含解析后的 SkillManifest）
 * - 两者 [combine] 为 [SkillUiModel] 列表，config 来自 Repository（实时），
 *   manifest 来自 Registry（按 name 匹配）
 *
 * **与 CapabilitiesViewModel 的关系**：
 * - 职责分离：CapabilitiesViewModel 管 MCP Server，SkillsViewModel 管 Skill
 * - 同属 CapabilitiesScreen，各自独立的 ViewModel + Factory
 *
 * **可测性**（BR-testing-004）：
 * - [Companion.combineSkills] 为 internal 纯函数，可在纯 JVM 测试中验证
 * - [Companion.toUiModel] 为 internal 纯函数，按 source 映射 icon（UI 层负责颜色）
 * - [Companion.mapInstallResult] 为 internal 纯函数，将 [DownloadResult] 映射为 [InstallState]
 * - 构造器仅依赖 SkillRepository + SkillRegistry + SkillDownloader + SkillExecutionRepository + File，无 Android Context stub
 *
 * @param skillRepository Skill 配置仓库（持久化层，实时响应）
 * @param skillRegistry Skill 注册中心（内存层，提供 manifest + scanAndSync）
 * @param skillDownloader 远程 Skill 下载器（US-028，ADR-013 5.6）
 * @param skillExecutionRepository Skill 执行记录仓库（US-029，ADR-013 5.7）
 * @param remoteSkillsDir 远程 Skill 安装根目录（`filesDir/skills/remote/`，由 Application 注入）
 */
class SkillsViewModel(
    private val skillRepository: SkillRepository,
    private val skillRegistry: SkillRegistry,
    private val skillDownloader: SkillDownloader,
    private val skillExecutionRepository: SkillExecutionRepository,
    private val remoteSkillsDir: File
) : ViewModel() {

    /**
     * 全部 Skill 列表（UI 模型），实时响应启用/禁用切换。
     *
     * [combine] SkillRepository.skills（config，实时）与 SkillRegistry.skills（manifest），
     * 按 name 匹配合并为 [SkillUiModel]。未匹配到 manifest 的 Skill（如文件缺失）
     * manifest 为 null，UI 层降级展示。
     */
    val skills: StateFlow<List<SkillUiModel>> = combine(
        skillRepository.skills,
        skillRegistry.skills
    ) { configs, entries ->
        Companion.combineSkills(configs, entries)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedSkill = MutableStateFlow<SkillUiModel?>(null)
    /** 当前选中的 Skill（null 表示未选中，详情弹层关闭）。 */
    val selectedSkill: StateFlow<SkillUiModel?> = _selectedSkill.asStateFlow()

    private val _executionRecords = MutableStateFlow<List<SkillExecutionRecord>>(emptyList())
    /** 当前选中 Skill 的最近 10 次执行记录（US-029，ADR-013 5.7）。 */
    val executionRecords: StateFlow<List<SkillExecutionRecord>> = _executionRecords.asStateFlow()

    /**
     * Skill 执行状态（US-029 占位，US-027 暂未使用）。
     *
     * US-029 将扩展为 sealed interface：Idle / Executing / Success / Fail，
     * 用于 Skill 详情页展示执行记录。
     */
    sealed interface ExecutionState {
        data object Idle : ExecutionState
    }

    private val _executionState = MutableStateFlow<ExecutionState>(ExecutionState.Idle)
    /** Skill 执行状态（US-029 扩展）。 */
    val executionState: StateFlow<ExecutionState> = _executionState.asStateFlow()

    /**
     * 远程安装状态（US-028，ADR-013 5.6）。
     *
     * - [Idle]：未在安装，安装弹层初始状态
     * - [Installing]：正在下载 + 校验 + 入库（UI 展示 loading）
     * - [Success]：安装成功（UI 展示成功提示 + slug，自动刷新 skills 列表）
     * - [Fail]：安装失败（UI 展示脱敏后的 message）
     *
     * 状态流转：Idle → Installing → (Success | Fail) → Idle（用户 dismiss 或重新发起）
     */
    sealed interface InstallState {
        data object Idle : InstallState
        data class Installing(val url: String) : InstallState
        data class Success(val slug: String) : InstallState
        data class Fail(val message: String) : InstallState
    }

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    /** 远程安装状态（UI 订阅展示 loading / 成功 / 失败）。 */
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    /**
     * 选中 Skill（打开详情弹层）。null 关闭弹层。
     *
     * 选中后自动加载该 Skill 的最近 10 次执行记录（US-029，ADR-013 5.7）。
     */
    fun selectSkill(skill: SkillUiModel?) {
        _selectedSkill.value = skill
        if (skill != null) {
            loadExecutionRecords(skill.config.id)
        } else {
            _executionRecords.value = emptyList()
        }
    }

    /**
     * 加载指定 Skill 的最近 10 次执行记录（US-029，ADR-013 5.7）。
     *
     * 同步调用 [SkillExecutionRepository.getRecentBySkill]，结果直接写入 [_executionRecords]。
     * ObjectBox 查询是同步快速操作（<10ms），无需协程；UI 通过 [executionRecords] StateFlow 订阅刷新。
     *
     * @param skillConfigId 关联的 SkillConfig id
     */
    fun loadExecutionRecords(skillConfigId: Long) {
        try {
            _executionRecords.value = skillExecutionRepository.getRecentBySkill(skillConfigId)
        } catch (e: Exception) {
            // 加载失败不阻断 UI（BR-error-handling-004），仅记录日志
            android.util.Log.w(TAG, "loadExecutionRecords failed: skillConfigId=$skillConfigId", e)
            _executionRecords.value = emptyList()
        }
    }

    /**
     * 切换 Skill 启用状态（落库，BR-interface-004）。
     *
     * **R2-1 修复（弹层开关视觉滞后）**：
     * 原实现仅调用 [SkillRepository.setEnabled]，[skills] StateFlow 经 combine + stateIn
     * 异步刷新，但 [selectedSkill] 是一次性快照不随列表刷新同步，导致详情弹层开关视觉滞后。
     *
     * 修复策略（optimistic update）：
     * 1. 先同步更新 [_selectedSkill]（开关 UI 立即响应）
     * 2. 再调用 [skillRepository.setEnabled] 持久化（异步刷新 [skills]）
     * 3. 若持久化失败（罕见，ObjectBox 同步 put），[_selectedSkill] 已更新但 [skills] 未刷新，
     *    用户下次打开弹层会看到真实状态（最终一致性）
     *
     * @param id SkillConfig id
     * @param enabled 目标启用状态
     */
    fun setSkillEnabled(id: Long, enabled: Boolean) {
        // R2-1 修复：optimistic update 同步刷新 selectedSkill
        // 纯逻辑提取到 [Companion.applyEnabledUpdate]，便于纯 JVM 单元测试（BR-testing-004）
        _selectedSkill.update { current -> Companion.applyEnabledUpdate(current, id, enabled) }
        skillRepository.setEnabled(id, enabled)
    }

    /**
     * 远程安装 Skill（US-028，ADR-013 5.6）。
     *
     * **流程**：
     * 1. 状态置为 [InstallState.Installing]
     * 2. 调用 [SkillDownloader.download]（多层安全校验 + 原子安装到 `filesDir/skills/remote/{slug}/`）
     * 3. 成功后触发 [SkillRegistry.scanAndSync] 同步 SkillConfig 表（source=REMOTE, isInstalled=true）
     * 4. 状态置为 [InstallState.Success] 或 [InstallState.Fail]
     *
     * **失败处理**：[SkillDownloader] 已对错误信息脱敏（CWE-209），直接展示给用户。
     * 协程取消遵循 BR-error-handling-007（CancellationException 重抛，不吞）。
     *
     * @param url 远程 Skill URL（必须 https，扩展名 .skill.md / .zip / .md）
     */
    fun installFromUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            _installState.value = InstallState.Fail("URL 不能为空")
            return
        }
        viewModelScope.launch {
            _installState.value = InstallState.Installing(trimmed)
            try {
                val result = skillDownloader.download(trimmed, remoteSkillsDir)
                val mapped = Companion.mapInstallResult(result)
                _installState.value = mapped
                // 成功后触发 Registry 扫描，将新 Skill 同步到 SkillConfig 表 + skills StateFlow
                if (mapped is InstallState.Success) {
                    try {
                        skillRegistry.scanAndSync()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // scanAndSync 失败不影响安装结果（文件已落地），仅记录日志
                        android.util.Log.w(TAG, "scanAndSync after install failed", e)
                    }
                }
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007
            } catch (e: Exception) {
                // 兜底（SkillDownloader 内部已 try-catch，但防御性处理）
                android.util.Log.e(TAG, "installFromUrl unexpected failure", e)
                _installState.value = InstallState.Fail("安装失败，请重试")
            }
        }
    }

    /** 重置安装状态到 [InstallState.Idle]（UI dismiss 成功/失败提示后调用）。 */
    fun resetInstallState() {
        _installState.value = InstallState.Idle
    }

    companion object {
        private const val TAG = "SkillsViewModel"

        /**
         * R2-1 optimistic update 纯函数（BR-testing-004）。
         *
         * 当用户在 Skill 详情弹层切换启用开关时，[setSkillEnabled] 同步刷新 [_selectedSkill]，
         * 使开关 UI 立即响应（无需等待 [skills] StateFlow 异步 combine 刷新）。
         *
         * **逻辑**：
         * - current 为 null（未选中任何 Skill）→ 返回 null（无操作）
         * - current.config.id != id（选中 Skill 与切换的不一致）→ 返回 current（无操作）
         * - current.config.id == id → 返回 current.copy(config = current.config.copy(isEnabled = enabled))
         *
         * **可测性**：提取为 companion object internal 纯函数，不依赖 Android Context / ObjectBox /
         * 协程，可在纯 JVM 测试中验证全部分支（null / id 不匹配 / id 匹配 / enabled true/false）。
         *
         * @param current 当前选中的 SkillUiModel（可能为 null）
         * @param id 被切换启用状态的 SkillConfig id
         * @param enabled 目标启用状态
         * @return 更新后的 SkillUiModel（或原值/null）
         */
        internal fun applyEnabledUpdate(
            current: SkillUiModel?,
            id: Long,
            enabled: Boolean
        ): SkillUiModel? = if (current != null && current.config.id == id) {
            current.copy(config = current.config.copy(isEnabled = enabled))
        } else {
            current
        }

        /**
         * 合并持久化 config 列表与内存 manifest 列表为 UI 模型（纯函数，BR-testing-004）。
         *
         * @param configs 来自 SkillRepository 的持久化配置（实时响应 setEnabled）
         * @param entries 来自 SkillRegistry 的加载条目（含 manifest）
         * @return UI 模型列表，按 config.createdAt 升序（与 Repository 一致）
         */
        internal fun combineSkills(
            configs: List<SkillConfig>,
            entries: List<SkillRegistry.SkillEntry>
        ): List<SkillUiModel> {
            val manifestByName = entries.associateBy { it.config.name }
            return configs.map { config ->
                val manifest = manifestByName[config.name]?.manifest
                Companion.toUiModel(config, manifest)
            }
        }

        /**
         * 将 config + manifest 转换为 UI 模型（纯函数，BR-testing-004）。
         *
         * icon 按 [io.prism.data.SkillSource] 映射（D-2 决策）：
         * - LOCAL_BUILTIN → "◈"（内置预设）
         * - LOCAL_USER → "✎"（用户自建）
         * - REMOTE → "⌂"（远程下载）
         * - 未知 → "▣"
         *
         * 颜色映射在 UI 层（SkillRow composable），避免 ViewModel 依赖 Compose 类型。
         */
        internal fun toUiModel(config: SkillConfig, manifest: SkillManifest?): SkillUiModel {
            val icon = when (config.source) {
                io.prism.data.SkillSource.LOCAL_BUILTIN -> "◈"
                io.prism.data.SkillSource.LOCAL_USER -> "✎"
                io.prism.data.SkillSource.REMOTE -> "⌂"
                else -> "▣"
            }
            return SkillUiModel(
                config = config,
                manifest = manifest,
                icon = icon
            )
        }

        /**
         * 将 [DownloadResult] 映射为 [InstallState]（纯函数，BR-testing-004）。
         *
         * - [DownloadResult.Success] → [InstallState.Success]（携带 slug）
         * - [DownloadResult.Fail] → [InstallState.Fail]（message 为 null 时回退通用文案，CWE-209 兜底）
         *
         * @param result SkillDownloader 返回的结果
         * @return UI 可消费的安装状态
         */
        internal fun mapInstallResult(result: DownloadResult): InstallState = when (result) {
            is DownloadResult.Success -> InstallState.Success(result.slug)
            is DownloadResult.Fail -> InstallState.Fail(result.message.ifBlank { "安装失败，请检查 URL 或网络后重试" })
        }

        /** 供 viewModel() initializer 使用的工厂。 */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PrismApplication
                SkillsViewModel(
                    skillRepository = app.skillRepository,
                    skillRegistry = app.skillRegistry,
                    skillDownloader = app.skillDownloader,
                    skillExecutionRepository = app.skillExecutionRepository,
                    remoteSkillsDir = java.io.File(app.filesDir, "skills/remote")
                )
            }
        }
    }
}

/**
 * Skill UI 模型（US-027）。
 *
 * 组合持久化 [SkillConfig]（实时状态）与内存 [SkillManifest]（元数据），
 * 供 CapabilitiesScreen Skills 段渲染。
 *
 * @property config 持久化配置（启用状态、来源、版本等）
 * @property manifest 解析后的 manifest（可能为 null，如 SKILL.md 文件缺失）
 * @property icon 图标字符（按 source 映射，UI 层渲染）
 */
data class SkillUiModel(
    val config: SkillConfig,
    val manifest: SkillManifest?,
    val icon: String
)
