# 安全与质量审计报告 —— US-013 文本切片器 Chunker

| 项目 | 内容 |
|---|---|
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-US013-GUARDRAIL-001 |
| 审计日期 | 2026-08-06 |
| 关联 ADR | [ADR-007-m3-rag-tech-stack.md](../../docs/decisions/ADR-007-m3-rag-tech-stack.md)（5.4 检索） |
| 关联代码变更 | [Chunker.kt](../../app/src/main/java/io/prism/document/Chunker.kt)（新增）、[ChunkerTest.kt](../../app/src/test/java/io/prism/document/ChunkerTest.kt)（新增） |
| 风险等级 | P1（单模块内部逻辑，无接口契约变更） |
| 审查范围 | 2 个新增文件，Chunker.kt 共 108 行，ChunkerTest.kt 共 118 行 |

## 0. 上下文重建摘要

- 本任务为 M3 RAG 里程碑 US-013 文本切片器实现，纯 Kotlin 文本处理算法，无 IO/网络/数据库/注入面。
- 审查依据：ADR-007 5.4（纯向量 top-k 检索）、PRD US-003 验收标准（「文档解析→切片（可配置 chunk size/overlap）」，类内自述 US-013 验收标准 2「避免在句子中间截断」）。
- 主 Agent 提供的三个脆弱点：① overlap 多段落跨段拼接语义粘连；② 中文分词边界缺失；③ `findSentenceBoundary` 在 `to` 位置漏判句尾标点。均已逐一验证。

## 1. 输入与边界审计（范围检查）

