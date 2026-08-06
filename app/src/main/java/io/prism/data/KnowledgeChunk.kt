package io.prism.data

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.VectorDistanceType

/**
 * 知识库分块实体 —— Prism 个人知识库的核心持久化单元。
 *
 * 每条记录对应一个文档分块，供端侧 RAG 检索使用。
 * - [embedding] 存储 all-MiniLM-L6-v2 ONNX 量化的向量（384 维），
 *   在向量索引建立前为 null（仅文本入库阶段）。
 * - [knowledgeBaseId] 关联所属知识库（US-015 新增，ADR-008 5.2/5.3）：
 *   `0L` = 虚拟默认库（旧数据加字段后自动归属，无孤儿风险），
 *   `>0` = 用户自建库的 [KnowledgeBase.id]。
 *   扁平 Long 外键而非 `@Relation`，规避 ToMany 副作用
 *   （GitHub objectbox-java#1065/#583、objectbox-go#25）。
 * - US-002 仅实现基础 CRUD；US-011 起为 embedding 建立 HNSW 向量索引（ADR-007 5.1）。
 *
 * 注意：data class 包含 [FloatArray] 字段，Kotlin 自动生成的 equals/hashCode
 * 使用引用比较（而非内容比较）。当前不影响 ObjectBox CRUD（ObjectBox 自有序列化），
 * 但若后续业务逻辑需要按内容比较实体，需覆盖 equals/hashCode（BR-security-001）。
 *
 * @see KnowledgeBase
 * @see <a href="https://docs.objectbox.io/entity-annotations">ObjectBox Entity Annotations</a>
 */
@Entity
data class KnowledgeChunk(
    @Id var id: Long = 0,
    var title: String,
    var content: String,
    @HnswIndex(dimensions = 384, distanceType = VectorDistanceType.COSINE)
    var embedding: FloatArray? = null,
    var knowledgeBaseId: Long = 0L
)
