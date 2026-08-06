# M3 里程碑「个人知识库 RAG」技术选型对比分析报告

| 元信息 | 内容 |
|---|---|
| 报告类型 | 技术选型对比分析报告（Technical Selection Comparative Analysis） |
| 生成日期 | 2026-08-06 |
| 作者 | tech-selection-researcher 子 Agent |
| 调研方法 | 四阶段法：定标尺 → 广撒网 → 深验证 → 出报告（强制 web-access 联网搜索，一手来源优先） |
| 调研范围 | M3「个人知识库 RAG」：向量存储选型 / 嵌入表示与近似检索算法 / 嵌入运行时 / 文档解析库 |
| 任务令牌 | TKN-PRISM-M3-RAG-001 |
| 执行 Agent | tech-selection-researcher |
| 状态 | 定稿（可作为后续 ADR 的输入） |
| 信息时效提醒 | 本报告基于 2026-08-06 联网搜索结果。ONNX Runtime、Apache POI 快速迭代，若决策时间超过 3 个月建议重新调研版本。 |

---

## 1. 执行摘要

### 1.1 调研目的

为 Prism（仅 Android、Kotlin 2.3.21 + Jetpack Compose，纯云端 BYOK 的 AI 聊天 Agent）的 **M3 里程碑「个人知识库 RAG」** 完成端侧 RAG 全链路技术选型，覆盖四个决策点：

- **决策点 1（最高优先）**：向量存储选型
- **决策点 2**：嵌入表示与近似检索算法
- **决策点 3**：嵌入运行时
- **决策点 4**：文档解析库（PDF / DOCX / XLSX / MD / TXT）

目标设备含 **4GB RAM 低端机**，需小批次模式端侧推理。ADR-001 已锁定架构：文档摄入 → 切片 → 嵌入（all-MiniLM-L6-v2 ONNX INT8）→ 向量存储 → 对话时 top-k 检索 → 注入 prompt → 强制引用来源。

### 1.2 最终推荐（一句话）

**决策点 1（向量存储）采用已集成的 ObjectBox 5.4.2 原生向量搜索（HNSW 索引，`@HnswIndex` 注解 + `nearestNeighbors()` 查询，零新增依赖）；决策点 2 用 all-MiniLM-L6-v2 384 维 + COSINE 距离 + HNSW 近似检索；决策点 3 采用 onnxruntime-android 1.27.0（MIT）跑 INT8 量化模型；决策点 4 用 Android 原生 PdfRenderer（PDF）+ Apache POI poi-ooxml 5.5.1（DOCX/XLSX）+ 自研 MD/TXT 解析器。**

> **核心决策约束**：ADR-001 已将 **ObjectBox 5.4.2** 作为主数据库（存 KnowledgeChunk 等），且项目 `KnowledgeChunk.embedding` 字段已定义为 `FloatArray?`。因此向量存储选型的第一考量是「复用 ObjectBox 原生向量搜索」而非引入第二套向量库，以最小化依赖、内存与学习成本。

---

## 2. 需求与约束回顾

### 2.1 量化验收矩阵（Phase 1 产出）

| 指标名称 | 最低要求 | 理想目标 | 测量方法 | 权重(1-10) |
|---|---|---|---|---|
| top-5 向量检索延迟 | <100ms | <50ms | 端侧计时（近邻查询 P99） | 10 |
| 向量库 APK 体积增量 | 不显著（<15MB） | <5MB | `assembleDebug` 产物对比 | 8 |
| 4GB 低端机内存占用 | 峰值增量 <150MB | <80MB | Profiler 采样（检索瞬间） | 9 |
| 检索召回质量（top-5 命中率） | 语义相关 top-5 命中 ≥60% | ≥80% | 人工标注测试集评价 | 8 |
| 嵌入推理延迟（单条） | <500ms（小批次） | <200ms（CPU） | 端侧计时（INT8 模型） | 9 |
| 嵌入模型体积 | <50MB | <30MB | 模型文件 + 运行时 | 8 |
| 文档解析正确率 | DOCX/XLSX/PDF 文本抽取 ≥90% | ≥95% | 文件解析测试集 | 7 |
| 零后端 / 端侧运行 | 全链路离线可用 | 无网络依赖 | 飞行模式验证 | 10 |
| License 合规 | Apache 2.0 / MIT / BSD | 无强制开源传染 | 依赖审查 | 10 |
| 维护活跃度 | 近 6 月有 release | 官方/主流维护 | GitHub 活跃度核查 | 7 |

### 2.2 刚性约束（一票否决项）

