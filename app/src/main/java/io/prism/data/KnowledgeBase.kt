package io.prism.data

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

/**
 * 知识库分库实体 —— Prism 个人知识库的分库管理单元（US-015）。
 *
 * 每条记录对应一个用户自建的知识库（如「工作」「学习」「个人」），
 * [KnowledgeChunk] 通过 `knowledgeBaseId` 字段关联到本实体。
 *
 * **字段说明**（ADR-008 5.1）：
 * - [id] 主键
 * - [name] 库显示名称（如 "工作"）
 * - [createdAt] 创建时间戳（毫秒）
 *
 * **统计字段策略**：
 * 不持久化 `docs/chunks/indexed/citations` 等统计字段，运行时按
 * `knowledgeBaseId` 聚合查询 [KnowledgeChunk] 计算（ADR-008 5.1）。
 * 避免写入路径维护一致性成本。
 *
 * **默认库语义**（ADR-008 5.3）：
 * `knowledgeBaseId = 0L` 代表虚拟默认库，**不持久化为本表记录**。
 * 本表 `@Id` 自增从 1 开始，0L 不在分配范围。UI 层检索本表显示用户自建库，
 * 默认库作为「全部/未分类」入口单独处理。
 *
 * **关联策略**（ADR-008 5.2）：
 * 不使用 `@Relation`/`ToOne`/`ToMany`，采用扁平 `Long` 外键
 * （见 [KnowledgeChunk.knowledgeBaseId]）。理由：项目零关系注解先例 +
 * ObjectBox ToMany 已知副作用（GitHub objectbox-java#1065/#583、objectbox-go#25）。
 *
 * @see KnowledgeChunk
 * @see <a href="https://docs.objectbox.io/relations">ObjectBox Relations</a>
 */
@Entity
data class KnowledgeBase(
    @Id var id: Long = 0,
    var name: String,
    var createdAt: Long = System.currentTimeMillis()
)
