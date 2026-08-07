# ADR-010: M3 向量检索（US-017）

> 从 `docs/templates/adr-template.md` 复制新建，依 CLAUDE.md 第十七节。
> 本 ADR 记录 M3「个人知识库 RAG」向量检索模块的设计决策：检索方法归属、相似度转换、分库检索策略、kbId 语义、k 默认值、维度校验、Query 资源管理、结果结构、title 解析。

| 项目 | 内容 |
| --- | --- |
| 状态 | Proposed |
| 日期 | 2026-08-07 |
| 决策者 | 主 Agent（基于 code-archaeologist 考古报告 TKN-US017-ARCH-001 + web-access 调研 + sequential-thinking 推演 + 探针测试实证） |
| 关联文档 | [ADR-007](ADR-007-m3-rag-tech-stack.md) 5.4 / [ADR-008](ADR-008-m3-knowledgebase-model.md) 5.2/5.3 / [ADR-009](ADR-009-m3-ingestion-pipeline.md) 5.2 / [PRD.md](../PRD.md) US-003 / [prd.json](../../prd.json) US-017 |
| 上游调研 | [US-017 检索源码考古报告](../reports/2026-08-07-us017-retrieval-archaeology.md) + web-access ObjectBox nearestNeighbors+equal 组合调研（2026-08-07） + 探针测试 [ProbeNearestNeighborsWithEqualTest.kt](../../app/src/test/java/io/prism/data/ProbeNearestNeighborsWithEqualTest.kt) 5 用例全通过 |
| 风险等级 | P2 跨模块（新增检索接口，依赖既有 Embedder/KnowledgeBaseRepository/KnowledgeChunk，触发 ADR-017.1 条件 2/7） |

## 背景（Context）

PRD US-003 验收 3「检索」与 US-017 验收标准要求：top-k 检索（默认 k=5，可配置）基于 nearestNeighbors；支持指定库或全库检索；检索结果含相似度分数与来源（文件/片段位置）；检索单元测试通过（含空库、无匹配）。

考古报告（TKN-US017-ARCH-001）确认的关键事实与风险：
- ObjectBox `nearestNeighbors` 返回 **COSINE 距离**（值越低越相似，范围 [0,2]），非相似度。US-017 要求「相似度分数」须显式转换（§5.1 风险）。
- 全项目 11 处 nearestNeighbors 用法**均不带 equal 过滤**；3 处 equal(knowledgeBaseId) 全部用于 count/findIds。**组合用法零先例**（§5.2 风险）。
- 维度不匹配属 ObjectBox 未定义行为，US-017 须前置校验 query.size==384（§5.3 风险）。
- Query 对象须 close 释放 native 句柄（§5.4 风险，BR-concurrency-003）。
- title 格式 `${documentTitle}#${index+1}`，文件名可能含 `#`（如「C#入门.pdf」），split("#") 会误分割（§5.5 风险）。
- HNSW 索引实体级，全库检索跨库返回，分库检索须叠加 equal 过滤（§5.7 风险）。

设计未决问题：
1. 检索方法归属（扩展 KnowledgeBaseRepository / 新建 RetrievalService）？
2. 相似度转换公式（`1-d` 范围[-1,1] / `1-d/2` 范围[0,1]）？
3. 分库检索策略（query 内组合 nearestNeighbors+equal / 全库 top-N + 内存过滤）？
4. kbId 三态语义（null / 0L / >0）如何表达？
5. k 默认值与可配置方式？
6. Query 资源管理方式？
7. 检索结果数据结构？

本 ADR 锁定以上 7 项决策。

## 决策（Decision）

### 5.1 检索方法扩展 KnowledgeBaseRepository（方案 A）

```kotlin
class KnowledgeBaseRepository(private val boxStore: BoxStore) {
    // 既有：save/get/getAll/findByName/remove/removeAll/addChunk/chunkCount

    /** US-017 新增：向量检索 */
    fun search(
        query: FloatArray,
        k: Int = DEFAULT_SEARCH_K,
        knowledgeBaseId: Long? = null
    ): List<RetrievalResult> { ... }

    companion object {
        const val DEFAULT_KB_ID: Long = 0L
        const val DEFAULT_SEARCH_K: Int = 5
    }
}
```

