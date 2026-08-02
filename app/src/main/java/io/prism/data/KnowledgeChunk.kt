package io.prism.data

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

/**
 * 知识库分块实体 —— Prism 个人知识库的核心持久化单元。
 *
 * 每条记录对应一个文档分块，供端侧 RAG 检索使用。
 * - [embedding] 存储 all-MiniLM-L6-v2 ONNX 量化的向量（384 维），
 *   在向量索引建立前为 null（仅文本入库阶段）。
 * - US-002 仅实现基础 CRUD；向量检索（HNSW 索引）在后续 RAG 用户故事中添加。
 *
 * 注意：data class 包含 [FloatArray] 字段，Kotlin 自动生成的 equals/hashCode
 * 使用引用比较（而非内容比较）。当前不影响 ObjectBox CRUD（ObjectBox 自有序列化），
 * 但若后续业务逻辑需要按内容比较实体，需覆盖 equals/hashCode（BR-security-001）。
 *
 * @see <a href="https://docs.objectbox.io/entity-annotations">ObjectBox Entity Annotations</a>
 */
@Entity
data class KnowledgeChunk(
    @Id var id: Long = 0,
    var title: String,
    var content: String,
    var embedding: FloatArray? = null
)