| # | 约束 | 理由 |
|---|---|---|
| C1 | **License 必须 Apache 2.0 / MIT / BSD**（商业闭源友好） | 项目 Apache 2.0，避免 GPL/AGPL 传染；AGPL 依赖（如 pymupdf）已禁用（ADR-001 3.8） |
| C2 | **复用 ObjectBox 5.4.2 优先** | ADR-001 已锁定 ObjectBox 为主数据库，`KnowledgeChunk` 已含 embedding 字段；引入第二向量库需强理由 |
| C3 | **零后端 / 端侧运行** | 纯本地 RAG，无服务器；服务端架构向量库（如 Chroma）直接排除 |
| C4 | **compileSdk 34 / minSdk 26 / JVM 17** | 环境仅装 android-34 平台，新依赖不得要求更高 compileSdk |
| C5 | **4GB 低端机内存受限** | 嵌入模型按需加载/unload；向量库内存占用需可控；小批次模式 |
| C6 | **严格版本控制**（CLAUDE.md 十八节 P0 依赖） | 新增 P0 依赖必须写 ADR、锁版本、手动审查升级 |
| C7 | **model 大小与维度固定** | all-MiniLM-L6-v2 384 维已锁定，向量存储必须支持 384 维 float 向量 + COSINE 距离 |

---

## 3. 候选方案综合对比

### 3.1 决策点 1：向量存储选型（最高优先）

#### 3.1.1 候选清单与过滤

| 候选 | 语言/平台 | License | 最后更新 | 过滤结果 |
|---|---|---|---|---|
| **ObjectBox 向量搜索** | Java/Kotlin | Apache 2.0（bindings） | 活跃（5.4.2 稳定，2026） | **保留（首选）** |
| **sqlite-vec（原版）** | C/各语言绑定 | MIT / Apache-2.0 双许可 | 停滞（维护者转投 itd） | 可留（备选） |
| **sqlite-vector（接管版）** | C | **Elastic License 2.0** | 活跃 | **否决（合规风险）** |
| **FAISS** | C++ | MIT | 活跃 | 否决（无 Android 原生打包） |
| **LanceDB** | Rust | Apache 2.0 | 活跃 | 否决（Android 无官方支持后端） |
| **Chroma** | Python server | Apache 2.0 | 活跃 | 否决（服务端架构，违反 C3） |
| **Zvec（Dart）** | Dart | — | — | 否决（原生方案下 ObjectBox 更成熟，ADR-001 已决） |

#### 3.1.2 深度对比矩阵

| 维度 | **ObjectBox 向量搜索** | sqlite-vec（原版） | sqlite-vector（接管版） | FAISS |
|---|---|---|---|---|
| **License** | Apache 2.0（bindings），向量搜索**免费** | MIT/Apache-2.0 | **Elastic License 2.0**（生产需商业许可） | MIT |
| **Android 兼容** | ✅ 原生 Java/Kotlin AAR | ⚠️ 需 C 绑定 + 手动集成 | ⚠️ 需 C 绑定 + 手动集成 | ❌ 无官方 Android 产物 |
| **ANN 算法** | ✅ **HNSW**（近似近邻） | ❌ 仅暴力扫描（brute-force） | ❌ 仅暴力扫描 | ✅ HNSW / IVF |
| **距离度量** | ✅ COSINE / EUCLIDEAN 等 | ✅ | ✅ | ✅ |
| **与主数据库协同** | ✅ 同一 ObjectBox 库，embedding 与元数据同存 | ❌ 独立 SQLite | ❌ 独立 SQLite | ❌ 独立存储 |
| **零新增依赖** | ✅ 复用已集成 5.4.2 | ❌ 新增 | ❌ 新增 | ❌ 新增 |
| **内存占用（嵌入场景）** | 低（嵌入式 C++ 核心） | 中 | 中 | 高 |
| **维护活跃度** | 官方 active（5.4.2 稳定，6.0.0-beta 开发中） | 停滞 | 活跃但换 License | 活跃（Meta） |
| **体积** | <8MB（含主库） | ~1-2MB | ~1-2MB | 大 |

#### 3.1.3 推荐方案

**推荐：ObjectBox 5.4.2 原生向量搜索（零新增依赖）**