**理由**：
- 与 ADR-009 5.2「KnowledgeChunk 由 KB Repository 代管」模式一致，避免散落 Box 访问点。
- `search` 与 `chunkCount` 同属读路径，职责内聚；当前 Repository 8 方法，加 `search` 后 9 方法，仍属合理范围。
- 复用私有 `chunkBox`，统一 Query 资源管理（`use{}` 关闭）。
- 方案 B（新建 RetrievalService）虽职责分离更纯粹，但引入新模块增加认知负荷，且需重新 `boxStore.boxFor(KnowledgeChunk)` 散落访问点。若未来检索逻辑复杂化（重排、混合检索）再重构为方案 B。

### 5.2 相似度转换公式：`similarity = 1.0 - distance`（范围 [-1, 1]）

```kotlin
val similarity = 1.0 - match.getScore()  // ObjectBox COSINE 距离 d∈[0,2] → sim∈[-1,1]
```

**理由**：
- **数学语义对齐**：ObjectBox COSINE 距离 `d = 1 - cos(θ)`，故 `sim = 1 - d = cos(θ)`，与数学余弦相似度严格一致。
- **实证一致**：web-access 调研发现 PicQuery 项目（juejin.cn）采用相同公式 `cosineSimilarity = 1.0 - result.score`。
- all-MiniLM-L6-v2 输出已 L2 归一化，`cos(θ)` 即点积，语义清晰。
- 公式 B（`1 - d/2`，范围 [0,1]）是展示层归一化，可在 UI 层按需 `(sim + 1) / 2` 转换，不应在数据层固化。
- 返回时按 similarity **降序**（最相似在前），与 ObjectBox 原生距离升序相反——实现时先按原距离升序取 top-k，再 map 转换为 similarity（自然降序）。

### 5.3 分库检索策略：query 内组合 nearestNeighbors + equal（探针验证通过）

```kotlin
val queryBuilder = chunkBox.query(
    KnowledgeChunk_.embedding.nearestNeighbors(query, k)
)
if (knowledgeBaseId != null) {
    queryBuilder.equal(KnowledgeChunk_.knowledgeBaseId, knowledgeBaseId)
}
val query = queryBuilder.build()
```

**理由**：
- **探针测试实证**（[ProbeNearestNeighborsWithEqualTest.kt](../../app/src/test/java/io/prism/data/ProbeNearestNeighborsWithEqualTest.kt) 5 用例全通过）：
  - `probe_nearestNeighbors_with_equal_returns_only_specified_kb`：指定 kbId=0L 仅返回默认库 3 条
  - `probe_nearestNeighbors_with_equal_returns_self_built_kb`：指定 kbId=1L 仅返回自建库 3 条
  - `probe_nearestNeighbors_with_equal_k_greater_than_available`：k 超量返回该库全部（不跨库补足）
  - `probe_nearestNeighbors_with_equal_empty_kb_returns_empty`：空库返回空（不跨库返回）
  - `probe_nearestNeighbors_without_equal_returns_cross_kb`：不带 equal 跨库返回
- DB 层过滤，精确 top-k，性能最优。
- WebSearch 确认 ObjectBox Dart API 支持 `nearestNeighbors.and(otherCondition)` 组合，Java/Kotlin API 同样支持（核心 C++ 一致）。
- 备选方案「全库 top-N + 内存过滤」破坏 top-k 语义（某库结果可能不足 k），N 难定，性能浪费，**否决**。

### 5.4 kbId 三态语义：`null`=全库，`0L`=默认库，`>0`=指定自建库

```kotlin
fun search(
    query: FloatArray,
    k: Int = DEFAULT_SEARCH_K,
    knowledgeBaseId: Long? = null  // null=全库，0L=默认库，>0=指定库
): List<RetrievalResult>
```

| knowledgeBaseId | 语义 | query 构建 |
|---|---|---|
| `null` | 全库检索（所有 chunk 不分库） | 仅 nearestNeighbors，不加 equal |
| `0L` | 默认库（DEFAULT_KB_ID，虚拟库） | nearestNeighbors + equal(knowledgeBaseId, 0L) |
| `>0` | 指定自建库 | nearestNeighbors + equal(knowledgeBaseId, id) |

