# US-015 知识库分库数据模型 源码考古报告（简化版）

> 由 code-archaeologist 子 Agent 生成，依 CLAUDE.md 第三节 3.1 简化版探查规范。
> 为 US-015「实现知识库分库数据模型」编码做准备，聚焦数据层现有模式与影响评估。

| 项目 | 内容 |
|---|---|
| 执行 Agent | code-archaeologist |
| 任务令牌 | TKN-US015-DATA-ARCH-001 |
| 考古日期 | 2026-08-07 |
| 考古目标 | `app/src/main/java/io/prism/data/`、`app/src/test/java/io/prism/data/`、`app/objectbox-models/default.json`、`app/build.gradle.kts` 及 KnowledgeChunk 引用方 |
| 考古模式 | 简化版探查（模块职责 / 关键依赖 / 风险点 / 入门路径） |

---

## 1. 数据层现状（模块职责）

Prism 数据层位于 `app/src/main/java/io/prism/data/`，采用 ObjectBox 5.4.2（ADR-001 / ADR-007）作为端侧主库。当前共 **3 个 `@Entity` 实体 + 2 个 Repository + 2 个类型转换器 + 2 个预设模板**，全部为扁平独立实体，**零 `@Relation`/`ToOne` 关系注解**。

### 1.1 实体与 Repository 清单

| 实体 | 文件 | 字段 | Repository | 模式特征 |
|---|---|---|---|---|
| `KnowledgeChunk` | [KnowledgeChunk.kt](../../../app/src/main/java/io/prism/data/KnowledgeChunk.kt) | `id/title/content/embedding` | **无**（测试直接用 `Box`） | 含 `@HnswIndex(384, COSINE)` 向量索引 |
| `ProviderConfig` | [ProviderConfig.kt](../../../app/src/main/java/io/prism/data/ProviderConfig.kt) | `id/name/baseUrl/apiKeyRef/models/headers/isActive/createdAt` | [ProviderConfigRepository.kt](../../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt) | 单激活不变式 + `runInTx` 事务 + 双 `StateFlow` |
| `McpServerConfig` | [McpServerConfig.kt](../../../app/src/main/java/io/prism/data/McpServerConfig.kt) | `id/name/serverType/transport/baseUrl/apiKeyRef/headers/isEnabled/createdAt` | [McpServerRepository.kt](../../../app/src/main/java/io/prism/data/McpServerRepository.kt) | 轻量 CRUD + 单 `StateFlow` |

辅助文件：[StringListConverter.kt](../../../app/src/main/java/io/prism/data/StringListConverter.kt)（`List<String>↔String`，换行分隔+转义）、[StringMapConverter.kt](../../../app/src/main/java/io/prism/data/StringMapConverter.kt)（`Map<String,String>↔String`，`key=value`+转义）、[ProviderPresets.kt](../../../app/src/main/java/io/prism/data/ProviderPresets.kt) / [McpServerPresets.kt](../../../app/src/main/java/io/prism/data/McpServerPresets.kt)（预设模板）。

### 1.2 数据层依赖图

```mermaid
graph LR
  subgraph 实体层
    KC[KnowledgeChunk<br/>@HnswIndex 384维]
    PC[ProviderConfig]
    MC[McpServerConfig]
  end
  subgraph Repository层
    PCR[ProviderConfigRepository<br/>runInTx 单激活]
    MCR[McpServerRepository<br/>轻量 CRUD]
  end
  subgraph 转换器
    SLC[StringListConverter]
    SMC[StringMapConverter]
  end
  BS[(BoxStore<br/>ObjectBox 5.4.2)]

  PC -. @Convert .-> SLC
  PC -. @Convert .-> SMC
  MC -. @Convert .-> SMC
  PCR --> PC
  MCR --> MC
  PCR --> BS
  MCR --> BS
  KC --> BS
  Note[KnowledgeChunk 无 Repository<br/>无 @Relation 关系]:::note
  classDef note fill:#fee,stroke:#c33;
```

**关键观察**：`KnowledgeChunk` 是三个实体中唯一没有 Repository 封装的，当前仅通过 `Box` 直接操作（见 [KnowledgeChunkCrudTest.kt:32](../../../app/src/test/java/io/prism/data/KnowledgeChunkCrudTest.kt)）。三个实体之间**无任何关系注解**，完全扁平独立。