**核心理由**：
1. **零新增依赖 + 复用既有主库**——ADR-001 已集成 ObjectBox 5.4.2，`KnowledgeChunk` 已含 `FloatArray?` embedding 字段（US-002 已实现 CRUD）。M3 只需在 embedding 字段上加 `@HnswIndex` 注解并建立 HNSW 索引，无需引入第二套存储，完美命中 C2。
2. **License 合规**——官方一手来源确认：bindings 为 Apache 2.0，核心数据库免费，**向量搜索免费**（仅 Data Sync、Time Series 为商业付费）。满足 C1。
3. **HNSW 原生近似检索**——满足 C7（384 维 + COSINE），`nearestNeighbors()` + `findWithScores()` 直接返回相似度分数，贴合 RAG 检索需求。
4. **性能**——官方/第三方基准：百万级向量库相似性搜索 <10ms（HNSW），嵌入式架构消除网络开销，适合端侧。
5. **4GB 低端机友好**——嵌入式 C++ 核心，内存占用低，满足 C5。
6. **官方 Android API 成熟**——`@HnswIndex(dimensions=384, distanceType=HnswDistanceType.COSINE)` 注解 float[] 字段即可，与项目 Kotlin 栈无缝。

**否决方案与理由**：
- **sqlite-vector（接管版）**：**License 从 MIT/Apache 改为 Elastic License 2.0**，生产环境需商业许可，违反 C1（合规风险）。原版 asg017/sqlite-vec 虽双许可但**仅支持暴力扫描**，无 ANN 近似检索，性能不达标，且维护者已转投新项目版本停滞。
- **FAISS**：无官方 Android 原生产物，需手动交叉编译，体积大、集成成本高。
- **LanceDB**：Python/Rust 为主，Android 无官方后端支持，端侧集成不可行。
- **Chroma**：服务端架构（Python server），直接违反 C3 零后端约束。

#### 3.1.4 ObjectBox 向量搜索 API 落地示意（深验证）

经官方文档（docs.objectbox.io/on-device-vector-search）确认，Java/Kotlin 用法：

```kotlin
// 1. 实体定义：embedding 字段加 HNSW 索引
@Entity
data class KnowledgeChunk(
    @Id var id: Long = 0,
    var title: String,
    var content: String,
    @HnswIndex(dimensions = 384, distanceType = HnswDistanceType.COSINE)
    var embedding: FloatArray? = null
)

// 2. 近邻检索（top-k）
val query: Query<KnowledgeChunk> = box
    .query(KnowledgeChunk_.embedding.nearestNeighbors(queryVector, 5))
    .build()
// 3. 带相似度分数取结果
val matches = query.findWithScores()  // 按距离升序，嵌入式可直接过滤阈值
```

> **注意**：`KnowledgeChunk.embedding` 当前为 `FloatArray?`（可空）。为建立向量索引，需在字段上加 `@HnswIndex`；索引要求向量维度固定（384）且与模型输出一致。确立索引后，embedding 为 null 的记录（仅文本入库阶段）不参与近邻检索，需在索引建立流程中处理。

### 3.2 决策点 2：嵌入表示与近似检索算法

#### 3.2.1 嵌入表示（Embedding Encoding）

ADR-001 已锁定 **all-MiniLM-L6-v2**（句向量模型，384 维）。深验证补充：

| 项 | 值 | 说明 |
|---|---|---|
| 模型 | all-MiniLM-L6-v2 | Sentence Transformers 家族，轻量句向量模型 |
| 维度 | **384 维** | float32 → 单条向量 1.536KB；INT8 量化后更小 |
| 输出 | 归一化句向量 | 适合 COSINE 相似度 |
| 中文支持 | 一般（英文为主） | **风险点**：中文语义质量有限，见 §5 R1 |
| 量化 | INT8 | 模型体积 ~23MB，内存占用低，精度损失可接受 |

**备选（降级路径）**：bge-m3（中文强，但 568M 参数过大，ADR-001 已否决）；EmbeddingGemma / Qwen3 Embedding（MTEB 竞争力，但移动端体积与内存不友好，后续可评估）。

#### 3.2.2 近似检索算法（ANN）

| 算法 | 支持方 | 特征 | 结论 |
|---|---|---|---|
| **HNSW**（Hierarchical Navigable Small Worlds） | ObjectBox 内置 | 图结构多层导航，近似近邻，百万级 <10ms | **首选**（ObjectBox 原生） |
| Brute-force 暴力扫描 | sqlite-vec | 精确但 O(n)，大数据量慢 | 否决（性能不达标） |
| IVF / PQ | FAISS | 聚类 + 量化 | 否决（Android 无原生） |

**推荐：HNSW（ObjectBox 内置，冻跟踪参数可调）**。HNSW 参数（连接数 M、efSearch）可通过 `@HnswIndex` 注解配置，在召回率与速度间取舍。

