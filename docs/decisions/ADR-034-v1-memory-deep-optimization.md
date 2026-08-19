# ADR-034: v1 记忆系统深度优化（原子抽取 / 混合检索 / 去重 / 软衰减 / 预算）

> 从模板复制新建，参照 TencentDB-Agent-Memory（`feat/server_team` 分支源码实证）为 Prism 三层记忆系统做深度优化。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-19 |
| 决策者 | 主 Agent + 用户（D-7 确认"记忆优化全部采纳"） |
| 关联文档 | [prd-v1-features.md](../prd-v1-features.md)（US-101~104）、[ADR-015](ADR-015-m5-memory-system-architecture.md)、[ADR-031](ADR-031-uxr9-multilingual-embedding-and-l2-memory.md)、[ADR-033](ADR-033-uxr11-real-device-fixes.md) |
| 上游调研 | 记忆系统深度优化技术选型对比分析报告（TKN-V1-MEMORY-RESEARCH-001） |
| 风险等级 | P2（跨模块：数据模型字段扩展 + 检索路径变更 + 配置扩展 + ViewModel 接线） |

## 背景（Context）

Prism 已有 M5 三层记忆（L1 滑动窗口 / L2 跨会话向量 / L3 画像）+ UXR11 L2 原子记忆三态抽取。用户要求参照腾讯开源 `TencentDB-Agent-Memory` 深度优化记忆能力。考古发现四项技术债/短板：① L2 逐对/摘要存储**无条件 insert 无去重**；② 检索**纯向量（HNSW）**，中文精确词句（如"上次说的 Kotlin 协程"）向量相似度不足时漏召回；③ L2 库**无 TTL/衰减/容量上限**，长期无限膨胀；④ 记忆注入**无条数/字符预算**，可能挤占上下文。调研结论：TencentDB-Agent-Memory 的 L0-L3 分层蒸馏、BM25+向量+RRF(k=60) 混合检索、批量去重（store/update/merge/skip）、稳定/动态分层注入+预算 与 Prism 现状高度同构且可在 Android 端**零新增重型依赖**落地（SQLite FTS5 系统内置、RRF 纯 Kotlin、去重复用现有 BYOK LLM）。

## 决策（Decision）

对 L2 跨会话记忆系统做四项深度优化（全部经 D-7 用户确认）：

1. **原子记忆抽取升级（US-101）**：`ConversationSummarizer.extractMemories` 改为 JSON 结构化输出（`content`/`type`(persona/episodic/instruction)/`priority`(0-100)），单次 LLM 调用完成「场景切分+提取」；`MemoryRecord` 新增 `priority`/`accessCount`/`version`/`sourceMessageIds` 字段（ObjectBox 自动迁移）；类型/优先级经 `ExtractedMemory.normalizeType/normalizePriority` 规范化（非数字→50、越界 clamp）。
2. **混合检索（US-102）**：新增 `MemoryKeywordIndex`（生产 `SqliteFtsMemoryIndex`：系统 SQLite FTS5 + 中文预分词；测试/降级 `InMemoryMemoryKeywordIndex`：纯 Kotlin BM25）+ `RrfFusion(k=60)`。`retrieveRelevantMemories` 改为「FTS5 BM25 + 向量 HNSW → RRF 融合 → 阈值过滤」；命中记忆 `incrementAccessCount`（软衰减频率信号，**不递增 mutationVersion** 避免 FTS 全量重建）。
3. **批量去重 + 软衰减 + 容量（US-103）**：新增 `dedupeSessionMemories`（会话结束异步，候选召回 top5 + 单次 LLM 四态判定 store/update/merge/skip + 失败降级不处理）；`computeRecallScore = priority × exp(-λ·age_days) × (1+α·accessCount)` 低于注入阈值的记忆**移出注入集但保留在库**；`MemoryRepository.evictIfOverLimit` 超限按「低 priority + 最旧」回收。
4. **注入预算（US-104）**：`retrieveRelevantMemories` 注入条数上限（默认 5）+ 单条字符截断（`truncateContent`，截断为展示层不影响库内容）+ 失败静默降级。

