# ADR-007: M3 个人知识库 RAG 技术栈（US-003）

> 从 `docs/templates/adr-template.md` 复制新建，依 CLAUDE.md 第十七节。
> 本 ADR 记录 M3「个人知识库 RAG」的端侧向量存储 / 嵌入运行时 / 文档解析三层技术选型。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-06（Proposed → Accepted 2026-08-09，M3 里程碑审计 TKN-M3-MILESTONE-AUDIT-001 同步） |
| 决策者 | 主 Agent（基于 tech-selection-researcher 调研 + 用户确认） |
| 关联文档 | [ADR-001](ADR-001-prism-tech-stack.md) / [PRD.md](../PRD.md) US-003 / [prd.json](../prd.json) |
| 上游调研 | [M3 RAG 技术选型对比分析报告](../reports/2026-08-06-m3-rag-tech-selection.md) |
| 风险等级 | P3 重大（引入 onnxruntime-android / poi-ooxml 生产依赖 + 端侧向量索引 + 嵌入推理） |

## 背景（Context）

M3 需要实现 PRD US-003「个人知识库（端侧 RAG）」：文档导入（PDF/DOCX/XLSX/MD/TXT）
→ 切片 → 嵌入（all-MiniLM-L6-v2 ONNX INT8）→ 存入向量库 → 对话时 top-k 检索 →
注入 prompt → 强制引用来源。目标含 4GB RAM 低端机，需小批次模式。

ADR-001 已锁定：ObjectBox 5.4.2 为主数据库（`KnowledgeChunk` 已含 `embedding: FloatArray?` 字段）、
嵌入模型 all-MiniLM-L6-v2（384 维）、禁用 pymupdf（AGPL）。实现前需锁定三个未决技术点：

1. **向量存储选型**：复用 ObjectBox 原生向量搜索，还是引入 sqlite-vec 等第二向量库。
2. **嵌入运行时**：onnxruntime-android / TensorFlow Lite / ML Kit 的选择。
3. **文档解析库**：PDF/DOCX/XLSX 的解析方案（禁 pymupdf 后）。

tech-selection-researcher（TKN-PRISM-M3-RAG-001）已完成四阶段联网调研，本 ADR 固化为决策。

## 决策（Decision）

### 5.1 向量存储：复用 ObjectBox 5.4.2 原生向量搜索（零新增依赖）

**决策**：不引入第二向量库，直接使用已集成的 ObjectBox 5.4.2 原生向量搜索
（HNSW 近似检索）。在 `KnowledgeChunk.embedding` 字段加 `@HnswIndex` 注解并建立向量索引：

```kotlin
@Entity
data class KnowledgeChunk(
    @Id var id: Long = 0,
    var title: String,
    var content: String,
    @HnswIndex(dimensions = 384, distanceType = HnswDistanceType.COSINE)
    var embedding: FloatArray? = null
)

// 近邻检索（top-k）
val query = box
    .query(KnowledgeChunk_.embedding.nearestNeighbors(queryVector, 5))
    .build()
val matches = query.findWithScores()  // 带相似度分数
```

**理由**：复用主库、零新增依赖、HNSW 原生近似检索、Apache 2.0 且向量搜索免费、
官方确认 384 维 COSINE 原生支持、嵌入式核心对 4GB 低端机内存友好。

### 5.2 嵌入运行时：onnxruntime-android 1.27.0

**决策**：引入 `com.microsoft.onnxruntime:onnxruntime-android:1.27.0`（MIT License），
运行 all-MiniLM-L6-v2 ONNX INT8 量化模型（~23MB）。高端机可开 NNAPI 加速
（`addNnapi(NNAPIFlags.USE_FP16)`），低端机 CPU 降级。模型按需加载，闲置 5 分钟卸载。

**理由**：MIT License 合规、官方 Maven AAR、v1.19+ Mobile 包已停发故用 full 包、
INT8 支持、与 ADR-001 的 ONNX 技术栈一致。

### 5.3 文档解析：PDFBox（PDF）+ Apache POI poi-ooxml 5.5.1（DOCX/XLSX）+ 自研（MD/TXT）

**决策**：

- **PDF**：`org.apache.pdfbox:pdfbox`（Apache 2.0，`PDFTextStripper` 文本抽取）。
  **修正**：原决策「Android PdfRenderer」无法抽取文本（`android.graphics.pdf.PdfRenderer`
  仅渲染位图，无文本 API），经用户确认改用 PDFBox 满足 RAG 文本摄入，规避 pymupdf AGPL。
- **DOCX/XLSX**：`org.apache.poi:poi-ooxml:5.5.1`（Apache 2.0，最新稳定版），
  用 `XWPFWordExtractor` / `XSSFWorkbook` 做文本抽取。需启用 Multidex + ProGuard 裁剪。
- **MD/TXT**：自研轻量解析器，无第三方依赖。

**理由**：License 全部友好（Apache 2.0 / Apache 2.0 / 自研），无 AGPL 传染；
RAG 仅需文本抽取，不依赖 POI 缺失的 java.awt 功能。