#### 3.2.3 检索 Backend 增强（混合检索）

ADR-001 3.7 引用 Continuous-learning 设计「vector cosine 0.7 + BM25 0.3 混合」。M3 建议**分阶段**：

- **首期（M3 本体）**：纯向量检索（top-k，k=5~10），满足「强制引用来源」核心闭环。
- **增强期（后续）**：加入 ObjectBox FTS5 全文检索，按 `0.7*cosine + 0.3*bm25` 进行混合加权重排序，提升中文与精确关键词召回。

> ObjectBox 支持 FTS（全文检索）与向量检索协同，可在同一查询中结合，作为 M3 之后的增强路径，不阻塞首期交付。

### 3.3 决策点 3：嵌入运行时

#### 3.3.1 候选清单与过滤

| 候选 | License | 最后更新 | 过滤结果 |
|---|---|---|---|
| **onnxruntime-android** | MIT | 活跃（1.27.0，2026-06-30） | **保留（首选）** |
| TensorFlow Lite | Apache 2.0 | 活跃 | 可留（备选） |
| ML Kit（Embedding） | Apache 2.0 | 活跃 | 否决（依赖 Google Play Services，与零后端/自发布冲突） |
| PyTorch Mobile | BSD-3 | 活跃 | 否决（体积大，非首选） |

#### 3.3.2 深度对比矩阵

| 维度 | **onnxruntime-android** | TensorFlow Lite |
|---|---|---|
| **License** | **MIT** | Apache 2.0 |
| **模型格式** | ONNX（all-MiniLM-L6-v2 官方可导出 INT8） | TFLite（需先转换，生态略弱） |
| **Android AAR** | ✅ 官方 AAR，`com.microsoft.onnxruntime:onnxruntime-android` | ✅ 官方 AAR |
| **体积** | full 包较大（需自定义裁剪） | 中 |
| **NNAPI 加速** | ✅ `addNnapi()` | ✅ `useNNAPI` |
| **维护** | Microsoft 官方，月更 | Google 官方 |
| **模型生态** | ONNX 生态广，HuggingFace `optimum` 直接导出 | TFLite 生态稳定 |

#### 3.3.3 推荐方案

**推荐：onnxruntime-android 1.27.0（with all-MiniLM-L6-v2 ONNX INT8 模型）**

**核心理由**：
1. **MIT License**——商业闭源友好，满足 C1。
2. **官方 Maven 产物**——`com.microsoft.onnxruntime:onnxruntime-android:1.27.0`（最新稳定版 2026-06-30），无需手动打包。
3. **v1.19+ Mobile 包停发**——ADR-001 已确认改用 full 包；full 包含全部算子/数据类型，all-MiniLM-L6-v2 的算子（Embedding/LayerNorm/MatMul）均在支持范围。
4. **NNAPI 加速**——高端机可开启 `addNnapi(NNAPIFlags.USE_FP16)`，低端机 CPU 降级，满足 4GB 设备分档。
5. **INT8 量化模型**——HuggingFace `optimum` 可导出 ONNX INT8，模型 ~23MB，内存占用低，满足 C5/C7。

**否决**：**ML Kit**（依赖 Google Play Services，与自发布/F-Droid/PGY 分发冲突，且 embedding 能力受限）；**PyTorch Mobile**（体积大，非首选）。

> **版本说明**：项目 ADR-001 Context7 验证「v1.19+ Mobile 包停发，用 full 包」。本次核验确认最新稳定版为 **1.27.0**，建议锁定该版本（Maven 坐标 `com.microsoft.onnxruntime:onnxruntime-android:1.27.0`）。

### 3.4 决策点 4：文档解析库（PDF / DOCX / XLSX / MD / TXT）

#### 3.4.1 候选清单与过滤

| 格式 | 候选 | License | 过滤结果 |
|---|---|---|---|
| **PDF** | Android 原生 PdfRenderer | 系统 API | **保留（首选）** |
| **PDF** | pdfplumber（BSD） | BSD | 可留（备选，需 JVM 移植） |
| **PDF** | pymupdf | **AGPL-3.0** | **否决（License 不兼容，ADR-001 3.8 已禁）** |
| **DOCX** | Apache POI（poi-ooxml） | Apache 2.0 | **保留（首选）** |
| **DOCX** | docx4j | Apache 2.0 | 否决（Android 适配困难） |
| **DOCX** | Kexcel（纯 Kotlin） | MIT | 可留（备选，实验性） |
| **XLSX** | Apache POI（poi-ooxml） | Apache 2.0 | **保留（首选）** |
| **XLSX** | DroidXLS | 商业收费 | 否决（商业 License） |
| **MD/TXT** | 自研解析器 | — | **保留（首选，轻量）** |

