package io.prism.ui.capabilities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.prism.PrismApplication
import io.prism.data.SkillConfig
import io.prism.data.SkillRepository
import io.prism.skill.SkillManifest
import io.prism.skill.SkillRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Skills 管理 ViewModel（US-027，ADR-013 5.5）。
 *
 * **职责**：
 * - 暴露 [skills] StateFlow 供 UI 订阅（实时响应启用/禁用切换）
 * - 管理 [selectedSkill]（详情弹层状态）
 * - 提供 [setSkillEnabled] 落库启用状态
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
 *
 * @param skillRepository Skill 配置仓库（持久化层，实时响应）
 * @param skillRegistry Skill 注册中心（内存层，提供 manifest）
 */
class SkillsViewModel(
    private val skillRepository: SkillRepository,
    private val skillRegistry: SkillRegistry
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

    /** 选中 Skill（打开详情弹层）。null 关闭弹层。 */
    fun selectSkill(skill: SkillUiModel?) {
        _selectedSkill.value = skill
    }

    /**
     * 切换 Skill 启用状态（落库，BR-interface-004）。
     *
     * 调用 [SkillRepository.setEnabled]，[skills] StateFlow 自动刷新
     * （Repository 内部 refreshFlows → combine 重新发射）。
     */
    fun setSkillEnabled(id: Long, enabled: Boolean) {
        skillRepository.setEnabled(id, enabled)
    }

    companion object {
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

        /** 供 viewModel() initializer 使用的工厂。 */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PrismApplication
                SkillsViewModel(app.skillRepository, app.skillRegistry)
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