---

## 2. KnowledgeChunk 实体结构分析

实体定义见 [KnowledgeChunk.kt:22-29](../../../app/src/main/java/io/prism/data/KnowledgeChunk.kt)：

```kotlin
@Entity
data class KnowledgeChunk(
    @Id var id: Long = 0,
    var title: String,
    var content: String,
    @HnswIndex(dimensions = 384, distanceType = VectorDistanceType.COSINE)
    var embedding: FloatArray? = null
)
```

要点：
- `@HnswIndex(dimensions = 384, COSINE)` 对应 all-MiniLM-L6-v2 ONNX INT8 量化向量（ADR-007 5.1/5.2），`embedding` 在文本入库阶段为 `null`，向量化后回填。
- schema 中 `embedding` 的 `type: 28, flags: 8`（见 [default.json:28-33](../../../app/objectbox-models/default.json)），是 ObjectBox 的向量属性类型 + HNSW 索引标记（`indexId: 1:2432317062331387289`）。
- 实体注释（[KnowledgeChunk.kt:16-18](../../../app/src/main/java/io/prism/data/KnowledgeChunk.kt)）已警示：`FloatArray` 导致 `data class` 自动生成的 `equals/hashCode` 用引用比较，关联行为规则 `BR-security-001`。
- **无 `createdAt` 字段**（与 ProviderConfig/McpServerConfig 不同），**无分库维度字段**。

---

## 3. 既有 Repository 模式分析

### 3.1 两种 Repository 模式对比

| 维度 | ProviderConfigRepository（复杂状态型） | McpServerRepository（轻量 CRUD 型） |
|---|---|---|
| 构造 | `boxStore.boxFor()` + 双 `MutableStateFlow` | `boxStore.boxFor()` + 单 `MutableStateFlow` |
| `save` | `boxStore.runInTx { 取消其他激活 + put }`（[L52-67](../../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt)） | `box.put` 直接写（[L40-44](../../../app/src/main/java/io/prism/data/McpServerRepository.kt)） |
| 事务 | 有（单激活不变式兜底，`BR-concurrency-001`） | 无 |
| 状态切换 | `setActive`/`clearActive`（事务） | `setEnabled`（单条 put） |
| `getAll` | `box.all.sortedBy { it.createdAt }` | 同 |
| `findByName` | `box.all.find { it.name == name }` | 同 |
| `createFromPreset` | 有 | 有 |
| `refreshFlows` | 列表 + 激活两个 Flow | 仅列表 Flow |

### 3.2 事务先例（US-015 级联删除可直接借鉴）

[ProviderConfigRepository.kt:52-67](../../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt) 的 `save` 是项目唯一 `runInTx` 先例，模式为「先遍历取消其他激活 → 再 put 目标」，整段包在 `boxStore.runInTx {}` 内保证原子性。**全仓搜索 `runInTx`/`callInTx` 仅此一处命中**。US-015 的级联删除应直接复用此模式：`runInTx { 查目标库的 chunks → 逐条 remove → remove 库本身 }`。

### 3.3 类型转换器模式（若分库元数据需存列表/映射）

[StringListConverter.kt](../../../app/src/main/java/io/prism/data/StringListConverter.kt) / [StringMapConverter.kt](../../../app/src/main/java/io/prism/data/StringMapConverter.kt) 已实现单次扫描反转义（修复了链式 `replace` 的 bug，对应测试见 [ProviderConfigRepositoryTest.kt:355-402](../../../app/src/test/java/io/prism/data/ProviderConfigRepositoryTest.kt)）。若 `KnowledgeBase` 实体需要存 `tags: List<String>` 或 `metadata: Map<String,String>`，可直接复用这两个转换器，无需新建。

---

## 4. ObjectBox schema 迁移机制

### 4.1 default.json 结构

[default.json](../../../app/objectbox-models/default.json) 是 ObjectBox Gradle plugin（`alias(libs.plugins.objectbox)`，见 [build.gradle.kts:7](../../../app/build.gradle.kts)）在编译期生成的 schema 快照（`modelVersion: 5`）。当前状态：

- `entities`：3 个（KnowledgeChunk / ProviderConfig / McpServerConfig）
- `retiredEntityUids` / `retiredPropertyUids` / `retiredRelationUids` 均为 `[]` —— **项目从未发生过字段删除/重命名/实体删除迁移**。
- `lastEntityId: 3:...`、`lastIndexId: 1:...` —— 下一个新实体将自动分配 `4:...`，新索引 `2:...`。