| 检查项 | 结论 | 证据 |
|---|---|---|
| 数值/类型边界 | 通过 | 构造函数 `require(chunkSize > 0)`、`require(overlap in 0 until chunkSize)` 显式校验（[Chunker.kt:27-30](../../app/src/main/java/io/prism/document/Chunker.kt#L27-L30)） |
| 集合/字符串索引越界 | 通过 | `substring(start, cut)` 中 `start ≥ 0` 且 `cut ≤ end ≤ paragraph.length`；（[Chunker.kt:70-71](../../app/src/main/java/io/prism/document/Chunker.kt#L70-L71)）`takeLast(minOf(overlap, prev.length))` 有界（[Chunker.kt:97](../../app/src/main/java/io/prism/document/Chunker.kt#L97)） |
| **appendChunk 死循环** | **通过（已推演证实安全）** | `end = minOf(start+chunkSize, length)`，若非末尾分支则 `end = start+chunkSize > start`；`findSentenceBoundary` 返回 `from(=start)` 或 `≥start+1`；故 `cut = boundary(>start) 或 end(>start)`，恒有 `cut > start`，`start` 严格递增，无死循环（[Chunker.kt:60-74](../../app/src/main/java/io/prism/document/Chunker.kt#L60-L74)） |
| `findSentenceBoundary` 在 `to==from` 极端 | 通过（安全） | `for (i in to-1 downTo from)` 当 `to-1 < from` 时 Kotlin 产生空区间，环体不执行，直接 `return from`，不越界（[Chunker.kt:79-84](../../app/src/main/java/io/prism/document/Chunker.kt#L79-L84)）。且调用侧 `to>from` 恒成立，实际路径不可达 |
| 业务状态机 | 不适用 | 纯函数，无状态迁移 |

## 2. 执行安全审计（注入 / 最小权限 / 输出编码）

**结论：本变更无任何可被外部利用的安全漏洞（Security PASS）。**

- 无 SQL/NoSQL/OS 命令/模板/表达式注入面——纯字符串算法，无任何数据库、子进程、`eval`/反射调用。
- 无最小权限问题——不触碰文件系统、摄像头等敏感权限。
- 无输出编码问题——不产生 HTML/JS/URL 上下文输出。
- ReDoS 检查：段落分隔正则 `\\n\\s*\\n`（[Chunker.kt:44](../../app/src/main/java/io/prism/document/Chunker.kt#L44)）为线性正则，无嵌套量词/回溯，无 ReDoS 风险。
- 性能：`chunk` 整体 O(n)。每块 `findSentenceBoundary` 至多扫描 `chunkSize` 字符，总块数 O(n/chunkSize)，合计 O(n)；`applyOverlap` 的 `takeLast`/字符串拼接累计 O(n)。无 O(n^2) 陷阱。
- 线程安全：无状态，类注释已声明可跨线程复用（[Chunker.kt:17](../../app/src/main/java/io/prism/document/Chunker.kt#L17)）。

## 3. 密钥与配置安全

通过。无硬编码密钥/口令/令牌，不涉及环境变量、`.gitignore`、依赖锁文件变更。

## 4. 依赖与供应链风险

通过。本次变更未新增或修改任何依赖。

## 5. 代码质量审查（TRAE-code-review / Karpathy Guidelines）

命名清晰且语义化（`chunkSize/overlap/appendChunk/findSentenceBoundary`），函数短小聚焦，注释准确表达设计意图，无过度工程。存在以下正确性/设计问题，按严重度排序：

### 5.1 发现清单

| 编号 | 严重度 | 类别 | 位置 | 描述 |
|---|---|---|---|---|
| G-1 | 中 | 设计意图冲突 | [Chunker.kt:89-102](../../app/src/main/java/io/prism/document/Chunker.kt#L89-L102) | `applyOverlap` 对所有相邻块（**含跨段落边界**）无条件拼接上一块末尾重叠字符，触发主 Agent 盲点 1 |
| G-2 | 低-中 | 边界漏判（正确性） | [Chunker.kt:79-84](../../app/src/main/java/io/prism/document/Chunker.kt#L79-L84) | `findSentenceBoundary` 窗口为半开区间 `[from, to)`，句尾标点恰在 `to` 位置时漏判，标点成为「句号孤儿」遗留到下一块开头，触发盲点 3 |
| G-3 | 低 | 词边界缺失（验收 2 偏差） | [Chunker.kt:70](../../app/src/main/java/io/prism/document/Chunker.kt#L70) | 窗口内无句尾标点时硬切 `cut=end`，在英文单词/中文词语中间截断，触发盲点 2 |
| G-4 | 低 | 测试覆盖缺口 | [ChunkerTest.kt:109-117](../../app/src/test/java/io/prism/document/ChunkerTest.kt#L109-L117) | 现有测试未覆盖 G-2/G-3 边界，且 `chunk_paragraphs_within_chunk_have_overlap_continuity` 反而固化了 G-1 的跨段落 overlap 行为，需对语义定论 |

### 5.2 G-1 详解（跨段落 overlap 语义粘连）

类 KDoc 明示「段落优先……**避免跨段落拼接语义无关内容**」（[Chunker.kt:12](../../app/src/main/java/io/prism/document/Chunker.kt#L12)），但 `applyOverlap` 在 `chunk()` 最后对结果列表整体应用（[Chunker.kt:54](../../app/src/main/java/io/prism/document/Chunker.kt#L54)），未区分块间边界是否跨越段落。当 `chunk[i]` 为段落 A 末块、`chunk[i+1]` 为段落 B 首块时，会把 A 末尾 `overlap` 字符拼到 B 开头，产生跨段落语义粘连，与文档自述意图直接矛盾。

> 该行为究竟是缺陷还是有意设计需主 Agent/用户决策：
>
> - 方案 A（符合 KDoc）：在段落边界重置 overlap，仅在同段落相邻块间应用；需改造 `applyOverlap` 及 `chunk` 的块来源记录。
> - 方案 B（有意为之）：overlap 跨段落用于检索上下文衔接，需**更新 KDoc 与 ADR-007 5.4**，消除文档与实现矛盾，并在测试中显式断言该语义。
>
> 无论选哪一方，当前「文档声明 A、实现为 B、测试锁定 B」的三方不一致必须被消除。

### 5.3 G-2 详解（`to` 位置句尾漏判）

`findSentenceBoundary` 搜索 `[from, to)`（`to-1 downTo from`）。当句子标点恰位于 `to`（即 chunkSize 边界处恰是句号）时，标点不被计入前块，`cut=end=to`，该标点被遗留为下一块首字符。例：`chunkSize` 使 `end` 落于「句子。」的「子」与「。」之间时，前块止于「子」，后块以「。」开头。理想做法是允许搜索窗口含 `to`（即 `[from, to]` 闭区间），使 `cut` 可 = `to+1`，把标点并入前块。

### 5.4 G-3 详解（无词边界回退）

`findSentenceBoundary` 仅识别 `SENTENCE_ENDINGS`（。！？；.!?;…，[Chunker.kt:106](../../app/src/main/java/io/prism/document/Chunker.kt#L106)）。当窗口内无这些标点时，`boundary==from`，落入 `cut=end` 硬切分支（[Chunker.kt:70](../../app/src/main/java/io/prism/document/Chunker.kt#L70)），会在英文单词或中文词组中间截断。对 AC-2「避免在句子中间截断」，硬切兜底是必要的（否则无法推进），但缺少「空格/词边界回退」的软兜底，属已知设计取舍，非阻断缺陷。建议在硬切前先尝试回退到最近空格分词边界，改善英文召回质量。

### 5.5 测试充分性

12 个用例覆盖：空/纯空白、短文本单块、无边界硬切、段落边界优先、句号边界回退、块间 overlap、overlap=0 无重复、超长输入、非空白块、构造函数校验（2 组）、多段落 overlap 衔接。覆盖良好，但缺 G-2/G-3 边界用例，且 G-1 用例需按语义定论重写断言。

## 6. OWASP / CWE 发现

| 编号 | 等级 | 位置 | 修复建议 |
|---|---|---|---|
| （无） | — | — | 本变更无安全漏洞，无 OWASP/CWE 映射项 |

## 7. 结论

- [x] 有条件通过（可进入测试阶段）

**无阻断级安全漏洞，无死循环，无越界，性能 O(n)。** 但存在 1 项中危设计意图冲突（G-1）与 2 项低危正确性/质量边界问题（G-2/G-3），需主 Agent 在进入 ac-verifier 前明确处理：

1. **G-1（必须决策）**：就「overlap 是否跨段落」达成明确语义，并同步代码/文档/测试三方一致。
2. **G-2 / G-3（建议修复）**：`to` 位置句尾漏判补闭区间搜索；无标点窗口补词边界回退。
3. **G-4**：补充对应边界测试用例。

若主 Agent 判定 G-1/G-2/G-3 均接受为「当前算法已知取舍」，应在 PR 描述中显式声明，并将本报告归档，随后方可启动 ac-verifier。

## 8. 规则提议（accepted review → behavioral-rules）

提议新增 1 条规则（需 guardrail 确认非重复后追加至 `docs/behavioral-rules.md`）：

- **类别：design**
- **规则**：当算法实现的行为与代码内 KDoc / ADR 声明的设计意图可能冲突时，不得以「实现为准」或「文档为准」单方默默偏向，必须通过显式决策消除三方（文档、实现、测试）不一致；任何有意为之的边界行为（如跨段落 overlap）须用测试断言固化语义并在文档中声明。
- **反例**：KDoc 写「避免跨段落拼接」，`applyOverlap` 却跨段落拼接，测试反向断言该拼接行为，三方矛盾未处理。
- **正例**：明确决策后，要么改实现符合文档，要么改文档符合实现，并补测试断言该语义。
- **来源**：US-013 Chunker 审查（TKN-US013-GUARDRAIL-001，G-1）
- **添加日期**：2026-08-06
- **适用场景**：dev
- **状态**：proposed

## 9. 审查过程自检

- 所有代码链接使用相对路径（`../../`），深度 2 层，符合 `consistency-check.js` 第 5/6 项（无 `file:///` 绝对路径、`../` 深度 ≤ 3）。
- 报告命名符合 `YYYY-MM-DD-<task>-<type>.md`。
- 元信息表含「执行 Agent」「任务令牌」字段，令牌与主 Agent 签发一致。