**理由**：
- 与 ADR-008 5.3 默认库语义一致（`0L` = 虚拟默认库）。
- `null` 明确表达「不过滤」，与 `0L`（过滤默认库）语义区分清晰，避免用魔法值（如 `-1`）表达全库。
- 默认值 `null`（全库）与 AC「支持指定库或全库」中「全库」作为默认行为一致。

### 5.5 k 默认值 5，可配置

```kotlin
const val DEFAULT_SEARCH_K: Int = 5

fun search(query: FloatArray, k: Int = DEFAULT_SEARCH_K, ...): List<RetrievalResult>
```

**理由**：
- AC 明确「默认 k=5，可配置」。
- PRD US-003 注「4GB 低端机小批次 top-k=3」是运行时调优建议，非接口默认值；接口默认值用 AC 的 5，调用方（US-019 RAG 集成）可按设备配置传 k=3。
- `require(k > 0)` 前置校验，fail-fast 拒绝非正 k。

### 5.6 维度前置校验：`require(query.size == 384)`

```kotlin
fun search(query: FloatArray, k: Int = DEFAULT_SEARCH_K, knowledgeBaseId: Long? = null): List<RetrievalResult> {
    require(query.size == EMBEDDING_DIM) {
        "查询向量维度必须为 $EMBEDDING_DIM（收到 ${query.size}）。嵌入模型 all-MiniLM-L6-v2 固定 384 维。"
    }
    require(k > 0) { "k 必须为正数（收到 $k）" }
    require(knowledgeBaseId == null || knowledgeBaseId >= 0) {
        "knowledgeBaseId 不能为负数（收到 $knowledgeBaseId）"
    }
    // ...
}
```

**理由**：
- 考古报告 §5.3 + US-011 验收报告 §8：ObjectBox 5.4.2 对维度不匹配属**未定义行为**（可能抛异常/返回空/返回 2.0 哨兵）。
- `require` fail-fast 抛 `IllegalArgumentException`，不依赖 ObjectBox 未定义行为。
- `EMBEDDING_DIM = 384` 常量与 OnnxEmbedder `embeddingDim` 对齐。
- `knowledgeBaseId >= 0` 与 Repository 既有 `require(id >= 0)` 风格一致（G-04 纵深防御）。

### 5.7 Query 资源管理：`use {}` 关闭

```kotlin
val query = queryBuilder.build()
return query.use { q ->
    val matches = q.findWithScores()
    matches.map { result ->
        RetrievalResult(
            chunkId = result.get().id,
            content = result.get().content,
            title = result.get().title,
            similarity = 1.0 - result.getScore(),
            knowledgeBaseId = result.get().knowledgeBaseId
        )
    }
}
```

**理由**：
- 考古报告 §5.4 + BR-concurrency-003：ObjectBox Query 持有 native 句柄，须显式 close。
- `use {}` 是 Kotlin 惯用模式（等价 try-finally close），与 `chunkCount`/`remove` 既有模式一致。
- **禁止照搬** `KnowledgeChunkVectorSearchTest.kt` 4 用例未 close 的瑕疵模式（考古报告 §3.4 已标注）。

### 5.8 RetrievalResult 数据结构

```kotlin
/**
 * 向量检索结果（US-017）。
 *
 * @property chunkId KnowledgeChunk id
 * @property content 分块原文
 * @property title 分块标题（原文，格式 `${documentTitle}#${index+1}`）
 * @property similarity 相似度分数 ∈ [-1, 1]，1=完全相同，0=正交，-1=相反（COSINE 距离转换：1 - distance）
 * @property documentTitle 解析自 title 的文档标题（lastIndexOf('#') 分割左侧）；title 不含 # 时等于 title 原文
 * @property chunkIndex 解析自 title 的分块序号（1-based）；title 不含 # 时为 null
 * @property knowledgeBaseId 所属知识库 id（0L=默认库，>0=自建库）
 */
