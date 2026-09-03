# Guardrail 审查报告：US-011 依赖落地 + KnowledgeChunk 向量索引

## 元信息

| 项目 | 内容 |
| --- | --- |
| 执行 Agent | `guardrail-enforcer` |
| 任务令牌 | `TKN-US011-GUARDRAIL-001` |
| 报告类型 | guardrail |
| 审查日期 | 2026-08-06 |
| 审查范围 | 代码质量审查（TRAE-code-review）+ 安全审计（TRAE-security-review） |
| 变更风险等级 | P2（跨模块：新增 onnxruntime/poi-ooxml 生产依赖 + multiDexEnabled + 实体注解） |
| 审查结论 | ✅ 通过（Pass） |

## 1. 总体结论

**通过（Pass）。未发现阻断级（HIGH）或高危（MEDIUM）安全漏洞。**

新增依赖（onnxruntime-android 1.27.0 / poi-ooxml 5.5.1）License 合规（MIT / Apache 2.0），
本期仅声明未使用，无注入面、无硬编码密钥。`@HnswIndex` 注解参数与 COSINE 分数语义
**经对 ObjectBox 5.4.2 真实 API 反编译核对全部正确**。测试覆盖 4 个验收场景，逻辑正确。

### 审查范围汇总

- 变更文件：4（`libs.versions.toml` / `build.gradle.kts` / `KnowledgeChunk.kt` / `KnowledgeChunkVectorSearchTest.kt`）
- 关联核对文件：`ADR-007-m3-rag-tech-stack.md`、`.gitignore`、`docs/behavioral-rules.md`
- 发现问题总数：5（0 阻断 / 0 高危 / 0 中危 / 2 低危 / 3 建议）

---

## 2. 变更概览

```mermaid
flowchart LR
    A[libs.versions.toml 新增版本] --> B[build.gradle.kts 声明依赖]
    B --> C[multiDexEnabled=true]
    B --> D[onnxruntime-android 1.27.0]
    B --> E[poi-ooxml 5.5.1]
    F[KnowledgeChunk.embedding] --> G[@HnswIndex dim=384 COSINE]
    G --> H[nearestNeighbors 近邻检索]
    H --> I[findWithScores 带分数结果]
    style G fill:#c8e6c9,color:#1a5e20
    style H fill:#c8e6c9,color:#1a5e20
    style B fill:#fff3e0,color:#e65100
```

本期为「依赖落地 + 索引声明」阶段：依赖仅在构建脚本声明，实体加注解建立向量索引，
索引的实际写入与检索由 US-011 后续切片/嵌入模块驱动。

---

## 3. ObjectBox 5.4.2 API 核对（本项核心）

对 Gradle 缓存中 `objectbox-java-api-5.4.2.jar` / `objectbox-java-5.4.2.jar` 反编译核对，全部通过：

| 核对项 | 代码用法 | ObjectBox 5.4.2 实际 API | 结论 |
| --- | --- | --- | --- |
| `@HnswIndex` 注解 | `@HnswIndex(dimensions = 384, distanceType = VectorDistanceType.COSINE)`（KnowledgeChunk.kt:27） | `interface HnswIndex { long dimensions(); long neighborsPerNode(); long indexingSearchCount(); HnswFlags flags(); VectorDistanceType distanceType(); ... }` | ✅ 属性名正确 |
| `VectorDistanceType.COSINE` | 注解 `distanceType` 参数 | `enum VectorDistanceType { DEFAULT, EUCLIDEAN, COSINE, DOT_PRODUCT, GEO, ... }` | ✅ 枚举值存在 |
| `nearestNeighbors` | `KnowledgeChunk_.embedding.nearestNeighbors(queryVector, 3)`（Test:62） | `PropertyQueryCondition<ENTITY> Property.nearestNeighbors(float[], int)` | ✅ 签名正确 |
| `findWithScores` | `query.findWithScores()`（Test:64） | `List<ObjectWithScore<T>> Query.findWithScores()` | ✅ 存在 |
| `getScore()/get()` | `matches[0].get().title` / `matches[0].getScore()`（Test:67-69） | `ObjectWithScore<T> { T get(); double getScore(); }` | ✅ 方法正确 |

**COSINE 分数语义确认**：ObjectBox `findWithScores()` 对 COSINE 距离返回**距离分数，
值越低越相似**。测试断言 `matches[0].getScore() < matches[1].getScore()`（Test:69）
与注释「ObjectBox COSINE 返回距离分数：值越低越相似」语义一致，方向正确。
主 Agent 对该语义的理解无误。

---

## 4. 代码质量审查（TRAE-code-review）

### 4.1 变更意图推断

将 US-011 的依赖声明与向量索引落地：在既有 `KnowledgeChunk` 实体上为 `embedding`
字段声明 HNSW 索引，并新增 4 个单元测试验证 `nearestNeighbors` 行为，为后续切片/
嵌入模块提供可验证的检索契约。

### 4.2 Karpathy Guidelines 核对

| 维度 | 结论 | 说明 |
| --- | --- | --- |
| 命名 | ✅ | `oneHot`/`dominantIndex`/`queryVector` 语义清晰 |
| 设计 | ✅ | 测试隔离使用临时目录 + `@Before/@After` 生命周期，与既有 `KnowledgeCrudTest` 同模式 |
| 错误处理 | ✅ | 测试资源 `boxStore.close()` + `tempDir.deleteRecursively()` 在 `tearDown` 释放 |
| 逻辑正确性 | ✅ | top-k、null 排除、空库、k 上限四场景均正确构造 |

### 4.3 低危发现

#### L-01 测试未显式关闭 `Query` 对象

