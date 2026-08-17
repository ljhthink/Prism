package io.prism.data

import io.objectbox.Box
import io.objectbox.BoxStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Skill 配置仓库 —— 管理 [SkillConfig] 的 CRUD 操作（ADR-013 5.1）。
 *
 * **架构**（仿 [McpServerRepository]，考古报告集成点 2 确认照搬模式）：
 * - [BoxStore] 提供 ObjectBox 持久化
 * - [box] 延迟初始化的 [SkillConfig] Box
 * - [skills] 暴露全部 Skill 配置列表，供 UI 订阅
 *
 * **激活机制**：Skill 允许多个并存启用，**不需要**单激活不变式
 * （与 [McpServerRepository] 一致，每个 Skill 独立 [SkillConfig.isEnabled]）。
 *
 * **更新时间戳**：每次 [save] / [setEnabled] 自动刷新 [SkillConfig.updatedAt]，
 * 便于扫描同步时判断变更。
 */
class SkillRepository(private val boxStore: BoxStore) {

    private val box: Box<SkillConfig> = boxStore.boxFor(SkillConfig::class.java)

    private val _skills = MutableStateFlow<List<SkillConfig>>(emptyList())
    /** 全部 Skill 配置列表（按 createdAt 升序），供 UI 订阅 */
    val skills: StateFlow<List<SkillConfig>> = _skills.asStateFlow()

    init {
        refreshFlows()
    }

    /**
     * 保存或更新 Skill 配置。自动刷新 [SkillConfig.updatedAt]。
     *
     * @param config 要保存的配置（id=0 为新建，id>0 为更新）
     * @return 保存后的 id
     */
    fun save(config: SkillConfig): Long {
        config.updatedAt = System.currentTimeMillis()
        val id = box.put(config)
        refreshFlows()
        return id
    }

    /**
     * 按 id 获取 Skill 配置。
     *
     * @param id SkillConfig id
     * @return 配置对象，不存在返回 null
     */
    fun get(id: Long): SkillConfig? = box.get(id)

    /**
     * 获取全部 Skill 配置（按 createdAt 升序）。
     */
    fun getAll(): List<SkillConfig> = box.all.sortedBy { it.createdAt }

    /**
     * 按 slug name 查找 Skill 配置（精确匹配）。
     *
     * @param name Skill slug（frontmatter name）
     * @return 匹配的配置，未找到返回 null
     */
    fun findByName(name: String): SkillConfig? = box.all.find { it.name == name }

    /**
     * 删除指定 id 的 Skill 配置。
     *
     * @param id SkillConfig id
     */
    fun remove(id: Long) {
        box.remove(id)
        refreshFlows()
    }

    /**
     * 删除所有 Skill 配置。
     */
    fun removeAll() {
        box.removeAll()
        refreshFlows()
    }

    /**
     * 设置 Skill 的启用状态。自动刷新 [SkillConfig.updatedAt]。
     *
     * @param id SkillConfig id
     * @param enabled 是否启用
     */
    fun setEnabled(id: Long, enabled: Boolean) {
        box.get(id)?.let { config ->
            config.isEnabled = enabled
            config.updatedAt = System.currentTimeMillis()
            box.put(config)
            refreshFlows()
        }
    }

    /**
     * 标记 Skill 安装状态（远程下载失败 / 文件缺失扫描后标记 false）。
     *
     * @param id SkillConfig id
     * @param installed 是否已安装
     */
    fun setInstalled(id: Long, installed: Boolean) {
        box.get(id)?.let { config ->
            config.isInstalled = installed
            config.updatedAt = System.currentTimeMillis()
            box.put(config)
            refreshFlows()
        }
    }

    /**
     * 标记 Skill 删除状态（用户删除 Skill 后置 true，扫描不再恢复）。
     *
     * **删除语义**（修复：内置 Skill 无法删除文件的限制）：
     * - LOCAL_USER / REMOTE：调用方先删除磁盘目录，再置 hidden（双保险）
     * - LOCAL_BUILTIN：无法删除 assets 文件，仅置 hidden，扫描同步时跳过恢复
     *
     * @param id SkillConfig id
     * @param hidden 是否已删除（隐藏）
     */
    fun setHidden(id: Long, hidden: Boolean) {
        box.get(id)?.let { config ->
            config.isHidden = hidden
            config.updatedAt = System.currentTimeMillis()
            box.put(config)
            refreshFlows()
        }
    }

    /**
     * 获取所有已启用的 Skill 配置（供 ConversationViewModel 注入）。
     *
     * 过滤条件：启用 + 已安装 + 未删除（用户删除的 Skill 不参与工具注入）。
     */
    fun getEnabled(): List<SkillConfig> = box.all.filter { it.isEnabled && it.isInstalled && !it.isHidden }

    /**
     * 刷新 Skill 列表的 Flow。
     */
    private fun refreshFlows() {
        _skills.value = box.all.sortedBy { it.createdAt }
    }
}