#### 3.4.2 深度对比矩阵（DOCX/XLSX 解析）

| 维度 | **Apache POI（poi-ooxml）** | docx4j | Kexcel / DroidXLS |
|---|---|---|---|
| **License** | Apache 2.0 | Apache 2.0 | MIT / 商业 |
| **Android 兼容** | ⚠️ 可用，需 Multidex + ProGuard | ❌ 适配困难（依赖多） | Kexcel 实验性 / DroidXLS 收费 |
| **功能覆盖** | ✅ DOCX/XLSX 全面 | 全面 | 有限 |
| **体积** | 大（AAR ~10.8MB，需裁剪） | 大 | 小 |
| **维护活跃度** | Apache 官方活跃（5.5.1，2025-11） | 活跃但 Android 支持弱 | 弱 |
| **java.awt 依赖** | ⚠️ 部分功能（自选列宽/图片）缺失 | 同 | — |

#### 3.4.3 推荐方案

**推荐组合：PDF 用 Android 原生 PdfRenderer + DOCX/XLSX 用 Apache POI（poi-ooxml 5.5.1）+ MD/TXT 自研解析器**

**核心理由**：
1. **PDF 零依赖**——Android `PdfRenderer`（API 21+，minSdk 26 满足）原生渲染 PDF 并抽取文本，规避 pymupdf 的 AGPL 风险（ADR-001 3.8 已明确禁用 pymupdf）。
2. **Apache POI 功能全面**——poi-ooxml 5.5.1（Apache 2.0，最新稳定版 2025-11-30）支持 DOCX（XWPF）与 XLSX（XSSF）完整文本抽取，满足「文本摄入 RAG」需求。
3. **License 全部友好**——Apache 2.0 / 系统 API / 自研，无 AGPL 传染，满足 C1。
4. **MD/TXT 自研**——RAG 摄入本源格式，轻量解析器，无第三方依赖。

**代价与缓解**：
- **Apache POI 体积大**（~10.8MB AAR）：需启用 Multidex + ProGuard 裁剪未用功能；也可考虑 `centic9/poi-on-android`（shadow jar）但更新慢（5.2.5-4，2024-04），故**优先官方 poi-ooxml 5.5.1 + ProGuard 精简**。
- **java.awt 部分功能缺失**（自选列宽/图片渲染）：RAG 仅需文本抽取（`XWPFWordExtractor` / `XSSFWorkbook` 遍历单元格），不依赖这些缺失功能，无影响。

**否决**：**docx4j**（Android 依赖过多、适配困难，成本高）；**DroidXLS**（商业 License）；**Kexcel**（实验性，功能有限，可作为 DOCX 备选降级）。

---

## 4. PoC 与关键发现

### 4.1 公开基准 / 证据数据汇总

