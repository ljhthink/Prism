# ADR-008: M3 知识库分库数据模型（US-015）

> 从 `docs/templates/adr-template.md` 复制新建，依 CLAUDE.md 第十七节。
> 本 ADR 记录 M3「个人知识库 RAG」分库数据模型的设计决策：KnowledgeBase 实体结构、KnowledgeChunk 关联字段、级联删除策略、默认库语义。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-07（Proposed → Accepted 2026-08-09，M3 里程碑审计 TKN-M3-MILESTONE-AUDIT-001 同步） |
| 决策者 | 主 Agent（基于 code-archaeologist 考古报告 + web-access 调研 + 用户确认） |
| 关联文档 | [ADR-001](ADR-001-prism-tech-stack.md) / [ADR-007](ADR-007-m3-rag-tech-stack.md) / [PRD.md](../PRD.md) US-003 / [prd.json](../../prd.json) US-015 |
| 上游调研 | [US-015 数据层源码考古报告](../reports/2026-08-07-us015-data-archaeology.md) + web-access ObjectBox 级联删除最佳实践调研（2026-08-07） |
| 风险等级 | P2 跨模块（新增实体 + 既有实体 KnowledgeChunk 加字段，schema 兼容性变更） |

## 背景（Context）

PRD US-003 验收 5「分库管理」要求知识库支持按库组织文档（如工作/学习/个人）。US-015 落地分库数据模型，为后续 US-016 摄入管线 / US-017 向量检索 / US-018 知识库管理 UI / US-019 RAG 对话集成提供持久化基础。

当前数据层（考古报告 §1）：

- `KnowledgeChunk` 是唯一无 Repository 封装的实体，零业务依赖，仅 5 个测试文件直接操作 `Box`。
- 3 个既有实体（KnowledgeChunk / ProviderConfig / McpServerConfig）完全扁平独立，**零 `@Relation`/`ToOne`/`ToMany` 关系注解先例**。
- `runInTx` 事务仅 1 处先例（[ProviderConfigRepository.kt:52-67](../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt) 的单激活不变式）。
- ObjectBox schema `retiredPropertyUids=[]`，项目从未发生破坏性迁移。

设计未决问题（考古报告 R7）：

1. KnowledgeBase 实体字段集（是否含统计字段如 docs/chunks/indexed）？
2. KnowledgeChunk 与 KnowledgeBase 的关联方式（`@Relation` vs 扁平外键）？
3. 级联删除策略（`@Relation` 自动级联 vs `runInTx` 手动级联）？
4. 默认库语义（旧 chunk 无 `knowledgeBaseId` 字段，加字段后默认值归属）？

本 ADR 锁定以上 4 项决策，作为 US-015 编码前置。

## 决策（Decision）

### 5.1 KnowledgeBase 实体：最小字段集 `id/name/createdAt`

```kotlin
@Entity
data class KnowledgeBase(
    @Id var id: Long = 0,
    var name: String,
    var createdAt: Long = System.currentTimeMillis()
)
```

**理由**：

- 与 [McpServerConfig.kt](../../app/src/main/java/io/prism/data/McpServerConfig.kt) 扁平实体风格一致。
- 统计字段（docs/chunks/indexed/citations）运行时按 `knowledgeBaseId` 聚合查询计算，避免写入路径维护一致性成本（每入库/删除一个 chunk 都需更新库级计数器，多步骤事务易破坏不变式）。
- UI Mock（[KnowledgeBaseScreen.kt:78-82](../../app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt)）的 `docs/chunks/updated/indexed/citations/glow` 字段属视图层装饰，运行时计算即可满足。

### 5.2 KnowledgeChunk 关联字段：扁平 `knowledgeBaseId: Long = 0L`

```kotlin
@Entity
data class KnowledgeChunk(
    @Id var id: Long = 0,
    var title: String,
    var content: String,
    @HnswIndex(dimensions = 384, distanceType = VectorDistanceType.COSINE)
    var embedding: FloatArray? = null,
    var knowledgeBaseId: Long = 0L  // US-015 新增：0L = 虚拟默认库
)
```

**理由**：