### 4.2 加字段迁移路径（US-015 核心）

ObjectBox 对**新增字段**属兼容性变更：下次构建时 plugin 自动为新字段分配 `property id` 并写入 `default.json`，旧记录读出新字段为默认值（`Long→0`、`String→空串/null`、`Boolean→false`）。**无需手动迁移脚本**。

| US-015 可能变更 | 迁移性质 | 对既有数据影响 | 是否需手动迁移 |
|---|---|---|---|
| `KnowledgeChunk` 加 `collectionId: Long` | 兼容性（新增字段） | 旧 chunk `collectionId = 0` | 否 |
| `KnowledgeChunk` 加 `collectionId: String` | 兼容性（新增字段） | 旧 chunk `collectionId = ""` | 否 |
| 新增 `KnowledgeBase` 实体 | 兼容性（新增实体） | 无影响（空表） | 否 |
| 给 `collectionId` 加 `@Index` | 索引重建 | 首次启动重建索引，轻微开销 | 否 |
| 删除/重命名既有字段 | 破坏性 | 需 `retiredPropertyUids` | **是**（US-015 不应触碰） |

**结论**：US-015 在 `KnowledgeChunk` 加分库字段 + 新增 `KnowledgeBase` 实体，均为兼容性变更，schema 自动迁移，对既有数据无破坏。**但需在业务层约定「旧 chunk（`collectionId=0` 或 `""`）归属默认库」的语义**，否则历史数据将成孤儿。

---

## 5. 既有数据层测试模式

测试位于 `app/src/test/java/io/prism/data/`，共 10 个文件（5 个 KnowledgeChunk 相关 + 5 个 Provider/MCP 相关）。

### 5.1 BoxStore 搭建模式（统一）

所有数据层测试统一用「临时目录 + 纯 JVM ObjectBox」模式，无需 Android 设备：

```kotlin
@Before
fun setUp() {
    tempDir = kotlin.io.path.createTempDirectory(prefix = "objectbox-test-").toFile()
    boxStore = MyObjectBox.builder().directory(tempDir).build()
    box = boxStore.boxFor(KnowledgeChunk::class.java)
}
@After
fun tearDown() {
    boxStore.close()
    tempDir.deleteRecursively()
}
```

见 [KnowledgeChunkCrudTest.kt:28-39](../../../app/src/test/java/io/prism/data/KnowledgeChunkCrudTest.kt)、[ProviderConfigRepositoryTest.kt:32-43](../../../app/src/test/java/io/prism/data/ProviderConfigRepositoryTest.kt)。US-015 新测试应照搬此模式（`MyObjectBox` 由 ObjectBox plugin 自动生成）。

### 5.2 测试分层模式

| 层 | 文件 | 覆盖内容 |
|---|---|---|
| 基础 CRUD | [KnowledgeChunkCrudTest.kt](../../../app/src/test/java/io/prism/data/KnowledgeChunkCrudTest.kt) | put 分配 id / get 往返 / remove / 更新 / 批量 / 空向量 |
| 边缘场景 | [KnowledgeChunkEdgeCaseTest.kt](../../../app/src/test/java/io/prism/data/KnowledgeChunkEdgeCaseTest.kt) | 空字符串 / 超长输入 / 384 维真实向量 / 1000 条批量 / 重启持久化 / 幂等删除 / 浮点极值 |
| 向量检索 | [KnowledgeChunkVectorSearchTest.kt](../../../app/src/test/java/io/prism/data/KnowledgeChunkVectorSearchTest.kt) | `nearestNeighbors` top-k / null 向量排除 / 空库 / k 限制 |
| 检索边缘 | [KnowledgeChunkVectorSearchEdgeCaseTest.kt](../../../app/src/test/java/io/prism/data/KnowledgeChunkVectorSearchTest.kt) | 检索边界补充 |
| 性能基准 | [KnowledgeChunkPerformanceBenchmark.kt](../../../app/src/test/java/io/prism/data/KnowledgeChunkPerformanceBenchmark.kt) | p50/p95/p99 延迟，默认跳过（`-PignorePerformanceTests=false` 启用） |
| Repository | [ProviderConfigRepositoryTest.kt](../../../app/src/test/java/io/prism/data/ProviderConfigRepositoryTest.kt) | 持久化 / CRUD / 预设 / 激活机制 / 转换器往返 / Flow 订阅 |

