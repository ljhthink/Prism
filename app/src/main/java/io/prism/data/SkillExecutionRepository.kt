package io.prism.data

import io.objectbox.Box
import io.objectbox.BoxStore

/**
 * Skill 执行记录仓库 —— 管理 [SkillExecutionRecord] 的 CRUD 操作（ADR-013 5.7，US-029）。
 *
 * **架构**（仿 [SkillRepository]，考古报告集成点 2 确认照搬模式）：
 * - [BoxStore] 提供 ObjectBox 持久化
 * - [box] 延迟初始化的 [SkillExecutionRecord] Box
 * - 无 StateFlow（执行记录按需查询，UI 通过 [getRecentBySkill] 主动拉取）
 *
 * **与 [SkillRepository] 的差异**：
 * - 不维护全量列表 StateFlow（执行记录量大，按 Skill 维度查询更高效）
 * - 不需要 setEnabled / setInstalled 等状态变更方法（记录不可变，仅追加/删除）
 * - 提供按 [SkillConfig.id] 查询最近 N 条记录的便捷方法（UI 详情页用）
 *
 * **查询实现**：采用 `box.all.filter { ... }.sortedByDescending { ... }` 模式（与
 * [SkillRepository.getAll] / [SkillRepository.findByName] 保持一致）。
 * 单 Skill 执行记录量级为百级，全量加载 + 内存过滤性能完全可接受；
 * ObjectBox query DSL（`box.query { equal(...) order(...) }`）需要 `io.objectbox.kotlin`
 * 扩展依赖且 API 版本敏感，本项目未使用该模式（KISS，避免引入未验证依赖）。
 * 未来若性能瓶颈再迁移到 query DSL + `@Index`。
 *
 * **可测性**（BR-testing-004）：构造器仅依赖 [BoxStore]，无 Android Context stub，
 * 可在纯 JVM 单元测试中通过 `MyObjectBox.builder().build()` 构造真实 BoxStore 验证。
 */
class SkillExecutionRepository(private val boxStore: BoxStore) {

    private val box: Box<SkillExecutionRecord> = boxStore.boxFor(SkillExecutionRecord::class.java)

    /**
     * 保存执行记录（仅追加，不更新）。
     *
     * @param record 执行记录（id=0 为新建）
     * @return 保存后的 id
     */
    fun save(record: SkillExecutionRecord): Long {
        val id = box.put(record)
        return id
    }

    /**
     * 按 id 获取执行记录。
     *
     * @param id SkillExecutionRecord id
     * @return 记录对象，不存在返回 null
     */
    fun get(id: Long): SkillExecutionRecord? = box.get(id)

    /**
     * 按 SkillConfig id 获取全部执行记录（按 startedAt 降序，最近的在前）。
     *
     * @param skillConfigId 关联的 SkillConfig id
     * @return 执行记录列表（降序），无记录返回空列表
     */
    fun getBySkill(skillConfigId: Long): List<SkillExecutionRecord> =
        box.all.filter { it.skillConfigId == skillConfigId }
            .sortedByDescending { it.startedAt }

    /**
     * 按 SkillConfig id 获取最近 N 条执行记录（按 startedAt 降序）。
     *
     * UI Skill 详情页调用，默认取最近 10 条（ADR-013 5.7）。
     *
     * @param skillConfigId 关联的 SkillConfig id
     * @param limit 返回记录数上限（默认 10）
     * @return 最近的 N 条执行记录（降序），不足则返回全部
     */
    fun getRecentBySkill(skillConfigId: Long, limit: Int = DEFAULT_RECENT_LIMIT): List<SkillExecutionRecord> {
        val all = getBySkill(skillConfigId)
        return if (all.size <= limit) all else all.take(limit)
    }

    /**
     * 按 SkillConfig id 删除全部执行记录（Skill 删除时级联清理）。
     *
     * @param skillConfigId 关联的 SkillConfig id
     * @return 删除的记录数
     */
    fun removeBySkill(skillConfigId: Long): Long {
        val records = getBySkill(skillConfigId)
        val ids = records.map { it.id }
        if (ids.isNotEmpty()) {
            // vararg spread 模式（仿 KnowledgeBaseRepository.remove，ObjectBox Box.remove 无 List 重载）
            box.remove(*ids.toLongArray())
        }
        return records.size.toLong()
    }

    /**
     * 删除指定 id 的执行记录。
     *
     * @param id SkillExecutionRecord id
     */
    fun remove(id: Long) {
        box.remove(id)
    }

    /**
     * 删除所有执行记录。
     */
    fun removeAll() {
        box.removeAll()
    }

    companion object {
        /** 默认最近记录数（ADR-013 5.7：UI 详情页展示最近 10 次）。 */
        internal const val DEFAULT_RECENT_LIMIT = 10
    }
}