data class RetrievalResult(
    val chunkId: Long,
    val content: String,
    val title: String,
    val similarity: Double,
    val documentTitle: String,
    val chunkIndex: Int?,
    val knowledgeBaseId: Long
)
```

**理由**：
- AC 要求「检索结果含相似度分数与来源（文件/片段位置）」：`similarity` + `documentTitle` + `chunkIndex` 满足。
- `similarity: Double`（非 Float）：分数比较精度更高，与 ObjectBox `getScore()` 返回的 Double 对齐。
- `title` 原文保留：避免解析失败时丢失信息，UI 层可直接展示原文。
- `chunkIndex: Int?` 可空：title 不含 `#` 时无法解析，返回 null（语义清晰，非 -1 魔法值）。

### 5.9 title 解析：`lastIndexOf('#')`

```kotlin
private fun parseTitle(title: String): Pair<String, Int?> {
    val idx = title.lastIndexOf('#')
    return if (idx > 0 && idx < title.length - 1) {
        val docTitle = title.substring(0, idx)
        val chunkIdx = title.substring(idx + 1).toIntOrNull()
        if (chunkIdx != null && chunkIdx > 0) {
            docTitle to chunkIdx
        } else {
            title to null
        }
    } else {
        title to null
    }
}
```

**理由**：
- 考古报告 §5.5：IngestionPipeline 生成 title = `"${documentTitle}#${index+1}"`，documentTitle 可含 `#`（如「C#入门.pdf」→「C#入门#1」）。
- `lastIndexOf('#')` 取最后一个 `#` 分割，规避文件名含 `#` 的歧义。
- `idx > 0`：排除 `#` 在首位（无文档标题的退化情况）。
- `idx < title.length - 1`：排除 `#` 在末尾（无序号的退化情况）。
- `toIntOrNull()` + `chunkIdx > 0`：序号必须为正整数（IngestionPipeline 用 `index+1` 从 1 开始），否则视为无序号。
- **容错降级**（不抛异常，保持检索可用）：
  - title 不含 `#`、`#` 在首位/末尾时：返回 `title to null`（无法分割，documentTitle=title 原文）。
  - 序号非正整数（0/负数/非数字）时：返回 `documentTitle to null`（保留 `#` 左侧有效部分作为文档标题，丢弃非法序号；UI 层展示 documentTitle 不需要看到 `#0` 等非法序号）。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| 新建 RetrievalService（方案 B） | 职责分离，读路径与写路径解耦 | 1) 散落 Box 访问点；2) 与 ADR-009 5.2 代管模式不一致；3) 当前检索逻辑简单，无需独立模块；4) 增加认知负荷 |
| 全库 top-N + 内存过滤 kbId | 绕过组合不确定性 | 1) 破坏 top-k 语义（某库结果可能不足 k）；2) N 难定（库占比低时 N 需极大）；3) 性能浪费；4) 探针已验证 query 内组合可用，无需此备选 |
| 相似度公式 `1 - d/2`（范围 [0,1]） | 展示友好 | 1) 数学语义不对齐 cos(θ)；2) 展示层归一化应在 UI 层做；3) 数据层应保留数学语义 |
| kbId 用 `-1` 表达全库 | 避免 nullable | 1) 与 Repository 既有 `require(id >= 0)` 冲突；2) 魔法值语义不清；3) `null` 是 Kotlin 表达「无值」的惯用方式 |
| k 默认值 3（PRD 建议） | 4GB 低端机性能更优 | 1) AC 明确「默认 k=5」；2) PRD 建议是运行时调优，非接口默认值；3) 调用方可按设备配置传 k=3 |
| title 解析用 `split("#")` | 实现简单 | 1) 文件名含 `#` 时误分割（如「C#入门.pdf」→「C」+「入门.pdf#1」）；2) `lastIndexOf` 更稳健 |

## 后果（Consequences）

- 正面后果：
  - 检索方法复用 KnowledgeBaseRepository 私有 chunkBox，统一资源管理，避免散落 Box 访问点
  - 相似度公式与数学语义对齐，便于上层理解与调试
  - 分库检索在 DB 层过滤，精确 top-k，性能最优
  - kbId 三态语义清晰，null/0L/>0 各有明确含义
  - 维度前置校验避免依赖 ObjectBox 未定义行为
  - Query 用 `use{}` 关闭，无 native 句柄泄漏
  - RetrievalResult 含完整来源信息（documentTitle/chunkIndex），支持 US-019 引用标注