### 5.3 向量检索查询模式（US-015 跨库隔离需叠加过滤）

[KnowledgeChunkVectorSearchTest.kt:61-64](../../../app/src/test/java/io/prism/data/KnowledgeChunkVectorSearchTest.kt) 的检索模式：

```kotlin
val query = box.query(
    KnowledgeChunk_.embedding.nearestNeighbors(queryVector, 3)
).build()
val matches = query.findWithScores()
```

**US-015 风险点**：分库后，向量检索必须叠加 `collectionId` 过滤，避免跨库召回污染。ObjectBox 支持 `query().equal(collectionId, ...).nearestNeighbors(...)` 链式条件，但需测试验证「HNSW 索引 + 普通字段过滤」的联合查询行为（是否先过滤再近邻、性能是否退化）。此为高认知负荷点。

---

## 6. KnowledgeChunk 引用方影响评估

### 6.1 引用方清单（全仓搜索结果）

| 范围 | 引用方 | 说明 |
|---|---|---|
| `app/src/main` | 仅 [KnowledgeChunk.kt](../../../app/src/main/java/io/prism/data/KnowledgeChunk.kt) 自身 | **无任何业务模块（Service/ViewModel/Embedder/Chunker/Parser）引用 KnowledgeChunk** |
| `app/src/test` | 5 个测试文件（见 §5.2） | 全部直接用 `Box<KnowledgeChunk>`，无 Repository 中间层 |

**结论**：`KnowledgeChunk` 当前**零业务依赖**。M3 RAG 流水线（[embedding/](../../../app/src/main/java/io/prism/embedding) 目录：Embedder/OnnxEmbedder/BertWordPieceTokenizer；[document/](../../../app/src/main/java/io/prism/document) 目录：Chunker/DocumentParser）目前**未与持久化层对接**，即「解析→分片→向量化」的产出尚未写入 `KnowledgeChunk`。

### 6.2 UI 层现状（分库意图来源）

[KnowledgeBaseScreen.kt](../../../app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt) 已存在知识库屏幕，但**纯 Mock**：

- `KbSpace("工作"/"学习"/"个人")` 硬编码三个分库（[L78-82](../../../app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt)），字段含 `name/docs/chunks/updated/indexed/citations/glow`。
- `RecentDoc` 硬编码最近导入（[L94-97](../../../app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt)）。
- 导入弹层 `ImportSheet` 的「目标知识库」用 `ImportTarget("工作"/"学习"/"个人")` 硬编码（[L199-203](../../../app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt)），`onClick = {}` 空实现。
- **未 import `io.prism.data.KnowledgeChunk`，未接数据层**。

这正是 US-015「知识库分库数据模型」的需求来源：为这些 Mock 的「分库」建立真实持久化模型。

### 6.3 影响半径

US-015 在 `KnowledgeChunk` 加字段 + 新增 `KnowledgeBase` 实体：

- **main 影响面 = 0**（无业务模块引用 KnowledgeChunk，加字段不破坏任何调用方）。
- **test 影响面**：5 个 KnowledgeChunk 测试文件的构造调用 `KnowledgeChunk(title=..., content=...)` 因新字段有默认值，**编译兼容、无需改测试**；但若新增 `KnowledgeBase` 实体，`MyObjectBox` 会重新生成，所有测试的 `MyObjectBox.builder()` 调用不变（向后兼容）。
- **schema 影响面**：`default.json` 自动更新，无需手工干预。

---

## 7. US-015 风险清单