| 数据点 | 值 | 来源 |
|---|---|---|
| ObjectBox 向量搜索免费、bindings Apache 2.0 | 官方一手来源 | [objectbox.io/llms.txt](https://objectbox.io/llms.txt)、[objectbox.io/FAQ](https://objectbox.io/FAQ/) |
| ObjectBox 向量搜索用 HNSW，`@HnswIndex` + `nearestNeighbors()` | 官方文档 | [docs.objectbox.io/on-device-vector-search](https://docs.objectbox.io/on-device-vector-search) |
| ObjectBox 百万级向量 <10ms | 官方/第三方博客 | [ObjectBox 向量搜索博客](https://blog.gitcode.com/f49131588a66b196934a22c5d09b6389.html) |
| ObjectBox 最新稳定版 5.4.2（6.0.0-beta 开发中） | GitHub Releases | [objectbox-java releases](https://github.com/objectbox/objectbox-java/releases) |
| onnxruntime-android 最新稳定版 1.27.0（2026-06-30） | Maven Central | [onnxruntime-android](https://mvnrepository.com/artifact/com.microsoft.onnxruntime/onnxruntime-android) |
| ONNX Runtime MIT License | Maven Central | 同上 |
| v1.18+ Mobile 包停发，用 onnxruntime-android | 官方 Release | [ONNX Runtime v1.18.0](https://github.com/microsoft/onnxruntime/releases/v1.18.0) |
| Apache POI poi-ooxml 最新稳定版 5.5.1（2025-11-30） | Maven Central | [poi-ooxml](https://mvnrepository.com/artifact/org.apache.poi/poi-ooxml) |
| Apache POI Android 需 Multidex + java.awt 功能缺失 | 生态实测 | [SUPERCILEX/poi-android](https://gitmemories.com/index.php/SUPERCILEX/poi-android) |
| sqlite-vec 原版停滞、接管版改 Elastic License 2.0 | 生态调研 | （见 §3.1.1 判断） |
| Android PdfRenderer 原生 PDF 抽取 | Android 官方 API | [PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer) |

### 4.2 致命否决发现

| 发现 | 影响方案 | 严重度 |
|---|---|---|
| **sqlite-vec 接管版（sqlite-vector）License 改为 Elastic License 2.0**，生产需商业许可 | 决策点 1「sqlite-vector」 | **致命**——违反 C1 合规约束，排除 |
| **sqlite-vec 原版仅暴力扫描，无 HNSW/ANN 近似检索** | 决策点 1「sqlite-vec」 | 高——性能不达标，排除 |
| **pymupdf 为 AGPL-3.0**，与 Apache 2.0 不兼容 | 决策点 4「PDF 解析」 | **致命**——ADR-001 3.8 已禁用，用 Android PdfRenderer 替代 |
| **ObjectBox Gradle plugin 在 4.0.0 起 License 改为 AGPL**（构建期工具） | 决策点 1 复用 ObjectBox | 中——需披露与确认（见 §4.4） |
| **Apache POI 体积大（~10.8MB）**，需 Multidex + ProGuard 裁剪 | 决策点 4「Apache POI」 | 中——可缓解 |

### 4.3 异常场景行为记录

| 场景 | 推荐方案已知行为 | 降级策略 |
|---|---|---|
| 4GB 低端机检索瞬间内存 | HNSW 索引在内存中，检索瞬间峰值可控 | 按需加载/卸载；小批次处理；限制库容量 |
| 低端机嵌入推理慢 | CPU 推理（无 NNAPI），单条 <500ms 小批次 | 后台线程 + 进度提示；分档降级（4-6GB 小批次，3-4GB 禁 RAG 仅关键词） |
| DOCX/XLSX 解析大文件 OOM | POI 内存占用随文件增大 | 流式解析（SAX-based）+ 限制文件大小；后台线程 |
| PDF 扫描件（无文本层） | PdfRenderer 无法抽取文本 | 提示无法解析；后续可集成 OCR（不阻塞首期） |
| embedding 为 null 的记录 | 不参与近邻检索 | 索引建立前先完成切片+嵌入；UI 提示「部分文档未建立索引」 |

### 4.4 需披露的既有风险：ObjectBox Gradle plugin AGPL

**重要提示**：经官网 release-history 核实，ObjectBox **Gradle plugin** 自 4.0.0 起 License 改为 **AGPL**（见 §7.3 引用）。该 plugin 是**构建期工具**，不随 APK 分发，AGPL 传染性对运行时产物影响有限且存在业界争议。**本项目已在 M0-M2 使用 ObjectBox 5.4.2 + `io.objectbox` Gradle plugin 并通过 guardrail 审查**，故本次 M3 复用 ObjectBox 向量搜索**不新增该风险**，但建议团队在 ADR 中明确知悉并记录该合规说明。运行时 bindings（objectbox-android / objectbox-java）仍为 Apache 2.0，向量搜索免费。

---

## 5. 风险与缓解措施

### 5.1 推荐方案 Top 3 风险（决策点 1 + 3 + 4 汇总）

| # | 风险 | 等级 | 缓解措施 |
|---|---|---|---|
| R1 | **all-MiniLM-L6-v2 中文语义质量一般**，影响中文知识库检索召回 | 高 | 首期以英文/技术文档为主验证；PoC 用中文测试集评估召回；若 <60% 触发切换评估更强中文嵌入模型（如 bge-small-zh，受控导入） |
| R2 | **Apache POI 体积大（~10.8MB）+ 低端机内存** | 中 | ProGuard 精简未用类；限制单文件大小；流式解析；若体积/内存不可接受，降级 Kexcel 或自研 OOXML 极简解析器 |
| R3 | **onnxruntime-android full 包体积较大**（无 Mobile 包） | 中 | 用 ProGuard/R8 裁剪；评估自定义裁剪 build；模型 INT8 量化 ~23MB 已控制模型侧 |

### 5.2 备选 / 切换触发条件

| 决策点 | 推荐方案 | 备选 | 切换触发条件 |
|---|---|---|---|
| 向量存储 | ObjectBox 5.4.2 向量搜索 | sqlite-vec（原版，需自实现 ANN 或接受暴力扫描） | 1) ObjectBox 向量索引在 10 万+ chunk 时 P99 >100ms 且无法调参；2) 出现无法规避的 ObjectBox 向量 bug |
| 嵌入表示 | all-MiniLM-L6-v2（384 维） | bge-small-zh / EmbeddingGemma | PoC 中文召回 <60% 且确认是模型语义问题而非切片/检索参数问题 |
| 嵌入运行时 | onnxruntime-android 1.27.0 | TensorFlow Lite | 1) ONNX 模型算子不兼容；2) NNAPI 加速需求 TFLite 更优 |
| 文档解析 | PDF=PdfRenderer，DOCX/XLSX=Apache POI 5.5.1 | Kexcel（DOCX 备选） | Apache POI 体积/内存不可接受，或 ProGuard 裁剪后功能缺失 |

---

## 6. 最终推荐与下一步

### 6.1 推荐技术栈组合（M3 引入）

| 层 | 推荐方案 | 版本 | 理由 |
|---|---|---|---|
| **向量存储** | ObjectBox 原生向量搜索（`@HnswIndex` + `nearestNeighbors`） | **5.4.2（已集成，零新增）** | 复用主库，HNSW，Apache 2.0 免费，384 维 COSINE 原生支持 |
| **嵌入表示/ANN** | all-MiniLM-L6-v2 384 维 + COSINE + HNSW | 模型 INT8 ~23MB | 已锁定，端侧轻量；后续可加 FTS5 混合检索 |
| **嵌入运行时** | **onnxruntime-android** | **1.27.0**（Maven `com.microsoft.onnxruntime:onnxruntime-android:1.27.0`） | MIT，官方 AAR，NNAPI 加速，INT8 支持 |
| **PDF 解析** | Android 原生 PdfRenderer | 系统 API | 零依赖，规避 pymupdf AGPL |
| **DOCX/XLSX 解析** | **Apache POI（poi-ooxml）** | **5.5.1** | Apache 2.0，功能全面，RAG 文本抽取足够 |
| **MD/TXT 解析** | 自研解析器 | — | 轻量，本源格式 |

### 6.2 新增依赖清单（libs.versions.toml 建议）

```toml
[versions]
objectbox = "5.4.2"          # 已集成，无变更
onnxruntime = "1.27.0"       # 新增
poiOoxml = "5.5.1"           # 新增

[libraries]
# 嵌入运行时（决策点 3）
onnxruntime-android = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android", version.ref = "onnxruntime" }
# 文档解析 DOCX/XLSX（决策点 4）
poi-ooxml = { group = "org.apache.poi", name = "poi-ooxml", version.ref = "poiOoxml" }
```

> **注意**：
> 1. `org.apache.poi:poi-ooxml:5.5.1` 是 Maven Central **最新稳定版**（2025-11-30），非 5.4.1（2025-04-06）。ADR-001 若引用 5.4.1 需更新为 5.5.1。
> 2. 引入 Apache POI 需在 `defaultConfig` 启用 `multiDexEnabled = true`（若 minSdk 26 且方法数超限），并配置 ProGuard 规则。
> 3. 引入 onnxruntime-android 需内存策略：模型按需加载，避免常驻内存（4GB 低端机）。

### 6.3 后续实施步骤

| 步骤 | 内容 | 产出 |
|---|---|---|
| 1 | **写 ADR**（如 ADR-007：M3 个人知识库 RAG 技术栈），基于本报告结论 | ADR 文档 |
| 2 | **依赖落地**：更新 `libs.versions.toml`（onnxruntime 1.27.0 + poi-ooxml 5.5.1）+ 启用 Multidex + ProGuard；`./gradlew assembleDebug` 验证 | 编译通过 |
| 3 | **向量检索 PoC**：给 `KnowledgeChunk.embedding` 加 `@HnswIndex`，灌入测试向量，实测 top-5 检索延迟 p50/p95/p99，建性能基线 | 性能基线报告 |
| 4 | **嵌入 PoC**：加载 all-MiniLM-L6-v2 ONNX INT8，真机（含 4GB 低端机）实测单条嵌入延迟 + 内存峰值，验证小批次模式 | 性能基线报告 |
| 5 | **中文召回评估**：构造中文测试集，评估 top-5 命中率；若 <60% 触发嵌入模型切换评估 | 召回评估报告 |
| 6 | **文档解析 PoC**：Apache POI 抽取 DOCX/XLSX + PdfRenderer 抽取 PDF + 自研 MD/TXT，验证文本抽取正确率与内存 | 解析验证报告 |
| 7 | **集成试点**：实现端侧 RAG 主链路（摄入→切片→嵌入→入库→top-k 检索→注入 prompt→强制引用来源），产出最小可运行 RAG | 可运行 RAG 链路 |

---

## 7. 附录

### 7.1 研究指标文档（Phase 1 产出）

见本报告第 2 节「需求与约束回顾」。

### 7.2 长候选清单与过滤日志（Phase 2 产出）

| 决策点 | 候选 | 否决理由 |
|---|---|---|
| 向量存储 | **sqlite-vector（接管版）** | **License 改 Elastic License 2.0**，生产需商业许可（C1） |
| 向量存储 | **sqlite-vec（原版）** | 仅暴力扫描无 ANN，性能不达标；维护者停滞 |
| 向量存储 | **FAISS** | 无 Android 官方原生产物，集成成本高 |
| 向量存储 | **LanceDB** | Android 无官方后端支持 |
| 向量存储 | **Chroma** | 服务端架构，违反零后端 C3 |
| 嵌入运行时 | **ML Kit** | 依赖 Google Play Services，与自发布/F-Droid 分发冲突 |
| 嵌入运行时 | **PyTorch Mobile** | 体积大，非首选 |
| PDF 解析 | **pymupdf** | **AGPL-3.0**，License 不兼容（ADR-001 3.8 已禁） |
| DOCX 解析 | **docx4j** | Android 适配困难，依赖过多 |
| DOCX 解析 | **Kexcel** | 实验性，功能有限（保留为备选） |
| XLSX 解析 | **DroidXLS** | 商业 License |

### 7.3 关键引用链接索引

#### ObjectBox（向量存储）
- [ObjectBox 向量搜索官方文档（HNSW/api）](https://docs.objectbox.io/on-device-vector-search)
- [ObjectBox AI 导航（License：bindings Apache 2.0，Data Sync 付费）](https://objectbox.io/llms.txt)
- [ObjectBox FAQ（License & Pricing）](https://objectbox.io/FAQ/)
- [ObjectBox Java Release History（4.0.0 起向量搜索 + Gradle plugin AGPL 说明）](https://docs.objectbox.io/release-history)
- [objectbox-java 仓库（5.4.2 稳定版 / 6.0.0-beta）](https://github.com/objectbox/objectbox-java)
- [objectbox-java Releases](https://github.com/objectbox/objectbox-java/releases)
- [ObjectBox 向量搜索性能博客（百万级 <10ms）](https://blog.gitcode.com/f49131588a66b196934a22c5d09b6389.html)

#### ONNX Runtime（嵌入运行时）
- [onnxruntime-android Maven（1.27.0，MIT）](https://mvnrepository.com/artifact/com.microsoft.onnxruntime/onnxruntime-android)
- [ONNX Runtime v1.18.0 Release（Mobile 包停发公告）](https://github.com/microsoft/onnxruntime/releases/v1.18.0)

#### Apache POI（文档解析）
- [poi-ooxml Maven（5.5.1 最新稳定版，Apache 2.0）](https://mvnrepository.com/artifact/org.apache.poi/poi-ooxml)
- [SUPERCILEX/poi-android（POI 在 Android 的适配与限制）](https://gitmemories.com/index.php/SUPERCILEX/poi-android)
- [centic9/poi-on-android（shadow jar 方案，更新慢）](https://github.com/centic9/poi-on-android/releases)

#### Android 系统 API
- [Android PdfRenderer（PDF 文本/渲染）](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)

#### 项目既有决策
- [ADR-001 Prism 技术栈与架构选型](../decisions/ADR-001-prism-tech-stack.md)（向量库 ObjectBox 5.4.2 / 嵌入模型 all-MiniLM-L6-v2 ONNX INT8 / pymupdf AGPL 禁用）

---

## 8. 声明

- 本报告所有结论基于 2026-08-06 联网搜索 + 一手来源（官方文档/仓库/Maven Central）证据，引用链接见第 7.3 节。
- ObjectBox 向量搜索性能（百万级 <10ms）为官方/第三方博客数据，非本机实测；top-5 检索延迟需真机 PoC 实测（第 6.3 节步骤 3）。
- all-MiniLM-L6-v2 中文召回质量无法从公开数据确定，需中文测试集实测（第 6.3 节步骤 5）。
- 本报告作为 M3 后续 ADR 的输入，最终技术决策需经 guardrail-enforcer 审查后写入 ADR。
- 本报告为「完整版」调研（四阶段全部执行），未省略任何阶段。