- **不引入 `@Relation`/`ToOne`/`ToMany`**。web-access 调研发现 ObjectBox `ToMany` 有三类已知副作用：
  - GitHub Issue [objectbox-java#1065](https://github.com/objectbox/objectbox-java/issues/1065)：Backlink ToMany 删除顺序敏感，先 `box.remove` 再 `applyChangesToDb()` 会导致实体「复活」。
  - GitHub Issue [objectbox-java#583](https://github.com/objectbox/objectbox-java/issues/583)：`applyChangesToDb()` 仅创建/销毁关系本身，**不会自动 remove 或 update 已存在的关联实体**，必须手动用 box 删除。
  - GitHub Issue [objectbox-go#25](https://github.com/objectbox/objectbox-go/issues/25)：删除有 ToMany 关系的父表在 10k+ 对象时触发 ObjectBox「挂起」bug。
- 扁平 Long 外键与项目既有 3 实体风格一致（零关系注解先例），降低认知负荷。
- `Long` 而非 `String`：性能更好（ObjectBox 原生 8 字节 vs 序列化字符串），默认值 `0L` 语义清晰。
- `@HnswIndex` 字段不动，HNSW 向量索引完全不受 schema 变更影响（Index 增删不影响 schema 兼容性）。

### 5.3 默认库语义：`knowledgeBaseId = 0L` 代表虚拟默认库，不写入 KnowledgeBase 表

**理由**：

- 旧 KnowledgeChunk 记录无 `knowledgeBaseId` 字段，加字段后 ObjectBox 自动填默认值 `0L`（考古报告 §4.2 兼容性变更），**旧数据自动归属默认库，无孤儿数据风险**（考古 R1 缓解）。
- 默认库不持久化为 KnowledgeBase 记录：
  - 避免与「KnowledgeBase 表 id 从 1 开始自增」冲突（0L 不在 ObjectBox `@Id` 分配范围）；
  - 避免应用首次启动时需写迁移逻辑创建默认库记录；
  - 「虚拟库」语义清晰：UI 层检索 `KnowledgeBase` 表显示用户自建库，默认库作为「全部/未分类」入口单独处理。
- 检索时 `knowledgeBaseId = 0L` 等价于「默认库」，`knowledgeBaseId > 0` 等价于「指定自建库」。

### 5.4 级联删除：`runInTx` 手动级联，仿 `ProviderConfigRepository.save` 事务模式

```kotlin
fun remove(id: Long) {
    require(id >= 0) { "KnowledgeBase id 不能为负数（id=$id）" }
    require(id != DEFAULT_KB_ID) {
        "禁止删除虚拟默认库（id=$DEFAULT_KB_ID）。默认库不持久化为 KnowledgeBase 记录。"
    }
    boxStore.runInTx {
        // 1. 先删除关联 KnowledgeChunk（规避 HNSW #1209：findIds + Box.remove 而非 Query.remove）
        val chunkIds = chunkBox.query()
            .equal(KnowledgeChunk_.knowledgeBaseId, id)
            .build()
            .use { it.findIds() }
        if (chunkIds.isNotEmpty()) {
            chunkBox.remove(*chunkIds)
        }
        // 2. 再删除 KnowledgeBase 本身
        box.remove(id)
    }
    refreshFlows()
}
```

**理由**：

- 考古报告 §3.2 已确认 `runInTx` 是项目唯一事务先例，模式成熟。
- ObjectBox 官方 Data Modeling 指南明确推荐：「Use a transaction for logical groups of writes... Batch put/remove within a single transaction where it makes sense. Handle errors inside the transaction and fail fast.」
- `@Relation` 不提供自动级联删除（GitHub Issue #583 确认），手动级联是 ObjectBox 的必然选择。
- 事务保证原子性：若 chunk 删除中途异常，事务回滚，不会留下「库已删但 chunk 残留」的不一致状态（BR-concurrency-001 适用）。
- **禁止删除默认库**（`id = 0L`）：`remove(0L)` 应在入口校验拒绝，因默认库是虚拟库不持久化。此约束由 Repository 层强制。
- **负数 id 纵深防御**（G-04）：`require(id >= 0)` 拒绝负数 id，避免 `box.get(-1)` 抛底层异常或 `box.remove(-1)` no-op 的语义模糊。
- **HNSW 索引删除策略**（规避 objectbox-java#1209）：KnowledgeChunk 有 `@HnswIndex(384, COSINE)` 向量索引，`Query.remove()`（nativeRemove 路径）在 HNSW 索引下可能抛 `IllegalStateException: Vector is missing for neighbor to repair`（已知 bug #1209，报告于 4.2.0，截至 5.4.2 未公开确认修复）。本方法改用 `Query.findIds()` 查 id + `Box.remove(ids)` 走 Box native 路径删除，规避该 bug。同时 `findIds()` 后用 `use {}` 关闭 Query 释放 native 句柄（G-02，符合 ObjectBox 官方 Query javadoc 要求）。`removeAll()` 同此策略。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| `@Relation` ToMany/ToOne 关系注解 | 面向对象语义、自动变更跟踪 | 1) 项目零先例，引入新概念增加认知负荷；2) GitHub #1065/#583/#25 已知副作用：删除顺序敏感、不自动 remove 关联实体、10k+ 性能问题；3) `applyChangesToDb()` 语义复杂，级联仍需手动 box.remove |
| KnowledgeBase 含统计字段（docs/chunks/indexed） | UI 渲染快，无需聚合查询 | 1) 写入路径维护一致性成本高（每入库/删除 chunk 需更新计数器）；2) 多步骤事务易破坏不变式；3) 统计字段属视图层装饰，运行时聚合即可 |
| `knowledgeBaseId: String` 默认 `"default"` | 语义自描述 | 1) 性能不如 Long（ObjectBox 原生 8 字节 vs 序列化）；2) 默认值 `"default"` 与 KnowledgeBase 表 name 字段命名空间冲突；3) 旧数据迁移需手动写 `"default"` 字符串 |
| 默认库写入 KnowledgeBase 表（id=0 或固定 id=1） | 默认库可被 CRUD | 1) ObjectBox `@Id` 自增从 1 开始，id=0 不在分配范围；2) 重启后默认库记录可能与 id 分配冲突；3) 与「0L 虚拟默认库」语义重复；4) 默认库不应被用户删除/重命名，无需 CRUD |
| 级联删除用 `box.removeAll()` 然后重建 | 实现简单 | 1) 非原子操作；2) 删除范围错误（会删其他库的 chunk）；3) 违反 BR-concurrency-001 |