| 风险 | 等级 | 证据 / 说明 | 缓解建议 |
|---|---|---|---|
| R1 既有数据成孤儿 | 中 | 旧 chunk 无 `collectionId`，加字段后默认 `0`/`""`，若不约定默认库语义则无法归属 | 业务层约定 `collectionId=0`（或 `"default"`）= 默认库；首次启动迁移逻辑把旧 chunk 归入默认库 |
| R2 级联删除原子性 | 中 | 项目零 `@Relation` 先例，级联需手动 `runInTx`；`KnowledgeBaseRepository.remove()` 须「查 chunk → 删 chunk → 删 base」 | 仿 [ProviderConfigRepository.kt:52-67](../../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt) 的 `runInTx` 模式；测试覆盖「删库后其下 chunk 全删、他库 chunk 不受影响」 |
| R3 向量检索跨库污染 | 中 | [KnowledgeChunkVectorSearchTest.kt:61-64](../../../app/src/test/java/io/prism/data/KnowledgeChunkVectorSearchTest.kt) 的 `nearestNeighbors` 未叠加 `collectionId` 过滤 | US-015 数据模型层保证 `collectionId` 可过滤；检索层叠加 `equal(collectionId)` 条件；测试验证「HNSW + 过滤」联合查询行为（数据模型层先保证字段存在，检索隔离可能属后续 US） |
| R4 `@Index` 零先例 | 低 | 全仓搜索 `@Index` 无命中，分库过滤查询性能未验证 | 数据量小可先不加索引；若分库查询变热点再加 `@Index`（ObjectBox 自动重建） |
| R5 schema 迁移 | 低 | [default.json](../../../app/objectbox-models/default.json) `retiredPropertyUids=[]`，新增字段/实体为兼容性变更 | 无需手动迁移；勿删除/重命名既有字段 |
| R6 `KnowledgeChunk` 无 Repository | 低 | 当前测试直接用 `Box`，无 Repository 中间层 | US-015 若新建 `KnowledgeChunkRepository` 应参照 [McpServerRepository.kt](../../../app/src/main/java/io/prism/data/McpServerRepository.kt)；旧测试可继续直接用 `Box`（向后兼容） |
| R7 分库元数据设计未定 | 中 | 全仓无 US-015 文档定义（PRD/ADR 均未覆盖「分库」），`KnowledgeBaseScreen` Mock 字段（docs/chunks/indexed/citations）暗示需库级统计字段 | 编码前主 Agent 应补 ADR 锁定 `KnowledgeBase` 字段集与级联策略（CLAUDE.md 第十七节 17.1） |

---

## 8. 入门路径与编码建议

### 8.1 推荐代码阅读顺序

1. **掌握轻量实体 + Repository 模式**：[McpServerConfig.kt](../../../app/src/main/java/io/prism/data/McpServerConfig.kt) + [McpServerRepository.kt](../../../app/src/main/java/io/prism/data/McpServerRepository.kt)（最贴近 US-015 的 `KnowledgeBase` 实体 + Repository 形态）。
2. **掌握事务级联模式**：[ProviderConfigRepository.kt:52-67](../../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt)（`runInTx` 唯一先例，用于级联删除）。
3. **掌握向量检索模式**：[KnowledgeChunkVectorSearchTest.kt:49-86](../../../app/src/test/java/io/prism/data/KnowledgeChunkVectorSearchTest.kt)（为叠加 `collectionId` 过滤做准备）。
4. **掌握 schema 机制**：[default.json](../../../app/objectbox-models/default.json)（理解自动迁移）+ [build.gradle.kts:7](../../../app/build.gradle.kts)（`objectbox` plugin）。
5. **掌握测试搭建**：[KnowledgeChunkCrudTest.kt:28-39](../../../app/src/test/java/io/prism/data/KnowledgeChunkCrudTest.kt)（临时目录 + `MyObjectBox.builder()`）。
6. **理解分库意图**：[KnowledgeBaseScreen.kt:57-97](../../../app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt)（Mock 字段暗示库级元数据需求）+ [ADR-007](../../decisions/ADR-007-m3-rag-tech-stack.md)（RAG 技术栈决策）。

### 8.2 US-015 编码建议