- 负面后果 / 代价：
  - KnowledgeBaseRepository 增加 search 方法，职责略增（仍属合理范围）
  - 相似度范围 [-1,1] 需 UI 层按需归一化为 [0,1] 展示
  - title 解析依赖 IngestionPipeline 的 title 格式约定，若格式变更需同步更新解析
- 需要同步更新的文档或代码：
  - `KnowledgeBaseRepository.kt`：新增 `search` 方法 + `DEFAULT_SEARCH_K` / `EMBEDDING_DIM` 常量
  - 新增 `RetrievalResult.kt`：检索结果数据类
  - 新增 `KnowledgeBaseRetrievalTest.kt`：检索单元测试（9 用例）
  - `app/objectbox-models/default.json`：无变更（不涉及 schema）
  - `docs/decisions/README.md` / `README.md`：ADR 索引同步
  - `prd.json`：US-017 `passes=true` + notes 补 ADR-010 引用

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| ObjectBox 升级后 nearestNeighbors+equal 组合行为变更 | 低 | 探针测试作为回归保护，升级后重跑；版本锁定 5.4.2（gradle/libs.versions.toml） |
| 相似度范围 [-1,1] UI 展示不友好 | 低 | UI 层按需 `(sim + 1) / 2` 归一化为 [0,1]，或 `max(0, sim)` 截断负值 |
| title 格式变更导致解析失效 | 低 | 解析容错降级（失败返回 title 原文 + null 序号），不抛异常；IngestionPipeline title 格式变更须同步更新本解析 |
| Embedder close 后检索失败 | 低 | search 方法不捕获 embed 异常（调用方处理生命周期，保持 fail-fast）；US-019 调用方确保 Embedder 生命周期与 Application 一致 |
| 全库检索跨库泄漏 | 中 | API 默认 kbId=null=全库，调用方须显式选择；US-019 RAG 集成时按业务需求决定全库或指定库 |
| 并发检索 embed 瓶颈 | 中 | OnnxEmbedder 串行持锁（BR-concurrency-002），nearestNeighbors 本身线程安全；UI 层应防抖避免频繁检索 |
| HNSW 近似性导致结果数 < k（guardrail L4） | 低 | HNSW 是近似最近邻算法，查询向量与库内向量正交（distance≈1）时可能不返回全部 k 条匹配（如 k=5 但仅返回 2 条）。这是近似算法固有特性非缺陷。调用方（US-019）应处理 `results.size < k` 的情况，UI 层不应硬编码「必须返回 k 条」断言。 |
| 无相似度阈值过滤导致展示无关结果（guardrail L3） | 低 | 本数据层方法不做相似度阈值过滤，返回 top-k 不论相似度高低（即使全部 similarity ≈ 0）。阈值过滤是业务决策，由 US-019 RAG 集成时调用方根据业务需求决定（如 `results.filter { it.similarity > 0.3 }`）。数据层保留数学语义不固化业务阈值，便于不同场景调优。 |

## 参考

- [US-017 检索源码考古报告](../reports/2026-08-07-us017-retrieval-archaeology.md)
- [探针测试：nearestNeighbors + equal 组合验证](../../app/src/test/java/io/prism/data/ProbeNearestNeighborsWithEqualTest.kt)
- [ObjectBox On-Device Vector Search](https://docs.objectbox.io/on-device-vector-search)
- [ObjectBox Release History 4.0.0 - Vector Search](https://docs.objectbox.io/release-history)
- [PicQuery：移动端向量搜索实证（cosineSimilarity = 1.0 - result.score）](https://juejin.cn/post/7523368862986108937)
- [HNSW 论文](https://arxiv.org/abs/1603.09320)
- [ADR-007](ADR-007-m3-rag-tech-stack.md)：M3 RAG 技术栈 / KnowledgeChunk 向量索引
- [ADR-008](ADR-008-m3-knowledgebase-model.md)：知识库分库模型 / knowledgeBaseId 语义
- [ADR-009](ADR-009-m3-ingestion-pipeline.md)：摄入管线 / KnowledgeChunk 由 KB Repository 代管