## 后果（Consequences）

- 正面后果：
  - KnowledgeBase 实体极简，写入路径单一（仅 id/name/createdAt），无一致性维护成本
  - 扁平外键避免 `@Relation` 副作用，与项目既有 3 实体风格统一
  - 默认库 0L 语义使旧 KnowledgeChunk 数据零迁移自动归属，无孤儿风险
  - `runInTx` 级联删除保证原子性，事务短小符合官方建议
  - schema 变更属兼容性（新增字段 + 新增实体），`default.json` 自动更新，无需手动迁移脚本
- 负面后果 / 代价：
  - 统计字段（docs/chunks）需运行时聚合查询，UI 列表页可能需额外索引优化（数据量大时）
  - 默认库不持久化，UI 层需特殊处理「虚拟默认库」入口（与 KnowledgeBase 表记录区分）
  - 扁平外键无 DB 层引用完整性约束，依赖 Repository 层保证（级联删除必须经 Repository，禁止直接 `box.remove(kbId)`）
- 需要同步更新的文档或代码：
  - `KnowledgeChunk.kt`：新增 `knowledgeBaseId: Long = 0L` 字段
  - 新增 `KnowledgeBase.kt` 实体
  - 新增 `KnowledgeBaseRepository.kt`（CRUD + `runInTx` 级联删除 + 默认库删除拒绝）
  - 新增 `KnowledgeBaseRepositoryTest.kt`（CRUD + 级联原子性 + 旧数据归属默认库 + 默认库删除拒绝）
  - `app/objectbox-models/default.json`：ObjectBox plugin 编译期自动更新
  - `docs/decisions/README.md` / `README.md`：ADR 索引同步
  - `prd.json`：US-015 `passes=true` + notes 补 ADR-008 引用

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 旧 KnowledgeChunk 加字段后成孤儿 | 中 | `knowledgeBaseId: Long = 0L` 默认值使旧数据自动归属虚拟默认库（5.3） |
| 级联删除原子性破坏 | 中 | `runInTx` 事务保证全成功或全回滚（5.4，BR-concurrency-001） |
| 默认库 0L 被误删 | 低 | Repository.remove(id) 入口校验 `require(id != 0L)`，拒绝删除默认库（5.4） |
| 向量检索跨库污染 | 中 | 数据模型层已保证 `knowledgeBaseId` 字段存在；US-017 检索模块叠加 `equal(knowledgeBaseId)` 过滤条件 |
| 统计字段运行时聚合性能 | 低 | 首期数据量小（4GB 低端机限制库容量），US-018 UI 层按需聚合；若变热点再加 `@Index` |
| schema 迁移失败 | 低 | ObjectBox 官方明确新增字段/实体为自动迁移；`default.json` `retiredPropertyUids=[]` 无破坏性变更历史 |

## 参考

- [US-015 数据层源码考古报告](../reports/2026-08-07-us015-data-archaeology.md)
- [ObjectBox Data Model Updates](https://docs.objectbox.io/advanced/data-model-updates)
- [ObjectBox Data Modeling for Offline-First Apps](https://objectbox.io/dev-how-to/guides/data-modeling-offline-first/)
- [ObjectBox Relations（ToOne/ToMany）](https://docs.objectbox.io/relations)
- [GitHub objectbox-java#1065 - Backlink ToMany reappearing](https://github.com/objectbox/objectbox-java/issues/1065)
- [GitHub objectbox-java#583 - ToMany clear and add behaviour](https://github.com/objectbox/objectbox-java/issues/583)
- [GitHub objectbox-go#25 - Cascade delete hang](https://github.com/objectbox/objectbox-go/issues/25)
- [ADR-001](ADR-001-prism-tech-stack.md)：技术栈锁定 / ObjectBox 主库
- [ADR-007](ADR-007-m3-rag-tech-stack.md)：M3 RAG 技术栈 / KnowledgeChunk 向量索引