- **实体设计**：新建 `KnowledgeBase` 实体，参照 `McpServerConfig` 扁平模式（`id/name/createdAt` + 必要统计字段或运行时计算）；`KnowledgeChunk` 加 `collectionId: Long` 字段（参照 `McpServerConfig` 的 `isEnabled` 默认值写法）。**避免引入 `@Relation`/`ToOne`**，保持与既有三个实体一致的扁平风格。
- **Repository 设计**：新建 `KnowledgeBaseRepository`（参照 `McpServerRepository` 轻量 CRUD）+ `removeWithCascade(id)` 方法用 `runInTx` 级联删除（参照 `ProviderConfigRepository.save` 事务模式）。可选新建 `KnowledgeChunkRepository`（按 `collectionId` 过滤查询）。
- **类型转换器**：若 `KnowledgeBase` 需 `tags`/`metadata`，复用 [StringListConverter.kt](../../../app/src/main/java/io/prism/data/StringListConverter.kt) / [StringMapConverter.kt](../../../app/src/main/java/io/prism/data/StringMapConverter.kt)。
- **测试设计**：照搬 `KnowledgeChunkCrudTest` 的 BoxStore 搭建；新增「级联删除原子性」「旧 chunk 归属默认库」「跨库查询隔离」三类用例；参照 `KnowledgeChunkEdgeCaseTest` 补「删库后检索不返回孤儿 chunk」边界。
- **文档前置**：编码前主 Agent 应补 ADR 锁定 `KnowledgeBase` 字段集 + 级联策略 + 默认库语义（CLAUDE.md 第十七节 17.1，属「修改现有架构的模块划分或核心接口」）。

---

## 9. 假设验证记录

| 假设 | 验证方式 | 结果 | 证据 |
|---|---|---|---|
| H1 US-015 意图 = 为 KnowledgeChunk 建立分库维度 | 静态：`KnowledgeBaseScreen` Mock 三库 + ADR-007 无分库 + 全仓无 US-015 文档 | **强推断通过**（待 PRD/ADR 补充确认） | [KnowledgeBaseScreen.kt:78-82](../../../app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt)、[ADR-007 5.1](../../decisions/ADR-007-m3-rag-tech-stack.md) |
| H2 新增字段/实体 schema 自动迁移，无需手动脚本 | 静态：`default.json` `retiredPropertyUids=[]` + ObjectBox 迁移规则 | **通过** | [default.json:147-150](../../../app/objectbox-models/default.json) |
| H3 级联删除应选手动 `runInTx` 而非 `@Relation` | 静态：全仓零 `@Relation`/`ToOne` 先例 + `runInTx` 唯一先例 | **通过** | [ProviderConfigRepository.kt:52-67](../../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt) |
| H4 KnowledgeChunk 加字段对 main 零影响 | 静态：全仓搜索 `KnowledgeChunk` 在 main 仅自身命中 | **通过** | §6.1 引用方清单 |
| H5 向量检索跨库需叠加 `collectionId` 过滤 | 静态：`nearestNeighbors` 当前无过滤条件 | **待动态验证**（数据模型层先保证字段，检索隔离属后续 US） | [KnowledgeChunkVectorSearchTest.kt:61-64](../../../app/src/test/java/io/prism/data/KnowledgeChunkVectorSearchTest.kt) |
| H6 旧测试因新字段默认值而编译兼容 | 静态：`KnowledgeChunk(title=..., content=...)` 构造不依赖新字段 | **通过**（编译期推断，建议 `ac-verifier` 实测） | [KnowledgeChunkCrudTest.kt:43](../../../app/src/test/java/io/prism/data/KnowledgeChunkCrudTest.kt) |

---

## 10. 结论与建议

1. **数据层现状清晰**：3 实体 + 2 Repository 扁平独立架构，`KnowledgeChunk` 是唯一无 Repository 的实体，零业务依赖，US-015 改动影响面极小（main=0，test 编译兼容）。
2. **schema 迁移零负担**：新增 `collectionId` 字段 + `KnowledgeBase` 实体均为兼容性变更，`default.json` 自动更新，对既有数据无破坏。
3. **核心风险在业务语义而非技术**：R1（旧数据孤儿）、R2（级联原子性）、R3（跨库检索隔离）均需业务层约定 + 测试覆盖，而非 schema 层干预。
4. **前置动作**：编码前主 Agent 必须补 ADR 锁定 `KnowledgeBase` 字段集、级联策略、默认库语义（R7），否则编码会因设计未定而返工。
5. **模式参照明确**：`KnowledgeBase` 实体 + Repository 参照 `McpServerConfig`/`McpServerRepository`；级联删除参照 `ProviderConfigRepository.runInTx`；测试搭建照搬 `KnowledgeChunkCrudTest` 临时目录模式。

> 本报告为简化版探查，未含完整四阶段考古的动态逆向（Git 热点 / 运行时插桩）。如需深入验证 H5（HNSW + 过滤联合查询性能），建议在编码后由 `ac-verifier` 设计专项性能测试。