**关键设计约束**：`saveSessionMemories` 落库 `timestamp = System.currentTimeMillis()`（持久化时刻，而非源消息时刻）——保证软衰减语义正确（新存记忆视为新）。`MemoryConfigRepository` 新增去重开关/容量上限/衰减系数/注入预算配置项（DataStore 持久化，默认值对齐 TencentDB-Agent-Memory 与 Mem0 实践）。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| 引入 Lucene on Android（lucene-core 4.4MB） | 成熟 BM25/倒排 | APK 增量 4.4MB 违反「新增依赖 ≤1MB」红线；FTS5 系统内置零依赖足够 |
| 引入 tantivy-android（Rust native 3.8MB） | 性能好 | 16 commits 极不成熟 + 团队不熟 Rust + 体积超标 |
| 引入 jieba-android 分词 | 中文切词质量高 | +1.4MB；本项目用 CJK 二元组+整段预分词即可命中，且 RRF 与向量互补 |
| 去重集成进 saveSessionMemories | 一步到位 | 破坏既有"抽取→保存"测试语义（callCount/降级路径强耦合）；拆为独立 `dedupeSessionMemories` 更贴合「会话结束异步触发」AC 且可独立测试 |
| 硬 TTL 删除过时记忆 | 简单 | 会误删「旧但正确」的事实（Bjork 新失用论）；采用软衰减（降权移出注入集）更优 |

## 后果（Consequences）

- 正面后果：
  - L2 检索从纯向量升级为「关键词 + 语义」双路召回，中文精确词句召回能力提升（RRF 排名融合对分数量纲鲁棒）。
  - 重复记忆批量去重（四态 + 失败降级），记忆库不再无限膨胀。
  - 旧/低分记忆软衰减降权 + 容量回收，上下文注入受预算约束，防 token 膨胀。
  - 记忆溯源（sourceMessageIds/version/accessCount）建立。
- 负面后果 / 代价：
  - 去重新增一次会话结束 LLM 调用（BYOK 成本；默认开启，失败降级不处理）。
  - 软衰减过滤使 1970 纪元时间戳的旧测试记录被移出注入集——已更新相关测试用近期时间戳。
  - `MemoryRecord` 新增字段改变实体 schema（ObjectBox 自动迁移，backup .bak 生成）。
  - 每次检索多 5 次 DataStore 配置读取（可接受，缓存后为内存读）。
- 需要同步更新的文档或代码：
  - `AGENTS.md` 进度记录（批次1 完成）、`docs/prd-v1-features.md` 执行状态表。

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| FTS5 中文预分词（CJK 二元组）可能过碎 | 中 | 二元组+整段双轨 + RRF 与向量互补；PoC 标注集 MRR 评估后再全量（US-102 AC） |
| 去重 LLM 调用放大 BYOK 成本/限流（kimi RPM=3） | 中 | 仅会话结束异步；失败降级不处理；单批 ≤10；可配置开关 |
| FTS5 与 ObjectBox 数据一致性（双写漂移） | 低/高 | ObjectBox 为 source of truth；FTS 为可重建派生索引；`mutationVersion` 版本化增量重建 |
| accessCount 写入频繁 | 低 | 命中即 +1 fire-and-forget；不递增 mutationVersion（避免 FTS 重建风暴） |

## 参考

- [TencentDB-Agent-Memory](https://github.com/TencentCloud/TencentDB-Agent-Memory)（`feat/server_team` 分支 l1-extractor / l1-dedup / auto-recall / search-utils / quota-manager 源码）
- Mem0 eviction / Microsoft 睡眠期合并 / Letta（调研报告 §5）
- [prd-v1-features.md](../prd-v1-features.md) US-101~104 验收标准