- **位置**：`KnowledgeChunkVectorSearchTest.kt`
- **问题**：`box.query(...).build()` 后未调用 `query.close()`。ObjectBox 在 JVM 下
  Query 通常随 `boxStore.close()` 释放，但官方建议显式 close 以尽早释放原生资源。
  属防御性资源管理遗留，非内存泄漏证据。
- **建议**：在断言后 `query.close()`，或将 `build()` 置于 `use` 块。

#### L-02 top-k 排序断言仅覆盖首尾两项

- **位置**：`KnowledgeChunkVectorSearchTest.kt`
- **问题**：`nearestNeighbors_returns_topk_by_similarity` 仅断言 `matches[0] < matches[1]`，
  未断言 `matches[1] < matches[2]` 的完整序。对「top-k 按相似度正确排序」的验证不充分。
- **建议**：补充对全部 3 条结果的分数严格递增断言。

### 4.4 建议

#### R-01 poi-ooxml 未来启用时需防 XXE

poi-ooxml 历史上存在 XXE 相关 CVE（如 CVE-2022-26336 系列）。本期依赖仅声明未使用，
无实际解析入口，不构成当前漏洞。但 US-011 后续文档解析将调用 `XWPFWordExtractor` /
`XSSFWorkbook` 解析 DOCX（XML 容器），届时必须显式配置 XML 解析器防外部实体展开。
**建议在实现文档解析模块时，对照 OWASP XXE 防护清单（DocumentBuilderFactory 禁用
`DOCTYPE`/外部实体）编码，并纳入该模块的 guardrail 审查。**

#### R-02 依赖供应链扫描未接入 CI

本期新增 2 个生产依赖，虽 License 已在 ADR-007 确认合规，但供应链漏洞扫描尚未纳入 CI 门禁。
**建议在 `.github/workflows/` 增加 Gradle 依赖漏洞扫描（如 OWASP Dependency-Check 或
`dependencyCheck` 插件），作为必需状态检查。**

#### R-03 项目无独立 SECURITY.md

任务要求提供安全策略文件，但仓库根目录无 `SECURITY.md`。安全策略由 `CLAUDE.md` 第二十节
（密钥管理）与第十八节（依赖安全）承载，功能完整，**不构成阻断**。建议在里程碑审计时
评估是否补充独立 `SECURITY.md` 以便外部协作者查阅。

---

## 5. 安全专项审计结论

| 审计项 | 结论 | 证据 |
| --- | --- | --- |
| 注入类（SQL/命令/代码/eval） | ✅ 通过 | 本期仅依赖声明与实体注解，无数据库拼接、命令执行、动态求值 |
| 硬编码密钥 | ✅ 通过 | 无任何密钥/口令/令牌；384 为模型维度常量非敏感信息 |
| 密钥/配置管理 | ✅ 通过 | 依赖变更不涉及运行时配置；`.gitignore` 已排除 `.env`/密钥文件 |
| 依赖 License 合规 | ✅ 通过 | onnxruntime MIT、poi-ooxml Apache 2.0，ADR-007 5.2/5.3 已确认 |
| 供应链风险 | ✅ 本期可控 | 版本固定（1.27.0 / 5.5.1），已解析到 Gradle 缓存，无模糊版本范围 |
| multiDex 安全影响 | ✅ 无风险 | `multiDexEnabled=true` 为构建级配置，minSdk 26 原生支持 multidex，不引入安全面 |
| CWE-209 信息泄露 | ✅ 通过 | 无日志输出 |

### 5.1 主 Agent 自问回应

**5.1.1 @HnswIndex 与 COSINE 语义（最没把握的事）**

确认正确。API 反编译核对（第三节）证明注解属性、枚举值、查询方法、分数方向全部无误。
ObjectBox 的 COSINE `findWithScores` 返回距离分数（值越低越相似），测试断言方向正确。

**5.1.2 模型重建 / multiDex 盲区（最大盲区）**

- 模型重建：`@HnswIndex` 会触发 ObjectBox 模型重建，`MyObjectBox` 生成器在编译期处理；
  主 Agent 已运行全量测试通过，`objectbox-models/default.json` schema 已按 BR-build-005 提交，
  无 schema 回归证据。
- multiDex：`multiDexEnabled=true` 在 minSdk 26（原生支持 multidex）下无 legacy 兼容负担，
  不引入额外依赖或安全面，属于为 poi-ooxml 方法数超限的预防性配置，合理。

---

## 6. 修复清单（供主 Agent 参考）

| 优先级 | ID | 动作 | 是否阻断本轮 |
| --- | --- | --- | --- |
| 建议 | L-01 | 测试中显式 `close()` Query | 否 |
| 建议 | L-02 | 补全 top-k 完整排序断言 | 否 |
| 建议 | R-01 | 文档解析模块实现时防 XXE（纳入该模块审查） | 否 |
| 建议 | R-02 | CI 接入依赖漏洞扫描 | 否 |
| 建议 | R-03 | 评估补充独立 SECURITY.md | 否 |

以上均为低危/建议项，不构成阻断。主 Agent 可进入 `ac-verifier` 验收阶段；
L-02（top-k 完整排序断言）建议在验收测试中由 `ac-verifier` 补充极端用例进一步加强。

---

## 7. 自动化建议（CI 集成参考）

在 `.github/workflows/` 新增 Gradle 依赖漏洞扫描门禁：

```yaml
name: dependency-check
on: [pull_request]
jobs:
  audit:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - name: OWASP Dependency Check
        run: ./gradlew dependencyCheckAggregate
```

建议同时启用 Gradle 的依赖约束锁定（`dependencyLocking`），固化间接依赖版本，
防止传递依赖漂移引入供应链风险。

---

*报告内所有代码引用均为相对路径（符合 ADR-010）。报告由 `guardrail-enforcer` 生成，
任务令牌 `TKN-US011-GUARDRAIL-001`。*