### 5.4 检索：首期纯向量 top-k，混合检索留待增强

**决策**：M3 首期采用纯 COSINE 向量检索（top-k，默认 k=5）。
ObjectBox FTS5 全文检索 + `0.7*cosine + 0.3*bm25` 混合加权重排序作为后续增强路径，
不阻塞首期交付。

**理由**：首期聚焦「强制引用来源」核心闭环；中文召回若 <60% 触发嵌入模型切换评估。

### 5.5 合规披露：ObjectBox Gradle plugin 自 4.0.0 起为 AGPL

**决策**：知悉 ObjectBox **Gradle plugin**（构建期工具，不随 APK 分发）自 4.0.0 起 License
为 AGPL。本项目已在 M0-M2 使用 `io.objectbox` plugin 并通过 guardrail 审查，
M3 复用**不新增该风险**。运行时 bindings（objectbox-android / objectbox-java）仍为 Apache 2.0，
向量搜索免费。本项作为已知合规事实记录，不改变既有决策。

**理由**：构建期工具不进入发布产物，AGPL 传染性对运行时影响有限且业界有争议；
主库 bindings 与向量搜索均免费合规。

---

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| sqlite-vec（原版） | MIT/Apache 双许可、轻量 | 仅暴力扫描无 ANN，性能不达标；维护者停滞 |
| sqlite-vector（接管版） | 维护活跃 | **License 改 Elastic License 2.0**，生产需商业许可，违反 C1 合规 |
| FAISS | 业界成熟、HNSW/IVF | 无 Android 官方原生产物，需手动交叉编译，体积大 |
| TensorFlow Lite（嵌入） | Apache 2.0、官方 AAR | 模型需先转换 TFLite，生态略弱；onnxruntime 与 ONNX 栈更一致 |
| ML Kit（嵌入） | Google 官方 | 依赖 Google Play Services，与自发布/F-Droid/PGY 分发冲突 |
| docx4j（DOCX） | Apache 2.0、功能全面 | Android 适配困难、依赖过多 |
| Kexcel（DOCX 备选） | MIT、纯 Kotlin | 实验性、功能有限，仅作降级 |
| pymupdf（PDF） | 文本抽取强 | **AGPL-3.0**，License 不兼容（ADR-001 3.8 已禁） |

---

## 后果（Consequences）

- 正面后果：
  - 端侧 RAG 全链路零后端，数据不出设备，满足隐私要求
  - 向量存储复用主库，依赖最小化，内存占用可控（4GB 低端机友好）
  - 全部新依赖 License 合规（MIT / Apache 2.0 / 系统 API）
  - onnxruntime NNAPI 加速 + CPU 降级，支持设备分档
- 负面后果 / 代价：
  - 新增 onnxruntime-android（full 包体积较大，需 R8 裁剪）+ poi-ooxml（~10.8MB，需 Multidex + ProGuard）
  - all-MiniLM-L6-v2 中文召回质量一般（R1），需中文测试集实测
  - ObjectBox Gradle plugin 的 AGPL 构建期许可需持续知悉
- 需要同步更新的文档或代码：
  - `gradle/libs.versions.toml`（onnxruntime / poi-ooxml）
  - `app/build.gradle.kts`（依赖 + multiDexEnabled + ProGuard）
  - `KnowledgeChunk` 实体（`@HnswIndex` 注解）
  - 新增嵌入 / 切片 / 检索 / 文档解析 / 知识库 UI 模块
  - `docs/decisions/README.md` / `README.md` 索引
  - `prd.json`（M3 US 分解）

---

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| all-MiniLM-L6-v2 中文召回 <60% | 高 | 首期以英文/技术文档验证；PoC 中文测试集评估；若 <60% 触发切换 bge-small-zh 等更强中文模型 |
| Apache POI 体积大 + 低端机内存 | 中 | ProGuard 精简、限制单文件大小、流式解析；若不可接受降级 Kexcel 或自研 OOXML 极简解析器 |
| onnxruntime-android full 包体积大 | 中 | R8 裁剪；模型 INT8 量化 ~23MB 已控制模型侧 |
| embedding 为 null 的记录参与检索 | 中 | 索引建立前先完成切片+嵌入；UI 提示「部分文档未建立索引」 |
| 4GB 低端机检索/嵌入内存峰值 | 中 | HNSW 索引按需加载/卸载；小批次模式；限制库容量 |

---

## 参考

- [M3 RAG 技术选型对比分析报告](../reports/2026-08-06-m3-rag-tech-selection.md)
- [ObjectBox 向量搜索官方文档](https://docs.objectbox.io/on-device-vector-search)
- [onnxruntime-android Maven](https://mvnrepository.com/artifact/com.microsoft.onnxruntime/onnxruntime-android)
- [poi-ooxml Maven](https://mvnrepository.com/artifact/org.apache.poi/poi-ooxml)
- [Android PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)
- [ADR-001](ADR-001-prism-tech-stack.md)：技术栈锁定 / 禁用 pymupdf / ObjectBox 主库
